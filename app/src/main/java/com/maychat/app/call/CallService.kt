package com.maychat.app.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.maychat.app.MainActivity
import com.maychat.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// Keeps a call alive while MayChat is not on screen.
//
// Android stops the microphone of an app a short while after it leaves the
// screen, unless the app runs a "foreground service" and shows a permanent
// notification saying what it is doing. This service is exactly that: it
// runs only during a call and shows "Đang trong cuộc gọi" with a button to
// hang up.
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val name = intent?.getStringExtra(EXTRA_NAME) ?: lastName
        lastName = name

        // Android requires startForeground() after every start of the
        // service, also when the service is only started to be stopped.
        val shown = showForeground(name)
        // Somebody waits to hear whether screen capturing is now allowed.
        if (action == ACTION_SHARE) {
            shareAnswer?.complete(shown && screenShare)
            shareAnswer = null
        }

        when (action) {
            ACTION_HANG_UP -> {
                CallManager.hangUp()
                stopNow()
            }
            ACTION_STOP -> stopNow()
        }
        return START_NOT_STICKY
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Returns true when the notification is up with everything asked for
    // (including "is capturing the screen" while sharing).
    private fun showForeground(name: String): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cuộc gọi đang diễn ra", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Hiện trong lúc đang gọi để giữ cuộc gọi khi rời ứng dụng"
                setShowBadge(false)
            },
        )

        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this,
            2,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Đang trong cuộc gọi với $name")
            .setContentText(
                if (screenShare) "Đang chia sẻ màn hình · Chạm để quay lại" else "Chạm để quay lại cuộc gọi",
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Kết thúc", hangUp)
            .build()

        val microphone = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        // While sharing the screen Android (10+) wants that said as well.
        if (screenShare && Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    microphone or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                return true
            } catch (e: Exception) {
                // Not allowed: carry on as a normal call (below), no sharing.
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, microphone)
            // Below Android 10 nothing more is needed to capture the screen.
            !screenShare || Build.VERSION.SDK_INT < 29
        } catch (e: Exception) {
            // Not allowed right now (for example the app is not on screen):
            // the call still works while MayChat stays open.
            stopSelf()
            false
        }
    }

    companion object {
        private const val CHANNEL_ID = "ongoing_call"
        private const val NOTIFICATION_ID = 7002
        private const val EXTRA_NAME = "name"
        private const val ACTION_HANG_UP = "com.maychat.app.call.HANG_UP"
        private const val ACTION_STOP = "com.maychat.app.call.STOP"
        private const val ACTION_SHARE = "com.maychat.app.call.SHARE"

        private var running = false
        private var lastName = "MayChat"

        // True while my screen is being shared in the call.
        @Volatile
        private var screenShare = false
        private var shareAnswer: CompletableDeferred<Boolean>? = null

        // Before capturing the screen: marks the call's notification as
        // "capturing the screen" and waits until Android accepted that.
        // Returns false when Android refuses.
        suspend fun beginScreenShare(context: Context): Boolean {
            screenShare = true
            val answer = CompletableDeferred<Boolean>()
            shareAnswer = answer
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallService::class.java).setAction(ACTION_SHARE).putExtra(EXTRA_NAME, lastName),
                )
                running = true
            } catch (e: Exception) {
                shareAnswer = null
                screenShare = false
                return false
            }
            return withTimeoutOrNull(3_000) { answer.await() } ?: false
        }

        // Sharing is over: the notification is a normal call again.
        fun endScreenShare(context: Context) {
            if (!screenShare) return
            screenShare = false
            // Only while the app is on screen: Android refuses to renew the
            // notification of an app in the background, and the call must
            // not lose it. (Then it simply keeps its wording until the end.)
            if (!running || !com.maychat.app.push.Push.appVisible) return
            runCatching {
                context.startService(
                    Intent(context, CallService::class.java).setAction(ACTION_SHARE).putExtra(EXTRA_NAME, lastName),
                )
            }
        }

        // Called when a call starts (the app is on screen at that moment).
        fun start(context: Context, peerName: String) {
            lastName = peerName
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallService::class.java).putExtra(EXTRA_NAME, peerName),
                )
                running = true
            } catch (e: Exception) {
                running = false
            }
        }

        // Called when the call is over.
        fun stop(context: Context) {
            screenShare = false
            if (!running) return
            running = false
            try {
                context.startService(Intent(context, CallService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                runCatching { context.stopService(Intent(context, CallService::class.java)) }
            }
        }
    }
}
