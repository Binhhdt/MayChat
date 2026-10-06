package com.maychat.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
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
    val kind: String = "text",          // "text", "image" or "voice"
    val mediaPath: String? = null,      // null while my own file is still uploading
    val durationMs: Int? = null,
    val recalled: Boolean = false,      // the sender took it back
)

// A message I created that the server has not confirmed yet.
private class PendingMessage(
    val localId: String,
    val text: String,
    val kind: String = "text",
    val bytes: ByteArray? = null,
    val durationMs: Int? = null,
) {
    var failed: Boolean = false

    // Set once the file is uploaded, so a retry does not upload it twice.
    var uploadedPath: String? = null
}

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

    // Messages I chose to hide on my side only ("delete for me").
    private val hidden = HashSet<String>()

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

    fun showError(message: String) {
        error = message
    }

    // Rebuilds the list shown on screen: newest first.
    private fun publish() {
        val sent = confirmed.values
            .filter { it.id !in hidden }
            .sortedByDescending { ChatRepository.toEpochMillis(it.createdAt) }
            .map { m ->
                val mine = m.senderId == myId
                UiMessage(
                    key = m.id,
                    text = m.content,
                    mine = mine,
                    createdAt = m.createdAt,
                    state = if (mine && m.readAt != null) SendState.READ else SendState.SENT,
                    kind = m.kind,
                    mediaPath = m.mediaPath,
                    durationMs = m.durationMs,
                    recalled = m.recalledAt != null,
                )
            }
        val waiting = pending.asReversed().map { p ->
            UiMessage(
                key = p.localId,
                text = p.text,
                mine = true,
                createdAt = null,
                state = if (p.failed) SendState.FAILED else SendState.SENDING,
                kind = p.kind,
                mediaPath = p.uploadedPath,
                durationMs = p.durationMs,
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
        // Which messages I have hidden. If this fails (for example migration
        // 09 was not run) nothing is hidden, exactly as before.
        attempt { ChatRepository.loadHiddenMessageIds(conversationId) }.onSuccess {
            hidden.clear()
            hidden.addAll(it)
        }
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

    // Safety net for when the live connection silently stops: quietly fetch
    // the newest few messages. Errors are ignored here; refresh() reports them.
    suspend fun poll() {
        if (loading) return
        val page = attempt { ChatRepository.loadMessages(conversationId, limit = 15) }.getOrNull() ?: return
        var changed = false
        page.forEach {
            if (confirmed[it.id] != it) {
                confirmed[it.id] = it
                changed = true
            }
        }
        if (changed) {
            publish()
            markReadIfNeeded()
        }
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

    // Take back one of my own messages, for both people.
    fun recall(messageId: String) {
        scope.launch {
            attempt { ChatRepository.recallMessage(messageId) }
                .onSuccess {
                    error = null
                    poll()
                }
                .onFailure { error = it.toUserMessage() }
        }
    }

    // Remove a message from MY screen only. The other person still has it.
    fun hide(messageId: String) {
        hidden.add(messageId)
        publish()
        scope.launch {
            attempt { ChatRepository.hideMessage(messageId) }
                .onFailure {
                    hidden.remove(messageId)
                    error = it.toUserMessage()
                    publish()
                }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        enqueue(PendingMessage(UUID.randomUUID().toString(), clean.take(4000)))
    }

    fun sendImage(jpegBytes: ByteArray) {
        enqueue(PendingMessage(UUID.randomUUID().toString(), "📷 Ảnh", kind = "image", bytes = jpegBytes))
    }

    fun sendVoice(audioBytes: ByteArray, durationMs: Int) {
        enqueue(
            PendingMessage(
                UUID.randomUUID().toString(),
                "🎤 Tin nhắn thoại",
                kind = "voice",
                bytes = audioBytes,
                durationMs = durationMs,
            ),
        )
    }

    fun retry(localId: String) {
        val item = pending.firstOrNull { it.localId == localId && it.failed } ?: return
        item.failed = false
        publish()
        deliver(item)
    }

    private fun enqueue(item: PendingMessage) {
        pending.add(item)
        publish()
        deliver(item)
    }

    private fun deliver(item: PendingMessage) {
        scope.launch {
            attempt {
                val data = item.bytes
                if (item.kind == "text" || data == null) {
                    ChatRepository.sendMessage(conversationId, item.text)
                } else {
                    // Step 1: upload the file (skipped on retry if already done).
                    val path = item.uploadedPath ?: run {
                        val extension = if (item.kind == "image") "jpg" else "m4a"
                        val newPath = "$conversationId/${UUID.randomUUID()}.$extension"
                        ChatRepository.uploadMedia(newPath, data)
                        MediaCache.put(newPath, data)
                        item.uploadedPath = newPath
                        newPath
                    }
                    // Step 2: create the message that points to the file.
                    ChatRepository.sendMediaMessage(conversationId, item.kind, path, item.text, item.durationMs)
                }
            }
                .onSuccess { saved ->
                    pending.remove(item)
                    // Keep a newer copy if the live event already arrived.
                    if (confirmed[saved.id] == null) confirmed[saved.id] = saved
                    error = null
                }
                .onFailure {
                    item.failed = true
                    error = it.toUserMessage()
                }
            publish()
        }
    }
}
