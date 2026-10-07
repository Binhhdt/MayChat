package com.maychat.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatMemory
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
import com.maychat.app.data.Message
import com.maychat.app.data.PinnedMessage
import com.maychat.app.data.Reaction
import com.maychat.app.data.SPECIAL_KINDS
import com.maychat.app.data.attempt
import com.maychat.app.data.picturePaths
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // Only filled in by group chats: who wrote this message, and who wrote
    // the message it quotes.
    val senderId: String? = null,
    val replySenderId: String? = null,
    // Who reacted with what (for the "who reacted" sheet).
    val reactors: List<Reactor> = emptyList(),
    // Sticker / location / contact card data.
    val extra: String? = null,
    // When this message answers a picture (or an album) or a sticker: what
    // to show small inside the quote.
    val replyImagePath: String? = null,
    val replyStickerCode: String? = null,
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
    // For a sticker, a location or a contact card.
    val extra: String? = null,
    // For an album: the pictures (JPEG bytes), in order.
    val album: List<ByteArray> = emptyList(),
) {
    var failed: Boolean = false

    // Pictures of an album that are already uploaded (kept for a retry).
    val albumPaths = ArrayList<String>()

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

    // Remembers the newest page (with reactions, pin and background) for
    // the next time this chat is opened, also after the app was closed.
    private fun keep(
        newestFirst: List<Message> = confirmed.values
            .sortedByDescending { ChatRepository.toEpochMillis(it.createdAt) },
    ) {
        val page = newestFirst.take(ChatRepository.PAGE_SIZE)
        val ids = page.map { it.id }.toSet()
        ChatMemory.saveChat(
            conversationId,
            ChatMemory.Chat(
                messages = page,
                hidden = hidden.toSet(),
                reactions = reactions.filterKeys { it in ids }.values.flatten(),
                wallpaper = wallpaper,
                pinned = pinned,
            ),
        )
    }

    // Rebuilds the list shown on screen: newest first.
    private fun publish() {
        val newestFirst = confirmed.values
            .sortedByDescending { ChatRepository.toEpochMillis(it.createdAt) }
        keep(newestFirst)
        val sent = newestFirst
            .filter { it.id !in hidden }
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
                    replyImagePath = quotedPicture(m.replyToId),
                    replyStickerCode = quotedSticker(m.replyToId),
                    reactions = chipsFor(m.id),
                    fileName = m.fileName,
                    fileSize = m.fileSize,
                            reactors = (reactions[m.id] ?: emptyList()).map { Reactor(it.userId, it.emoji, it.count) },
                    extra = m.extra,
                )
            }
        val waiting = pending.asReversed().map { p ->
            UiMessage(
                key = p.localId,
                text = p.text,
                mine = true,
                createdAt = null,
                state = if (p.failed) SendState.FAILED else SendState.SENDING,
                // An album still on its way is shown as a line of text.
                kind = if (p.kind == "album") "text" else p.kind,
                mediaPath = p.uploadedPath,
                durationMs = p.durationMs,
                replyPreview = p.replyPreview,
                replyToMine = p.replySenderId == myId,
                replyImagePath = quotedPicture(p.replyToId),
                replyStickerCode = quotedSticker(p.replyToId),
                extra = p.extra,
            )
        }
        messages = waiting + sent
        fetchMissingQuotes()
    }

    // ----- The picture inside a quote -------------------------------------
    // A reply stores only the text of the message it answers. To show the
    // PICTURE of a quoted photo (or sticker), that message is looked up:
    // among the loaded messages, or fetched once when it is older.
    private val quotedOlder = HashMap<String, Message>()
    private val quotedAsked = HashSet<String>()

    private fun quotedMessage(id: String?): Message? = id?.let { confirmed[it] ?: quotedOlder[it] }

    private fun quotedPicture(id: String?): String? {
        val original = quotedMessage(id) ?: return null
        if (original.recalledAt != null) return null
        return if (original.kind == "image" || original.kind == "album") original.mediaPath else null
    }

    private fun quotedSticker(id: String?): String? {
        val original = quotedMessage(id) ?: return null
        return if (original.kind == "sticker" && original.recalledAt == null) original.extra else null
    }

    private fun fetchMissingQuotes() {
        val missing = confirmed.values
            .mapNotNull { it.replyToId }
            .filter { it !in confirmed && it !in quotedOlder && it !in quotedAsked }
            .distinct()
        if (missing.isEmpty()) return
        quotedAsked.addAll(missing)
        scope.launch {
            attempt { ChatRepository.loadMessagesByIds(missing) }.onSuccess { found ->
                if (found.isNotEmpty()) {
                    found.forEach { quotedOlder[it.id] = it }
                    publish()
                }
            }
        }
    }

    // Groups the reactions of one message: same emoji together, most used first.
    private fun chipsFor(messageId: String): List<ReactionChip> {
        val list = reactions[messageId] ?: return emptyList()
        return list.groupBy { it.emoji }
            .map { (emoji, group) -> ReactionChip(emoji, group.sumOf { it.count }, group.any { it.userId == myId }) }
            .sortedByDescending { it.count }
    }

    // Loads all reactions of this conversation. If this fails (for example
    // migration 10 was not run) no reactions are shown, exactly as before.
    suspend fun reloadReactions() {
        if (reactInFlight > 0) return
        attempt { ChatRepository.loadReactions(conversationId) }.onSuccess { all ->
            val next = all.groupBy { it.messageId }
            // Only when something really changed (this runs every few seconds).
            if (next != reactions) {
                reactions = next
                publish()
            }
        }
    }

    // The background of this conversation as stored on the server, so both
    // people see the same one (null = none).
    var wallpaper by mutableStateOf<String?>(null)
        private set

    // If this fails (for example migration 13 was not run) there is simply
    // no background.
    suspend fun reloadWallpaper() {
        attempt { ChatRepository.loadWallpaper(conversationId) }.onSuccess {
            if (wallpaper != it) {
                wallpaper = it
                keep()
            }
        }
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
        attempt { ChatRepository.loadPinnedMessage(conversationId) }.onSuccess {
            if (pinned != it) {
                pinned = it
                keep()
            }
        }
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

    // How many of my taps are still on their way to the server. While there
    // are any, the reactions are not re-read, so the count never jumps back
    // in the middle of a quick series of taps.
    private var reactInFlight = 0

    // Tap an emoji: the same one as my current reaction counts one more
    // (like tapping the heart several times in Zalo), another one replaces it.
    fun react(messageId: String, emoji: String) {
        val mineNow = reactions[messageId]?.firstOrNull { it.userId == myId }
        val next = if (mineNow != null && mineNow.emoji == emoji) {
            mineNow.copy(count = minOf(mineNow.count + 1, 999))
        } else {
            Reaction(messageId, myId, emoji, 1)
        }

        // Show the change at once, then confirm with the server.
        val others = (reactions[messageId] ?: emptyList()).filter { it.userId != myId }
        reactions = reactions + (messageId to (others + next))
        publish()

        reactInFlight++
        scope.launch {
            attempt { ChatRepository.addReaction(messageId, emoji) }
                .onFailure { error = it.toUserMessage() }
            reactInFlight--
            if (reactInFlight == 0) reloadReactions()
        }
    }

    // Takes my reaction off a message.
    fun unreact(messageId: String) {
        val others = (reactions[messageId] ?: emptyList()).filter { it.userId != myId }
        reactions = reactions + (messageId to others)
        publish()

        reactInFlight++
        scope.launch {
            attempt { ChatRepository.setReaction(messageId, null) }
                .onFailure { error = it.toUserMessage() }
            reactInFlight--
            if (reactInFlight == 0) reloadReactions()
        }
    }

    private fun markReadIfNeeded() {
        if (!visible) return
        val hasUnread = confirmed.values.any { it.senderId != myId && it.readAt == null }
        if (hasUnread) {
            scope.launch { attempt { ChatRepository.markConversationRead(conversationId) } }
        }
    }

    // Starts refresh() unless one is already running. Every trigger (screen
    // opened, connection came back, app returned to the front) goes through
    // here, so a refresh is never cut off halfway and started again - which
    // could keep the spinner turning for a long time.
    private var refreshJob: Job? = null

    fun requestRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    // Loads the newest page. Also used to catch up after being offline.
    suspend fun refresh() = coroutineScope {
        // Know up to where I deleted this conversation on my side before
        // asking for messages. If this fails (for example migration 16 was
        // not run) nothing is left out, exactly as before.
        // (Waited for only when it was never read since login; otherwise it
        // is refreshed alongside, so the messages are not held up.)
        if (ChatRepository.conversationPrefsKnown) {
            launch { attempt { ChatRepository.loadConversationPrefs() } }
        } else {
            // At most 4 seconds: a slow answer must not hold the messages back.
            withTimeoutOrNull(4_000) { attempt { ChatRepository.loadConversationPrefs() } }
        }
        // The hidden messages and the newest page are asked for AT THE SAME
        // TIME (before: one after the other).
        // Which messages I have hidden: if this fails (for example migration
        // 09 was not run) nothing is hidden, exactly as before.
        val hiddenAnswer = async { attempt { ChatRepository.loadHiddenMessageIds(conversationId) } }
        val pageAnswer = async { attempt { ChatRepository.loadMessages(conversationId) } }
        hiddenAnswer.await().onSuccess {
            hidden.clear()
            hidden.addAll(it)
        }
        pageAnswer.await()
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
        launch { reloadReactions() }
        launch { reloadPin() }
        launch { reloadWallpaper() }
        Unit
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
        val extra = original.extra
        if (original.kind in SPECIAL_KINDS && extra != null) {
            ChatRepository.sendSpecial(targetConversationId, original.kind, original.content, extra)
        } else if (original.kind == "album") {
            ChatRepository.forwardAlbum(
                targetConversationId,
                toGroup = false,
                paths = picturePaths(original.kind, path, extra),
            )
        } else if (original.kind == "text" || path == null) {
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

    // Sends a COPY of a message into a GROUP.
    suspend fun forwardToGroup(targetGroupId: String, messageKey: String) {
        val original = confirmed[messageKey] ?: throw IllegalStateException("message not found")
        ChatRepository.sendCopyToGroup(
            targetGroupId,
            original.kind,
            original.content,
            original.mediaPath,
            original.durationMs,
            original.fileName,
            original.extra,
        )
    }

    // Take back one of my own messages, for both people.
    fun recall(messageId: String) {
        // The file of a recalled picture, voice message or file is no
        // longer needed by anyone: remove it from the storage as well.
        val filePath = confirmed[messageId]?.mediaPath
        // An album has more pictures than the first one.
        val morePaths = confirmed[messageId]
            ?.let { picturePaths(it.kind, it.mediaPath, it.extra) }
            ?.drop(1) ?: emptyList()
        scope.launch {
            attempt { ChatRepository.recallMessage(messageId) }
                .onSuccess {
                    error = null
                    if (filePath != null) ChatRepository.deleteMedia(filePath)
                    morePaths.forEach { ChatRepository.deleteMedia(it) }
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

    // A sticker ("sticker"), my location ("location") or a contact card
    // ("contact"). text is what lists and notifications show; extra is the
    // data: which sticker, "lat,lng", or the person's id.
    fun sendSpecial(kind: String, text: String, extra: String) {
        enqueue(PendingMessage(UUID.randomUUID().toString(), text, kind = kind, extra = extra))
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

    // Several pictures (JPEG bytes) as ONE message, shown as a grid.
    fun sendAlbum(pictures: List<ByteArray>) {
        if (pictures.isEmpty()) return
        enqueue(
            PendingMessage(
                UUID.randomUUID().toString(),
                "📷 ${pictures.size} ảnh",
                kind = "album",
                album = pictures,
            ),
        )
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
                val extra = item.extra
                if (item.kind in SPECIAL_KINDS && extra != null) {
                    ChatRepository.sendSpecial(conversationId, item.kind, item.text, extra)
                } else if (item.kind == "album") {
                    // Upload the pictures not uploaded yet, then one message.
                    for (index in item.albumPaths.size until item.album.size) {
                        val bytes = item.album[index]
                        val newPath = "$conversationId/${UUID.randomUUID()}.jpg"
                        ChatRepository.uploadMedia(newPath, bytes)
                        MediaCache.put(newPath, bytes)
                        item.albumPaths.add(newPath)
                    }
                    ChatRepository.sendAlbum(conversationId, item.albumPaths.toList())
                } else if (item.kind == "text" && replyToId != null && replyPreview != null && replySenderId != null) {
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

    // Opened before (even before the app was last closed): show what was
    // there at once, without a spinner; refresh() then brings the current
    // state. This block is at the END of the class on purpose: everything
    // above must exist before it runs.
    init {
        ChatMemory.chat(conversationId)?.let { remembered ->
            remembered.messages.forEach { confirmed[it.id] = it }
            hidden.addAll(remembered.hidden)
            reactions = remembered.reactions.groupBy { it.messageId }
            wallpaper = remembered.wallpaper
            pinned = remembered.pinned
            if (confirmed.isNotEmpty()) {
                publish()
                loading = false
            }
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
