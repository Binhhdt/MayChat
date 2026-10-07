package com.maychat.app.ui.group

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Group
import com.maychat.app.data.GroupMessage
import com.maychat.app.data.MediaCache
import com.maychat.app.data.NewGroupMessage
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.chat.ChatImage
import com.maychat.app.ui.chat.FileBubbleContent
import com.maychat.app.ui.chat.ImageViewer
import com.maychat.app.ui.chat.VideoBubbleContent
import com.maychat.app.ui.chat.VoiceBubbleContent
import com.maychat.app.ui.chat.VoicePlayer
import com.maychat.app.ui.chat.VoiceRecorder
import com.maychat.app.ui.chat.compressImage
import com.maychat.app.ui.chat.formatDuration
import com.maychat.app.ui.chat.isVideoFile
import com.maychat.app.ui.chat.readPickedFile
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.dayLabel
import com.maychat.app.ui.common.formatTime
import com.maychat.app.ui.common.localDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val MAX_VOICE_MS = 120_000L

// Chat screen of one group. Phase 1: text, pictures, voice messages and
// files; each message shows who sent it. New messages are fetched every
// 3 seconds while the screen is open.
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
    val listState = rememberLazyListState()

    var group by remember(groupId) { mutableStateOf<Group?>(null) }
    var members by remember(groupId) { mutableStateOf<List<Profile>>(emptyList()) }
    // Messages by id; the screen shows them newest first.
    var byId by remember(groupId) { mutableStateOf<Map<String, GroupMessage>>(emptyMap()) }
    var loading by remember(groupId) { mutableStateOf(true) }
    var hasOlder by remember(groupId) { mutableStateOf(false) }
    var error by remember(groupId) { mutableStateOf<String?>(null) }
    var draft by remember(groupId) { mutableStateOf("") }
    // How many of my messages are on their way to the server.
    var sending by remember(groupId) { mutableStateOf(0) }
    var infoOpen by remember(groupId) { mutableStateOf(false) }
    var viewerPath by remember(groupId) { mutableStateOf<String?>(null) }

    val messages = remember(byId) {
        byId.values.sortedByDescending { ChatRepository.toEpochMillis(it.createdAt) }
    }
    val memberById = remember(members) { members.associateBy { it.id } }

    suspend fun reloadGroup() {
        attempt { ChatRepository.loadGroup(groupId) }.onSuccess { loaded ->
            // No longer a member (removed, or the group is gone): leave the screen.
            if (loaded == null) onBack() else group = loaded
        }
        attempt { ChatRepository.loadGroupMembers(groupId) }.onSuccess { members = it }
    }

    suspend fun fetchNewest(limit: Int) {
        attempt { ChatRepository.loadGroupMessages(groupId, limit = limit) }
            .onSuccess { page ->
                val before = byId.size
                byId = byId + page.associateBy { it.id }
                if (loading) hasOlder = page.size == limit
                error = null
                if (byId.size != before) attempt { ChatRepository.markGroupRead(groupId) }
            }
            .onFailure { if (loading) error = it.toUserMessage() }
        loading = false
    }

    LaunchedEffect(groupId) {
        reloadGroup()
        fetchNewest(ChatRepository.PAGE_SIZE)
        attempt { ChatRepository.markGroupRead(groupId) }
    }

    // Fetch new messages every 3 seconds while the screen is showing, and
    // the member list every 15 seconds.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(groupId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var round = 0
            while (true) {
                delay(3_000)
                round++
                if (!loading) fetchNewest(20)
                if (round % 5 == 0) reloadGroup()
            }
        }
    }

    // Stay at the bottom when a new message arrives and I am already there.
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    // Uploads a file into the group's folder, then sends the message for it.
    fun sendMedia(kind: String, bytes: ByteArray, extension: String, label: String, durationMs: Int?, fileName: String?) {
        sending++
        scope.launch {
            attempt {
                val path = "$groupId/${UUID.randomUUID()}.$extension"
                ChatRepository.uploadMedia(path, bytes)
                MediaCache.put(path, bytes)
                ChatRepository.sendGroupMessage(
                    NewGroupMessage(
                        groupId = groupId,
                        content = label,
                        kind = kind,
                        mediaPath = path,
                        durationMs = durationMs,
                        fileName = fileName,
                        fileSize = if (kind == "file") bytes.size else null,
                    ),
                )
            }
                .onSuccess {
                    fetchNewest(20)
                    listState.scrollToItem(0)
                }
                .onFailure { error = it.toUserMessage() }
            sending--
        }
    }

    // ----- Pictures and files --------------------------------------------
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        scope.launch {
            for (uri in uris) {
                val bytes = compressImage(context, uri)
                if (bytes == null) {
                    error = "Có ảnh không đọc được và đã bị bỏ qua."
                } else {
                    sendMedia("image", bytes, "jpg", "📷 Ảnh", null, null)
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
                        error = "Không đọc được file này."
                    } else {
                        val ending = picked.name.substringAfterLast('.', "").lowercase()
                            .filter { it in 'a'..'z' || it in '0'..'9' }.take(8).ifEmpty { "bin" }
                        sendMedia("file", picked.bytes, ending, "📎 ${picked.name}", null, picked.name)
                    }
                } catch (e: IllegalArgumentException) {
                    error = "File quá lớn. Chỉ gửi được file tối đa 5 MB."
                }
            }
        }
    }

    // ----- Voice message --------------------------------------------------
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }

    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start()) {
            recordedMs = 0L
            recording = true
        } else {
            error = "Không dùng được micro lúc này."
        }
    }

    fun finishRecording(sendIt: Boolean) {
        recording = false
        if (!sendIt) {
            recorder.cancel()
            return
        }
        val result = recorder.stop()
        if (result == null) {
            error = "Bản ghi quá ngắn. Hãy ghi ít nhất 1 giây."
        } else {
            sendMedia("voice", result.bytes, "m4a", "🎤 Tin nhắn thoại", result.durationMs, null)
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "Cần quyền micro để ghi tin nhắn thoại."
    }
    LaunchedEffect(recording) {
        while (recording) {
            recordedMs = recorder.elapsedMs
            if (recordedMs >= MAX_VOICE_MS) finishRecording(sendIt = true)
            delay(200)
        }
    }
    DisposableEffect(groupId) {
        onDispose {
            VoicePlayer.stop()
            recorder.cancel()
        }
    }

    // Which messages start a new day, and which start a run of one sender
    // (those show the sender's picture and name). Newest first, so the
    // message before in time is at index + 1.
    val dateLabels = remember(messages) {
        val labels = HashMap<String, String>()
        for (i in messages.indices) {
            val day = localDay(messages[i].createdAt)
            val older = messages.getOrNull(i + 1)
            if (older == null || localDay(older.createdAt) != day) labels[messages[i].id] = dayLabel(day)
        }
        labels
    }
    val runStarts = remember(messages) {
        val keys = HashSet<String>()
        for (i in messages.indices) {
            val current = messages[i]
            val older = messages.getOrNull(i + 1)
            if (older == null || older.senderId != current.senderId || older.kind == "system" ||
                dateLabels.containsKey(current.id)
            ) {
                keys.add(current.id)
            }
        }
        keys
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { infoOpen = true },
                    ) {
                        Avatar(name = group?.name ?: initialName, online = false, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                group?.name ?: initialName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (members.isEmpty()) "Nhóm" else "${members.size} thành viên",
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
                            contentDescription = "Thành viên nhóm",
                            tint = MaterialTheme.colorScheme.primary,
                        )
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
            if (loading) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) { LoadingScreen() }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        GroupBubble(
                            message = message,
                            mine = message.senderId == myId,
                            sender = memberById[message.senderId],
                            startsRun = message.id in runStarts,
                            dateLabel = dateLabels[message.id],
                            onOpenImage = { viewerPath = it },
                        )
                    }
                    if (hasOlder) {
                        item(key = "load-older") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                TextButton(
                                    onClick = {
                                        val oldest = messages.lastOrNull()?.createdAt ?: return@TextButton
                                        scope.launch {
                                            attempt { ChatRepository.loadGroupMessages(groupId, before = oldest) }
                                                .onSuccess { page ->
                                                    byId = byId + page.associateBy { it.id }
                                                    hasOlder = page.size == ChatRepository.PAGE_SIZE
                                                }
                                                .onFailure { error = it.toUserMessage() }
                                        }
                                    },
                                ) { Text("Tải tin nhắn cũ hơn") }
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (sending > 0) {
                Text(
                    "Đang gửi…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            if (recording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "● Đang ghi ${formatDuration(recordedMs)}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { finishRecording(sendIt = false) }) { Text("Hủy") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { finishRecording(sendIt = true) }) { Text("Gửi") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
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
                                runCatching { pickFile.launch(arrayOf("*/*")) }
                                    .onFailure { error = "Không mở được trình chọn file trên điện thoại này." }
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
                                pickImages.launch(
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
                                val text = draft.trim().take(4000)
                                // Cleared at once; put back if sending fails.
                                draft = ""
                                sending++
                                scope.launch {
                                    attempt { ChatRepository.sendGroupMessage(NewGroupMessage(groupId, text)) }
                                        .onSuccess {
                                            fetchNewest(20)
                                            listState.scrollToItem(0)
                                        }
                                        .onFailure {
                                            error = it.toUserMessage()
                                            if (draft.isBlank()) draft = text
                                        }
                                    sending--
                                }
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
            }
        }
    }

    viewerPath?.let { path -> ImageViewer(path = path, onClose = { viewerPath = null }) }

    val current = group
    if (infoOpen && current != null) {
        GroupInfoScreen(
            myId = myId,
            group = current,
            members = members,
            friends = friends,
            onChanged = { scope.launch { reloadGroup() } },
            onLeft = {
                infoOpen = false
                onBack()
            },
            onClose = { infoOpen = false },
        )
    }
}

// One message of a group.
@Composable
private fun GroupBubble(
    message: GroupMessage,
    mine: Boolean,
    sender: Profile?,
    startsRun: Boolean,
    dateLabel: String?,
    onOpenImage: (String) -> Unit,
) {
    val senderName = sender?.displayName ?: "Thành viên cũ"

    Column(modifier = Modifier.fillMaxWidth()) {
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

        // A notice written by the server, for example "đã thêm An vào nhóm".
        if (message.kind == "system") {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "${if (mine) "Bạn" else senderName} ${message.content}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        } else {

        val bubbleColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        val textColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val shape = if (mine) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
        } else {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
        }
        val path = message.mediaPath

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // Other people's messages: picture of the sender at the start of a run.
            if (!mine) {
                if (startsRun) {
                    Avatar(name = senderName, online = false, size = 32.dp, avatarPath = sender?.avatarPath)
                } else {
                    Spacer(Modifier.width(32.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                if (!mine && startsRun) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                when {
                    message.kind == "image" && path != null ->
                        Box(modifier = Modifier.clickable { onOpenImage(path) }) { ChatImage(path) }

                    message.kind == "voice" && path != null -> Surface(color = bubbleColor, shape = shape) {
                        VoiceBubbleContent(path = path, durationMs = message.durationMs, textColor = textColor)
                    }

                    message.kind == "file" && path != null && isVideoFile(message.fileName) ->
                        VideoBubbleContent(path = path)

                    message.kind == "file" && path != null -> Surface(color = bubbleColor, shape = shape) {
                        FileBubbleContent(
                            path = path,
                            fileName = message.fileName ?: "file",
                            fileSize = message.fileSize,
                            textColor = textColor,
                        )
                    }

                    else -> Surface(
                        color = bubbleColor,
                        shape = shape,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Text(
                            message.content,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Text(
                    formatTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        }
    }
}
