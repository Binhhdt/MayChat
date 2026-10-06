package com.maychat.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
import com.maychat.app.data.Message
import com.maychat.app.data.Reaction
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
    val replyPreview: String? = null,   // quoted text, when this message is a reply
    val replyToMine: Boolean = false,   // the quoted message was written by me
    val reactions: List<ReactionChip> = emptyList(),
)

// One emoji under a message: how many people chose it, and whether I did.
data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean)

// A message I created that the server has not confirmed yet.
private class PendingMessage(
    val localId: String,
    val text: String,
    val kind: String = "text",
    val bytes: ByteArray? = null,
    val durationMs: Int? = null,
    // Filled in when this message answers another one.
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val replySenderId: String? = null,
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

    // Reactions by message id.
    private var reactions: Map<String, List<Reaction>> = emptyMap()
    private var pollCount = 0

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
                    replyPreview = m.replyPreview,
                    replyToMine = m.replySenderId == myId,
                    reactions = chipsFor(m.id),
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
                replyPreview = p.replyPreview,
                replyToMine = p.replySenderId == myId,
            )
        }
        messages = waiting + sent
    }

    // Groups the reactions of one message: same emoji together, most used first.
    private fun chipsFor(messageId: String): List<ReactionChip> {
        val list = reactions[messageId] ?: return emptyList()
        return list.groupBy { it.emoji }
            .map { (emoji, group) -> ReactionChip(emoji, group.size, group.any { it.userId == myId }) }
            .sortedByDescending { it.count }
    }

    // Loads all reactions of this conversation. If this fails (for example
    // migration 10 was not run) no reactions are shown, exactly as before.
    suspend fun reloadReactions() {
        attempt { ChatRepository.loadReactions(conversationId) }.onSuccess { all ->
            reactions = all.groupBy { it.messageId }
            publish()
        }
    }

    // Tap an emoji: sets it as my reaction; tapping my current one removes it.
    fun react(messageId: String, emoji: String) {
        val current = reactions[messageId]?.firstOrNull { it.userId == myId }?.emoji
        val next: String? = if (current == emoji) null else emoji

        // Show the change at once, then confirm with the server.
        val others = (reactions[messageId] ?: emptyList()).filter { it.userId != myId }
        val updated = if (next == null) others else others + Reaction(messageId, myId, next)
        reactions = reactions + (messageId to updated)
        publish()

        scope.launch {
            attempt { ChatRepository.setReaction(messageId, next) }
                .onFailure { error = it.toUserMessage() }
            reloadReactions()
        }
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
        reloadReactions()
    }

    // Safety net for when the live connection silently stops: quietly fetch
    // the newest few messages. Errors are ignored here; refresh() reports them.
    suspend fun poll() {
        if (loading) return
        // Every third time (about every 12 seconds) also re-read reactions.
        pollCount++
        if (pollCount % 3 == 0) reloadReactions()
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

    // Loads older pages until the message with this time is on screen
    // (used to jump to a search result). Stops after 20 pages (600 messages).
    suspend fun loadUntil(targetCreatedAt: String) {
        val targetMs = ChatRepository.toEpochMillis(targetCreatedAt)
        var pages = 0
        while (pages < 20) {
            pages++
            val oldest = confirmed.values.minByOrNull { ChatRepository.toEpochMillis(it.createdAt) } ?: break
            if (ChatRepository.toEpochMillis(oldest.createdAt) <= targetMs) break
            val page = attempt {
                ChatRepository.loadMessages(conversationId, before = oldest.createdAt)
            }.getOrNull() ?: break
            if (page.isEmpty()) {
                hasOlder = false
                break
            }
            page.forEach { confirmed[it.id] = it }
            hasOlder = page.size == ChatRepository.PAGE_SIZE
        }
        publish()
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

    // Sends a COPY of a message into another conversation. A picture or
    // voice file is copied too, because each conversation has its own
    // private folder in the storage.
    suspend fun forwardTo(targetConversationId: String, messageKey: String) {
        val original = confirmed[messageKey] ?: throw IllegalStateException("message not found")
        val path = original.mediaPath
        if (original.kind == "text" || path == null) {
            ChatRepository.sendMessage(targetConversationId, original.content)
        } else {
            val bytes = MediaCache.bytes(path)
            val extension = if (original.kind == "image") "jpg" else "m4a"
            val newPath = "$targetConversationId/${UUID.randomUUID()}.$extension"
            ChatRepository.uploadMedia(newPath, bytes)
            MediaCache.put(newPath, bytes)
            ChatRepository.sendMediaMessage(
                targetConversationId,
                original.kind,
                newPath,
                original.content,
                original.durationMs,
            )
        }
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

    // replyToKey: the message being answered (its key on screen), or null.
    fun send(text: String, replyToKey: String? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val original = replyToKey?.let { confirmed[it] }
        if (original == null) {
            enqueue(PendingMessage(UUID.randomUUID().toString(), clean.take(4000)))
        } else {
            enqueue(
                PendingMessage(
                    UUID.randomUUID().toString(),
                    clean.take(4000),
                    replyToId = original.id,
                    replyPreview = original.content.take(120),
                    replySenderId = original.senderId,
                ),
            )
        }
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
                val replyToId = item.replyToId
                val replyPreview = item.replyPreview
                val replySenderId = item.replySenderId
                if (item.kind == "text" && replyToId != null && replyPreview != null && replySenderId != null) {
                    ChatRepository.sendReply(conversationId, item.text, replyToId, replyPreview, replySenderId)
                } else if (item.kind == "text" || data == null) {
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
