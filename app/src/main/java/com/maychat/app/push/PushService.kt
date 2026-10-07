package com.maychat.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.maychat.app.call.CallManager
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.DeviceId
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.call.GROUP_CALL_END_TEXT
import com.maychat.app.call.GROUP_CALL_VIDEO_TEXT
import com.maychat.app.call.GROUP_CALL_VOICE_TEXT
import com.maychat.app.call.GroupCallManager
import com.maychat.app.call.groupCallLink
import com.maychat.app.data.E2E
import com.maychat.app.data.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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

    // The server sends "data" messages, which reach this function also when
    // the app is in the background or closed:
    //   type = "message": a chat message. The app draws the notification
    //       itself and tells the server "received" ("Đã nhận").
    //   type = "call": an incoming call. The app rings and shows the
    //       incoming call over the lock screen.
    // (A notification of the OLD kind, drawn by Android itself, has no type;
    // it only gets here while the app is on screen and is then ignored,
    // because the message already appears live inside the app.)
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "message") {
            handleChatMessage(data)
            return
        }
        if (data["type"] != "call") return
        // App already on screen: the call shows up by itself. Fetch the
        // waiting call messages right away instead of at the next check.
        if (Push.appVisible) {
            CallManager.pokeSignals()
            return
        }

        val conversationId = data["conversation_id"] ?: return
        val senderId = data["sender_id"] ?: return
        val senderName = data["sender_name"] ?: "MayChat"
        Push.showIncomingCall(applicationContext, conversationId, senderId, senderName)
        Push.tryOpenCallScreen(applicationContext, conversationId, senderId, senderName)
    }

    // A chat message arrived. Runs to the end before returning, because
    // Android may stop the app's process right after this function.
    private fun handleChatMessage(data: Map<String, String>) {
        // App on screen: the message is already there, and the app itself
        // reports "received".
        if (Push.appVisible) return
        if (!SupabaseProvider.isConfigured) return
        runBlocking {
            withTimeoutOrNull(12_000) {
                // After Android started the app for this message, the saved
                // login needs a moment to load (needed for the picture and
                // for "received").
                val loggedIn = withTimeoutOrNull(4_000) {
                    ChatRepository.sessionStatus.first { it is SessionStatus.Authenticated }
                } != null
                // Nobody is logged in on this phone: show nothing.
                if (!loggedIn) return@withTimeoutOrNull

                // A group call that just started rings instead of showing
                // as a message. The server is asked first, so a message
                // that arrives late does not ring for a call long over.
                // A message of an encrypted conversation is opened here, on
                // the phone, before it is shown.
                @Suppress("NAME_SHADOWING")
                var data = data
                val rawText = data["text"] ?: data["body"] ?: ""
                val conversation = data["conversation_id"]
                if (data["is_group"] != "1" && conversation != null && E2E.isLocked(rawText)) {
                    val opened = attempt { E2E.open(conversation, rawText) }.getOrNull() ?: E2E.UNREADABLE
                    data = data + mapOf("text" to opened, "body" to opened)
                }
                val messageText = data["text"] ?: data["body"] ?: ""
                val groupId = data["conversation_id"]
                var rang = false
                if (data["is_group"] == "1" && groupId != null &&
                    (messageText.startsWith(GROUP_CALL_VOICE_TEXT) || messageText.startsWith(GROUP_CALL_VIDEO_TEXT))
                ) {
                    val latest = attempt { ChatRepository.loadLatestGroupMessage(groupId) }.getOrNull()
                    val call = latest?.let { groupCallLink(it.kind, it.extra) }
                    if (latest != null && call != null && GroupCallManager.isFresh(latest.createdAt)) {
                        Push.showIncomingGroupCall(
                            applicationContext,
                            groupId,
                            data["title"] ?: "Nhóm",
                            call.callId,
                            call.video,
                            data["person_name"] ?: "Một thành viên",
                        )
                        rang = true
                    }
                }
                // "The group call is over": stop the ringing, show nothing.
                if (data["is_group"] == "1" && messageText.startsWith(GROUP_CALL_END_TEXT)) {
                    Push.cancelIncomingGroupCall(applicationContext)
                    rang = true
                }
                if (!rang) attempt { Push.showMessage(applicationContext, data) }

                // A new group reminder was announced: set this phone's alarm
                // for it now, so it rings even if the app is never opened.
                if (data["is_group"] == "1" && (data["text"] ?: data["body"] ?: "").startsWith("⏰")) {
                    attempt { Reminders.sync(force = true) }
                }

                // "Đã nhận" for the sender (one-to-one messages).
                if (data["is_group"] != "1") {
                    attempt {
                        if (ChatRepository.deviceId.isBlank()) {
                            ChatRepository.deviceId = DeviceId.get(applicationContext)
                        }
                        ChatRepository.markDelivered()
                    }
                }
            }
        }
    }
}
