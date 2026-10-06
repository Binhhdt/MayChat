package com.maychat.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.formatTime
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

    val relation = friends.relation(other.id)
    val blockedByMe = relation == Relation.BLOCKED

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
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = compressImage(context, uri)
                if (bytes == null) {
                    state.showError("Không đọc được ảnh này. Hãy chọn ảnh khác.")
                } else {
                    state.sendImage(bytes)
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
            state.sendVoice(result.bytes, result.durationMs)
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
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = other.displayName, online = other.id in online, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(other.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                if (other.id in online) "Đang hoạt động" else "Ngoại tuyến",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                            MessageBubble(message = message, onRetry = { state.retry(message.key) })
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

            if (otherTyping && !blockedByMe) {
                Text(
                    "••• ${other.displayName} đang soạn tin…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (recording) {
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextButton(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !blockedByMe,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("🖼", style = MaterialTheme.typography.titleLarge) }
                    TextButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !blockedByMe,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("🎤", style = MaterialTheme.typography.titleLarge) }
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
                        placeholder = { Text("Nhập tin nhắn") },
                        enabled = !blockedByMe,
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            state.send(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank() && !blockedByMe,
                    ) {
                        Text("Gửi")
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
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

@Composable
private fun MessageBubble(message: UiMessage, onRetry: () -> Unit) {
    val bubbleColor =
        if (message.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (message.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    // Small line under the bubble: time, plus delivery state for my own messages.
    val time = formatTime(message.createdAt)
    val meta = when {
        !message.mine -> time
        message.state == SendState.SENDING -> "Đang gửi…"
        message.state == SendState.FAILED -> "Gửi lỗi. Chạm vào tin nhắn để gửi lại"
        message.state == SendState.READ -> "$time · Đã xem"
        else -> "$time · Đã gửi"
    }

    val path = message.mediaPath
    val retryModifier = Modifier.clickable(enabled = message.state == SendState.FAILED, onClick = onRetry)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        when {
            // A picture that is already on the server: show it without a bubble.
            message.kind == "image" && path != null -> Box(modifier = retryModifier) {
                ChatImage(path)
            }

            // A voice message that is already on the server.
            message.kind == "voice" && path != null && message.state != SendState.FAILED -> Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(16.dp),
            ) {
                VoiceBubbleContent(path = path, durationMs = message.durationMs, textColor = textColor)
            }

            // Text, or a picture / voice message that is still uploading or failed.
            else -> Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 300.dp).then(retryModifier),
            ) {
                Text(
                    message.text,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            meta,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.state == SendState.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
