package com.maychat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.group.GroupAvatar

// One person a message can be forwarded to. conversationId is null when
// there is no conversation with them yet (it is created when sending).
// groupId is set when the target is a GROUP; profile then only carries the
// group's name and picture for the list.
data class ForwardTarget(val profile: Profile, val conversationId: String?, val groupId: String? = null)

// Full-screen "Chia sẻ" screen: tick one or more people, optionally add a
// message, then send. People I already chat with come first, then friends.
@Composable
fun ForwardScreen(
    myId: String,
    friends: List<Profile>,
    previewText: String,
    onSend: (targets: List<ForwardTarget>, note: String) -> Unit,
    onClose: () -> Unit,
) {
    var people by remember { mutableStateOf<List<ForwardTarget>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(myId) {
        attempt { ChatRepository.loadConversations(myId) }
            .onSuccess { conversations ->
                val fromChats = conversations.map { ForwardTarget(it.other, it.conversation.id) }
                val known = fromChats.map { it.profile.id }.toSet()
                val fromFriends = friends.filter { it.id !in known }.map { ForwardTarget(it, null) }
                // My groups come after the people. If they cannot be read
                // (for example migration 21 was not run) only people are shown.
                val fromGroups = attempt { ChatRepository.loadGroups() }.getOrNull().orEmpty().map { group ->
                    ForwardTarget(
                        profile = Profile(
                            id = "group-${group.id}",
                            username = "",
                            displayName = group.name,
                            avatarPath = group.avatarPath,
                        ),
                        conversationId = null,
                        groupId = group.id,
                    )
                }
                people = fromChats + fromFriends + fromGroups
            }
            .onFailure { error = it.toUserMessage() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // Header: back, title, how many are ticked.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                    Column {
                        Text("Chia sẻ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Đã chọn: ${selectedIds.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Tìm kiếm") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )

                val all = people
                val shown = (all ?: emptyList()).filter {
                    val q = filter.trim()
                    q.isEmpty() ||
                        it.profile.displayName.contains(q, ignoreCase = true) ||
                        it.profile.username.contains(q, ignoreCase = true)
                }

                when {
                    error != null -> Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).padding(16.dp),
                    )
                    all == null -> Text("Đang tải…", modifier = Modifier.weight(1f).padding(16.dp))
                    shown.isEmpty() -> Text(
                        "Không có ai phù hợp.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(shown, key = { it.profile.id }) { target ->
                            val id = target.profile.id
                            val ticked = id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (ticked) selectedIds - id else selectedIds + id
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val targetGroup = target.groupId
                                if (targetGroup != null) {
                                    GroupAvatar(
                                        groupId = targetGroup,
                                        name = target.profile.displayName,
                                        avatarPath = target.profile.avatarPath,
                                        size = 44.dp,
                                    )
                                } else {
                                    Avatar(
                                        name = target.profile.displayName,
                                        online = false,
                                        size = 44.dp,
                                        avatarPath = target.profile.avatarPath,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        target.profile.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (target.groupId != null) {
                                        Text(
                                            "Nhóm",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // Round tick mark, like in the share screen of Zalo.
                                RadioButton(selected = ticked, onClick = null)
                            }
                        }
                    }
                }

                HorizontalDivider()

                // What is being forwarded.
                Text(
                    previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )

                // Optional extra message and the send button.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("Nhập tin nhắn") },
                        maxLines = 3,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            val chosen = (people ?: emptyList()).filter { it.profile.id in selectedIds }
                            onSend(chosen, note.trim())
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_send),
                            contentDescription = "Gửi",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
