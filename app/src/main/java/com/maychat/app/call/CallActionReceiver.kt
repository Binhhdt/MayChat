package com.maychat.app.call

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Handles the "Từ chối" button of the incoming-call notification.
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val senderId = intent.getStringExtra("sender_id") ?: return
        val notificationId = intent.getIntExtra("notification_id", 0)

        // Stop the ringing notification at once.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationId != 0) manager.cancel(notificationId)

        // Telling the caller needs the network, which takes a moment; Android
        // gives a receiver about ten seconds when it asks for it like this.
        val pending = goAsync()
        CallManager.declineFromNotification(context.applicationContext, senderId) {
            pending.finish()
        }
    }
}
