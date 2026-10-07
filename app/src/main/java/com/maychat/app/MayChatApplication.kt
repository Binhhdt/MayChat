package com.maychat.app

import android.app.Application
import com.maychat.app.data.ChatMemory
import com.maychat.app.data.E2E
import com.maychat.app.data.ListCache
import com.maychat.app.push.Push
import com.maychat.app.push.Reminders

// Runs once when the app process starts, before any screen or service.
class MayChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        ListCache.init(this)
        ChatMemory.init(this)
        E2E.init(this)
        Reminders.init(this)
    }
}
