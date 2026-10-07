package com.maychat.app.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.GroupNote
import com.maychat.app.data.GroupPoll
import com.maychat.app.data.GroupPollVote
import com.maychat.app.data.GroupReminder
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.push.Reminders
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import com.maychat.app.ui.common.formatTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// =====================================================================
// The board of a group: polls ("Bình chọn"), reminders ("Nhắc hẹn") and
// notes ("Ghi chú"). Every member may read and create; only who made a
// thing, the leader or a deputy may end, change or delete it.
// =====================================================================

enum class BoardTab(val label: String) {
    POLLS("Bình chọn"),
    REMINDERS("Nhắc hẹn"),
    NOTES("Ghi chú"),
}

// Which tab a message of the chat points to ("poll", "note", "remind").
fun boardTabOf(what: String): BoardTab = when (what) {
    "poll" -> BoardTab.POLLS
    "remind" -> BoardTab.REMINDERS
    else -> BoardTab.NOTES
}

private val whenFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val whenShortFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val dayOnlyFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun localTime(timestamp: String?): LocalDateTime? =
    runCatching { OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
        .getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupBoardDialog(
    groupId: String,
    myId: String,
    canManage: Boolean,
    members: List<Profile>,
    startTab: BoardTab,
    // The poll, note or reminder to show first (from a tapped message).
    focusId: String? = null,
    // true: open the "create" form of the start tab at once.
    createAtStart: Boolean = false,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(startTab) }
    var polls by remember { mutableStateOf<List<GroupPoll>?>(null) }
    var votes by remember { mutableStateOf<List<GroupPollVote>>(emptyList()) }
    var notes by remember { mutableStateOf<List<GroupNote>?>(null) }
    var reminders by remember { mutableStateOf<List<GroupReminder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var creating by remember { mutableStateOf(createAtStart) }
    var editingNote by remember { mutableStateOf<GroupNote?>(null) }
    var votersOf by remember { mutableStateOf<Pair<GroupPoll, Int>?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }

    val names = remember(members) { members.associate { it.id to it.displayName } }
    fun nameOf(id: String) = if (id == myId) "Bạn" else names[id] ?: "Thành viên cũ"

    suspend fun reload() = coroutineScope {
        val p = async { attempt { ChatRepository.loadGroupPolls(groupId) } }
        val n = async { attempt { ChatRepository.loadGroupNotes(groupId) } }
        val r = async { attempt { ChatRepository.loadGroupReminders(groupId) } }
        val loadedPolls = p.await()
        loadedPolls.onSuccess { list ->
            attempt { ChatRepository.loadGroupPollVotes(list.map { it.id }) }.onSuccess { votes = it }
            polls = list
        }
        n.await().onSuccess { notes = it }
        r.await().onSuccess { reminders = it }
        val failure = loadedPolls.exceptionOrNull()
        if (failure != null && polls == null) error = failure.toUserMessage()
    }

    // Read now, then again every 5 seconds (other members vote meanwhile).
    LaunchedEffect(groupId) {
        while (true) {
            reload()
            delay(5_000)
        }
    }

    // One change on the server, then read everything again.
    fun perform(action: suspend () -> Unit, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            attempt { action() }
                .onSuccess {
                    after()
                    reload()
                }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onClick = onDismiss)
                    Text("Bảng tin nhóm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                // The three tabs.
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    BoardTab.entries.forEach { one ->
                        val selected = tab == one
                        Text(
                            one.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { tab = one }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        BoardTab.POLLS -> BoardList(
                            rows = polls,
                            emptyText = "Chưa có bình chọn nào.",
                            keyOf = { it.id },
                            focusId = focusId,
                        ) { poll, focused ->
                            PollCard(
                                poll = poll,
                                votes = votes.filter { it.pollId == poll.id },
                                myId = myId,
                                focused = focused,
                                busy = busy,
                                creatorName = nameOf(poll.creatorId),
                                canChange = canManage || poll.creatorId == myId,
                                onVote = { picks -> perform({ ChatRepository.voteGroupPoll(poll.id, picks) }) },
                                onVoters = { index -> votersOf = poll to index },
                                onClose = { perform({ ChatRepository.closeGroupPoll(poll.id) }) },
                                onDelete = {
                                    confirmDelete = "Xóa bình chọn \"${poll.question}\"?" to
                                        suspend { ChatRepository.deleteGroupPoll(poll.id) }
                                },
                            )
                        }

                        BoardTab.REMINDERS -> {
                            val now = System.currentTimeMillis()
                            // Coming ones first (soonest on top), then the past ones.
                            val sorted = reminders?.let { list ->
                                val (coming, past) = list.partition {
                                    (runCatching { OffsetDateTime.parse(it.remindAt).toInstant().toEpochMilli() }
                                        .getOrNull() ?: 0L) > now
                                }
                                coming + past.reversed()
                            }
                            BoardList(
                                rows = sorted,
                                emptyText = "Chưa có nhắc hẹn nào.",
                                keyOf = { it.id },
                                focusId = focusId,
                            ) { reminder, focused ->
                                val at = localTime(reminder.remindAt)
                                val past = at == null || !at.isAfter(LocalDateTime.now())
                                BoardCard(focused = focused, dimmed = past) {
                                    Text(
                                        "⏰  " + (at?.format(whenFormat) ?: ""),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(reminder.title, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            (if (past) "Đã qua · " else "") + "Tạo bởi ${nameOf(reminder.creatorId)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (canManage || reminder.creatorId == myId) {
                                            TextButton(
                                                enabled = !busy,
                                                onClick = {
                                                    confirmDelete = "Xóa nhắc hẹn \"${reminder.title}\"?" to suspend {
                                                        ChatRepository.deleteGroupReminder(reminder.id)
                                                        Reminders.sync(force = true)
                                                    }
                                                },
                                            ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                                        }
                                    }
                                }
                            }
                        }

                        BoardTab.NOTES -> BoardList(
                            rows = notes,
                            emptyText = "Chưa có ghi chú nào.",
                            keyOf = { it.id },
                            focusId = focusId,
                        ) { note, focused ->
                            BoardCard(focused = focused) {
                                Text(note.content, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val edited = note.updatedAt != null && note.updatedAt != note.createdAt
                                    Text(
                                        "${nameOf(note.authorId)} · " +
                                            (if (edited) "sửa " else "") + formatTime(note.updatedAt ?: note.createdAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (canManage || note.authorId == myId) {
                                        TextButton(enabled = !busy, onClick = { editingNote = note }) { Text("Sửa") }
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                confirmDelete = "Xóa ghi chú này?" to
                                                    suspend { ChatRepository.deleteGroupNote(note.id) }
                                            },
                                        ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { creating = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(50.dp),
                ) {
                    Text(
                        when (tab) {
                            BoardTab.POLLS -> "＋ Tạo bình chọn"
                            BoardTab.REMINDERS -> "＋ Tạo nhắc hẹn"
                            BoardTab.NOTES -> "＋ Tạo ghi chú"
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        when (tab) {
            BoardTab.POLLS -> CreatePollDialog(
                busy = busy,
                onDismiss = { creating = false },
                onCreate = { question, options, multiple ->
                    perform({ ChatRepository.createGroupPoll(groupId, question, options, multiple) }) {
                        creating = false
                    }
                },
            )
            BoardTab.REMINDERS -> CreateReminderDialog(
                busy = busy,
                onDismiss = { creating = false },
                onCreate = { title, at ->
                    perform({
                        val moment = at.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                        ChatRepository.createGroupReminder(groupId, title, moment, at.format(whenShortFormat))
                        Reminders.sync(force = true)
                    }) { creating = false }
                },
            )
            BoardTab.NOTES -> NoteDialog(
                title = "Ghi chú mới",
                initial = "",
                busy = busy,
                onDismiss = { creating = false },
                onSave = { text -> perform({ ChatRepository.createGroupNote(groupId, text) }) { creating = false } },
            )
        }
    }

    editingNote?.let { note ->
        NoteDialog(
            title = "Sửa ghi chú",
            initial = note.content,
            busy = busy,
            onDismiss = { editingNote = null },
            onSave = { text -> perform({ ChatRepository.updateGroupNote(note.id, text) }) { editingNote = null } },
        )
    }

    confirmDelete?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(question) },
            text = { Text("Việc này không hoàn tác được.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        perform(action)
                    },
                ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Không") } },
        )
    }

    // Who ticked one option of a poll.
    votersOf?.let { (poll, index) ->
        val ids = votes.filter { it.pollId == poll.id && it.optionIndex == index }.map { it.userId }
        val byId = remember(members) { members.associateBy { it.id } }
        AlertDialog(
            onDismissRequest = { votersOf = null },
            title = { Text(poll.options.getOrNull(index) ?: "") },
            text = {
                if (ids.isEmpty()) {
                    Text("Chưa ai chọn.")
                } else {
                    Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        ids.forEach { id ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(
                                    name = nameOf(id),
                                    online = false,
                                    size = 36.dp,
                                    avatarPath = byId[id]?.avatarPath,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(nameOf(id), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { votersOf = null }) { Text("Đóng") } },
        )
    }
}

// A list of one tab: "loading", "nothing yet", or the cards. The card of
// focusId is scrolled to and drawn with a coloured edge.
@Composable
private fun <T> BoardList(
    rows: List<T>?,
    emptyText: String,
    keyOf: (T) -> String,
    focusId: String?,
    card: @Composable (T, Boolean) -> Unit,
) {
    if (rows == null || rows.isEmpty()) {
        Text(
            if (rows == null) "Đang tải…" else emptyText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    val listState = rememberLazyListState()
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(focusId, rows.size) {
        if (focusId != null && !jumped) {
            val index = rows.indexOfFirst { keyOf(it) == focusId }
            if (index >= 0) {
                listState.scrollToItem(index)
                jumped = true
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(rows, key = { keyOf(it) }) { one -> card(one, keyOf(one) == focusId) }
    }
}

@Composable
private fun BoardCard(focused: Boolean, dimmed: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (dimmed) 0.45f else 1f),
        shape = RoundedCornerShape(16.dp),
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun PollCard(
    poll: GroupPoll,
    votes: List<GroupPollVote>,
    myId: String,
    focused: Boolean,
    busy: Boolean,
    creatorName: String,
    canChange: Boolean,
    onVote: (List<Int>) -> Unit,
    onVoters: (Int) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    val closed = poll.closedAt != null
    val mine = votes.filter { it.userId == myId }.map { it.optionIndex }.toSet()
    val voters = votes.map { it.userId }.distinct().size
    BoardCard(focused = focused) {
        Text("📊  ${poll.question}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                closed -> "Đã kết thúc"
                poll.multiple -> "Được chọn nhiều lựa chọn"
                else -> "Chỉ chọn một lựa chọn"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (closed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        poll.options.forEachIndexed { index, option ->
            val count = votes.count { it.optionIndex == index }
            val ticked = index in mine
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = if (ticked) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !closed && !busy) {
                        onVote(
                            when {
                                poll.multiple && ticked -> (mine - index).toList()
                                poll.multiple -> (mine + index).toList()
                                ticked -> emptyList()      // tap my choice again: take it back
                                else -> listOf(index)
                            },
                        )
                    },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The tick mark.
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(if (poll.multiple) RoundedCornerShape(5.dp) else CircleShape)
                                .background(
                                    if (ticked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ticked) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(option, modifier = Modifier.weight(1f))
                        Text(
                            count.toString(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onVoters(index) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (voters == 0) 0f else count.toFloat() / voters },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "$voters người đã bình chọn · Tạo bởi $creatorName · ${formatTime(poll.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Bấm vào con số để xem ai đã chọn.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canChange) {
            Row {
                if (!closed) TextButton(enabled = !busy, onClick = onClose) { Text("Kết thúc bình chọn") }
                TextButton(enabled = !busy, onClick = onDelete) {
                    Text("Xóa", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CreatePollDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>, Boolean) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var multiple by remember { mutableStateOf(false) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bình chọn mới") },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).imePadding()) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { if (it.length <= 200) question = it },
                    label = { Text("Câu hỏi") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { typed ->
                                if (typed.length <= 100) {
                                    options = options.toMutableList().also { it[index] = typed }
                                }
                            },
                            label = { Text("Lựa chọn ${index + 1}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (options.size > 2) {
                            TextButton(
                                onClick = { options = options.toMutableList().also { it.removeAt(index) } },
                            ) { Text("✕") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (options.size < 10) {
                    TextButton(onClick = { options = options + "" }) { Text("＋ Thêm lựa chọn") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { multiple = !multiple },
                ) {
                    Checkbox(checked = multiple, onCheckedChange = { multiple = it })
                    Text("Cho chọn nhiều lựa chọn")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && question.isNotBlank() && filled.size >= 2,
                onClick = { onCreate(question.trim(), filled, multiple) },
            ) { Text(if (busy) "Đang tạo…" else "Tạo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@Composable
private fun NoteDialog(
    title: String,
    initial: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) text = it },
                label = { Text("Nội dung") },
                supportingText = { Text("${text.length}/2000") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth().imePadding(),
            )
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = { onSave(text.trim()) }) {
                Text(if (busy) "Đang lưu…" else "Lưu")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateReminderDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, LocalDateTime) -> Unit,
) {
    // Starts at the next full hour.
    val start = remember { LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0) }
    var title by remember { mutableStateOf("") }
    var day by remember { mutableStateOf<LocalDate>(start.toLocalDate()) }
    var time by remember { mutableStateOf<LocalTime>(start.toLocalTime()) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val chosen = LocalDateTime.of(day, time)
    val inFuture = chosen.isAfter(LocalDateTime.now())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nhắc hẹn mới") },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 200) title = it },
                    label = { Text("Nội dung nhắc") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ngày:", modifier = Modifier.width(52.dp))
                    TextButton(onClick = { pickingDay = true }) { Text(day.format(dayOnlyFormat)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Giờ:", modifier = Modifier.width(52.dp))
                    TextButton(onClick = { pickingTime = true }) {
                        Text("%02d:%02d".format(time.hour, time.minute))
                    }
                }
                if (!inFuture) {
                    Text(
                        "Thời gian phải ở tương lai.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Đến giờ, điện thoại của mọi thành viên sẽ báo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank() && inFuture,
                onClick = { onCreate(title.trim(), chosen) },
            ) { Text(if (busy) "Đang tạo…" else "Tạo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )

    if (pickingDay) {
        val dayState = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dayState.selectedDateMillis?.let {
                            day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        pickingDay = false
                    },
                ) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Hủy") } },
        ) {
            DatePicker(state = dayState)
        }
    }

    if (pickingTime) {
        val timeState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Chọn giờ") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        time = LocalTime.of(timeState.hour, timeState.minute)
                        pickingTime = false
                    },
                ) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Hủy") } },
        )
    }
}
