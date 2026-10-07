package com.maychat.app.ui.group

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.GroupMessage
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.chat.CameraCapture
import com.maychat.app.ui.chat.ChatWallpaperLayer
import com.maychat.app.ui.chat.EmojiPanel
import com.maychat.app.ui.chat.ForwardScreen
import com.maychat.app.ui.chat.ImageViewer
import com.maychat.app.ui.chat.MessageBubble
import com.maychat.app.ui.chat.ReactionsSheet
import com.maychat.app.ui.chat.SendState
import com.maychat.app.ui.chat.UiMessage
import com.maychat.app.ui.chat.VoicePlayer
import com.maychat.app.ui.chat.VoiceRecorder
import com.maychat.app.ui.chat.WallpaperDialog
import com.maychat.app.ui.chat.compressImage
import com.maychat.app.ui.chat.formatDuration
import com.maychat.app.ui.chat.readPickedFile
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.dayLabel
import com.maychat.app.ui.common.localDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Longest voice message: 2 minutes.
private const val MAX_VOICE_MS = 120_000L

// Chat screen of one group. It has the features of a one-to-one chat:
// reply, reactions, take back, delete on my side, pin, forward, choosing
// several messages, search, background, photo from the camera. Each message
// shows who sent it. There is no live connection for groups: new messages
// are fetched every 3 seconds while the screen is open.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    myId: String,
    groupId: String,
    initialName: String,
    friends: List<Profile>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(groupId) { GroupChatState(groupId, myId, scope) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val connectionCount by ChatRepository.connectionCount.collectAsState()
    val listState = rememberLazyListState()

    var draft by remember(groupId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }

    var infoOpen by remember(groupId) { mutableStateOf(false) }
    var wallpaperOpen by remember { mutableStateOf(false) }
    // The picture currently open on the whole screen (null = none).
    var viewerPath by remember(groupId) { mutableStateOf<String?>(null) }
    // The message I am answering (null = a normal message).
    var replyingTo by remember(groupId) { mutableStateOf<UiMessage?>(null) }
    // Whether the emoji panel under the text box is open.
    var emojiOpen by remember(groupId) { mutableStateOf(false) }

    val group = state.group
    val groupName = group?.name ?: initialName
    val memberById = remember(state.members) { state.members.associateBy { it.id } }

    // People who wrote here but are no longer members (they left or were
    // removed): their names and pictures are looked up once.
    var formerMembers by remember(groupId) { mutableStateOf<Map<String, Profile>>(emptyMap()) }
    val askedFor = remember(groupId) { HashSet<String>() }
    LaunchedEffect(state.messages, state.members) {
        if (state.members.isEmpty()) return@LaunchedEffect
        val unknown = state.messages.mapNotNull { it.senderId }.toSet()
            .filter { it !in memberById && it !in askedFor }
        if (unknown.isNotEmpty()) {
            askedFor.addAll(unknown)
            attempt { ChatRepository.loadProfiles(unknown) }
                .onSuccess { found -> formerMembers = formerMembers + found.associateBy { it.id } }
        }
    }
    fun personOf(userId: String?): Profile? = userId?.let { memberById[it] ?: formerMembers[it] }
    fun nameOf(userId: String?): String = personOf(userId)?.displayName ?: "Thành viên"

    // ----- "Is typing": who is writing right now -----------------------
    // User id -> when their last "typing" signal arrived.
    var typingAt by remember(groupId) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var myTypingSentAt by remember(groupId) { mutableLongStateOf(0L) }
    LaunchedEffect(groupId) {
        ChatRepository.listenTypingWho(groupId, myId) { who ->
            typingAt = typingAt + (who to System.currentTimeMillis())
        }
    }
    // A name disappears 4 seconds after that person's last signal.
    LaunchedEffect(typingAt.isEmpty()) {
        while (typingAt.isNotEmpty()) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val still = typingAt.filterValues { now - it < 4_000 }
            if (still.size != typingAt.size) typingAt = still
        }
    }
    // Their message has arrived, so they are no longer "typing".
    val newestSender = state.messages.firstOrNull()?.takeIf { !it.mine }
    LaunchedEffect(newestSender?.key) {
        val who = newestSender?.senderId
        if (who != null && who in typingAt) typingAt = typingAt - who
    }

    // Removed from the group, or the group no longer exists: leave the screen.
    LaunchedEffect(state.gone) {
        if (state.gone) onBack()
    }

    // Load at start, and again whenever the connection comes (back) up.
    LaunchedEffect(groupId, connectionCount) { state.refresh() }

    // New messages, reactions and "đã xem" every 3 seconds while showing.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(groupId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(3_000)
                state.poll()
            }
        }
    }

    // Only mark the group as read while the chat is really on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.visible = true
        scope.launch { state.refresh() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        state.visible = false
    }

    // Leaving the chat: stop any playing voice message and drop a recording.
    DisposableEffect(groupId) {
        onDispose {
            VoicePlayer.stop()
            recorder.cancel()
        }
    }

    // Line with the date above the first message of each day. The list is
    // newest-first, so the message before this one in time is at index + 1.
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

    // Which messages of other people start a run of one sender: those show
    // the sender's picture and name.
    val runStarts = remember(state.messages, dateLabels) {
        val list = state.messages
        val keys = HashSet<String>()
        for (i in list.indices) {
            val current = list[i]
            if (current.mine || current.kind == "system") continue
            val older = list.getOrNull(i + 1)
            if (older == null || older.senderId != current.senderId || older.kind == "system" ||
                dateLabels.containsKey(current.key)
            ) {
                keys.add(current.key)
            }
        }
        keys
    }

    // ----- Choosing several messages at once ---------------------------
    var selecting by remember(groupId) { mutableStateOf(false) }
    var selectedKeys by remember(groupId) { mutableStateOf<Set<String>>(emptySet()) }
    // Keys of the chosen messages being forwarded together (null = none).
    var forwardingMany by remember(groupId) { mutableStateOf<List<String>?>(null) }
    var confirmDeleteMany by remember(groupId) { mutableStateOf(false) }
    val leaveSelecting: () -> Unit = {
        selecting = false
        selectedKeys = emptySet()
    }
    // The chosen messages, oldest first.
    fun chosenMessages(): List<UiMessage> =
        state.messages.filter { it.key in selectedKeys }.reversed()
    BackHandler(enabled = selecting) { leaveSelecting() }

    // Forwarding: the message being forwarded (null = none).
    var forwarding by remember(groupId) { mutableStateOf<UiMessage?>(null) }
    // The message whose "who reacted" sheet is open (null = none).
    var reactionsFor by remember(groupId) { mutableStateOf<String?>(null) }
    // Short confirmation line, for example after forwarding.
    var notice by remember(groupId) { mutableStateOf<String?>(null) }
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
    LaunchedEffect(groupId) {
        CameraCapture.errors.collect { state.showError(it) }
    }
    LaunchedEffect(cameraSending) {
        if (!cameraSending) state.poll()
    }
    val askCameraForPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            state.showError("Cần quyền camera để chụp ảnh. Bạn có thể bật trong Cài đặt của điện thoại.")
        } else if (!CameraCapture.start(context, groupId, group = true)) {
            state.showError("Không mở được máy ảnh trên điện thoại này.")
        }
    }

    // Tapping the message area puts the keyboard (and the emoji panel) away.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        emojiOpen = false
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

    // ----- Search inside this group ------------------------------------
    var searchMode by remember(groupId) { mutableStateOf(false) }
    var searchQuery by remember(groupId) { mutableStateOf("") }
    // Matching messages, oldest first. searchIndex points at the one shown.
    var searchResults by remember(groupId) { mutableStateOf<List<GroupMessage>>(emptyList()) }
    var searchIndex by remember(groupId) { mutableStateOf(-1) }
    var searchBusy by remember(groupId) { mutableStateOf(false) }
    val currentResult = searchResults.getOrNull(searchIndex)

    // Run the search a short moment after typing stops; start at the newest match.
    LaunchedEffect(searchMode, searchQuery) {
        if (!searchMode || searchQuery.trim().length < 2) {
            searchResults = emptyList()
            searchIndex = -1
            searchBusy = false
            return@LaunchedEffect
        }
        searchBusy = true
        delay(400)
        attempt { ChatRepository.searchGroupMessages(groupId, searchQuery) }
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
        val at = target.createdAt ?: return@LaunchedEffect
        jumpTo(target.id, at)
    }

    // The search box gets the cursor (and the keyboard) as soon as it appears.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchMode) {
        if (searchMode) {
            delay(150)
            runCatching { searchFocus.requestFocus() }
        }
    }

    // The message the pin banner jumped to; it gets a short flash.
    var flashKey by remember(groupId) { mutableStateOf<String?>(null) }
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

    // True when a message from someone else arrived while I was reading
    // older messages further up. Shows the "Tin nhắn mới" button.
    var newBelow by remember(groupId) { mutableStateOf(false) }
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
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(atBottom) {
        if (atBottom) newBelow = false
    }

    // ----- Sending pictures and files -----------------------------------
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

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val picked = readPickedFile(context, uri)
                    if (picked == null) {
                        state.showError("Không đọc được file này.")
                    } else {
                        state.sendFile(picked.bytes, picked.name)
                    }
                } catch (e: IllegalArgumentException) {
                    state.showError("File quá lớn. Chỉ gửi được file tối đa 5 MB.")
                }
            }
        }
    }

    // ----- Recording a voice message -------------------------------------
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
            if (selecting) {
                TopAppBar(
                    title = { Text("Đã chọn ${selectedKeys.size}") },
                    navigationIcon = { BackButton(onClick = leaveSelecting) },
                )
            } else if (searchMode) {
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
                        BackButton(
                            onClick = {
                                searchMode = false
                                searchQuery = ""
                            },
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        // Tapping the picture or the name opens the group information.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { infoOpen = true },
                        ) {
                            Avatar(name = groupName, online = false, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    groupName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (state.members.isEmpty()) "Nhóm" else "${state.members.size} thành viên",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = { BackButton(onClick = onBack) },
                    actions = {
                        IconButton(onClick = { infoOpen = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_group),
                                contentDescription = "Thông tin nhóm",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more),
                                    contentDescription = "Tùy chọn",
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Thông tin nhóm") },
                                    onClick = {
                                        menuOpen = false
                                        infoOpen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tìm trong nhóm") },
                                    onClick = {
                                        menuOpen = false
                                        searchMode = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Hình nền") },
                                    onClick = {
                                        menuOpen = false
                                        wallpaperOpen = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Watches touches BEFORE the messages get them and does
                        // not use them up, so tapping, press-and-hold and
                        // scrolling on messages keep working.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                var pressedAt: Offset? = null
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull() ?: continue
                                    if (event.type == PointerEventType.Press) {
                                        pressedAt = change.position
                                    } else if (event.type == PointerEventType.Release) {
                                        val start = pressedAt
                                        pressedAt = null
                                        if (start != null &&
                                            (change.position - start).getDistance() < viewConfiguration.touchSlop
                                        ) {
                                            dismissKeyboard()
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    ChatWallpaperLayer(state.wallpaper)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.messages, key = { it.key }) { message ->
                            val sender = personOf(message.senderId)
                            val startsRun = message.key in runStarts
                            // Notices, and messages still being sent, cannot be chosen.
                            val canChoose = message.kind != "system" &&
                                message.state != SendState.SENDING &&
                                message.state != SendState.FAILED
                            Box {
                                MessageBubble(
                                    selectMark = if (selecting && canChoose) message.key in selectedKeys else null,
                                    onSelectMany = {
                                        selecting = true
                                        selectedKeys = setOf(message.key)
                                    },
                                    markQuery = if (searchMode && searchQuery.trim().length >= 2) {
                                        searchQuery.trim()
                                    } else {
                                        null
                                    },
                                    isPinned = message.key == state.pinned?.messageId,
                                    onTogglePin = {
                                        if (message.key == state.pinned?.messageId) {
                                            state.unpin()
                                        } else {
                                            state.pin(message.key)
                                        }
                                    },
                                    highlightQuery = when {
                                        message.key == currentResult?.id -> searchQuery.trim()
                                        message.key == flashKey -> ""
                                        else -> null
                                    },
                                    dateLabel = dateLabels[message.key],
                                    onForward = { forwarding = message },
                                    showAvatar = startsRun,
                                    otherAvatarPath = sender?.avatarPath,
                                    message = message,
                                    onRetry = { state.retry(message.key) },
                                    onOpenImage = { viewerPath = it },
                                    onRecall = { state.recall(message.key) },
                                    onHide = { state.hide(message.key) },
                                    otherName = nameOf(message.senderId),
                                    onReply = { replyingTo = message },
                                    onReact = { emoji -> state.react(message.key, emoji) },
                                    senderLabel = if (startsRun) nameOf(message.senderId) else null,
                                    quoteName = nameOf(message.replySenderId),
                                    sentMeta = state.sentMeta(message),
                                    onShowReactions = { reactionsFor = message.key },
                                )
                                // While choosing several messages: a layer over the
                                // message catches the tap and ticks it on or off.
                                if (selecting && canChoose) {
                                    val picked = message.key in selectedKeys
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) {
                                                selectedKeys = if (picked) {
                                                    selectedKeys - message.key
                                                } else {
                                                    selectedKeys + message.key
                                                }
                                            },
                                    )
                                }
                            }
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
                                    "Hãy gửi lời chào tới cả nhóm.",
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

            state.error?.let {
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

            if (typingAt.isNotEmpty()) {
                val names = typingAt.keys.map { nameOf(it) }
                Text(
                    "••• " + when (names.size) {
                        1 -> names[0]
                        2 -> "${names[0]} và ${names[1]}"
                        else -> "${names[0]} và ${names.size - 1} người khác"
                    } + " đang soạn tin…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

            if (selecting) {
                // What to do with the chosen messages.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            forwardingMany = chosenMessages().filter { !it.recalled }.map { it.key }
                        },
                        enabled = selectedKeys.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Chuyển tiếp") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { confirmDeleteMany = true },
                        enabled = selectedKeys.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Xóa phía tôi", color = MaterialTheme.colorScheme.error) }
                }
            } else if (searchMode) {
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
                            searchResults.size >= ChatRepository.SEARCH_LIMIT ->
                                "Kết quả thứ ${searchIndex + 1}/${searchResults.size}+"
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
                                if (target.mine) "Trả lời chính bạn" else "Trả lời ${nameOf(target.senderId)}",
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

                // Text box row: emoji on the left; file, camera, microphone and
                // picture on the right while the box is empty, the send button
                // once there is text.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { emojiOpen = !emojiOpen }) {
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
                            // Tell the group "typing", at most once every 2.5 seconds.
                            val now = System.currentTimeMillis()
                            if (it.isNotBlank() && now - myTypingSentAt > 2_500) {
                                myTypingSentAt = now
                                scope.launch { ChatRepository.sendTyping(groupId, myId) }
                            }
                        },
                        placeholder = { Text("Tin nhắn") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.isBlank()) {
                        IconButton(
                            onClick = {
                                try {
                                    pickFile.launch(arrayOf("*/*"))
                                } catch (e: Exception) {
                                    state.showError("Không mở được trình chọn file trên điện thoại này.")
                                }
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_attach),
                                contentDescription = "Gửi file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                val cameraGranted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!cameraGranted) {
                                    askCameraForPhoto.launch(Manifest.permission.CAMERA)
                                } else if (!CameraCapture.start(context, groupId, group = true)) {
                                    state.showError("Không mở được máy ảnh trên điện thoại này.")
                                }
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_camera),
                                contentDescription = "Chụp ảnh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
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
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic),
                                contentDescription = "Ghi tin nhắn thoại",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_image),
                                contentDescription = "Gửi ảnh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
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

    reactionsFor?.let { key ->
        val reactors = state.messages.firstOrNull { it.key == key }?.reactors ?: emptyList()
        if (reactors.isEmpty()) {
            // The last reaction was removed: nothing left to show.
            LaunchedEffect(key) { reactionsFor = null }
        } else {
            ReactionsSheet(
                reactors = reactors,
                myId = myId,
                nameOf = { nameOf(it) },
                avatarOf = { personOf(it)?.avatarPath },
                onReact = { emoji -> state.react(key, emoji) },
                onClose = { reactionsFor = null },
            )
        }
    }

    // Forwarding goes to PEOPLE (one-to-one conversations), like in a
    // one-to-one chat.
    forwarding?.let { target ->
        ForwardScreen(
            myId = myId,
            friends = friends,
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

    forwardingMany?.let { keys ->
        ForwardScreen(
            myId = myId,
            friends = friends,
            previewText = "${keys.size} tin nhắn",
            onClose = { forwardingMany = null },
            onSend = { targets, note ->
                forwardingMany = null
                leaveSelecting()
                scope.launch {
                    var sent = 0
                    var failure: String? = null
                    for (destination in targets) {
                        attempt {
                            val id = destination.conversationId
                                ?: ChatRepository.openConversation(destination.profile.id)
                            // One after the other, so they arrive in the same order.
                            for (key in keys) state.forwardTo(id, key)
                            if (note.isNotEmpty()) ChatRepository.sendMessage(id, note)
                        }
                            .onSuccess { sent++ }
                            .onFailure { failure = it.toUserMessage() }
                    }
                    if (sent > 0) notice = "Đã chuyển tiếp ${keys.size} tin nhắn tới $sent người."
                    failure?.let { state.showError(it) }
                }
            },
        )
    }

    if (confirmDeleteMany) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text("Xóa ${selectedKeys.size} tin nhắn?") },
            text = { Text("Các tin này sẽ biến mất trên máy bạn. Các thành viên khác vẫn thấy chúng.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteMany = false
                        chosenMessages().forEach { state.hide(it.key) }
                        leaveSelecting()
                    },
                ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteMany = false }) { Text("Không") }
            },
        )
    }

    if (infoOpen && group != null) {
        GroupInfoScreen(
            myId = myId,
            group = group,
            members = state.members,
            friends = friends,
            onChanged = {
                scope.launch {
                    state.reloadGroup()
                    state.poll()
                }
            },
            onLeft = {
                infoOpen = false
                onBack()
            },
            onSearch = {
                infoOpen = false
                searchMode = true
            },
            onWallpaper = {
                infoOpen = false
                wallpaperOpen = true
            },
            onOpenImage = { viewerPath = it },
            onCleared = {
                // The history is gone on my side: leave the chat.
                infoOpen = false
                onBack()
            },
            onClose = { infoOpen = false },
        )
    }

    if (wallpaperOpen) {
        WallpaperDialog(
            current = state.wallpaper,
            onChoose = { state.chooseWallpaper(it) },
            onChooseCustom = { state.chooseWallpaperPicture(it) },
            onClose = { wallpaperOpen = false },
        )
    }
}
