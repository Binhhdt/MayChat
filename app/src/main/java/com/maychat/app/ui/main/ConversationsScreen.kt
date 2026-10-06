package com.maychat.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.maychat.app.data.ConversationItem
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Home screen after login: the list of my conversations, newest first.
@Composable
fun ConversationsScreen(
    myId: String,
    onOpenSearch: () -> Unit,
    onOpenChat: (conversationId: String, other: Profile) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val online by ChatRepository.onlineUsers.collectAsState()
    val connectionCount by ChatRepository.connectionCount.collectAsState()

    var conversations by remember { mutableStateOf<List<ConversationItem>>(emptyList()) }
    var me by remember { mutableStateOf<Profile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    // Unread messages per conversation id (shown as a red number in the list).
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    suspend fun reload() {
        attempt { ChatRepository.loadConversations(myId) }
            .onSuccess {
                conversations = it
                error = null
            }
            .onFailure { error = it.toUserMessage() }
        // If this fails (for example migration 07 was not run), the list
        // simply shows no numbers, exactly as before.
        attempt { ChatRepository.loadUnreadCounts() }.onSuccess { unreadCounts = it }
        loading = false
    }

    // Load at start, and again whenever the live connection comes (back) up.
    LaunchedEffect(connectionCount) { reload() }

    LaunchedEffect(myId) {
        attempt { ChatRepository.loadProfile(myId) }.onSuccess { me = it }
    }

    // A new message anywhere: refresh the list (waits a moment so that a
    // burst of messages causes only one reload).
    LaunchedEffect(Unit) {
        ChatRepository.messageEvents.collectLatest {
            delay(300)
            reload()
        }
    }

    // Safety net: refresh the list every 12 seconds while it is on screen,
    // in case the live connection has silently stopped.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(12_000)
                reload()
            }
        }
    }

    // Coming back to the app from the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { reload() }
    }

    var accountMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Header: big title, account button, and the "find a friend" bar.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Trò chuyện",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    // Round button with my initial: shows who I am, and sign out.
                    Box {
                        Surface(
                            onClick = { accountMenuOpen = true },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    (me?.displayName ?: "").trim().take(1).uppercase().ifEmpty { "?" },
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            me?.let { profile ->
                                DropdownMenuItem(
                                    text = { Text("Bạn là @${profile.username}") },
                                    onClick = { accountMenuOpen = false },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Đăng xuất") },
                                onClick = {
                                    accountMenuOpen = false
                                    confirmSignOut = true
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Looks like a search box; tapping it opens the Find Friends screen.
                Surface(
                    onClick = onOpenSearch,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Tìm bạn theo tên người dùng",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
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

                conversations.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Chưa có cuộc trò chuyện nào", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tìm một người bằng tên người dùng của họ để bắt đầu nhắn tin.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenSearch) { Text("Tìm bạn") }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.conversation.id }) { item ->
                        ConversationRow(
                            item = item,
                            myId = myId,
                            online = item.other.id in online,
                            unread = unreadCounts[item.conversation.id] ?: 0,
                            onClick = { onOpenChat(item.conversation.id, item.other) },
                        )
                    }
                }
            }
        }
    }

    // Ask before signing out, so one wrong tap does not log the user out.
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Đăng xuất?") },
            text = { Text("Bạn có chắc muốn đăng xuất khỏi tài khoản này không? Tin nhắn vẫn được giữ, bạn chỉ cần đăng nhập lại để xem.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        scope.launch { attempt { ChatRepository.signOut() } }
                    },
                ) { Text("Đăng xuất") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Không") }
            },
        )
    }
}

// One conversation as a rounded card. A conversation with unread messages
// stands out: white card, bold text and a red number.
@Composable
private fun ConversationRow(
    item: ConversationItem,
    myId: String,
    online: Boolean,
    unread: Int,
    onClick: () -> Unit,
) {
    val c = item.conversation
    val preview = when {
        c.lastMessageText == null -> "Chưa có tin nhắn"
        c.lastSenderId == myId -> "Bạn: ${c.lastMessageText}"
        else -> c.lastMessageText ?: ""
    }
    val hasUnread = unread > 0

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (hasUnread) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (hasUnread) 1.dp else 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name = item.other.displayName, online = online, size = 54.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.other.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                Text(
                    formatTime(c.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (hasUnread) FontWeight.SemiBold else null,
                    color = if (hasUnread) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // Red number: unread messages in this conversation.
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
