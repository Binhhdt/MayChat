package com.maychat.app.game

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.maychat.app.MainActivity
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.SupabaseProvider
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// =====================================================================
// "Cây đã chín": one alarm of the phone at the time the next crop of the
// farm is ripe (the game page tells it after every change). The
// notification says nothing more than that, and only shows when the same
// account is still logged in on this phone.
// =====================================================================
object FarmAlarm {
    private const val PREFS = "maychat_farm_alarm"
    const val ACTION = "com.maychat.app.FARM_RIPE"
    private const val CHANNEL_ID = "farm"
    private const val NOTIFY_ID = 7301

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NOTIFY_ID,
            Intent(context, FarmRipeReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // atMs: phone time when the next crop is ripe; 0 = nothing growing.
    fun set(context: Context, atMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (atMs <= System.currentTimeMillis()) {
            runCatching { alarms.cancel(pending(context)) }
            prefs.edit().clear().apply()
            return
        }
        prefs.edit()
            .putLong("at", atMs)
            .putString("user", ChatRepository.currentUserId())
            .apply()
        // "About then" is enough for crops (Android may be a few minutes late while the phone sleeps).
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(context)) }
    }

    // After a restart of the phone the alarm is gone: set it again.
    fun restoreAfterBoot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0L)
        if (at <= 0L) return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val time = maxOf(at, System.currentTimeMillis() + 5_000)
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context)) }
    }

    suspend fun onAlarm(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val user = prefs.getString("user", null) ?: return
        prefs.edit().clear().apply()
        if (!SupabaseProvider.isConfigured) return
        val loggedIn = withTimeoutOrNull(5_000) {
            ChatRepository.sessionStatus.first { it is SessionStatus.Authenticated }
        } != null
        // Another account (or nobody) on this phone now: nothing to show.
        if (!loggedIn || ChatRepository.currentUserId() != user) return

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nông trại", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Báo khi cây trong nông trại đã chín" },
        )
        val open = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tap = PendingIntent.getActivity(app, NOTIFY_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🌾 Nông trại")
            .setContentText("Cây đã chín rồi, vào tab Game thu hoạch nhé!")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        // Without the permission to notify, Android simply shows nothing.
        runCatching { manager.notify("farm", NOTIFY_ID, notification) }
    }
}

// The farm alarm went off, or the phone has started.
class FarmRipeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> FarmAlarm.restoreAfterBoot(context.applicationContext)
            FarmAlarm.ACTION -> {
                val result = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        withTimeoutOrNull(9_000) { FarmAlarm.onAlarm(context) }
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }
}
