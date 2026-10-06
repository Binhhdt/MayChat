package com.maychat.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.maychat.app.BuildConfig
import com.maychat.app.MainActivity
import com.maychat.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// A chat the user asked to open by tapping a notification.
data class ChatToOpen(val conversationId: String, val senderId: String, val senderName: String)

// Everything about push notifications on the phone side.
object Push {
    const val CHANNEL_ID = "messages"

    // Separate channel for incoming calls: rings like a phone call.
    const val CALL_CHANNEL_ID = "calls"
    private const val CALL_NOTIFICATION_ID = 7001

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
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText("Cuộc gọi thoại đến. Chạm để nghe máy.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
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

    // Removes all MayChat notifications (and with them the number on the icon).
    fun clearNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }
}
