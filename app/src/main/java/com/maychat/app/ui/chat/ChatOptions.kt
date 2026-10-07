package com.maychat.app.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
import com.maychat.app.data.Message
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.picturePaths
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.main.ProfileViewDialog
import com.maychat.app.ui.common.BackButton
import com.maychat.app.ui.main.FriendsState
import com.maychat.app.ui.main.Relation
import kotlinx.coroutines.launch

// "Tùy chọn": everything about one conversation in one place. The person's
// picture and name, shortcuts to search and to the chat background, and the
// pictures and files that were sent in this conversation.
@Composable
fun ChatOptionsScreen(
    conversationId: String,
    other: Profile,
    statusText: String,
    friends: FriendsState,
    onCall: () -> Unit,
    onBlock: () -> Unit,
    onDeleted: () -> Unit,
    onSearch: () -> Unit,
    onWallpaper: () -> Unit,
    onOpenImage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val relation = friends.relation(other.id)
    val blockedByMe = relation == Relation.BLOCKED

    // Whether I switched this conversation's notifications off.
    var muted by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    if (profileOpen) {
        ProfileViewDialog(userId = other.id, known = other, onDismiss = { profileOpen = false })
    }
    LaunchedEffect(conversationId) {
        attempt { ChatRepository.loadConversationPrefs() }
            .onSuccess { muted = it[conversationId]?.muted == true }
    }

    var images by remember { mutableStateOf<List<Message>>(emptyList()) }
    var files by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        attempt { ChatRepository.loadSharedMedia(conversationId, "image", 12) }.onSuccess { images = it }
        attempt { ChatRepository.loadSharedMedia(conversationId, "file", 10) }.onSuccess { files = it }
        loaded = true
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onClick = onClose)
                    Text("Tùy chọn", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(12.dp))
                    // Tapping the picture opens the person's profile page.
                    Box(modifier = Modifier.clip(CircleShape).clickable { profileOpen = true }) {
                        Avatar(
                            name = other.displayName,
                            online = false,
                            size = 96.dp,
                            avatarPath = other.avatarPath,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(other.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "@${other.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { profileOpen = true }) { Text("Xem trang cá nhân") }
                    if (statusText.isNotEmpty()) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!blockedByMe) ShortcutTile("Gọi\nthoại", R.drawable.ic_call, onCall)
                        ShortcutTile("Tìm\ntin nhắn", R.drawable.ic_search, onSearch)
                        ShortcutTile("Đổi\nhình nền", R.drawable.ic_image, onWallpaper)
                        ShortcutTile(
                            if (muted) "Bật\nthông báo" else "Tắt\nthông báo",
                            R.drawable.ic_bell,
                            onClick = {
                                val wanted = !muted
                                muted = wanted
                                actionError = null
                                scope.launch {
                                    attempt { ChatRepository.setConversationPref(conversationId, muted = wanted) }
                                        .onFailure {
                                            muted = !wanted
                                            actionError = it.toUserMessage()
                                        }
                                }
                            },
                        )
                    }
                    if (muted) {
                        Text(
                            "Đã tắt thông báo của cuộc trò chuyện này",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    actionError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("Ảnh đã gửi")
                    if (images.isEmpty()) {
                        EmptyLine(if (loaded) "Chưa có ảnh nào." else "Đang tải…")
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Every picture, also those sent together as an album.
                            val paths = images.flatMap { picturePaths(it.kind, it.mediaPath, it.extra) }.distinct()
                            items(paths, key = { it }) { path ->
                                Thumbnail(path = path, onClick = { onOpenImage(path) })
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("File đã gửi")
                    if (files.isEmpty()) {
                        EmptyLine(if (loaded) "Chưa có file nào." else "Đang tải…")
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
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()

                    // Friendship, blocking, deleting.
                    when (relation) {
                        Relation.NONE -> ActionLine("Gửi lời mời kết bạn") { friends.sendRequest(other.id) }
                        Relation.REQUEST_SENT -> ActionLine("Hủy lời mời kết bạn") { friends.remove(other.id) }
                        Relation.REQUEST_RECEIVED -> {
                            ActionLine("Chấp nhận kết bạn") { friends.accept(other.id) }
                            ActionLine("Từ chối lời mời kết bạn") { friends.reject(other.id) }
                        }
                        Relation.FRIEND -> ActionLine("Hủy kết bạn") { friends.remove(other.id) }
                        Relation.BLOCKED -> {}
                    }
                    if (blockedByMe) {
                        ActionLine("Bỏ chặn") { friends.unblock(other.id) }
                    } else {
                        ActionLine("Chặn người này", danger = true, onClick = onBlock)
                    }
                    ActionLine("Xóa cuộc trò chuyện", danger = true) { confirmDelete = true }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Xóa cuộc trò chuyện?") },
                text = {
                    Text(
                        "Toàn bộ tin nhắn với ${other.displayName} sẽ biến mất trên máy bạn và không khôi phục được. " +
                            "${other.displayName} vẫn giữ nguyên tin nhắn ở phía họ.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            scope.launch {
                                attempt { ChatRepository.setConversationPref(conversationId, clear = true) }
                                    .onSuccess { onDeleted() }
                                    .onFailure { actionError = it.toUserMessage() }
                            }
                        },
                    ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Không") }
                },
            )
        }
    }
}

// One tappable line of the options screen.
@Composable
private fun ActionLine(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
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
