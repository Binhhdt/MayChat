package com.maychat.app

import android.app.Application
import com.maychat.app.data.ListCache
import com.maychat.app.push.Push

// Runs once when the app process starts, before any screen or service.
class MayChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        ListCache.init(this)
    }
}
