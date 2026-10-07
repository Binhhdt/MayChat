package com.maychat.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// Remembers the lists of the main screens (conversations, groups, numbers
// of unread messages...) on the phone, so they appear at once when the app
// opens; the fresh lists from the server then replace them.
//
// Only what the lists show is kept, never the messages themselves.
// Everything is stored per account and erased when signing out.
object ListCache {
    private const val PREFS = "maychat_lists"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var prefs: SharedPreferences? = null

    // Called once when the app process starts (see MayChatApplication).
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // Any problem with the saved copy just means "nothing saved".
    private fun <T> read(myId: String, name: String, serializer: KSerializer<T>): T? {
        val text = prefs?.getString("$myId/$name", null) ?: return null
        return runCatching { json.decodeFromString(serializer, text) }.getOrNull()
    }

    private fun <T> write(myId: String, name: String, serializer: KSerializer<T>, value: T) {
        runCatching {
            prefs?.edit()?.putString("$myId/$name", json.encodeToString(serializer, value))?.apply()
        }
    }

    private val unreadSerializer = MapSerializer(String.serializer(), Int.serializer())

    // ----- One-to-one conversations --------------------------------------

    fun conversations(myId: String): List<ConversationItem>? {
        val list = read(myId, "conversations", ListSerializer(Conversation.serializer())) ?: return null
        val people = (read(myId, "people", ListSerializer(Profile.serializer())) ?: return null)
            .associateBy { it.id }
        return list.mapNotNull { c ->
            people[if (c.userA == myId) c.userB else c.userA]?.let { ConversationItem(c, it) }
        }
    }

    fun saveConversations(myId: String, items: List<ConversationItem>) {
        write(myId, "conversations", ListSerializer(Conversation.serializer()), items.map { it.conversation })
        write(myId, "people", ListSerializer(Profile.serializer()), items.map { it.other }.distinctBy { it.id })
    }

    fun unread(myId: String): Map<String, Int> = read(myId, "unread", unreadSerializer) ?: emptyMap()
    fun saveUnread(myId: String, value: Map<String, Int>) = write(myId, "unread", unreadSerializer, value)

    fun conversationPrefs(myId: String): Map<String, ConversationPref> =
        (read(myId, "prefs", ListSerializer(ConversationPref.serializer())) ?: emptyList())
            .associateBy { it.conversationId }

    fun saveConversationPrefs(myId: String, value: Map<String, ConversationPref>) =
        write(myId, "prefs", ListSerializer(ConversationPref.serializer()), value.values.toList())

    // ----- Groups ---------------------------------------------------------

    fun groups(myId: String): List<Group>? = read(myId, "groups", ListSerializer(Group.serializer()))
    fun saveGroups(myId: String, value: List<Group>) = write(myId, "groups", ListSerializer(Group.serializer()), value)

    fun groupUnread(myId: String): Map<String, Int> = read(myId, "group-unread", unreadSerializer) ?: emptyMap()
    fun saveGroupUnread(myId: String, value: Map<String, Int>) = write(myId, "group-unread", unreadSerializer, value)

    fun groupPrefs(myId: String): Map<String, GroupPref> =
        (read(myId, "group-prefs", ListSerializer(GroupPref.serializer())) ?: emptyList())
            .associateBy { it.groupId }

    fun saveGroupPrefs(myId: String, value: Map<String, GroupPref>) =
        write(myId, "group-prefs", ListSerializer(GroupPref.serializer()), value.values.toList())

    // ----- My own profile (name and picture at the top) -------------------

    fun me(myId: String): Profile? = read(myId, "me", Profile.serializer())
    fun saveMe(myId: String, value: Profile) = write(myId, "me", Profile.serializer(), value)

    // Signing out: nothing of the account stays on the phone.
    fun clear() {
        runCatching { prefs?.edit()?.clear()?.apply() }
    }
}
