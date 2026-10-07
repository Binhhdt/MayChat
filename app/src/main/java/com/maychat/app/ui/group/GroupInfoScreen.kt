package com.maychat.app.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Group
import com.maychat.app.data.GroupMessage
import com.maychat.app.data.MediaCache
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.chat.FileBubbleContent
import com.maychat.app.ui.chat.compressImage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import kotlinx.coroutines.launch
import java.util.UUID

// "Thông tin nhóm": shortcuts (search, background, notifications, pin),
// the pictures and files sent in the group, the members, and the actions.
//   * every member can add friends;
//   * the leader and the deputies can rename the group and change its picture;
//   * the leader can remove anyone, a deputy can remove ordinary members;
//   * only the leader appoints deputies and hands the leadership over;
//   * everyone can leave.
@Composable
fun GroupInfoScreen(
    myId: String,
    group: Group,
    members: List<Profile>,
    deputyIds: Set<String>,
    friends: List<Profile>,
    onChanged: () -> Unit,
    onLeft: () -> Unit,
    onSearch: () -> Unit,
    onWallpaper: () -> Unit,
    onOpenImage: (String) -> Unit,
    onCleared: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val iAmLeader = group.ownerId == myId
    // Leader or deputy: may rename the group and change its picture.
    val canManage = iAmLeader || myId in deputyIds

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Profile?>(null) }
    var confirmTransfer by remember { mutableStateOf<Profile?>(null) }
    var confirmDisband by remember { mutableStateOf(false) }

    // My own settings for this group. If reading them fails (for example
    // migration 22 was not run) both are simply shown as "off".
    var muted by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    LaunchedEffect(group.id) {
        attempt { ChatRepository.loadGroupPrefs() }.onSuccess {
            muted = it[group.id]?.muted == true
            pinned = it[group.id]?.pinnedAt != null
        }
    }

    var images by remember { mutableStateOf<List<GroupMessage>>(emptyList()) }
    var files by remember { mutableStateOf<List<GroupMessage>>(emptyList()) }
    var mediaLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(group.id) {
        attempt { ChatRepository.loadGroupSharedMedia(group.id, "image", 12) }.onSuccess { images = it }
        attempt { ChatRepository.loadGroupSharedMedia(group.id, "file", 10) }.onSuccess { files = it }
        mediaLoaded = true
    }

    // Runs one action on the server, then asks the chat to reload the group.
    fun perform(action: suspend () -> Unit, after: () -> Unit = {}) {
        busy = true
        error = null
        scope.launch {
            attempt { action() }
                .onSuccess {
                    // The other members' open phones follow at once.
                    ChatRepository.sendGroupChanged(group.id, myId)
                    onChanged()
                    after()
                }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    // Picture of the group: the photo is stored in MY folder of the avatars
    // storage (the only place I may upload to), then set for the group.
    val pickGroupPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                val bytes = compressImage(context, uri, maxSide = 512)
                if (bytes == null) {
                    error = "Không đọc được ảnh này. Hãy chọn ảnh khác."
                } else {
                    val old = group.avatarPath
                    attempt {
                        val path = "$myId/group-${UUID.randomUUID()}.jpg"
                        ChatRepository.uploadAvatar(path, bytes)
                        ChatRepository.setGroupAvatar(group.id, path)
                        // The replaced picture is removed when it was mine
                        // (otherwise the storage refuses, which is fine).
                        if (old != null && old.startsWith("$myId/group-")) ChatRepository.deleteAvatar(old)
                    }
                        .onSuccess { onChanged() }
                        .onFailure { error = it.toUserMessage() }
                }
                busy = false
            }
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

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "head") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(name = group.name, online = false, size = 80.dp, avatarPath = group.avatarPath)
                            if (canManage) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        pickGroupPicture.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                ) { Text("Đổi ảnh nhóm") }
                            } else {
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                group.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Text(
                                "${members.size}/50 thành viên",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ShortcutTile("Tìm\ntin nhắn", R.drawable.ic_search, onSearch)
                                // Only the leader and the deputies change the background.
                                if (canManage) ShortcutTile("Đổi\nhình nền", R.drawable.ic_image, onWallpaper)
                                ShortcutTile(
                                    if (muted) "Bật\nthông báo" else "Tắt\nthông báo",
                                    R.drawable.ic_bell,
                                    onClick = {
                                        val wanted = !muted
                                        muted = wanted
                                        error = null
                                        scope.launch {
                                            attempt { ChatRepository.setGroupPref(group.id, muted = wanted) }
                                                .onFailure {
                                                    muted = !wanted
                                                    error = it.toUserMessage()
                                                }
                                        }
                                    },
                                )
                                ShortcutTile(
                                    if (pinned) "Bỏ\nghim" else "Ghim\nlên đầu",
                                    R.drawable.ic_pin,
                                    onClick = {
                                        val wanted = !pinned
                                        pinned = wanted
                                        error = null
                                        scope.launch {
                                            attempt { ChatRepository.setGroupPref(group.id, pinned = wanted) }
                                                .onFailure {
                                                    pinned = !wanted
                                                    error = it.toUserMessage()
                                                }
                                        }
                                    },
                                )
                            }
                            if (muted) {
                                Text(
                                    "Đã tắt thông báo của nhóm này",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item(key = "actions") {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton(onClick = { adding = true }, enabled = !busy && members.size < 50) {
                                Text("Thêm thành viên")
                            }
                            // Only the leader and the deputies may rename the group.
                            if (canManage) {
                                TextButton(onClick = { renaming = true }, enabled = !busy) { Text("Đổi tên nhóm") }
                            }
                        }
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        HorizontalDivider()
                    }

                    item(key = "images") {
                        SectionTitle("Ảnh đã gửi")
                        if (images.isEmpty()) {
                            EmptyLine(if (mediaLoaded) "Chưa có ảnh nào." else "Đang tải…")
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(images, key = { it.id }) { message ->
                                    val path = message.mediaPath
                                    if (path != null) Thumbnail(path = path, onClick = { onOpenImage(path) })
                                }
                            }
                        }
                    }

                    item(key = "files") {
                        Spacer(Modifier.height(12.dp))
                        SectionTitle("File đã gửi")
                        if (files.isEmpty()) {
                            EmptyLine(if (mediaLoaded) "Chưa có file nào." else "Đang tải…")
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                files.forEach { message ->
                                    val path = message.mediaPath
                                    if (path != null) {
                                        FileBubbleContent(
                                            path = path,
                                            fileName = message.fileName ?: "file",
                                            fileSize = message.fileSize,
                                            textColor = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        SectionTitle("Thành viên (${members.size})")
                    }

                    items(members.sortedByDescending { it.id == group.ownerId }, key = { "m-${it.id}" }) { person ->
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
                                val isDeputy = person.id in deputyIds
                                if (person.id == group.ownerId) {
                                    Text(
                                        "Trưởng nhóm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else if (isDeputy) {
                                    Text(
                                        "Phó nhóm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            // What I may do with this member:
                            //   leader: appoint / dismiss as deputy, hand over, remove;
                            //   deputy: remove an ordinary member.
                            val personIsDeputy = person.id in deputyIds
                            val isOther = person.id != myId && person.id != group.ownerId
                            if (iAmLeader && isOther) {
                                var rowMenu by remember(person.id) { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { rowMenu = true }, enabled = !busy) { Text("⋮") }
                                    DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (personIsDeputy) "Bãi nhiệm phó nhóm" else "Bổ nhiệm phó nhóm") },
                                            onClick = {
                                                rowMenu = false
                                                perform({
                                                    ChatRepository.setGroupDeputy(group.id, person.id, !personIsDeputy)
                                                })
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Chuyển quyền trưởng nhóm") },
                                            onClick = {
                                                rowMenu = false
                                                confirmTransfer = person
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Xóa khỏi nhóm", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                rowMenu = false
                                                confirmRemove = person
                                            },
                                        )
                                    }
                                }
                            } else if (!iAmLeader && myId in deputyIds && isOther && !personIsDeputy) {
                                TextButton(onClick = { confirmRemove = person }, enabled = !busy) {
                                    Text("Xóa", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    item(key = "danger") {
                        HorizontalDivider()
                        Text(
                            "Xóa lịch sử trò chuyện",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { confirmClear = true }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                        // Only the leader: delete the whole group for everyone.
                        if (iAmLeader) {
                            Text(
                                "Giải tán nhóm",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { confirmDisband = true }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                        Text(
                            "Rời nhóm",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { confirmLeave = true }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
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

        confirmTransfer?.let { person ->
            AlertDialog(
                onDismissRequest = { confirmTransfer = null },
                title = { Text("Chuyển quyền trưởng nhóm?") },
                text = {
                    Text(
                        "${person.displayName} sẽ thành trưởng nhóm, còn bạn trở thành thành viên thường. " +
                            "Sau đó chỉ ${person.displayName} mới chuyển lại được.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmTransfer = null
                            perform({ ChatRepository.transferGroupLeader(group.id, person.id) })
                        },
                    ) { Text("Chuyển") }
                },
                dismissButton = { TextButton(onClick = { confirmTransfer = null }) { Text("Không") } },
            )
        }

        if (confirmDisband) {
            AlertDialog(
                onDismissRequest = { confirmDisband = false },
                title = { Text("Giải tán nhóm \"${group.name}\"?") },
                text = {
                    Text(
                        "Nhóm sẽ biến mất với TẤT CẢ ${members.size} thành viên, cùng toàn bộ tin nhắn của nhóm. " +
                            "Việc này không khôi phục được.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDisband = false
                            perform({ ChatRepository.disbandGroup(group.id) }, after = onLeft)
                        },
                    ) { Text("Giải tán", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDisband = false }) { Text("Không") } },
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Xóa lịch sử trò chuyện?") },
                text = {
                    Text(
                        "Toàn bộ tin nhắn của nhóm này sẽ biến mất trên máy bạn và không khôi phục được. " +
                            "Bạn vẫn ở trong nhóm, và các thành viên khác vẫn giữ nguyên tin nhắn.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            busy = true
                            error = null
                            scope.launch {
                                attempt { ChatRepository.setGroupPref(group.id, clear = true) }
                                    .onSuccess { onCleared() }
                                    .onFailure { error = it.toUserMessage() }
                                busy = false
                            }
                        },
                    ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Không") } },
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

// Round shortcut button with a label under it.
@Composable
private fun ShortcutTile(label: String, icon: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// Small square preview of a sent picture.
@Composable
private fun Thumbnail(path: String, onClick: () -> Unit) {
    val bitmap by produceState(initialValue = MediaCache.cachedBitmap(path), path) {
        if (value == null) value = attempt { MediaCache.bitmap(path) }.getOrNull()
    }
    val picture = bitmap
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (picture != null) {
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = "Ảnh đã gửi",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
