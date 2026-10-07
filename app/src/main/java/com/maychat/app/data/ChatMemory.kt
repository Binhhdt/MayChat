package com.maychat.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Remembers the newest messages of every chat that was opened, so opening
// it again shows them at once while the fresh ones are fetched.
//
// Two levels:
//   * in memory, for chats opened since the app started;
//   * in a private file per chat, so they are also there after the app was
//     closed. The files are in the app's own storage (other apps cannot
//     read them), hold only the newest page of each chat, belong to one
//     account, and are erased when signing out or deleting a conversation.
object ChatMemory {
    @Serializable
    class Chat(
        val messages: List<Message>,
        val hidden: Set<String>,
        val reactions: List<Reaction> = emptyList(),
        val wallpaper: String? = null,
        val pinned: PinnedMessage? = null,
    )

    @Serializable
    class GroupChat(
        val messages: List<GroupMessage>,
        val hidden: Set<String>,
        val group: Group?,
        val members: List<Profile>,
        val reactions: List<Reaction> = emptyList(),
        val pinned: PinnedMessage? = null,
        val memberRows: List<GroupMember> = emptyList(),
    )

    private val chats = ConcurrentHashMap<String, Chat>()
    private val groupChats = ConcurrentHashMap<String, GroupChat>()

    // The call history as last shown (memory only).
    @Volatile
    var calls: List<CallItem>? = null

    // ----- Files ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Mutex()

    // What still has to be written, by file name. A chat that changes
    // several times in a moment is written once.
    private val waiting = ConcurrentHashMap<String, () -> String>()

    @Volatile
    private var root: File? = null

    // Called once when the app process starts (see MayChatApplication).
    fun init(context: Context) {
        root = File(context.applicationContext.filesDir, "chat-cache")
    }

    // The folder of the logged-in account, or null when nobody is logged in.
    private fun folder(): File? {
        val base = root ?: return null
        val me = ChatRepository.currentUserId() ?: return null
        return File(base, me.filter { it.isLetterOrDigit() || it == '-' })
    }

    private fun fileName(prefix: String, id: String) =
        prefix + id.filter { it.isLetterOrDigit() || it == '-' } + ".json"

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = File(folder() ?: return null, name)
        return runCatching {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
        }.getOrNull()
    }

    private fun writeLater(name: String, produce: () -> String) {
        val dir = folder() ?: return
        waiting[name] = produce
        io.launch {
            delay(500)
            val job = waiting.remove(name) ?: return@launch
            fileLock.withLock {
                runCatching {
                    dir.mkdirs()
                    File(dir, name).writeText(job())
                }
            }
        }
    }

    private fun delete(name: String) {
        waiting.remove(name)
        val dir = folder() ?: return
        io.launch { fileLock.withLock { runCatching { File(dir, name).delete() } } }
    }

    // ----- One-to-one chats ----------------------------------------------

    // Remembered messages are shown only after the server confirmed that
    // this phone holds the account (same rule as the remembered lists).
    private fun trusted(): Boolean = ChatRepository.currentUserId()?.let { ListCache.trusted(it) } == true

    fun chat(conversationId: String): Chat? {
        if (!trusted()) return null
        return chats[conversationId]
            ?: read(fileName("c-", conversationId), Chat.serializer())?.also { chats[conversationId] = it }
    }

    fun saveChat(conversationId: String, chat: Chat) {
        chats[conversationId] = chat
        writeLater(fileName("c-", conversationId)) { json.encodeToString(Chat.serializer(), chat) }
    }

    fun forgetChat(conversationId: String) {
        chats.remove(conversationId)
        delete(fileName("c-", conversationId))
    }

    // ----- Groups ---------------------------------------------------------

    fun groupChat(groupId: String): GroupChat? {
        if (!trusted()) return null
        return groupChats[groupId]
            ?: read(fileName("g-", groupId), GroupChat.serializer())?.also { groupChats[groupId] = it }
    }

    fun saveGroupChat(groupId: String, chat: GroupChat) {
        groupChats[groupId] = chat
        writeLater(fileName("g-", groupId)) { json.encodeToString(GroupChat.serializer(), chat) }
    }

    fun forgetGroupChat(groupId: String) {
        groupChats.remove(groupId)
        delete(fileName("g-", groupId))
    }

    // Signing out: nothing of any account stays, in memory or on the phone.
    fun clear() {
        chats.clear()
        groupChats.clear()
        calls = null
        waiting.clear()
        val base = root ?: return
        io.launch { fileLock.withLock { runCatching { base.deleteRecursively() } } }
    }
}
