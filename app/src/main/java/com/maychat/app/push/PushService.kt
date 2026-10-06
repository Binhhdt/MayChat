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
    override fun onMessageReceived(message: RemoteMessage) {
        // Nothing to do.
    }
}
