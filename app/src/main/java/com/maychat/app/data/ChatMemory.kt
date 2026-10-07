package com.maychat.app.data

import java.util.concurrent.ConcurrentHashMap

// Keeps, IN MEMORY ONLY, the newest messages of the chats opened since the
// app started, so opening a chat again shows them at once while the fresh
// ones are fetched. Nothing here is written to the phone's storage; it is
// gone when the app is closed, and erased when signing out.
object ChatMemory {
    class Chat(val messages: List<Message>, val hidden: Set<String>)
    class GroupChat(
        val messages: List<GroupMessage>,
        val hidden: Set<String>,
        val group: Group?,
        val members: List<Profile>,
    )

    val chats = ConcurrentHashMap<String, Chat>()
    val groupChats = ConcurrentHashMap<String, GroupChat>()

    // The call history as last shown.
    @Volatile
    var calls: List<CallItem>? = null

    fun clear() {
        chats.clear()
        groupChats.clear()
        calls = null
    }
}
