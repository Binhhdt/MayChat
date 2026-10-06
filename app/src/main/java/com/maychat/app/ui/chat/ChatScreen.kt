package com.maychat.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.maychat.app.R
import com.maychat.app.call.CallManager
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Message
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.formatTime
import com.maychat.app.ui.common.dayLabel
import com.maychat.app.ui.common.localDay
import com.maychat.app.ui.common.offlineLabel
import com.maychat.app.ui.main.FriendsState
import com.maychat.app.ui.main.Relation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Longest voice message: 2 minutes (about 480 KB).
private const val MAX_VOICE_MS = 120_000L

// One-to-one chat. The newest message is at the bottom.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    myId: String,
    conversationId: String,
    other: Profile,
    friends: FriendsState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(conversationId) { ChatState(conversationId, myId, scope) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val online by ChatRepository.onlineUsers.collectAsState()
    val connectionCount by ChatRepository.connectionCount.collectAsState()
    val listState = rememberLazyListState()

    var draft by remember(conversationId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }

    // "Is typing" indicator.
    var otherTypingAt by remember(conversationId) { mutableLongStateOf(0L) }   // last signal received
    var otherTyping by remember(conversationId) { mutableStateOf(false) }
    var myTypingSentAt by remember(conversationId) { mutableLongStateOf(0L) }  // last signal sent

    // The picture currently open on the whole screen (null = none).
    var viewerPath by remember(conversationId) { mutableStateOf<String?>(null) }

    // The message I am answering (null = a normal message).
    var replyingTo by remember(conversationId) { mutableStateOf<UiMessage?>(null) }
    // Whether the emoji panel under the text box is open.
    var emojiOpen by remember(conversationId) { mutableStateOf(false) }

    // Someone reacted to a message: refresh the reactions.
    LaunchedEffect(conversationId) {
        ChatRepository.reactionEvents.collect { state.reloadReactions() }
    }

    val relation = friends.relation(other.id)
    val blockedByMe = relation == Relation.BLOCKED

    // "Offline for how long": the other person's last active time, re-read
    // every 30 seconds while they are offline.
    val otherOnline = other.id in online
    var otherLastSeen by remember(conversationId) { mutableStateOf(other.lastSeenAt) }
    var statusNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(conversationId, otherOnline) {
        if (!otherOnline) {
            delay(1_500)
            while (true) {
                attempt { ChatRepository.loadProfile(other.id) }.getOrNull()?.let { otherLastSeen = it.lastSeenAt }
                statusNowMs = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }

    // Voice call: needs the microphone permission before calling.
    val askMicrophoneForCall = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            CallManager.startCall(other, conversationId)
        } else {
            state.showError("Cần quyền micro để gọi thoại. Bạn có thể bật trong Cài đặt của điện thoại.")
        }
    }

    // Load at start, and again whenever the live connection comes (back) up.
    LaunchedEffect(conversationId, connectionCount) { state.refresh() }

    // Live messages and read receipts.
    LaunchedEffect(conversationId) {
        ChatRepository.messageEvents.collect {
            state.onEvent(it)
            // Their message has arrived, so they are no longer "typing".
            if (it.conversationId == conversationId && it.senderId != myId) otherTyping = false
        }
    }

    // Receive "the other person is typing" signals.
    LaunchedEffect(conversationId) {
        ChatRepository.listenTyping(conversationId, myId) {
            otherTypingAt = System.currentTimeMillis()
        }
    }

    // Show the indicator, and hide it 4 seconds after the last signal.
    LaunchedEffect(otherTypingAt) {
        if (otherTypingAt > 0L) {
            otherTyping = true
            delay(4_000)
            otherTyping = false
        }
    }

    // Safety net: while this chat is on screen, also ask the server for new
    // messages every 4 seconds. Normally the live connection delivers them
    // instantly and this finds nothing; if the live connection has silently
    // died, a message is at most 4 seconds late instead of lost until reopen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(conversationId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(4_000)
                state.poll()
            }
        }
    }

    // Only mark messages as read while the chat is really on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.visible = true
        scope.launch { state.refresh() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        state.visible = false
    }

    // Leaving the chat: stop any playing voice message and drop a recording.
    DisposableEffect(conversationId) {
        onDispose {
            VoicePlayer.stop()
            recorder.cancel()
        }
    }

    // Line with the date ("Hôm nay", "Hôm qua", 05/10/2026) above the first
    // message of each day. The list is newest-first, so the message before
    // this one in time is at index + 1.
    val dateLabels = remember(state.messages) {
        val list = state.messages
        val labels = HashMap<String, String>()
        for (i in list.indices) {
            val day = localDay(list[i].createdAt)
            val older = list.getOrNull(i + 1)
            if (older == null || localDay(older.createdAt) != day) {
                labels[list[i].key] = dayLabel(day)
            }
        }
        labels
    }

    // Forwarding: the message being forwarded (null = none).
    var forwarding by remember(conversationId) { mutableStateOf<UiMessage?>(null) }
    // Short confirmation line, for example after forwarding.
    var notice by remember(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000)
            notice = null
        }
    }

    // Taking a photo: handled by CameraCapture (and MainActivity), so the
    // photo is still sent when Android closes this screen while the camera
    // app is open. Here we only show its progress and errors.
    val cameraSending by CameraCapture.sending.collectAsState()
    LaunchedEffect(conversationId) {
        CameraCapture.errors.collect { state.showError(it) }
    }
    // A photo that was just sent arrives like any other message; make sure
    // it shows up promptly.
    LaunchedEffect(cameraSending) {
        if (!cameraSending) state.poll()
    }

    // Brings one message into view, about a third up from the bottom,
    // loading older messages first when it is not on screen yet.
    suspend fun jumpTo(messageId: String, createdAt: String) {
        state.loadUntil(createdAt)
        val index = state.messages.indexOfFirst { it.key == messageId }
        if (index >= 0) {
            val third = listState.layoutInfo.viewportSize.height / 3
            listState.scrollToItem(index, -third)
        }
    }

    // ----- Search inside this conversation -----------------------------
    var searchMode by remember(conversationId) { mutableStateOf(false) }
    var searchQuery by remember(conversationId) { mutableStateOf("") }
    // Matching messages, oldest first. searchIndex points at the one shown.
    var searchResults by remember(conversationId) { mutableStateOf<List<Message>>(emptyList()) }
    var searchIndex by remember(conversationId) { mutableStateOf(-1) }
    var searchBusy by remember(conversationId) { mutableStateOf(false) }
    val currentResult = searchResults.getOrNull(searchIndex)

    // Run the search a short moment after typing stops; start at the newest match.
    LaunchedEffect(searchMode, searchQuery) {
        if (!searchMode || searchQuery.trim().length < 2) {
            searchResults = emptyList()
            searchIndex = -1
            return@LaunchedEffect
        }
        delay(400)
        searchBusy = true
        attempt { ChatRepository.searchMessages(conversationId, searchQuery) }
            .onSuccess { found ->
                searchResults = found.reversed()
                searchIndex = found.size - 1
            }
            .onFailure { state.showError(it.toUserMessage()) }
        searchBusy = false
    }

    // Jump to the current match: load older messages if needed, then scroll.
    LaunchedEffect(currentResult?.id) {
        val target = currentResult ?: return@LaunchedEffect
        jumpTo(target.id, target.createdAt)
    }

    // The search box gets the cursor (and the keyboard) as soon as it appears.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchMode) {
        if (searchMode) {
            delay(150)
            runCatching { searchFocus.requestFocus() }
        }
    }

    // The message the pin banner jumped to; it gets the same short flash
    // as a search result.
    var flashKey by remember(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(flashKey) {
        if (flashKey != null) {
            delay(1_500)
            flashKey = null
        }
    }

    // Back closes the search first, not the whole chat.
    BackHandler(enabled = searchMode) {
        searchMode = false
        searchQuery = ""
    }

    // Which received messages show the sender's picture next to them: the
    // first message of each run of messages from the other person (like
    // Zalo). The list is newest-first, so "the one before" is at index + 1.
    val avatarKeys = remember(state.messages) {
        val list = state.messages
        val keys = HashSet<String>()
        for (i in list.indices) {
            val current = list[i]
            val older = list.getOrNull(i + 1)
            if (!current.mine && (older == null || older.mine)) keys.add(current.key)
        }
        keys
    }

    // True when a message from the other person arrived while I was reading
    // older messages further up. Shows the "Tin nhắn mới" button.
    var newBelow by remember(conversationId) { mutableStateOf(false) }

    // A new newest message appeared:
    // - I am at (or near) the bottom, or I sent it myself -> stay at the bottom.
    // - I have scrolled up to read old messages -> do NOT jump; show the button.
    val newest = state.messages.firstOrNull()
    val newestKey = newest?.key
    LaunchedEffect(newestKey) {
        if (newest == null) return@LaunchedEffect
        if (newest.mine || listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
            newBelow = false
        } else {
            newBelow = true
        }
    }

    // Hide the button as soon as I am back at the bottom by scrolling myself.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(atBottom) {
        if (atBottom) newBelow = false
    }

    // ----- Sending an image -------------------------------------------------
    // The system photo picker needs no storage permission.
    // Up to 10 pictures can be ticked in one go; each is sent as its own message.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNotEmpty()) {
            // If I am answering a message, the first picture carries the quote.
            var replyKey = replyingTo?.key
            replyingTo = null
            scope.launch {
                for (uri in uris) {
                    val bytes = compressImage(context, uri)
                    if (bytes == null) {
                        state.showError("Có ảnh không đọc được và đã bị bỏ qua.")
                    } else {
                        state.sendImage(bytes, replyToKey = replyKey)
                        replyKey = null
                    }
                }
            }
        }
    }

    // ----- Recording a voice message ---------------------------------------
    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start()) {
            recordedMs = 0L
            recording = true
        } else {
            state.showError("Không dùng được micro lúc này.")
        }
    }

    fun finishRecording(send: Boolean) {
        recording = false
        if (!send) {
            recorder.cancel()
            return
        }
        val result = recorder.stop()
        if (result == null) {
            state.showError("Bản ghi quá ngắn. Hãy ghi ít nhất 1 giây.")
        } else {
            state.sendVoice(result.bytes, result.durationMs, replyToKey = replyingTo?.key)
            replyingTo = null
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecording()
        } else {
            state.showError("Cần quyền micro để ghi tin nhắn thoại. Bạn có thể bật trong Cài đặt của điện thoại.")
        }
    }

    // Counts the seconds while recording and stops at the maximum length.
    LaunchedEffect(recording) {
        while (recording) {
            recordedMs = recorder.elapsedMs
            if (recordedMs >= MAX_VOICE_MS) {
                finishRecording(send = true)
                break
            }
            delay(200)
        }
    }

    // App goes to the background while recording: throw the recording away.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (recording) finishRecording(send = false)
    }

    Scaffold(
        topBar = {
            if (searchMode) {
                // Search bar instead of the normal title bar.
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Tìm tin nhắn văn bản") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                                .focusRequester(searchFocus),
                        )
                    },
                    navigationIcon = {
                        TextButton(
                            onClick = {
                                searchMode = false
                                searchQuery = ""
                            },
                        ) { Text("‹") }
                    },
                )
            } else
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            name = other.displayName,
                            online = other.id in online,
                            size = 36.dp,
                            avatarPath = other.avatarPath,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(other.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                if (otherOnline) "Đang hoạt động" else offlineLabel(otherLastSeen, statusNowMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    // Voice call button.
                    TextButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                CallManager.startCall(other, conversationId)
                            } else {
                                askMicrophoneForCall.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !blockedByMe,
                    ) { Text("📞", style = MaterialTheme.typography.titleLarge) }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Tìm trong cuộc trò chuyện") },
                                onClick = {
                                    menuOpen = false
                                    searchMode = true
                                },
                            )
                            when (relation) {
                                Relation.NONE -> DropdownMenuItem(
                                    text = { Text("Gửi lời mời kết bạn") },
                                    onClick = {
                                        menuOpen = false
                                        friends.sendRequest(other.id)
                                    },
                                )
                                Relation.REQUEST_SENT -> DropdownMenuItem(
                                    text = { Text("Hủy lời mời kết bạn") },
                                    onClick = {
                                        menuOpen = false
                                        friends.remove(other.id)
                                    },
                                )
                                Relation.REQUEST_RECEIVED -> DropdownMenuItem(
                                    text = { Text("Chấp nhận kết bạn") },
                                    onClick = {
                                        menuOpen = false
                                        friends.accept(other.id)
                                    },
                                )
                                Relation.FRIEND -> DropdownMenuItem(
                                    text = { Text("Hủy kết bạn") },
                                    onClick = {
                                        menuOpen = false
                                        friends.remove(other.id)
                                    },
                                )
                                Relation.BLOCKED -> {}
                            }
                            DropdownMenuItem(
                                text = { Text(if (blockedByMe) "Bỏ chặn" else "Chặn người này") },
                                onClick = {
                                    menuOpen = false
                                    if (blockedByMe) friends.unblock(other.id) else confirmBlock = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            // Pinned message: tap to jump to it, ✕ to remove the pin.
            state.pinned?.let { pin ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            scope.launch {
                                jumpTo(pin.messageId, pin.createdAt)
                                flashKey = pin.messageId
                            }
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Tin nhắn đã ghim",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            pin.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { state.unpin() }) { Text("✕") }
                }
            }

            if (state.loading) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) { LoadingScreen() }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.messages, key = { it.key }) { message ->
                            MessageBubble(
                                highlightQuery = when {
                                    message.key == currentResult?.id -> searchQuery.trim()
                                    message.key == flashKey -> ""
                                    else -> null
                                },
                                isPinned = message.key == state.pinned?.messageId,
                                onTogglePin = {
                                    if (message.key == state.pinned?.messageId) state.unpin() else state.pin(message.key)
                                },
                                dateLabel = dateLabels[message.key],
                                onForward = { forwarding = message },
                                showAvatar = message.key in avatarKeys,
                                otherAvatarPath = other.avatarPath,
                                message = message,
                                onRetry = { state.retry(message.key) },
                                onOpenImage = { viewerPath = it },
                                onRecall = { state.recall(message.key) },
                                onHide = { state.hide(message.key) },
                                otherName = other.displayName,
                                onReply = { replyingTo = message },
                                onReact = { emoji -> state.react(message.key, emoji) },
                            )
                        }
                        if (state.hasOlder) {
                            item(key = "load-older") {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    TextButton(onClick = { state.loadOlder() }, enabled = !state.loadingOlder) {
                                        Text(if (state.loadingOlder) "Đang tải…" else "Tải tin nhắn cũ hơn")
                                    }
                                }
                            }
                        }
                        if (state.messages.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "Hãy gửi lời chào tới ${other.displayName}.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                )
                            }
                        }
                    }

                    // Floating button: jump down to the newest message.
                    if (newBelow) {
                        Surface(
                            onClick = {
                                newBelow = false
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp),
                        ) {
                            Text(
                                "↓ Tin nhắn mới",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            if (blockedByMe) {
                Text(
                    "Bạn đã chặn ${other.displayName}. Bỏ chặn trong menu ⋮ để nhắn tin lại.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            (state.error ?: friends.error)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (otherTyping && !blockedByMe) {
                Text(
                    "••• ${other.displayName} đang soạn tin…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (cameraSending) {
                Text(
                    "Đang gửi ảnh vừa chụp…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (searchMode) {
                // "Result 3/5" with buttons to the older and the newer match.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            searchQuery.trim().length < 2 -> "Nhập ít nhất 2 ký tự"
                            searchBusy -> "Đang tìm…"
                            searchResults.isEmpty() -> "Không tìm thấy"
                            else -> "Kết quả thứ ${searchIndex + 1}/${searchResults.size}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    // Down = towards newer messages, up = towards older ones.
                    TextButton(
                        onClick = { searchIndex++ },
                        enabled = searchIndex < searchResults.size - 1,
                    ) { Text("∨", style = MaterialTheme.typography.titleLarge) }
                    TextButton(
                        onClick = { searchIndex-- },
                        enabled = searchIndex > 0,
                    ) { Text("∧", style = MaterialTheme.typography.titleLarge) }
                }
            } else if (recording) {
                // Shown instead of the text box while the microphone is on.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "● Đang ghi ${formatDuration(recordedMs)}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { finishRecording(send = false) }) { Text("Hủy") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { finishRecording(send = true) }) { Text("Gửi") }
                }
            } else {
                // "Replying to ..." strip above the text box.
                replyingTo?.let { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_reply),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (target.mine) "Trả lời chính bạn" else "Trả lời ${other.displayName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                target.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { replyingTo = null }) { Text("✕") }
                    }
                }

                // Text box row: emoji on the left; microphone and picture on the
                // right while the box is empty, the send button once there is text.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { emojiOpen = !emojiOpen }, enabled = !blockedByMe) {
                        Icon(
                            painter = painterResource(R.drawable.ic_emoji),
                            contentDescription = "Biểu tượng cảm xúc",
                            tint = if (emojiOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            // Tell the other phone "typing", at most once every 2.5 seconds.
                            val now = System.currentTimeMillis()
                            if (it.isNotBlank() && now - myTypingSentAt > 2_500) {
                                myTypingSentAt = now
                                scope.launch { ChatRepository.sendTyping(conversationId, myId) }
                            }
                        },
                        placeholder = { Text("Tin nhắn") },
                        enabled = !blockedByMe,
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.isBlank()) {
                        IconButton(
                            onClick = {
                                if (!CameraCapture.start(context, conversationId)) {
                                    state.showError("Không mở được máy ảnh trên điện thoại này.")
                                }
                            },
                            enabled = !blockedByMe,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_camera),
                                contentDescription = "Chụp ảnh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) startRecording() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            enabled = !blockedByMe,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic),
                                contentDescription = "Ghi tin nhắn thoại",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = !blockedByMe,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_image),
                                contentDescription = "Gửi ảnh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.width(4.dp))
                        FilledIconButton(
                            onClick = {
                                state.send(draft, replyToKey = replyingTo?.key)
                                draft = ""
                                replyingTo = null
                            },
                            enabled = !blockedByMe,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_send),
                                contentDescription = "Gửi",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }

                // Emoji panel: tap one to add it to the message.
                if (emojiOpen) {
                    EmojiPanel(onPick = { draft += it })
                }
            }
        }
    }

    viewerPath?.let { path ->
        ImageViewer(path = path, onClose = { viewerPath = null })
    }

    forwarding?.let { target ->
        ForwardScreen(
            myId = myId,
            friends = friends.friends,
            previewText = target.text,
            onClose = { forwarding = null },
            onSend = { targets, note ->
                forwarding = null
                scope.launch {
                    var sent = 0
                    var failure: String? = null
                    for (destination in targets) {
                        attempt {
                            // Create the conversation first if there is none yet.
                            val id = destination.conversationId
                                ?: ChatRepository.openConversation(destination.profile.id)
                            state.forwardTo(id, target.key)
                            if (note.isNotEmpty()) ChatRepository.sendMessage(id, note)
                        }
                            .onSuccess { sent++ }
                            .onFailure { failure = it.toUserMessage() }
                    }
                    if (sent > 0) notice = "Đã chuyển tiếp tới $sent người."
                    failure?.let { state.showError(it) }
                }
            },
        )
    }

    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Chặn ${other.displayName}?") },
            text = {
                Text("Hai người sẽ không gửi được tin nhắn hay lời mời kết bạn cho nhau. Nếu đang là bạn bè thì sẽ hủy kết bạn.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBlock = false
                        friends.block(other.id)
                    },
                ) { Text("Chặn") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text("Không") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    highlightQuery: String?,
    dateLabel: String?,
    onForward: () -> Unit,
    showAvatar: Boolean,
    otherAvatarPath: String?,
    message: UiMessage,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
    onRecall: () -> Unit,
    onHide: () -> Unit,
    otherName: String,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
) {
    // Short flash when this message becomes the highlighted one.
    var flashing by remember(message.key) { mutableStateOf(false) }
    LaunchedEffect(highlightQuery != null) {
        if (highlightQuery != null) {
            flashing = true
            delay(1_200)
            flashing = false
        }
    }
    val outline = if (highlightQuery != null) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
    } else {
        null
    }

    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(message.key) { mutableStateOf(false) }

    val bubbleColor =
        if (message.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (message.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    // Small line under the bubble: time, plus delivery state for my own messages.
    val time = formatTime(message.createdAt)
    val meta = when {
        message.recalled -> time
        !message.mine -> time
        message.state == SendState.SENDING -> "Đang gửi…"
        message.state == SendState.FAILED -> "Gửi lỗi. Chạm vào tin nhắn để gửi lại"
        message.state == SendState.READ -> "$time · Đã xem"
        else -> "$time · Đã gửi"
    }

    val path = message.mediaPath
    val failed = message.state == SendState.FAILED
    // A message that is saved on the server (not still sending or failed).
    val saved = message.state == SendState.SENT || message.state == SendState.READ
    val openMenu: () -> Unit = { if (saved) menuOpen = true }

    Column(modifier = Modifier.fillMaxWidth()) {
    // Date line above the first message of a day.
    if (dateLabel != null) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                dateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The message a search (or the pin banner) jumped to flashes
            // with a coloured band for a moment; after that only the
            // outline around its bubble remains.
            .background(
                if (flashing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    Color.Transparent
                },
            ),
        verticalAlignment = Alignment.Top,
    ) {
    // Received messages: the sender's picture on the left of the first
    // message of a run; the following ones are indented by the same width.
    if (!message.mine) {
        if (showAvatar) {
            Avatar(name = otherName, online = false, size = 32.dp, avatarPath = otherAvatarPath)
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Spacer(Modifier.width(8.dp))
    }
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        Box {
            when {
                // Taken back by the sender: a quiet grey note for both people.
                message.recalled -> Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.combinedClickable(onLongClick = openMenu, onClick = {}),
                ) {
                    Text(
                        "Tin nhắn đã được thu hồi",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                // A picture that is already on the server: tap to view it
                // on the whole screen, press and hold for the menu.
                message.kind == "image" && path != null -> Box(
                    modifier = Modifier.combinedClickable(
                        onLongClick = openMenu,
                        onClick = { if (failed) onRetry() else onOpenImage(path) },
                    ),
                ) {
                    Column(horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
                        MediaQuote(message, otherName)
                        ChatImage(path)
                    }
                }

                // A voice message that is already on the server.
                message.kind == "voice" && path != null && !failed -> Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column {
                        if (message.replyPreview != null) {
                            Box(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
                                MediaQuote(message, otherName)
                            }
                        }
                        VoiceBubbleContent(
                            path = path,
                            durationMs = message.durationMs,
                            textColor = textColor,
                            onLongPress = openMenu,
                        )
                    }
                }

                // A message of only one to three emojis: shown large, without
                // a bubble, like a sticker.
                message.kind == "text" && message.replyPreview == null && isEmojiOnly(message.text) -> Box(
                    modifier = Modifier.combinedClickable(
                        onLongClick = openMenu,
                        onClick = { if (failed) onRetry() },
                    ),
                ) {
                    Text(message.text, fontSize = 46.sp, modifier = Modifier.padding(vertical = 2.dp))
                }

                // Text, or a picture / voice message that is still uploading or failed.
                else -> Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp),
                    border = outline,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(
                            onLongClick = openMenu,
                            onClick = { if (failed) onRetry() },
                        ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // The quoted message, when this one is a reply.
                        val quote = message.replyPreview
                        if (quote != null) {
                            Surface(
                                color = textColor.copy(alpha = 0.14f),
                                contentColor = textColor,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        if (message.replyToMine) "Bạn" else otherName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        quote,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(highlighted(message.text, highlightQuery), color = textColor)
                    }
                }
            }

            // Press-and-hold menu.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!message.recalled) {
                    // Quick reactions.
                    Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                        QUICK_REACTIONS.forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        menuOpen = false
                                        onReact(emoji)
                                    }
                                    .padding(8.dp),
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Trả lời") },
                        onClick = {
                            menuOpen = false
                            onReply()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Chuyển tiếp") },
                        onClick = {
                            menuOpen = false
                            onForward()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isPinned) "Bỏ ghim" else "Ghim") },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        },
                    )
                }
                if (message.kind == "text" && !message.recalled) {
                    DropdownMenuItem(
                        text = { Text("Sao chép") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(message.text))
                        },
                    )
                }
                if (message.mine && !message.recalled) {
                    DropdownMenuItem(
                        text = { Text("Thu hồi (cả hai bên)") },
                        onClick = {
                            menuOpen = false
                            onRecall()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Xóa ở phía tôi") },
                    onClick = {
                        menuOpen = false
                        onHide()
                    },
                )
            }
        }
        // Reactions under the bubble. Tapping mine removes it.
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions.forEach { chip ->
                    Surface(
                        onClick = { onReact(chip.emoji) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (chip.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            if (chip.count > 1) "${chip.emoji} ${chip.count}" else chip.emoji,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        Text(
            meta,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
    }
    }
}

// The six reactions offered in the press-and-hold menu.
private val QUICK_REACTIONS = listOf("❤️", "👍", "😆", "😮", "😢", "😡")

// Emojis offered in the panel under the text box.
private val PANEL_EMOJIS = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😋",
    "😎", "🤔", "😴", "😢", "😭", "😡", "😱", "🥰",
    "😇", "🙂", "😉", "😅", "🤗", "🤩", "😏", "😬",
    "👍", "👎", "👏", "🙏", "💪", "👌", "🤝", "✌️",
    "❤️", "💔", "💕", "🔥", "✨", "🎉", "🎂", "🌹",
    "☕", "🍜", "🍻", "⚽", "🎵", "📞", "✅", "❌",
)

// Simple emoji keyboard: a grid of common emojis.
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(PANEL_EMOJIS) { emoji ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 24.sp)
            }
        }
    }
}

// True for a short text made only of emojis (at most three), which is then
// shown large like a sticker.
private fun isEmojiOnly(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 24) return false
    var emojiCount = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 -> {}          // joiners, variation marks
            cp in 0x1F3FB..0x1F3FF -> {}                                 // skin tones
            Character.isWhitespace(cp) -> {}
            cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
                cp in 0x2190..0x21FF || cp in 0x2300..0x23FF -> emojiCount++
            else -> return false
        }
    }
    return emojiCount in 1..3
}

// The message text with every occurrence of the searched words marked in
// yellow (upper and lower case do not matter). Without a query: plain text.
private fun highlighted(text: String, query: String?): AnnotatedString {
    if (query.isNullOrEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        // Only mark when lower-casing did not change the length, so the
        // positions still match the original text.
        if (lowerText.length != text.length || lowerQuery.isEmpty()) return@buildAnnotatedString
        var from = lowerText.indexOf(lowerQuery)
        while (from >= 0) {
            addStyle(
                SpanStyle(background = Color(0xFFFFE066), color = Color(0xFF1B1B1B)),
                from,
                from + lowerQuery.length,
            )
            from = lowerText.indexOf(lowerQuery, from + lowerQuery.length)
        }
    }
}

// The quoted message shown above a picture or voice message that is a reply.
@Composable
private fun MediaQuote(message: UiMessage, otherName: String) {
    val quote = message.replyPreview ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 240.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                if (message.replyToMine) "Bạn" else otherName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                quote,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
