package com.maychat.app.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

// The only place in the app that talks to Supabase.
// Screens call these functions; they never use the Supabase client directly.
object ChatRepository {

    const val PAGE_SIZE = 30

    private val supabase get() = SupabaseProvider.client
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    // Tells the app whether someone is logged in. The session is stored on
    // the phone, so the user stays logged in after closing the app.
    val sessionStatus: StateFlow<SessionStatus>
        get() = supabase.auth.sessionStatus

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    suspend fun isUsernameAvailable(username: String): Boolean {
        val result = supabase.postgrest.rpc(
            "username_available",
            buildJsonObject { put("p_username", username) },
        )
        return result.data.trim() == "true"
    }

    // Creates the account. The database creates the profile in the same step
    // (see handle_new_user in supabase_schema.sql).
    suspend fun signUp(email: String, password: String, username: String, displayName: String) {
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject {
                put("username", username)
                put("display_name", displayName)
            }
        }
    }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        stopRealtime()
        supabase.auth.signOut()
    }

    // ------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------

    suspend fun loadProfile(userId: String): Profile? =
        supabase.postgrest.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeList<Profile>()
            .firstOrNull()

    // Finds users whose username starts with the typed text (max 20 results).
    suspend fun searchUsers(query: String, myId: String): List<Profile> {
        val clean = query.trim().lowercase().removePrefix("@")
            .filter { it.isLetterOrDigit() || it == '_' }
        if (clean.isEmpty()) return emptyList()
        return supabase.postgrest.from("profiles")
            .select {
                filter {
                    like("username", "$clean%")
                    neq("id", myId)
                }
                order("username", Order.ASCENDING)
                limit(20L)
            }
            .decodeList<Profile>()
    }

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    // Returns the id of my conversation with this user (created on first use).
    suspend fun openConversation(otherUserId: String): String {
        val result = supabase.postgrest.rpc(
            "get_or_create_conversation",
            buildJsonObject { put("p_other_user", otherUserId) },
        )
        return result.data.trim().trim('"')
    }

    // The security rules only return conversations I am a member of.
    suspend fun loadConversations(myId: String): List<ConversationItem> {
        val conversations = supabase.postgrest.from("conversations")
            .select { limit(100L) }
            .decodeList<Conversation>()
        if (conversations.isEmpty()) return emptyList()

        val otherIds = conversations.map { if (it.userA == myId) it.userB else it.userA }.distinct()
        val profiles = supabase.postgrest.from("profiles")
            .select { filter { isIn("id", otherIds) } }
            .decodeList<Profile>()
            .associateBy { it.id }

        return conversations
            .mapNotNull { c ->
                val otherId = if (c.userA == myId) c.userB else c.userA
                profiles[otherId]?.let { ConversationItem(c, it) }
            }
            .sortedByDescending { toEpochMillis(it.conversation.lastMessageAt ?: it.conversation.createdAt) }
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    // Loads one page of messages, newest first. Pass the time of the oldest
    // message already shown to get the page before it.
    suspend fun loadMessages(conversationId: String, before: String? = null): List<Message> {
        // Convert to the "...Z" form so the value contains no "+" sign.
        val beforeUtc = before?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toString() }.getOrNull()
        }
        return supabase.postgrest.from("messages")
            .select {
                filter {
                    eq("conversation_id", conversationId)
                    if (beforeUtc != null) lt("created_at", beforeUtc)
                }
                order("created_at", Order.DESCENDING)
                limit(PAGE_SIZE.toLong())
            }
            .decodeList<Message>()
    }

    suspend fun sendMessage(conversationId: String, text: String): Message =
        supabase.postgrest.from("messages")
            .insert(NewMessage(conversationId, text)) { select() }
            .decodeSingle<Message>()

    suspend fun markConversationRead(conversationId: String) {
        supabase.postgrest.rpc(
            "mark_conversation_read",
            buildJsonObject { put("p_conversation", conversationId) },
        )
    }

    // ------------------------------------------------------------------
    // Realtime: new messages, read receipts, who is online
    // ------------------------------------------------------------------

    private val _messageEvents = MutableSharedFlow<Message>(extraBufferCapacity = 64)

    // Every new or changed message I am allowed to see arrives here.
    val messageEvents: SharedFlow<Message> = _messageEvents.asSharedFlow()

    private val _onlineUsers = MutableStateFlow<Set<String>>(emptySet())

    // Ids of users who currently have the app open.
    val onlineUsers: StateFlow<Set<String>> = _onlineUsers.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)

    // Goes up by one each time the live connection is (re)established.
    // Screens reload their data when this changes, to catch up on anything
    // that happened while the phone was offline.
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private var realtimeJob: Job? = null
    private var dbChannel: RealtimeChannel? = null
    private var presenceChannel: RealtimeChannel? = null
    private val onlineCounts = HashMap<String, Int>()

    suspend fun startRealtime(myId: String) {
        stopRealtime()

        // Channel 1: database changes of the "messages" table. The server only
        // sends rows this user may read (Row Level Security).
        val db = supabase.channel("messages-$myId-${System.currentTimeMillis()}")
        // Channel 2: one shared channel where every open app announces itself.
        val presence = supabase.channel("online-users")
        dbChannel = db
        presenceChannel = presence

        realtimeJob = scope.launch {
            val changes = db.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "messages"
            }
            launch {
                changes.collect { action ->
                    val record: JsonObject? = when (action) {
                        is PostgresAction.Insert -> action.record
                        is PostgresAction.Update -> action.record
                        else -> null
                    }
                    if (record != null) {
                        runCatching { json.decodeFromJsonElement(Message.serializer(), record) }
                            .onSuccess { _messageEvents.emit(it) }
                    }
                }
            }

            val presenceChanges = presence.presenceChangeFlow()
            launch {
                presenceChanges.collect { action ->
                    synchronized(onlineCounts) {
                        action.joins.values.forEach { p ->
                            val id = p.state["user_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            onlineCounts[id] = (onlineCounts[id] ?: 0) + 1
                        }
                        action.leaves.values.forEach { p ->
                            val id = p.state["user_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val left = (onlineCounts[id] ?: 0) - 1
                            if (left <= 0) onlineCounts.remove(id) else onlineCounts[id] = left
                        }
                        _onlineUsers.value = onlineCounts.keys.toSet()
                    }
                }
            }

            // Announce "I am online" every time the presence channel is joined,
            // including after an automatic reconnect.
            launch {
                presence.status.collect { status ->
                    if (status == RealtimeChannel.Status.SUBSCRIBED) {
                        runCatching { presence.track(buildJsonObject { put("user_id", myId) }) }
                    }
                }
            }
            launch {
                db.status.collect { status ->
                    if (status == RealtimeChannel.Status.SUBSCRIBED) {
                        _connectionCount.value = _connectionCount.value + 1
                    }
                }
            }

            // Give the collectors above a moment to start listening, then join.
            delay(300)
            launch { joinWithRetry(db) }
            launch { joinWithRetry(presence) }
        }
    }

    // Joins a channel. If the phone is offline right now, tries again every
    // few seconds. After the first successful join, the library reconnects
    // by itself when the network drops and comes back.
    private suspend fun joinWithRetry(channel: RealtimeChannel) {
        while (currentCoroutineContext().isActive &&
            channel.status.value != RealtimeChannel.Status.SUBSCRIBED
        ) {
            try {
                withTimeoutOrNull(10_000) { channel.subscribe(blockUntilSubscribed = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Offline or server not reachable: fall through and retry.
            }
            if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) delay(3_000)
        }
    }

    suspend fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        dbChannel?.let { ch -> runCatching { supabase.realtime.removeChannel(ch) } }
        presenceChannel?.let { ch -> runCatching { supabase.realtime.removeChannel(ch) } }
        dbChannel = null
        presenceChannel = null
        synchronized(onlineCounts) { onlineCounts.clear() }
        _onlineUsers.value = emptySet()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    fun toEpochMillis(timestamp: String?): Long =
        if (timestamp == null) 0L
        else runCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrDefault(0L)
}
