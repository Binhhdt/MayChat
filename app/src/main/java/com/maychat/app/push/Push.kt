package com.maychat.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.AudioAttributes
import android.os.Build
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.maychat.app.BuildConfig
import com.maychat.app.MainActivity
import com.maychat.app.R
import com.maychat.app.call.CallActionReceiver
import com.maychat.app.data.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

// A chat the user asked to open by tapping a notification.
data class ChatToOpen(val conversationId: String, val senderId: String, val senderName: String)

// Everything about push notifications on the phone side.
object Push {
    const val CHANNEL_ID = "messages"

    // Separate channel for incoming calls: rings like a phone call.
    const val CALL_CHANNEL_ID = "calls"
    private const val CALL_NOTIFICATION_ID = 7001
    private const val GROUP_CALL_NOTIFICATION_ID = 7003

    // True while MayChat is the app on screen (set by MainActivity).
    @Volatile
    var appVisible: Boolean = false

    // False when the APK was built without the Firebase settings
    // (GitHub secret GOOGLE_SERVICES_JSON). The app then simply has no push.
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    private val _chatToOpen = MutableStateFlow<ChatToOpen?>(null)
    val chatToOpen: StateFlow<ChatToOpen?> = _chatToOpen.asStateFlow()

    fun requestOpenChat(chat: ChatToOpen) {
        _chatToOpen.value = chat
    }

    fun clearOpenChat() {
        _chatToOpen.value = null
    }

    // Called once when the app process starts (see MayChatApplication).
    fun init(app: Application) {
        // The notification channel must exist before the first notification
        // arrives. "Show badge" lets the launcher put a number on the app icon.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tin nhắn",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Thông báo khi có tin nhắn mới"
            setShowBadge(true)
        }
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        // Incoming calls: highest importance, phone ringtone, visible on the
        // lock screen. Needed for the full-screen incoming call.
        val callChannel = NotificationChannel(
            CALL_CHANNEL_ID,
            "Cuộc gọi",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Đổ chuông khi có cuộc gọi đến"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(callChannel)

        // Firebase is started by hand from values baked in at build time,
        // so no google-services.json file has to be stored in the repository.
        if (isConfigured && FirebaseApp.getApps(app).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(app, options)
        }
    }

    // This phone's Firebase address, or null if push is unavailable.
    suspend fun currentToken(): String? {
        if (!isConfigured) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    val token = if (task.isSuccessful) task.result else null
                    if (continuation.isActive) continuation.resume(token)
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    // Someone is calling and MayChat is not on screen. Shows a ringing
    // notification whose "full-screen intent" lets Android open MayChat over
    // the lock screen (or as a banner at the top if the phone is in use).
    // Opening it leads to the chat with the caller, where the real incoming
    // call screen appears as soon as the app has reconnected.
    private fun callIntent(
        context: Context,
        conversationId: String,
        senderId: String,
        senderName: String,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("conversation_id", conversationId)
        putExtra("sender_id", senderId)
        putExtra("sender_name", senderName)
        putExtra("incoming_call", true)
    }

    // Tries to open the call screen directly, also while another app is in
    // use. Android only allows this when the user has granted MayChat
    // "display over other apps" (on Xiaomi also "open new windows while
    // running in the background"). Without that permission nothing happens
    // here and the ringing notification below is what the user sees.
    fun tryOpenCallScreen(context: Context, conversationId: String, senderId: String, senderName: String) {
        try {
            context.startActivity(callIntent(context, conversationId, senderId, senderName))
        } catch (e: Exception) {
            // Not allowed on this phone: the notification remains.
        }
    }

    fun showIncomingCall(context: Context, conversationId: String, senderId: String, senderName: String) {
        val open = callIntent(context, conversationId, senderId, senderName)
        val pending = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // "Nghe máy": opens the app (the microphone may only be used while
        // the app is on screen) and answers at once.
        val acceptIntent = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID + 1,
            callIntent(context, conversationId, senderId, senderName).putExtra("call_action", "accept"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // "Từ chối": handled without opening the app.
        val declineIntent = PendingIntent.getBroadcast(
            context,
            CALL_NOTIFICATION_ID + 2,
            Intent(context, CallActionReceiver::class.java)
                .putExtra("sender_id", senderId)
                .putExtra("notification_id", CALL_NOTIFICATION_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText("Cuộc gọi thoại đến. Chạm để nghe máy.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            // Buttons right on the notification.
            .addAction(0, "Từ chối", declineIntent)
            .addAction(0, "Nghe máy", acceptIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            // The caller stops trying after 45 seconds.
            .setTimeoutAfter(40_000)
            .build()
        // Keep ringing until the notification is opened or times out.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(CALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed for the app: nothing can be shown.
        }
    }

    // A group call just started: ring like a phone call. Tapping (or the
    // full-screen display on a locked phone) opens the app on the "incoming
    // group call" screen, where the call is joined or refused.
    fun showIncomingGroupCall(
        context: Context,
        groupId: String,
        groupName: String,
        callId: String,
        video: Boolean,
        callerName: String,
    ) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("group_call_id", callId)
            putExtra("group_call_group", groupId)
            putExtra("group_call_name", groupName)
            putExtra("group_call_video", video)
            putExtra("group_call_caller", callerName)
            putExtra("group_call_at", System.currentTimeMillis())
        }
        val pending = PendingIntent.getActivity(
            context,
            GROUP_CALL_NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(groupName)
            .setContentText(
                "$callerName đang gọi nhóm" + (if (video) " (video)" else " (thoại)") + ". Chạm để tham gia.",
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(40_000)
            .build()
        // Keep ringing until the notification is opened or times out.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(GROUP_CALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed for the app: nothing can be shown.
        }
    }

    // The group call is over: stop ringing for it.
    fun cancelIncomingGroupCall(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(GROUP_CALL_NOTIFICATION_ID) }
    }

    // ----- Message notifications drawn by the app itself -----------------
    // The server sends a "data" message; the app builds the notification,
    // so it can look like a chat: the sender's round picture on the left,
    // the lines of one conversation collected in one notification.

    // The sender's picture as a circle, or null. Kept in a small folder of
    // files so it is only downloaded once.
    private suspend fun roundAvatar(context: Context, avatarPath: String): Bitmap? {
        if (avatarPath.isBlank()) return null
        return try {
            val folder = File(context.cacheDir, "notification-avatars")
            val file = File(folder, avatarPath.filter { it.isLetterOrDigit() || it == '-' || it == '.' })
            val bytes = if (file.exists() && file.length() > 0) {
                file.readBytes()
            } else {
                val downloaded = withTimeoutOrNull(4_000) { ChatRepository.downloadAvatar(avatarPath) } ?: return null
                runCatching {
                    folder.mkdirs()
                    file.writeBytes(downloaded)
                }
                downloaded
            }
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // Cut the middle square out and draw it as a circle.
            val size = 160
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(
                source,
                (source.width - side) / 2,
                (source.height - side) / 2,
                side,
                side,
            )
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            output
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // A round picture with the first letter of the name, for people
    // without an avatar (same look as inside the app).
    private fun letterAvatar(name: String): Bitmap {
        val size = 160
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF0F766E.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 76f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        val letter = name.trim().take(1).uppercase().ifEmpty { "?" }
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return output
    }

    // My own display name, kept so a notification can tell "@my name" (a
    // mention) also when the app was started just for that notification.
    private const val PUSH_PREFS = "maychat_push"

    fun rememberMyName(context: Context, name: String) {
        runCatching {
            context.applicationContext.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
                .edit().putString("my_name", name).apply()
        }
    }

    // True when a group message mentions me or everybody.
    private fun mentionsMe(context: Context, text: String): Boolean {
        if ("@Tất cả" in text) return true
        val myName = runCatching {
            context.applicationContext.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
                .getString("my_name", null)
        }.getOrNull()
        return !myName.isNullOrBlank() && "@$myName" in text
    }

    // Shows (or adds a line to) the notification of one conversation.
    // data: what the server sent, see fcmMessage in the notification function.
    suspend fun showMessage(context: Context, data: Map<String, String>) {
        val conversationId = data["conversation_id"] ?: return
        val senderId = data["sender_id"] ?: return
        val title = data["title"] ?: data["sender_name"] ?: "MayChat"
        val isGroup = data["is_group"] == "1"
        val personName = (data["person_name"] ?: title).ifBlank { title }
        val plainText = (data["text"] ?: data["body"] ?: "").ifBlank { "Tin nhắn mới" }
        // A mention is marked, so it stands out among the group's messages.
        val text = if (isGroup && mentionsMe(context, plainText)) "📣 Nhắc đến bạn: $plainText" else plainText
        val unread = data["unread"]?.toIntOrNull() ?: 1

        val picture = roundAvatar(context, data["avatar_path"] ?: "") ?: letterAvatar(personName)
        val sender = Person.Builder()
            .setName(personName)
            .setKey(senderId)
            .setIcon(IconCompat.createWithBitmap(picture))
            .build()
        val me = Person.Builder().setName("Bạn").build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = conversationId.hashCode()

        // Earlier lines of this conversation that are still on screen stay.
        val style = runCatching {
            manager.activeNotifications
                .firstOrNull { it.id == notificationId && it.tag == MESSAGE_TAG }
                ?.notification
                ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        }.getOrNull() ?: NotificationCompat.MessagingStyle(me)
        style.setGroupConversation(isGroup)
        if (isGroup) style.setConversationTitle(title)
        style.addMessage(text, System.currentTimeMillis(), sender)

        // Tapping opens that conversation (read by MainActivity).
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", conversationId)
            putExtra("sender_id", senderId)
            putExtra("sender_name", data["sender_name"] ?: title)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Xiaomi / Redmi / POCO phones draw the "chat" layout WITHOUT any
        // picture. On those the plain layout is used instead, whose large
        // picture (on the right) they do show. The lines of one conversation
        // are still collected in one notification.
        val line = if (isGroup) "$personName: $text" else text
        val plainStyle: NotificationCompat.Style? = if (!drawsChatLayoutWithoutPicture()) {
            null
        } else {
            val earlier = runCatching {
                manager.activeNotifications
                    .firstOrNull { it.id == notificationId && it.tag == MESSAGE_TAG }
                    ?.notification?.extras
                    ?.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
                    ?.toList()
            }.getOrNull() ?: emptyList()
            val lines = (earlier + line).takeLast(6)
            if (lines.size == 1) {
                NotificationCompat.BigTextStyle().bigText(line)
            } else {
                NotificationCompat.InboxStyle().also { inbox -> lines.forEach { inbox.addLine(it) } }
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0F766E.toInt())
            .setStyle(plainStyle ?: style)
            // Phones that do not draw the "chat" layout still show the picture.
            .setLargeIcon(picture)
            .setContentTitle(if (isGroup) title else personName)
            .setContentText(line)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setNumber(unread)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(MESSAGE_TAG, notificationId, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed for the app: nothing can be shown.
        }
    }

    private const val MESSAGE_TAG = "chat"

    // True on phones whose own notification design leaves the sender's
    // picture out of "chat" style notifications.
    private fun drawsChatLayoutWithoutPicture(): Boolean {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        return listOf("xiaomi", "redmi", "poco").any { it in maker || it in brand }
    }

    // Removes all MayChat notifications (and with them the number on the icon).
    fun clearNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }
}
