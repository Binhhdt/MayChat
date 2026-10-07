package com.maychat.app.push

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maychat.app.MainActivity
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.data.attempt
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

// =====================================================================
// Group reminders ("Nhắc hẹn") ring on every member's phone.
//
// The server only keeps the list. Each phone reads the reminders of its
// groups (at start, when a group message arrives, when the board is used)
// and sets one alarm of the phone per reminder. When the alarm goes off
// the phone asks the server whether the reminder still exists and whether
// this phone may still see it, and only then shows it.
// =====================================================================

@Serializable
private data class StoredReminder(
    val id: String,
    val groupId: String,
    val groupName: String,
    val title: String,
    val atMs: Long,
)

object Reminders {
    private const val PREFS = "maychat_reminders"
    private const val KEY = "list"
    const val ACTION = "com.maychat.app.REMINDER"
    const val CHANNEL_ID = "reminders"

    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastSync = 0L

    // Called once when the app process starts (see MayChatApplication).
    fun init(application: Application) {
        app = application
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nhắc hẹn",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Báo khi đến giờ một nhắc hẹn trong nhóm" }
        (application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun stored(context: Context): List<StoredReminder> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(StoredReminder.serializer()), text) }
            .getOrDefault(emptyList())
    }

    private fun store(context: Context, list: List<StoredReminder>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(ListSerializer(StoredReminder.serializer()), list))
            .apply()
    }

    private fun alarmIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun setAlarm(context: Context, reminder: StoredReminder) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = alarmIntent(context, reminder.id)
        // On the minute when the phone allows it, otherwise "about then"
        // (Android may then be a few minutes late while the phone sleeps).
        val exactAllowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMs, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMs, pending)
            }
        } catch (e: SecurityException) {
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMs, pending) }
        }
    }

    private fun cancelAlarm(context: Context, id: String) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.cancel(alarmIntent(context, id)) }
    }

    // Reads the coming reminders of my groups and sets the alarms. Asked
    // often, so it does nothing when it ran less than 5 seconds ago
    // (unless force = true).
    fun requestSync(force: Boolean = false) {
        scope.launch { sync(force) }
    }

    suspend fun sync(force: Boolean = false) {
        val context = app ?: return
        if (!SupabaseProvider.isConfigured) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 5_000) return
        lastSync = now
        lock.withLock {
            attempt {
                val coming = ChatRepository.loadUpcomingReminders()
                val names: Map<String, String> = if (coming.isEmpty()) {
                    emptyMap()
                } else {
                    ChatRepository.loadGroups().associate { it.id to it.name }
                }
                val fresh = coming.mapNotNull { r ->
                    val at = runCatching { OffsetDateTime.parse(r.remindAt).toInstant().toEpochMilli() }.getOrNull()
                        ?: return@mapNotNull null
                    StoredReminder(r.id, r.groupId, names[r.groupId] ?: "Nhóm", r.title, at)
                }
                val keep = fresh.map { it.id }.toSet()
                stored(context).filter { it.id !in keep }.forEach { cancelAlarm(context, it.id) }
                fresh.forEach { setAlarm(context, it) }
                store(context, fresh)
            }
        }
    }

    // After the phone was restarted its alarms are gone: set them again
    // from what was remembered.
    fun restoreAfterBoot(context: Context) {
        val now = System.currentTimeMillis()
        val list = stored(context).filter { it.atMs > now - 60_000 }
        list.forEach { setAlarm(context, it) }
        store(context, list)
    }

    // Signing out: no reminder of this account may ring on this phone.
    fun clear() {
        val context = app ?: return
        stored(context).forEach { cancelAlarm(context, it.id) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        lastSync = 0L
    }

    // An alarm went off. Shows the reminder only when the server confirms
    // that it still exists and that this phone is still logged in and in
    // the group. Without a connection only a line WITHOUT the content is
    // shown, so nothing of a group can appear on a phone that can no
    // longer be checked.
    suspend fun onAlarm(context: Context, id: String) {
        val appContext = context.applicationContext
        val reminder = stored(appContext).firstOrNull { it.id == id } ?: return
        store(appContext, stored(appContext).filter { it.id != id })
        if (!SupabaseProvider.isConfigured) return

        val loggedIn = withTimeoutOrNull(5_000) {
            ChatRepository.sessionStatus.first { it is SessionStatus.Authenticated }
        } != null
        if (!loggedIn) return

        val answer = withTimeoutOrNull(8_000) { attempt { ChatRepository.loadReminder(id) } }
        val text: String
        val title: String
        if (answer != null && answer.isSuccess) {
            // Deleted meanwhile, or I left the group: nothing to show.
            val row = answer.getOrNull() ?: return
            title = "⏰ Nhắc hẹn · ${reminder.groupName}"
            text = row.title
        } else {
            title = "⏰ Nhắc hẹn"
            text = "Đã đến giờ một nhắc hẹn trong nhóm. Mở MayChat để xem."
        }

        // Tapping opens the group (read by MainActivity like a message).
        val open = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", reminder.groupId)
            putExtra("sender_id", reminder.groupId)
            putExtra("sender_name", reminder.groupName)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            id.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // Without the permission to notify, Android simply shows nothing.
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify("reminder", id.hashCode(), notification)
        }
    }
}

// Receives the alarm of a reminder, and "the phone has started".
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Reminders.restoreAfterBoot(context.applicationContext)
            Reminders.ACTION -> {
                val id = intent.getStringExtra("id") ?: return
                // The check with the server takes a few seconds: keep the
                // receiver alive until it is done (Android allows ~10 s).
                val result = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        withTimeoutOrNull(9_000) { Reminders.onAlarm(context, id) }
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }
}
