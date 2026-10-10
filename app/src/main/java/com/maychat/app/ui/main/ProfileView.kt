package com.maychat.app.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val birthdayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// "2001-05-20" -> "20/05/2001". "" when it cannot be read.
fun birthdayLabel(birthday: String?): String {
    if (birthday.isNullOrBlank()) return ""
    return runCatching { LocalDate.parse(birthday).format(birthdayFormat) }.getOrDefault("")
}

// A picture from the "avatars" storage (avatar or cover), loaded once.
@Composable
fun rememberProfilePicture(path: String?): Bitmap? {
    val picture by produceState(initialValue = path?.let { MediaCache.cachedAvatar(it) }, path) {
        value = if (path == null) {
            null
        } else {
            MediaCache.cachedAvatar(path) ?: attempt { MediaCache.avatarBitmap(path) }.getOrNull()
        }
    }
    return picture
}

// The cover of a profile page: the person's picture, or a coloured band.
@Composable
fun ProfileCover(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
            ),
        ),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Ảnh bìa",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// The profile page of a person (me or somebody else): cover, picture,
// name, introduction, birthday.
//   onEdit: shown as "Sửa trang cá nhân" on my own page (null = no button).
//   onMessage: shown as "Nhắn tin" on somebody else's page (null = no button).
@Composable
fun ProfileViewDialog(
    userId: String,
    known: Profile? = null,
    onEdit: (() -> Unit)? = null,
    onMessage: ((Profile) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // What is already known is shown at once; the full page is then read.
    var profile by remember(userId) { mutableStateOf(known) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        attempt { ChatRepository.loadProfile(userId) }
            .onSuccess { if (it != null) profile = it else if (profile == null) error = "Không tìm thấy người này." }
            .onFailure { if (profile == null) error = it.toUserMessage() }
    }
    var zoomed by remember { mutableStateOf<Bitmap?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onClick = onDismiss)
                    Text("Trang cá nhân", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                val person = profile
                if (person == null) {
                    Text(
                        error ?: "Đang tải…",
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                    return@Column
                }

                val cover = rememberProfilePicture(person.coverPath)
                val face = rememberProfilePicture(person.avatarPath)
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProfileCover(
                        bitmap = cover,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clickable(enabled = cover != null) { zoomed = cover },
                    )
                    // The picture sits half over the cover.
                    Box(
                        modifier = Modifier
                            .offset(y = (-56).dp)
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .border(4.dp, MaterialTheme.colorScheme.background, CircleShape)
                            .clickable(enabled = face != null) { zoomed = face },
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(name = person.displayName, online = false, size = 104.dp, avatarPath = person.avatarPath)
                    }
                    Column(
                        modifier = Modifier.offset(y = (-44).dp).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            person.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "@${person.username}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        val bio = person.bio?.trim().orEmpty()
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (bio.isNotEmpty()) bio else "Chưa có lời giới thiệu.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (bio.isNotEmpty()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(18.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    "Thông tin",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                val day = birthdayLabel(person.birthday)
                                Text("🎂  Ngày sinh: " + day.ifEmpty { "chưa cập nhật" })
                                Spacer(Modifier.height(4.dp))
                                Text("👤  Tên người dùng: @${person.username}")
                            }
                        }
                        // The farm game: house and farm name (me and my friends only).
                        com.maychat.app.game.FarmProfileCard(
                            userId = person.id,
                            isMe = person.id == ChatRepository.currentUserId(),
                        )

                        if (onEdit != null) {
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = onEdit,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text("Sửa trang cá nhân") }
                        }
                        if (onMessage != null) {
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { onMessage(person) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text("Nhắn tin") }
                        }
                    }
                }
            }
        }
    }

    // A tapped cover or picture, large; tap again to close.
    val big = zoomed
    if (big != null) {
        Dialog(onDismissRequest = { zoomed = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black).clickable { zoomed = null },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = big.asImageBitmap(),
                    contentDescription = "Ảnh phóng to",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
