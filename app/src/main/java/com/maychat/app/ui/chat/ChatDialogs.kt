package com.maychat.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.ConversationItem
import com.maychat.app.data.Message
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.formatTime
import kotlinx.coroutines.delay

// "Forward to...": pick one of my conversations to send a copy of a message to.
@Composable
fun ForwardDialog(
    myId: String,
    onPick: (ConversationItem) -> Unit,
    onClose: () -> Unit,
) {
    var conversations by remember { mutableStateOf<List<ConversationItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(myId) {
        attempt { ChatRepository.loadConversations(myId) }
            .onSuccess { conversations = it }
            .onFailure { error = it.toUserMessage() }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Chuyển tiếp tới") },
        text = {
            val list = conversations
            when {
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                list == null -> Text("Đang tải…")
                list.isEmpty() -> Text("Bạn chưa có cuộc trò chuyện nào.")
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(list, key = { it.conversation.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(item) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = item.other.displayName,
                                online = false,
                                size = 40.dp,
                                avatarPath = item.other.avatarPath,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.other.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Đóng") } },
    )
}

// "Search in this conversation": looks for text messages containing the words.
// The search runs on the server, so it also finds messages that are not
// loaded on screen. onPick gets the id of the chosen message.
@Composable
fun SearchMessagesDialog(
    conversationId: String,
    myId: String,
    otherName: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Search a short moment after typing stops.
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(400)
        attempt { ChatRepository.searchMessages(conversationId, query) }
            .onSuccess {
                results = it
                searched = true
                error = null
            }
            .onFailure { error = it.toUserMessage() }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Tìm trong cuộc trò chuyện") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Nhập ít nhất 2 ký tự") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (searched && results.isEmpty() && error == null) {
                    Text("Không tìm thấy tin nhắn nào.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { message ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(message.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Row {
                                Text(
                                    if (message.senderId == myId) "Bạn" else otherName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatTime(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Đóng") } },
    )
}
