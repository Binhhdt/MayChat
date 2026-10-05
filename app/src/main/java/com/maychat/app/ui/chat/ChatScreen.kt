package com.maychat.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.formatTime
import kotlinx.coroutines.launch

// One-to-one chat. The newest message is at the bottom.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    myId: String,
    conversationId: String,
    other: Profile,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(conversationId) { ChatState(conversationId, myId, scope) }
    val online by ChatRepository.onlineUsers.collectAsState()
    val connectionCount by ChatRepository.connectionCount.collectAsState()
    val listState = rememberLazyListState()
    var draft by remember(conversationId) { mutableStateOf("") }

    // Load at start, and again whenever the live connection comes (back) up.
    LaunchedEffect(conversationId, connectionCount) { state.refresh() }

    // Live messages and read receipts.
    LaunchedEffect(conversationId) {
        ChatRepository.messageEvents.collect { state.onEvent(it) }
    }

    // Only mark messages as read while the chat is really on screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.visible = true
        scope.launch { state.refresh() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        state.visible = false
    }

    // When a new message arrives and the user is near the bottom, stay at the bottom.
    val newestKey = state.messages.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null && listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
        }
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
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Nhập tin nhắn") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        state.send(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("Gửi")
                }
            }
        }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable(enabled = message.state == SendState.FAILED, onClick = onRetry),
        ) {
            Text(
                message.text,
                color = textColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
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
