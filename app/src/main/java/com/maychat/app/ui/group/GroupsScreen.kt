package com.maychat.app.ui.group

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Group
import com.maychat.app.data.GroupPref
import com.maychat.app.data.ListCache
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.formatTime
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// The "Nhóm" tab: every group I am a member of, so it is easy to see
// whether I am in any group. Groups also stay in the "Trò chuyện" list.
@Composable
fun GroupsScreen(
    myId: String,
    onOpenGroup: (Group) -> Unit,
    onCreateGroup: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val connectionCount by ChatRepository.connectionCount.collectAsState()

    // Remembered from last time, so the list is there at once.
    val cachedGroups = remember(myId) { ListCache.groups(myId) }
    var groups by remember { mutableStateOf(cachedGroups ?: emptyList()) }
    var unread by remember { mutableStateOf(ListCache.groupUnread(myId)) }
    var prefs by remember { mutableStateOf(ListCache.groupPrefs(myId)) }
    var loading by remember { mutableStateOf(cachedGroups == null) }
    var error by remember { mutableStateOf<String?>(null) }

    // The three questions go to the server at the same time.
    suspend fun reload() = coroutineScope {
        launch {
            attempt { ChatRepository.loadGroups() }
                .onSuccess {
                    groups = it
                    error = null
                    ListCache.saveGroups(myId, it)
                }
                .onFailure { error = it.toUserMessage() }
            loading = false
        }
        launch {
            attempt { ChatRepository.loadGroupUnreadCounts() }.onSuccess {
                unread = it
                ListCache.saveGroupUnread(myId, it)
            }
        }
        // If this fails (for example migration 22 was not run) nothing is
        // pinned or muted.
        launch {
            attempt { ChatRepository.loadGroupPrefs() }.onSuccess {
                prefs = it
                ListCache.saveGroupPrefs(myId, it)
            }
        }
        Unit
    }

    fun changePref(groupId: String, pinned: Boolean? = null, muted: Boolean? = null, clear: Boolean = false) {
        scope.launch {
            attempt { ChatRepository.setGroupPref(groupId, pinned, muted, clear) }
                .onFailure { error = it.toUserMessage() }
            reload()
        }
    }

    LaunchedEffect(connectionCount) { reload() }

    // A message in one of my groups: refresh at once.
    LaunchedEffect(Unit) {
        ChatRepository.groupEvents.collectLatest {
            delay(300)
            reload()
        }
    }

    // Safety net, in case the live connection has silently stopped:
    // refresh every 8 seconds while this tab is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(8_000)
                reload()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { reload() }
    }

    // Pinned groups first, then the newest activity first.
    val shown = groups
        .sortedByDescending { ChatRepository.toEpochMillis(it.lastMessageAt ?: it.createdAt) }
        .sortedByDescending { prefs[it.id]?.pinnedAt != null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Nhóm",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onCreateGroup) { Text("＋ Tạo nhóm") }
                }
                if (!loading && groups.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bạn đang ở trong ${groups.size} nhóm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            when {
                loading -> LoadingScreen()

                groups.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Bạn chưa ở trong nhóm nào", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tạo một nhóm với bạn bè của bạn, hoặc chờ một người bạn thêm bạn vào nhóm của họ.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCreateGroup) { Text("Tạo nhóm") }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { group ->
                        val pref = prefs[group.id]
                        GroupListRow(
                            group = group,
                            myId = myId,
                            unread = unread[group.id] ?: 0,
                            pinned = pref?.pinnedAt != null,
                            muted = pref?.muted == true,
                            clearedAt = pref?.clearedAt,
                            onClick = { onOpenGroup(group) },
                            onTogglePin = { changePref(group.id, pinned = pref?.pinnedAt == null) },
                            onToggleMute = { changePref(group.id, muted = pref?.muted != true) },
                            onClear = { changePref(group.id, clear = true) },
                        )
                    }
                }
            }
        }
    }
}

// One group as a rounded card, used by the "Nhóm" tab and by the
// conversation list. Press and hold for: pin, notifications, delete history.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupListRow(
    group: Group,
    myId: String,
    unread: Int,
    pinned: Boolean,
    muted: Boolean,
    clearedAt: String?,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val hasUnread = unread > 0

    // After I deleted the history on my side, the old last message must not
    // show here either.
    val lastIsCleared = clearedAt != null &&
        ChatRepository.toEpochMillis(group.lastMessageAt ?: group.createdAt) <= ChatRepository.toEpochMillis(clearedAt)
    val lastText = group.lastMessageText
    val preview = when {
        lastText == null || lastIsCleared -> "Chưa có tin nhắn"
        group.lastSenderId == myId -> "Bạn: $lastText"
        else -> lastText
    }

    Box {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Bỏ ghim" else "Ghim lên đầu") },
                onClick = {
                    menuOpen = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text(if (muted) "Bật thông báo" else "Tắt thông báo") },
                onClick = {
                    menuOpen = false
                    onToggleMute()
                },
            )
            DropdownMenuItem(
                text = { Text("Xóa lịch sử trò chuyện", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    confirmClear = true
                },
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (hasUnread) MaterialTheme.colorScheme.surface else Color.Transparent,
            shadowElevation = if (hasUnread) 1.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = group.name, online = false, size = 54.dp, avatarPath = group.avatarPath)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_group),
                            contentDescription = "Nhóm",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (hasUnread) FontWeight.SemiBold else null,
                        color = if (hasUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Small marks: pinned, notifications off.
                        if (pinned) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pin),
                                contentDescription = "Đã ghim",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        if (muted) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bell),
                                contentDescription = "Đã tắt thông báo",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            if (lastIsCleared) "" else formatTime(group.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasUnread) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    // Red number: unread messages in this group.
                    if (hasUnread) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (unread > 99) "99+" else unread.toString(),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 22.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Xóa lịch sử trò chuyện?") },
            text = {
                Text(
                    "Toàn bộ tin nhắn của nhóm \"${group.name}\" sẽ biến mất trên máy bạn và không khôi phục được. " +
                        "Bạn vẫn ở trong nhóm, và các thành viên khác vẫn giữ nguyên tin nhắn.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Không") } },
        )
    }
}
