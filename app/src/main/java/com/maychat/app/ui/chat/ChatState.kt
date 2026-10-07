package com.maychat.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
import com.maychat.app.data.Message
import com.maychat.app.data.PinnedMessage
import com.maychat.app.data.Reaction
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

// SENT = saved on the server, DELIVERED = the other phone's app has it,
// READ = the other person opened the conversation.
enum class SendState { SENDING, SENT, DELIVERED, READ, FAILED }

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
    val fileName: String? = null,       // for a file message
    val fileSize: Int? = null,
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
    val fileName: String? = null,
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

    // The message pinned at the top of this conversation (null = none).
    var pinned by mutableStateOf<PinnedMessage?>(null)
        private set

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
                    state = when {
                        mine && m.readAt != null -> SendState.READ
                        mine && m.deliveredAt != null -> SendState.DELIVERED
                        else -> SendState.SENT
                    },
                    kind = m.kind,
                    mediaPath = m.mediaPath,
                    durationMs = m.durationMs,
                    recalled = m.recalledAt != null,
                    replyPreview = m.replyPreview,
                    replyToMine = m.replySenderId == myId,
                    reactions = chipsFor(m.id),
                    fileName = m.fileName,
                    fileSize = m.fileSize,
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

    // The background of this conversation as stored on the server, so both
    // people see the same one (null = none).
    var wallpaper by mutableStateOf<String?>(null)
        private set

    // If this fails (for example migration 13 was not run) there is simply
    // no background.
    suspend fun reloadWallpaper() {
        attempt { ChatRepository.loadWallpaper(conversationId) }.onSuccess { wallpaper = it }
    }

    // The picture of the background that was just replaced is not used
    // any more: remove it from the storage.
    private suspend fun deleteOldWallpaperPicture(before: String?, now: String?) {
        if (before != null && before != now && before.startsWith("img:")) {
            ChatRepository.deleteMedia(before.removePrefix("img:"))
        }
    }

    // value: null, or the id of a ready-made background.
    fun chooseWallpaper(value: String?) {
        val before = wallpaper
        wallpaper = value
        scope.launch {
            attempt { ChatRepository.setWallpaper(conversationId, value) }
                .onSuccess { deleteOldWallpaperPicture(before, value) }
                .onFailure { error = it.toUserMessage() }
            reloadWallpaper()
        }
    }

    // A picture (JPEG bytes) as background: stored in this conversation's
    // private folder, which only its two members can read.
    fun chooseWallpaperPicture(jpegBytes: ByteArray) {
        val before = wallpaper
        scope.launch {
            attempt {
                val path = "$conversationId/wallpaper-${UUID.randomUUID()}.jpg"
                ChatRepository.uploadMedia(path, jpegBytes)
                MediaCache.put(path, jpegBytes)
                // Show the new picture on this phone at once; the server
                // call below then makes it official for both people.
                wallpaper = "img:$path"
                ChatRepository.setWallpaper(conversationId, "img:$path")
                deleteOldWallpaperPicture(before, "img:$path")
            }.onFailure { error = it.toUserMessage() }
            reloadWallpaper()
        }
    }

    // Reads the pinned message. If this fails (for example migration 11 was
    // not run) nothing is pinned, exactly as before.
    suspend fun reloadPin() {
        attempt { ChatRepository.loadPinnedMessage(conversationId) }.onSuccess { pinned = it }
    }

    fun pin(messageId: String) {
        scope.launch {
            attempt { ChatRepository.pinMessage(messageId) }
                .onFailure { error = it.toUserMessage() }
            reloadPin()
        }
    }

    fun unpin() {
        pinned = null
        scope.launch {
            attempt { ChatRepository.unpinMessage(conversationId) }
                .onFailure { error = it.toUserMessage() }
            reloadPin()
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
        // Know up to where I deleted this conversation on my side before
        // asking for messages. If this fails (for example migration 16 was
        // not run) nothing is left out, exactly as before.
        attempt { ChatRepository.loadConversationPrefs() }
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
        reloadPin()
        reloadWallpaper()
    }

    // Safety net for when the live connection silently stops: quietly fetch
    // the newest few messages. Errors are ignored here; refresh() reports them.
    suspend fun poll() {
        if (loading) return
        // Every third time (about every 12 seconds) also re-read reactions.
        pollCount++
        if (pollCount % 3 == 0) {
            reloadReactions()
            reloadPin()
        }
        // Every time (about every 4 seconds): the background the other
        // person may just have changed. One tiny request.
        reloadWallpaper()
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
        // A notice from the server (for example "changed the background"):
        // fetch the new background immediately instead of waiting.
        if (message.kind == "system") scope.launch { reloadWallpaper() }
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
            val extension = when (original.kind) {
                "image" -> "jpg"
                "voice" -> "m4a"
                else -> safeExtension(original.fileName)
            }
            val newPath = "$targetConversationId/${UUID.randomUUID()}.$extension"
            ChatRepository.uploadMedia(newPath, bytes)
            MediaCache.put(newPath, bytes)
            if (original.kind == "file") {
                ChatRepository.sendFileMessage(
                    targetConversationId,
                    newPath,
                    original.content,
                    original.fileName ?: "file",
                    bytes.size,
                )
                return
            }
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
        // The file of a recalled picture, voice message or file is no
        // longer needed by anyone: remove it from the storage as well.
        val filePath = confirmed[messageId]?.mediaPath
        scope.launch {
            attempt { ChatRepository.recallMessage(messageId) }
                .onSuccess {
                    error = null
                    if (filePath != null) ChatRepository.deleteMedia(filePath)
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

    // replyToKey: the message being answered, or null for a normal picture.
    fun sendImage(jpegBytes: ByteArray, replyToKey: String? = null) {
        val original = replyToKey?.let { confirmed[it] }
        enqueue(
            PendingMessage(
                UUID.randomUUID().toString(),
                "📷 Ảnh",
                kind = "image",
                bytes = jpegBytes,
                replyToId = original?.id,
                replyPreview = original?.content?.take(120),
                replySenderId = original?.senderId,
            ),
        )
    }

    // Any file, at most 5 MB (checked before this is called).
    fun sendFile(bytes: ByteArray, fileName: String) {
        enqueue(
            PendingMessage(
                UUID.randomUUID().toString(),
                "📎 $fileName",
                kind = "file",
                bytes = bytes,
                fileName = fileName,
            ),
        )
    }

    fun sendVoice(audioBytes: ByteArray, durationMs: Int, replyToKey: String? = null) {
        val original = replyToKey?.let { confirmed[it] }
        enqueue(
            PendingMessage(
                UUID.randomUUID().toString(),
                "🎤 Tin nhắn thoại",
                kind = "voice",
                bytes = audioBytes,
                durationMs = durationMs,
                replyToId = original?.id,
                replyPreview = original?.content?.take(120),
                replySenderId = original?.senderId,
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
                        val extension = when (item.kind) {
                            "image" -> "jpg"
                            "voice" -> "m4a"
                            else -> safeExtension(item.fileName)
                        }
                        val newPath = "$conversationId/${UUID.randomUUID()}.$extension"
                        ChatRepository.uploadMedia(newPath, data)
                        MediaCache.put(newPath, data)
                        item.uploadedPath = newPath
                        newPath
                    }
                    // Step 2: create the message that points to the file.
                    if (item.kind == "file") {
                        ChatRepository.sendFileMessage(
                            conversationId, path, item.text, item.fileName ?: "file", data.size,
                        )
                    } else if (replyToId != null && replyPreview != null && replySenderId != null) {
                        ChatRepository.sendMediaReply(
                            conversationId, item.kind, path, item.text, item.durationMs,
                            replyToId, replyPreview, replySenderId,
                        )
                    } else {
                        ChatRepository.sendMediaMessage(conversationId, item.kind, path, item.text, item.durationMs)
                    }
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

// The ending of a file name ("pdf", "docx"...), cleaned so it is safe to
// use in a storage path. "bin" when the name has no usable ending.
private fun safeExtension(fileName: String?): String {
    val ending = (fileName ?: "").substringAfterLast('.', "")
        .lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
        .take(8)
    return ending.ifEmpty { "bin" }
}
