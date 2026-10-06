package com.maychat.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import com.maychat.app.call.CallManager
import com.maychat.app.push.ChatToOpen
import com.maychat.app.push.Push
import com.maychat.app.ui.MayChatApp
import com.maychat.app.ui.theme.MayChatTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The single Activity of the app. Every screen is a Compose function shown inside it.
class MainActivity : ComponentActivity() {

    private var lockScreenJob: Job? = null

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
        Push.appVisible = true
        Push.clearNotifications(this)
    }

    override fun onPause() {
        super.onPause()
        Push.appVisible = false
    }

    // A tapped notification carries the conversation to open.
    private fun handleNotificationTap(intent: Intent?) {
        val extras = intent?.extras ?: return
        val conversationId = extras.getString("conversation_id") ?: return
        val senderId = extras.getString("sender_id") ?: return
        val senderName = extras.getString("sender_name") ?: "MayChat"
        val incomingCall = extras.getBoolean("incoming_call", false)
        Push.requestOpenChat(ChatToOpen(conversationId, senderId, senderName))
        // Do not open the same chat again after a screen rotation.
        intent?.removeExtra("conversation_id")
        intent?.removeExtra("incoming_call")
        if (incomingCall) showOverLockScreenForCall()
    }

    // An incoming call opened the app: allow it to appear over the lock screen
    // and switch the display on, like a normal phone call.
    //
    // This is switched off again as soon as the call is over (or after 50
    // seconds if no call arrives), so the app can NOT be used on a locked
    // phone at any other time.
    private fun showOverLockScreenForCall() {
        setLockScreenMode(true)
        lockScreenJob?.cancel()
        lockScreenJob = lifecycleScope.launch {
            // Wait for the call screen to appear (the app needs a few seconds
            // to reconnect and receive the call)...
            withTimeoutOrNull(50_000) {
                snapshotFlow { CallManager.ui != null }.first { it }
            }
            // ...then wait until there is no call any more.
            snapshotFlow { CallManager.ui == null }.first { it }
            setLockScreenMode(false)
        }
    }

    private fun setLockScreenMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (enabled) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
