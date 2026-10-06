package com.maychat.app.data

import android.content.Context
import java.util.UUID

// A random id created the first time the app runs on this phone. It only
// tells the server "this is the same installation as before". It is not the
// phone's serial number and it is lost when the app is uninstalled.
object DeviceId {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("maychat_device", Context.MODE_PRIVATE)
        prefs.getString("id", null)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("id", created).apply()
        return created
    }
}
