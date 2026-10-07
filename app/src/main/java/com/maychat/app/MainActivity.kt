package com.maychat.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.util.Rational
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.maychat.app.call.CallManager
import com.maychat.app.call.CallPhase
import com.maychat.app.call.GroupCallManager
import com.maychat.app.call.GroupRing
import com.maychat.app.push.ChatToOpen
import com.maychat.app.push.Push
import com.maychat.app.ui.MayChatApp
import com.maychat.app.ui.chat.CameraCapture
import com.maychat.app.ui.theme.MayChatTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The single Activity of the app. Every screen is a Compose function shown inside it.
class MainActivity : ComponentActivity() {

    private var lockScreenJob: Job? = null

    // Opening the camera app and getting its answer is registered HERE, on
    // the Activity, so the answer still arrives when Android has closed and
    // re-created the screen while the camera was open.
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        CameraCapture.onResult(applicationContext, saved)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CameraCapture.launcher = { uri -> takePhoto.launch(uri) }
        handleNotificationTap(intent)
        watchCallForFloatingWindow()
        setContent {
            MayChatTheme {
                MayChatApp()
            }
        }
    }

    // ----- Video call as a small floating window -------------------------

    // A video call that is being set up or running.
    private fun callWantsFloatingWindow(): Boolean {
        val call = CallManager.ui ?: return false
        return call.video &&
            call.phase != CallPhase.INCOMING &&
            call.phase != CallPhase.ENDED
    }

    private fun floatingWindowParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        // Android 12+: also when leaving with the "home" swipe.
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    // Leaving the app (Home button) during a video call: keep the call on
    // screen as a small floating window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Only from the call screen itself: while the call is put aside the
        // app opens other screens (camera, photo picker...), which Android
        // reports the same way as "Home".
        if (callWantsFloatingWindow() && !CallManager.minimized && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(floatingWindowParams(true)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        CallManager.inPip = isInPictureInPictureMode
    }

    private fun watchCallForFloatingWindow() {
        lifecycleScope.launch {
            snapshotFlow { callWantsFloatingWindow() }.collect { wanted ->
                runCatching { setPictureInPictureParams(floatingWindowParams(wanted)) }
                // The call ended while it was a floating window: put the
                // small window away instead of showing the chats in it.
                if (!wanted && isInPictureInPictureMode) runCatching { moveTaskToBack(true) }
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

    override fun onDestroy() {
        CameraCapture.launcher = null
        super.onDestroy()
    }

    // A tapped notification carries the conversation to open.
    private fun handleNotificationTap(intent: Intent?) {
        val extras = intent?.extras ?: return
        // The notification of a group call: show the "incoming group call"
        // screen (only while the call can still be ringing).
        val groupCallId = extras.getString("group_call_id")
        if (groupCallId != null) {
            val shownAt = extras.getLong("group_call_at", 0L)
            if (System.currentTimeMillis() - shownAt < 60_000) {
                GroupCallManager.startRing(
                    GroupRing(
                        groupId = extras.getString("group_call_group") ?: "",
                        groupName = extras.getString("group_call_name") ?: "Nhóm",
                        callId = groupCallId,
                        video = extras.getBoolean("group_call_video", false),
                        callerName = extras.getString("group_call_caller") ?: "Một thành viên",
                    ),
                )
            }
            intent.removeExtra("group_call_id")
            return
        }
        val conversationId = extras.getString("conversation_id") ?: return
        val senderId = extras.getString("sender_id") ?: return
        val senderName = extras.getString("sender_name") ?: "MayChat"
        val incomingCall = extras.getBoolean("incoming_call", false)
        val callAction = extras.getString("call_action")
        Push.requestOpenChat(ChatToOpen(conversationId, senderId, senderName))
        // Do not open the same chat again after a screen rotation.
        intent?.removeExtra("conversation_id")
        intent?.removeExtra("incoming_call")
        intent?.removeExtra("call_action")
        if (incomingCall) {
            // Ring and show the incoming call screen right away.
            CallManager.prepareIncoming(applicationContext, senderId, senderName)
            showOverLockScreenForCall()
            // "Nghe máy" was pressed on the notification: answer right away
            // when the microphone is already allowed. Otherwise the call
            // screen stays, and its own button asks for the permission.
            val micAllowed = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (callAction == "accept" && micAllowed) CallManager.accept()
        }
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
