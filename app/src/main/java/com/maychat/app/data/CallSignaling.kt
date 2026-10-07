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
import kotlinx.coroutines.async
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
// (Private channels were tried in 0.19.0 and taken back in 0.19.4: joining
// them was unreliable. The rules in supabase_migration_18 stay unused.)
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
        // An ordinary channel again. The private channel tried in 0.19.0
        // often stayed in "joining" and calls stopped getting through.
        val channel = supabase.channel("call-$myId")
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

    // State of my own call channel, for the diagnosis line on the call screen.
    fun inboxStatus(): String = inbox?.status?.value?.name ?: "NONE"

    // Join my call channel right now if it is not joined (instead of waiting
    // for the next check a few seconds later).
    fun kick() {
        val channel = inbox ?: return
        if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) {
            scope.launch { runCatching { join(channel) } }
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

    // How the last message went out, for the diagnosis line:
    // "trực tiếp+CSDL", "trực tiếp", "CSDL" or "KHÔNG GỬI ĐƯỢC".
    @Volatile
    var lastSend: String = "-"
        private set

    // Send one call message to another user, over BOTH paths at the same
    // time: the live connection (fast) and the database (dependable, the
    // other phone fetches it about once a second). Throws only when neither
    // path worked.
    suspend fun send(peerId: String, payload: JsonObject) {
        val viaDatabase = scope.async {
            runCatching { ChatRepository.sendCallSignal(peerId, payload) }.isSuccess
        }
        val live = try {
            sendLive(peerId, payload)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
        val stored = viaDatabase.await()
        lastSend = when {
            live && stored -> "trực tiếp+CSDL"
            live -> "trực tiếp"
            stored -> "CSDL"
            else -> "KHÔNG GỬI ĐƯỢC"
        }
        if (!live && !stored) throw IllegalStateException("call message not sent")
    }

    // The live path: Realtime Broadcast on the other person's channel.
    private suspend fun sendLive(peerId: String, payload: JsonObject) {
        val channel = mutex.withLock {
            if (outboxPeer != peerId) closeOutboxLocked()
            outbox ?: supabase.channel("call-$peerId").also {
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
