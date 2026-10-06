package com.maychat.app.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // Asks the database whether an account with this email exists
    // (see supabase_migration_04_email_check.sql).
    suspend fun isEmailRegistered(email: String): Boolean {
        val result = supabase.postgrest.rpc(
            "email_registered",
            buildJsonObject { put("p_email", email) },
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
        // Free the account for other devices (does nothing if this device
        // is not the one holding it).
        attempt {
            supabase.postgrest.rpc("release_session", buildJsonObject { put("p_device", deviceId) })
        }
        // This phone must stop receiving notifications for this account.
        attempt {
            supabase.postgrest.rpc("unregister_push_token", buildJsonObject { put("p_device", deviceId) })
        }
        stopRealtime()
        supabase.auth.signOut()
    }

    // ------------------------------------------------------------------
    // One device at a time (see supabase_migration_05_single_device.sql)
    // ------------------------------------------------------------------

    // Set once when the app starts (see MayChatApp).
    var deviceId: String = ""

    private val _authNotice = MutableStateFlow<String?>(null)

    // A message for the login screen, for example why the user was signed out.
    val authNotice: StateFlow<String?> = _authNotice.asStateFlow()

    fun clearAuthNotice() {
        _authNotice.value = null
    }

    // True if this device now holds the account. False if another device
    // is using the account right now.
    suspend fun claimSession(): Boolean {
        val result = supabase.postgrest.rpc(
            "claim_session",
            buildJsonObject { put("p_device", deviceId) },
        )
        return result.data.trim() == "true"
    }

    // Tells the server where to send this account's notifications
    // (see supabase_migration_06_push.sql).
    suspend fun registerPushToken(token: String) {
        supabase.postgrest.rpc(
            "register_push_token",
            buildJsonObject {
                put("p_device", deviceId)
                put("p_token", token)
            },
        )
    }

    // Signs out and tells the login screen why.
    suspend fun signOutWithNotice(message: String) {
        _authNotice.value = message
        attempt { signOut() }
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

    suspend fun loadProfiles(ids: List<String>): List<Profile> {
        if (ids.isEmpty()) return emptyList()
        return supabase.postgrest.from("profiles")
            .select { filter { isIn("id", ids) } }
            .decodeList<Profile>()
    }

    // ------------------------------------------------------------------
    // Friends and blocking
    // The security rules only return rows that involve me. All changes go
    // through database functions that check who is calling.
    // ------------------------------------------------------------------

    suspend fun loadFriendships(): List<Friendship> =
        supabase.postgrest.from("friendships")
            .select { limit(500L) }
            .decodeList<Friendship>()

    suspend fun loadBlockedIds(): List<String> =
        supabase.postgrest.from("blocks")
            .select { limit(500L) }
            .decodeList<Block>()
            .map { it.blockedId }

    suspend fun sendFriendRequest(otherId: String) {
        supabase.postgrest.rpc("send_friend_request", buildJsonObject { put("p_other", otherId) })
    }

    suspend fun respondFriendRequest(otherId: String, accept: Boolean) {
        supabase.postgrest.rpc(
            "respond_friend_request",
            buildJsonObject {
                put("p_other", otherId)
                put("p_accept", accept)
            },
        )
    }

    suspend fun removeFriend(otherId: String) {
        supabase.postgrest.rpc("remove_friend", buildJsonObject { put("p_other", otherId) })
    }

    suspend fun blockUser(otherId: String) {
        supabase.postgrest.rpc("block_user", buildJsonObject { put("p_other", otherId) })
    }

    suspend fun unblockUser(otherId: String) {
        supabase.postgrest.rpc("unblock_user", buildJsonObject { put("p_other", otherId) })
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
    suspend fun loadMessages(
        conversationId: String,
        before: String? = null,
        limit: Int = PAGE_SIZE,
    ): List<Message> {
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
                limit(limit.toLong())
            }
            .decodeList<Message>()
    }

    suspend fun sendMessage(conversationId: String, text: String): Message =
        supabase.postgrest.from("messages")
            .insert(NewMessage(conversationId, text)) { select() }
            .decodeSingle<Message>()

    // ------------------------------------------------------------------
    // Images and voice messages (Supabase Storage, private bucket)
    // ------------------------------------------------------------------

    const val MEDIA_BUCKET = "chat-media"

    // path looks like "<conversation id>/<random name>.jpg"
    suspend fun uploadMedia(path: String, bytes: ByteArray) {
        supabase.storage.from(MEDIA_BUCKET).upload(path, bytes)
    }

    suspend fun downloadMedia(path: String): ByteArray =
        supabase.storage.from(MEDIA_BUCKET).downloadAuthenticated(path)

    suspend fun sendMediaMessage(
        conversationId: String,
        kind: String,
        mediaPath: String,
        label: String,
        durationMs: Int?,
    ): Message =
        supabase.postgrest.from("messages")
            .insert(NewMediaMessage(conversationId, label, kind, mediaPath, durationMs)) { select() }
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

    private val _friendEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    // Fires when a friend request or friendship that involves me changes.
    val friendEvents: SharedFlow<Unit> = _friendEvents.asSharedFlow()

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

    // Makes sure two restarts never run at the same time.
    private val realtimeMutex = Mutex()

    suspend fun startRealtime(myId: String) = realtimeMutex.withLock {
        closeRealtime()
        openRealtime(myId)
    }

    suspend fun stopRealtime() = realtimeMutex.withLock {
        closeRealtime()
    }

    private fun openRealtime(myId: String) {

        // Channel 1: database changes of the "messages" and "friendships" tables. The server only
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

            val friendChanges = db.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "friendships"
            }
            launch {
                friendChanges.collect { _friendEvents.emit(Unit) }
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

            // Watchdog: phones often drop the live connection (screen off,
            // Wi-Fi to mobile data, battery saver). If a channel stays
            // disconnected for about 20 seconds, throw both channels away
            // and build fresh ones.
            launch {
                var badChecks = 0
                while (isActive) {
                    delay(7_000)
                    val healthy = db.status.value == RealtimeChannel.Status.SUBSCRIBED &&
                        presence.status.value == RealtimeChannel.Status.SUBSCRIBED
                    if (healthy) {
                        badChecks = 0
                    } else {
                        badChecks++
                        if (badChecks >= 3) {
                            // Started from the outer scope because the restart
                            // cancels this very coroutine.
                            scope.launch { startRealtime(myId) }
                            break
                        }
                    }
                }
            }
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

    private suspend fun closeRealtime() {
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
    // "Is typing" indicator
    // Uses Realtime Broadcast: small messages that go straight from one
    // phone to the other and are never stored in the database.
    // ------------------------------------------------------------------

    private val typingChannels = HashMap<String, RealtimeChannel>()

    // Listens for "the other person is typing" in one conversation and calls
    // onTyping each time. Runs until the caller is cancelled (chat closed).
    suspend fun listenTyping(conversationId: String, myId: String, onTyping: () -> Unit) {
        val channel = supabase.channel("typing-$conversationId")
        synchronized(typingChannels) { typingChannels[conversationId] = channel }
        try {
            coroutineScope {
                val events = channel.broadcastFlow<JsonObject>(event = "typing")
                launch {
                    events.collect { payload ->
                        val from = payload["user_id"]?.jsonPrimitive?.contentOrNull
                        if (from != null && from != myId) onTyping()
                    }
                }
                delay(300)
                joinWithRetry(channel)
            }
        } finally {
            synchronized(typingChannels) {
                if (typingChannels[conversationId] === channel) typingChannels.remove(conversationId)
            }
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    // Tells the other person "I am typing". Does nothing when offline.
    suspend fun sendTyping(conversationId: String, myId: String) {
        val channel = synchronized(typingChannels) { typingChannels[conversationId] } ?: return
        if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        try {
            channel.broadcast(event = "typing", message = buildJsonObject { put("user_id", myId) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not important enough to show an error.
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    fun toEpochMillis(timestamp: String?): Long =
        if (timestamp == null) 0L
        else runCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrDefault(0L)
}
