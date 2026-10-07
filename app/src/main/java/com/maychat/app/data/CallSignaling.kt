package com.maychat.app.data

import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

// Carries the small "set up a call" messages between two phones through
// Supabase Realtime Broadcast. Nothing here is stored in the database, and
// the call audio itself never passes through Supabase.
//
// Every logged-in phone listens on its own channel "call-<my user id>".
// To reach someone, a phone joins THAT person's channel and sends there.
// The channels are private: the server checks who may listen and who may
// send (rules in supabase_migration_18_security.sql).
object CallSignaling {
    private val supabase get() = SupabaseProvider.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _incoming = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)

    // Every call message addressed to me.
    val incoming: SharedFlow<JsonObject> = _incoming.asSharedFlow()

    private var inbox: RealtimeChannel? = null
    private var inboxJob: Job? = null
    private var outbox: RealtimeChannel? = null
    private var outboxPeer: String? = null

    private suspend fun join(channel: RealtimeChannel): Boolean {
        try {
            withTimeoutOrNull(8_000) { channel.subscribe(blockUntilSubscribed = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Offline: the caller decides whether to try again.
        }
        return channel.status.value == RealtimeChannel.Status.SUBSCRIBED
    }

    // Start listening for calls to this account.
    suspend fun start(myId: String) = mutex.withLock {
        stopLocked()
        // Private channel: the server lets only me listen here
        // (see supabase_migration_18_security.sql).
        val channel = supabase.channel("call-$myId") { isPrivate = true }
        inbox = channel
        inboxJob = scope.launch {
            val signals = channel.broadcastFlow<JsonObject>(event = "signal")
            launch { signals.collect { _incoming.emit(it) } }
            delay(300)
            // Keep the channel joined: join now, and again whenever it drops.
            while (isActive) {
                if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) join(channel)
                delay(5_000)
            }
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        inboxJob?.cancel()
        inboxJob = null
        inbox?.let { ch -> runCatching { supabase.realtime.removeChannel(ch) } }
        inbox = null
        closeOutboxLocked()
    }

    // Send one call message to another user. Throws if it cannot be sent.
    suspend fun send(peerId: String, payload: JsonObject) {
        val channel = mutex.withLock {
            if (outboxPeer != peerId) closeOutboxLocked()
            // Private too: the server lets me send here only when I have a
            // conversation with this person.
            outbox ?: supabase.channel("call-$peerId") { isPrivate = true }.also {
                outbox = it
                outboxPeer = peerId
            }
        }
        if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED && !join(channel)) {
            throw IllegalStateException("call channel not connected")
        }
        channel.broadcast(event = "signal", message = payload)
    }

    suspend fun closeOutbox() = mutex.withLock { closeOutboxLocked() }

    private suspend fun closeOutboxLocked() {
        outbox?.let { ch -> runCatching { supabase.realtime.removeChannel(ch) } }
        outbox = null
        outboxPeer = null
    }
}
