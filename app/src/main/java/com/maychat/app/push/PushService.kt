package com.maychat.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.DeviceId
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.data.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Receives events from Firebase Cloud Messaging.
class PushService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Firebase gave this phone a new address: tell the server, if logged in.
    override fun onNewToken(token: String) {
        if (!SupabaseProvider.isConfigured) return
        scope.launch {
            attempt {
                if (ChatRepository.deviceId.isBlank()) {
                    ChatRepository.deviceId = DeviceId.get(applicationContext)
                }
                if (ChatRepository.currentUserId() != null) {
                    ChatRepository.registerPushToken(token)
                }
            }
        }
    }

    // Only called while the app is OPEN ON SCREEN. In that case the message
    // already appears live inside the app, so no notification is shown.
    // When the app is in the background or closed, Android shows the
    // notification by itself and this function is not called.
    //
    // Incoming CALLS are different: the server sends them as a "data" message,
    // which reaches this function even when the app is closed, so the app
    // can ring and show the incoming call over the lock screen.
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "call") return
        // App already on screen: the live connection shows the call itself.
        if (Push.appVisible) return

        val conversationId = data["conversation_id"] ?: return
        val senderId = data["sender_id"] ?: return
        val senderName = data["sender_name"] ?: "MayChat"
        Push.showIncomingCall(applicationContext, conversationId, senderId, senderName)
        Push.tryOpenCallScreen(applicationContext, conversationId, senderId, senderName)
    }
}
