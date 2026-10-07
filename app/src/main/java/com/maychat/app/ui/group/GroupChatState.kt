package com.maychat.app.ui.group

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatMemory
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Group
import com.maychat.app.data.GroupMember
import com.maychat.app.data.GroupMessage
import com.maychat.app.data.MediaCache
import com.maychat.app.data.NewGroupMessage
import com.maychat.app.data.NewGroupReplyMessage
import com.maychat.app.data.PinnedMessage
import com.maychat.app.data.Profile
import com.maychat.app.data.Reaction
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.chat.ReactionChip
import com.maychat.app.ui.chat.Reactor
import com.maychat.app.ui.chat.SendState
import com.maychat.app.ui.chat.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

// A message I created that the server has not confirmed yet.
private class PendingGroupMessage(
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

// Holds everything the chat screen shows for ONE group and talks to the
// repository. The group counterpart of ChatState; it produces the same
// UiMessage bubbles, so the group screen can draw them with the same code
// as a one-to-one chat. All functions are called from the main thread.
class GroupChatState(
    private val groupId: String,
    private val myId: String,
    private val scope: CoroutineScope,
) {
    // Messages confirmed by the server, by id (so duplicates are impossible).
    private val confirmed = HashMap<String, GroupMessage>()
    private val pending = ArrayList<PendingGroupMessage>()
    private var firstLoadDone = false

    // Messages I chose to hide on my side only ("delete for me").
    private val hidden = HashSet<String>()

    // Reactions by message id.
    private var reactions: Map<String, List<Reaction>> = emptyMap()
    private var pollCount = 0

    // True while the chat is on screen. The group is only marked as read then.
    var visible = false

    // null until loaded. "gone" becomes true when I am no longer a member.
    var group by mutableStateOf<Group?>(null)
        private set
    var gone by mutableStateOf(false)
        private set
    var members by mutableStateOf<List<Profile>>(emptyList())
        private set

    // Up to when each member has read, by user id (for "Đã xem").
    // (A state, so the lines under my messages update when someone reads.)
    private var readUpTo by mutableStateOf<Map<String, Long>>(emptyMap())

    // The message pinned at the top of this group (null = none).
    var pinned by mutableStateOf<PinnedMessage?>(null)
        private set

    // The background of this group, the same for every member (null = none).
    var wallpaper by mutableStateOf<String?>(null)
        private set

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

    // How many OTHER members have read a message sent at this time.
    private fun seenCount(createdAt: String?): Int {
        val at = ChatRepository.toEpochMillis(createdAt)
        if (at == 0L) return 0
        return readUpTo.count { (userId, readAt) -> userId != myId && readAt >= at }
    }

    // Ids of the OTHER members who have read a message sent at this time.
    fun readersOf(createdAt: String?): Set<String> {
        val at = ChatRepository.toEpochMillis(createdAt)
        if (at == 0L) return emptySet()
        return readUpTo.filter { (userId, readAt) -> userId != myId && readAt >= at }.keys
    }

    // The line under one of my messages: "Đã gửi" or "3 người đã xem".
    fun sentMeta(message: UiMessage): String? {
        if (!message.mine || message.createdAt == null) return null
        val seen = seenCount(message.createdAt)
        return if (seen > 0) "$seen người đã xem" else "Đã gửi"
    }

    // The rows of the member list as last read (role, read up to when).
    private var memberRows: List<GroupMember> = emptyList()

    // Remembers the newest page (with reactions, pin, members) for the next
    // time this group is opened, also after the app was closed.
    private fun keep(
        newestFirst: List<GroupMessage> = confirmed.values
            .sortedByDescending { ChatRepository.toEpochMillis(it.createdAt) },
    ) {
        if (gone) return
        val page = newestFirst.take(ChatRepository.PAGE_SIZE)
        val ids = page.map { it.id }.toSet()
        ChatMemory.saveGroupChat(
            groupId,
            ChatMemory.GroupChat(
                messages = page,
                hidden = hidden.toSet(),
                group = group,
                members = members,
                reactions = reactions.filterKeys { it in ids }.values.flatten(),
                pinned = pinned,
                memberRows = memberRows,
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
                UiMessage(
                    key = m.id,
                    text = m.content,
                    mine = m.senderId == myId,
                    createdAt = m.createdAt,
                    state = SendState.SENT,
                    kind = m.kind,
                    mediaPath = m.mediaPath,
                    durationMs = m.durationMs,
                    recalled = m.recalledAt != null,
                    replyPreview = m.replyPreview,
                    replyToMine = m.replySenderId == myId,
                    reactions = chipsFor(m.id),
                    fileName = m.fileName,
                    fileSize = m.fileSize,
                    senderId = m.senderId,
                    replySenderId = m.replySenderId,
                    reactors = (reactions[m.id] ?: emptyList()).map { Reactor(it.userId, it.emoji) },
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
                senderId = myId,
                replySenderId = p.replySenderId,
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

    // The group itself (name, leader, background) and its members. When the
    // group is not returned any more I was removed, or the group is gone.
    suspend fun reloadGroup() {
        attempt { ChatRepository.loadGroup(groupId) }.onSuccess { loaded ->
            if (loaded == null) {
                gone = true
                // Nothing of a group I am no longer in stays on the phone.
                ChatMemory.forgetGroupChat(groupId)
            } else {
                group = loaded
                wallpaper = loaded.wallpaper
            }
        }
        attempt { ChatRepository.loadGroupMemberRows(groupId) }.onSuccess { rows ->
            applyMemberRows(rows)
            val known = members.map { it.id }.toSet()
            val wanted = rows.map { it.userId }
            if (wanted.toSet() != known) {
                attempt { ChatRepository.loadProfiles(wanted) }.onSuccess { members = it }
            }
        }
        // Also remembers the group and its members for the next opening.
        publish()
    }

    // Ids of the deputy leaders (migration 24; empty without it).
    var deputies by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun applyMemberRows(rows: List<GroupMember>) {
        memberRows = rows
        val nextDeputies = rows.filter { it.role == "deputy" }.map { it.userId }.toSet()
        if (nextDeputies != deputies) deputies = nextDeputies
        val next = rows.associate { it.userId to ChatRepository.toEpochMillis(it.lastReadAt) }
        if (next != readUpTo) readUpTo = next
    }

    // If these fail (for example migration 22 was not run) there are simply
    // no reactions, no pin and no background, exactly as before.
    suspend fun reloadReactions() {
        attempt { ChatRepository.loadGroupReactions(groupId) }.onSuccess { all ->
            val next = all.groupBy { it.messageId }
            // Only when something really changed (this runs every few seconds).
            if (next != reactions) {
                reactions = next
                publish()
            }
        }
    }

    suspend fun reloadPin() {
        attempt { ChatRepository.loadGroupPinnedMessage(groupId) }.onSuccess {
            if (pinned != it) {
                pinned = it
                keep()
            }
        }
    }

    private fun markReadIfVisible() {
        if (!visible) return
        scope.launch {
            attempt { ChatRepository.markGroupRead(groupId) }
                // The others see "đã xem" at once.
                .onSuccess { tellOthers() }
        }
    }

    // Tells the other members' open phones that something other than a
    // message changed (a reaction, who has read, the pin).
    private suspend fun tellOthers() {
        ChatRepository.sendGroupChanged(groupId, myId)
    }

    // Another member's phone said "something changed": fetch reactions,
    // who has read and the pin right now instead of at the next check.
    suspend fun syncExtras() {
        if (loading) return
        reloadReactions()
        // Members, roles, who has read; also notices that the group is gone.
        reloadGroup()
        reloadPin()
    }

    // Who has read up to when, by user id (for the small pictures under
    // the messages). Reading it in a screen makes the screen follow changes.
    fun readTimes(): Map<String, Long> = readUpTo

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
        // Know up to where I deleted this group's history on my side before
        // asking for messages. (Waited for only when it was never read since
        // login; otherwise it is refreshed alongside.)
        // The group and its members are asked for right away, alongside.
        launch { reloadGroup() }
        if (ChatRepository.groupPrefsKnown) {
            launch { attempt { ChatRepository.loadGroupPrefs() } }
        } else {
            // At most 4 seconds: a slow answer must not hold the messages back.
            withTimeoutOrNull(4_000) { attempt { ChatRepository.loadGroupPrefs() } }
        }
        // Everything else is asked for AT THE SAME TIME (before: six
        // questions one after the other, which made opening a group slow).
        val hiddenAnswer = async { attempt { ChatRepository.loadHiddenGroupMessageIds(groupId) } }
        val pageAnswer = async { attempt { ChatRepository.loadGroupMessages(groupId) } }
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
                markReadIfVisible()
            }
            .onFailure { error = it.toUserMessage() }
        loading = false
        launch { reloadReactions() }
        launch { reloadPin() }
        Unit
    }

    // Every 3 seconds while the screen shows: the newest few messages (this
    // also brings recalls of recent messages), reactions, and who has read.
    suspend fun poll() {
        if (loading) return
        pollCount++
        val page = attempt { ChatRepository.loadGroupMessages(groupId, limit = 20) }.getOrNull()
        if (page != null) {
            var changed = false
            var somethingNew = false
            page.forEach {
                val before = confirmed[it.id]
                if (before != it) {
                    if (before == null) somethingNew = true
                    confirmed[it.id] = it
                    changed = true
                }
            }
            if (changed) publish()
            if (somethingNew) markReadIfVisible()
        }
        reloadReactions()
        // Every second time: the pinned message and the "đã xem" numbers.
        if (pollCount % 2 == 0) {
            reloadPin()
            attempt { ChatRepository.loadGroupMemberRows(groupId) }.onSuccess { applyMemberRows(it) }
        }
        // Every fifth time (about 15 seconds): name, leader, background and
        // members. Also at once when a new notice arrived ("changed the
        // background", "added An"...), to show what it is about.
        val newestNotice = page?.firstOrNull { it.kind == "system" }?.id
        val noticeIsNew = newestNotice != null && newestNotice != lastNoticeId
        if (noticeIsNew) lastNoticeId = newestNotice
        if (pollCount % 5 == 0 || noticeIsNew) reloadGroup()
    }

    private var lastNoticeId: String? = null

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
                ChatRepository.loadGroupMessages(groupId, before = oldest.createdAt)
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
            attempt { ChatRepository.loadGroupMessages(groupId, before = oldest.createdAt) }
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

    // ----- Background ----------------------------------------------------

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
            attempt { ChatRepository.setGroupWallpaper(groupId, value) }
                .onSuccess { deleteOldWallpaperPicture(before, value) }
                .onFailure { error = it.toUserMessage() }
            reloadGroup()
            poll()
        }
    }

    // A picture (JPEG bytes) as background, stored in the group's folder.
    fun chooseWallpaperPicture(jpegBytes: ByteArray) {
        val before = wallpaper
        scope.launch {
            attempt {
                val path = "$groupId/wallpaper-${UUID.randomUUID()}.jpg"
                ChatRepository.uploadMedia(path, jpegBytes)
                MediaCache.put(path, jpegBytes)
                wallpaper = "img:$path"
                ChatRepository.setGroupWallpaper(groupId, "img:$path")
                deleteOldWallpaperPicture(before, "img:$path")
            }.onFailure { error = it.toUserMessage() }
            reloadGroup()
            poll()
        }
    }

    // ----- Pin, reactions, recall, hide ----------------------------------

    fun pin(messageId: String) {
        scope.launch {
            attempt { ChatRepository.pinGroupMessage(messageId) }
                .onSuccess { tellOthers() }
                .onFailure { error = it.toUserMessage() }
            reloadPin()
        }
    }

    fun unpin() {
        pinned = null
        scope.launch {
            attempt { ChatRepository.unpinGroupMessage(groupId) }
                .onSuccess { tellOthers() }
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
            attempt { ChatRepository.setGroupReaction(messageId, next) }
                .onSuccess { tellOthers() }
                .onFailure { error = it.toUserMessage() }
            reloadReactions()
        }
    }

    // Take back one of my own messages, for every member.
    fun recall(messageId: String) {
        // The file of a recalled picture, voice message or file is no
        // longer needed by anyone: remove it from the storage as well.
        val filePath = confirmed[messageId]?.mediaPath
        scope.launch {
            attempt { ChatRepository.recallGroupMessage(messageId) }
                .onSuccess {
                    error = null
                    if (filePath != null) ChatRepository.deleteMedia(filePath)
                    // The recalled message may be older than what poll() reads.
                    confirmed[messageId]?.let { old ->
                        confirmed[messageId] = old.copy(
                            content = "Tin nhắn đã được thu hồi",
                            kind = "text",
                            mediaPath = null,
                            durationMs = null,
                            fileName = null,
                            fileSize = null,
                            recalledAt = old.createdAt ?: "recalled",
                        )
                    }
                    publish()
                    poll()
                    reloadPin()
                }
                .onFailure { error = it.toUserMessage() }
        }
    }

    // Remove a message from MY screen only. The other members still have it.
    fun hide(messageId: String) {
        hidden.add(messageId)
        publish()
        scope.launch {
            attempt { ChatRepository.hideGroupMessage(messageId) }
                .onFailure {
                    hidden.remove(messageId)
                    error = it.toUserMessage()
                    publish()
                }
        }
    }

    // ----- Forwarding -----------------------------------------------------

    // Sends a COPY of a message into a one-to-one conversation. A file is
    // copied too, because each conversation has its own private folder.
    suspend fun forwardTo(targetConversationId: String, messageKey: String) {
        val original = confirmed[messageKey] ?: throw IllegalStateException("message not found")
        val path = original.mediaPath
        if (original.kind == "text" || path == null) {
            ChatRepository.sendMessage(targetConversationId, original.content)
            return
        }
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
        } else {
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
        )
    }

    // ----- Sending --------------------------------------------------------

    // replyToKey: the message being answered (its key on screen), or null.
    fun send(text: String, replyToKey: String? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val original = replyToKey?.let { confirmed[it] }
        enqueue(
            PendingGroupMessage(
                UUID.randomUUID().toString(),
                clean.take(4000),
                replyToId = original?.id,
                replyPreview = original?.content?.take(120),
                replySenderId = original?.senderId,
            ),
        )
    }

    fun sendImage(jpegBytes: ByteArray, replyToKey: String? = null) {
        val original = replyToKey?.let { confirmed[it] }
        enqueue(
            PendingGroupMessage(
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
            PendingGroupMessage(
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
            PendingGroupMessage(
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

    private fun enqueue(item: PendingGroupMessage) {
        pending.add(item)
        publish()
        deliver(item)
    }

    private fun deliver(item: PendingGroupMessage) {
        scope.launch {
            attempt {
                val data = item.bytes
                // Step 1: upload the file (skipped on retry if already done).
                val path: String? = if (item.kind == "text" || data == null) {
                    null
                } else {
                    item.uploadedPath ?: run {
                        val extension = when (item.kind) {
                            "image" -> "jpg"
                            "voice" -> "m4a"
                            else -> safeExtension(item.fileName)
                        }
                        val newPath = "$groupId/${UUID.randomUUID()}.$extension"
                        ChatRepository.uploadMedia(newPath, data)
                        MediaCache.put(newPath, data)
                        item.uploadedPath = newPath
                        newPath
                    }
                }
                // Step 2: create the message.
                val replyToId = item.replyToId
                val replyPreview = item.replyPreview
                val replySenderId = item.replySenderId
                if (replyToId != null && replyPreview != null && replySenderId != null && item.kind != "file") {
                    ChatRepository.sendGroupReply(
                        NewGroupReplyMessage(
                            groupId = groupId,
                            content = item.text,
                            kind = if (path == null) "text" else item.kind,
                            mediaPath = path,
                            durationMs = item.durationMs,
                            replyToId = replyToId,
                            replyPreview = replyPreview,
                            replySenderId = replySenderId,
                        ),
                    )
                } else {
                    ChatRepository.sendGroupMessage(
                        NewGroupMessage(
                            groupId = groupId,
                            content = item.text,
                            kind = if (path == null) "text" else item.kind,
                            mediaPath = path,
                            durationMs = item.durationMs,
                            fileName = if (item.kind == "file") item.fileName ?: "file" else null,
                            fileSize = if (item.kind == "file") data?.size else null,
                        ),
                    )
                }
                // The saved message comes back with the next read; fetch it
                // BEFORE the waiting copy is removed, so the bubble does not
                // blink away and back.
                // (If only this read fails, the message is still sent: it
                // must not be marked as failed, or a retry would send it twice.)
                attempt { ChatRepository.loadGroupMessages(groupId, limit = 20) }
                    .onSuccess { page -> page.forEach { confirmed[it.id] = it } }
            }
                .onSuccess {
                    pending.remove(item)
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
        ChatMemory.groupChat(groupId)?.let { remembered ->
            remembered.messages.forEach { confirmed[it.id] = it }
            hidden.addAll(remembered.hidden)
            reactions = remembered.reactions.groupBy { it.messageId }
            group = remembered.group
            wallpaper = remembered.group?.wallpaper
            members = remembered.members
            pinned = remembered.pinned
            applyMemberRows(remembered.memberRows)
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
