package com.maychat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.maychat.app.push.ChatToOpen
import com.maychat.app.push.Push
import com.maychat.app.ui.MayChatApp
import com.maychat.app.ui.theme.MayChatTheme

// The single Activity of the app. Every screen is a Compose function shown inside it.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationTap(intent)
        setContent {
            MayChatTheme {
                MayChatApp()
            }
        }
    }

    // The app was already running and a notification was tapped.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    // The app is on screen: remove its notifications and the icon number.
    override fun onResume() {
        super.onResume()
        Push.clearNotifications(this)
    }

    // A tapped notification carries the conversation to open.
    private fun handleNotificationTap(intent: Intent?) {
        val extras = intent?.extras ?: return
        val conversationId = extras.getString("conversation_id") ?: return
        val senderId = extras.getString("sender_id") ?: return
        val senderName = extras.getString("sender_name") ?: "MayChat"
        Push.requestOpenChat(ChatToOpen(conversationId, senderId, senderName))
        // Do not open the same chat again after a screen rotation.
        intent?.removeExtra("conversation_id")
    }
}
