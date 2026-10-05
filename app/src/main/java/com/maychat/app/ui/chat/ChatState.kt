package com.maychat.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Message
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

enum class SendState { SENDING, SENT, READ, FAILED }

// One bubble on the chat screen.
data class UiMessage(
    val key: String,
    val text: String,
    val mine: Boolean,
    val createdAt: String?,
    val state: SendState,
)

// A message I typed that the server has not confirmed yet.
private class PendingMessage(val localId: String, val text: String, var failed: Boolean = false)

// Holds everything the chat screen shows for ONE conversation and talks to
// the repository. All functions are called from the main thread.
class ChatState(
    private val conversationId: String,
    private val myId: String,
    private val scope: CoroutineScope,
) {
    // Messages confirmed by the server, by id (so duplicates are impossible).
    private val confirmed = HashMap<String, Message>()
    private val pending = ArrayList<PendingMessage>()
    private var firstLoadDone = false

    // True while the chat is on screen. Messages are only marked as read then.
    var visible = false

    var messages by mutableStateOf<List<UiMessage>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // Rebuilds the list shown on screen: newest first.
    private fun publish() {
        val sent = confirmed.values
            .sortedByDescending { ChatRepository.toEpochMillis(it.createdAt) }
            .map { m ->
                val mine = m.senderId == myId
                UiMessage(
                    key = m.id,
                    text = m.content,
                    mine = mine,
                    createdAt = m.createdAt,
                    state = if (mine && m.readAt != null) SendState.READ else SendState.SENT,
                )
            }
        val waiting = pending.asReversed().map { p ->
            UiMessage(
                key = p.localId,
                text = p.text,
                mine = true,
                createdAt = null,
                state = if (p.failed) SendState.FAILED else SendState.SENDING,
            )
        }
        messages = waiting + sent
    }

    private fun markReadIfNeeded() {
        if (!visible) return
        val hasUnread = confirmed.values.any { it.senderId != myId && it.readAt == null }
        if (hasUnread) {
            scope.launch { attempt { ChatRepository.markConversationRead(conversationId) } }
        }
    }

    // Loads the newest page. Also used to catch up after being offline.
    suspend fun refresh() {
        attempt { ChatRepository.loadMessages(conversationId) }
            .onSuccess { page ->
                page.forEach { confirmed[it.id] = it }
                if (!firstLoadDone) {
                    hasOlder = page.size == ChatRepository.PAGE_SIZE
                    firstLoadDone = true
                }
                error = null
                publish()
                markReadIfNeeded()
            }
            .onFailure { error = it.toUserMessage() }
        loading = false
    }

    // Loads the page before the oldest message currently shown.
    fun loadOlder() {
        if (loadingOlder) return
        val oldest = confirmed.values.minByOrNull { ChatRepository.toEpochMillis(it.createdAt) } ?: return
        loadingOlder = true
        scope.launch {
            attempt { ChatRepository.loadMessages(conversationId, before = oldest.createdAt) }
                .onSuccess { page ->
                    page.forEach { confirmed[it.id] = it }
                    hasOlder = page.size == ChatRepository.PAGE_SIZE
                    error = null
                    publish()
                }
                .onFailure { error = it.toUserMessage() }
            loadingOlder = false
        }
    }

    // Called for every live message event (new message or read receipt).
    fun onEvent(message: Message) {
        if (message.conversationId != conversationId) return
        confirmed[message.id] = message
        publish()
        markReadIfNeeded()
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val item = PendingMessage(UUID.randomUUID().toString(), clean.take(4000))
        pending.add(item)
        publish()
        deliver(item)
    }

    fun retry(localId: String) {
        val item = pending.firstOrNull { it.localId == localId && it.failed } ?: return
        item.failed = false
        publish()
        deliver(item)
    }

    private fun deliver(item: PendingMessage) {
        scope.launch {
            attempt { ChatRepository.sendMessage(conversationId, item.text) }
                .onSuccess { saved ->
                    pending.remove(item)
                    // Keep a newer copy if the live event already arrived.
                    if (confirmed[saved.id] == null) confirmed[saved.id] = saved
                }
                .onFailure { item.failed = true }
            publish()
        }
    }
}
