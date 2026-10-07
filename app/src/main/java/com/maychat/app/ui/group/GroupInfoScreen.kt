package com.maychat.app.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Group
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import kotlinx.coroutines.launch

// "Thông tin nhóm": the members, and the actions on the group.
//   * every member can add friends and rename the group;
//   * only the group leader can remove a member;
//   * everyone can leave.
@Composable
fun GroupInfoScreen(
    myId: String,
    group: Group,
    members: List<Profile>,
    friends: List<Profile>,
    onChanged: () -> Unit,
    onLeft: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val iAmLeader = group.ownerId == myId

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Profile?>(null) }

    // Runs one action on the server, then asks the chat to reload the group.
    fun perform(action: suspend () -> Unit, after: () -> Unit = {}) {
        busy = true
        error = null
        scope.launch {
            attempt { action() }
                .onSuccess {
                    onChanged()
                    after()
                }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onClick = onClose)
                    Text("Thông tin nhóm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(name = group.name, online = false, size = 80.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(group.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${members.size}/50 thành viên",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(onClick = { adding = true }, enabled = !busy && members.size < 50) {
                        Text("Thêm thành viên")
                    }
                    TextButton(onClick = { renaming = true }, enabled = !busy) { Text("Đổi tên nhóm") }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HorizontalDivider()

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(members.sortedByDescending { it.id == group.ownerId }, key = { it.id }) { person ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = person.displayName,
                                online = false,
                                size = 44.dp,
                                avatarPath = person.avatarPath,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (person.id == myId) "${person.displayName} (bạn)" else person.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (person.id == group.ownerId) {
                                    Text(
                                        "Trưởng nhóm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            // Only the leader sees "Xóa", and not next to themselves.
                            if (iAmLeader && person.id != myId) {
                                TextButton(onClick = { confirmRemove = person }, enabled = !busy) {
                                    Text("Xóa", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text(
                    "Rời nhóm",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { confirmLeave = true }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }

        // ----- Add members: tick friends who are not in the group yet -----
        if (adding) {
            val memberIds = remember(members) { members.map { it.id }.toSet() }
            val candidates = friends.filter { it.id !in memberIds }
            var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
            AlertDialog(
                onDismissRequest = { adding = false },
                title = { Text("Thêm thành viên") },
                text = {
                    if (candidates.isEmpty()) {
                        Text("Tất cả bạn bè của bạn đã ở trong nhóm.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(candidates, key = { it.id }) { person ->
                                val ticked = person.id in picked
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { picked = if (ticked) picked - person.id else picked + person.id }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(
                                        name = person.displayName,
                                        online = false,
                                        size = 40.dp,
                                        avatarPath = person.avatarPath,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        person.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    RadioButton(selected = ticked, onClick = null)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = picked.isNotEmpty(),
                        onClick = {
                            val ids = picked.toList()
                            adding = false
                            perform({ ChatRepository.addGroupMembers(group.id, ids) })
                        },
                    ) { Text("Thêm (${picked.size})") }
                },
                dismissButton = { TextButton(onClick = { adding = false }) { Text("Hủy") } },
            )
        }

        if (renaming) {
            var newName by remember { mutableStateOf(group.name) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Đổi tên nhóm") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(60) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val clean = newName.trim()
                            renaming = false
                            perform({ ChatRepository.renameGroup(group.id, clean) })
                        },
                    ) { Text("Lưu") }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Hủy") } },
            )
        }

        confirmRemove?.let { person ->
            AlertDialog(
                onDismissRequest = { confirmRemove = null },
                title = { Text("Xóa ${person.displayName} khỏi nhóm?") },
                text = { Text("Người này sẽ không còn xem và gửi được tin trong nhóm.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmRemove = null
                            perform({ ChatRepository.removeGroupMember(group.id, person.id) })
                        },
                    ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Không") } },
            )
        }

        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text("Rời nhóm?") },
                text = {
                    Text(
                        if (iAmLeader) {
                            "Bạn là trưởng nhóm. Khi bạn rời đi, thành viên vào nhóm sớm nhất sẽ thành trưởng nhóm."
                        } else {
                            "Bạn sẽ không còn xem và gửi được tin trong nhóm này."
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmLeave = false
                            perform({ ChatRepository.leaveGroup(group.id) }, after = onLeft)
                        },
                    ) { Text("Rời nhóm", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Không") } },
            )
        }
    }
}
