package com.maychat.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    myId: String,
    onOpenSearch: () -> Unit,
    onOpenChat: (conversationId: String, other: Profile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val online by ChatRepository.onlineUsers.collectAsState()
    val connectionCount by ChatRepository.connectionCount.collectAsState()

    var conversations by remember { mutableStateOf<List<ConversationItem>>(emptyList()) }
    var me by remember { mutableStateOf<Profile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        attempt { ChatRepository.loadConversations(myId) }
            .onSuccess {
                conversations = it
                error = null
            }
            .onFailure { error = it.toUserMessage() }
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

    // Coming back to the app from the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { reload() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MayChat", fontWeight = FontWeight.SemiBold)
                        me?.let {
                            Text(
                                "Bạn là @${it.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onOpenSearch) { Text("Tìm bạn") }
                    TextButton(onClick = { scope.launch { attempt { ChatRepository.signOut() } } }) {
                        Text("Đăng xuất")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.conversation.id }) { item ->
                        ConversationRow(
                            item = item,
                            myId = myId,
                            online = item.other.id in online,
                            onClick = { onOpenChat(item.conversation.id, item.other) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    myId: String,
    online: Boolean,
    onClick: () -> Unit,
) {
    val c = item.conversation
    val preview = when {
        c.lastMessageText == null -> "Chưa có tin nhắn"
        c.lastSenderId == myId -> "Bạn: ${c.lastMessageText}"
        else -> c.lastMessageText ?: ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = item.other.displayName, online = online)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.other.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatTime(c.lastMessageAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
