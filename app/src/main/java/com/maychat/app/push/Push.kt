package com.maychat.app.push

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.maychat.app.BuildConfig
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

    // Removes all MayChat notifications (and with them the number on the icon).
    fun clearNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }
}
