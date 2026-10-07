package com.maychat.app.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.chat.compressImage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import com.maychat.app.ui.common.LoadingScreen
import kotlinx.coroutines.launch
import java.util.UUID

// "My profile": change the avatar picture, the display name, and the
// profile page (cover picture, introduction, birthday).
// "My profile" started as: change the avatar picture and the display name.
// The username cannot be changed (other people find me by it).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(myId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var me by remember { mutableStateOf<Profile?>(null) }
    var displayName by remember { mutableStateOf("") }
    var newAvatarBytes by remember { mutableStateOf<ByteArray?>(null) }   // picked, not saved yet
    var newAvatarPreview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Profile page: cover, introduction, birthday ("YYYY-MM-DD" or "").
    var bio by remember { mutableStateOf("") }
    var birthday by remember { mutableStateOf("") }
    var newCoverBytes by remember { mutableStateOf<ByteArray?>(null) }   // picked, not saved yet
    var newCoverPreview by remember { mutableStateOf<Bitmap?>(null) }
    var removeCover by remember { mutableStateOf(false) }
    var pickingDay by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = compressImage(context, uri, maxSide = 1280)
                if (bytes == null) {
                    error = "Không đọc được ảnh này. Hãy chọn ảnh khác."
                } else {
                    newCoverBytes = bytes
                    newCoverPreview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    removeCover = false
                    error = null
                }
            }
        }
    }

    LaunchedEffect(myId) {
        attempt { ChatRepository.loadProfile(myId) }
            .onSuccess {
                me = it
                displayName = it?.displayName ?: ""
                bio = it?.bio ?: ""
                birthday = it?.birthday ?: ""
            }
            .onFailure { error = it.toUserMessage() }
    }

    // The system photo picker needs no storage permission.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                // Avatars are small: at most 512 pixels on the longest side.
                val bytes = compressImage(context, uri, maxSide = 512)
                if (bytes == null) {
                    error = "Không đọc được ảnh này. Hãy chọn ảnh khác."
                } else {
                    newAvatarBytes = bytes
                    newAvatarPreview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    error = null
                }
            }
        }
    }

    fun save() {
        val name = displayName.trim()
        if (name.isEmpty() || name.length > 50) {
            error = "Tên hiển thị cần từ 1 đến 50 ký tự."
            return
        }
        busy = true
        scope.launch {
            attempt {
                // Upload the new picture first (if one was picked), then save.
                val bytes = newAvatarBytes
                val avatarPath = if (bytes != null) {
                    val path = "$myId/${UUID.randomUUID()}.jpg"
                    ChatRepository.uploadAvatar(path, bytes)
                    path
                } else {
                    null   // keep the current avatar
                }
                ChatRepository.updateMyProfile(name, avatarPath)

                // The profile page, only when something of it was changed
                // (it needs supabase_migration_29).
                val cover = newCoverBytes
                val pageChanged = cover != null || removeCover ||
                    bio.trim() != (me?.bio ?: "") || birthday != (me?.birthday ?: "")
                if (pageChanged) {
                    val coverPath = when {
                        cover != null -> {
                            val path = "$myId/cover-${UUID.randomUUID()}.jpg"
                            ChatRepository.uploadAvatar(path, cover)
                            path
                        }
                        removeCover -> ""
                        else -> null   // keep the current cover
                    }
                    ChatRepository.updateMyProfileDetails(bio.trim(), birthday, coverPath)
                    val oldCover = me?.coverPath
                    if (coverPath != null && oldCover != null && oldCover != coverPath) {
                        attempt { ChatRepository.deleteAvatar(oldCover) }
                    }
                }
                // The picture that was just replaced is not used any more.
                val oldPath = me?.avatarPath
                if (avatarPath != null && oldPath != null && oldPath != avatarPath) {
                    ChatRepository.deleteAvatar(oldPath)
                }
            }
                .onSuccess { onBack() }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hồ sơ của tôi") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        val profile = me
        if (profile == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (error == null) {
                    LoadingScreen()
                } else {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cover: the newly picked picture, else the saved one, else a band.
            val savedCover = rememberProfilePicture(if (removeCover) null else profile.coverPath)
            ProfileCover(
                bitmap = newCoverPreview ?: savedCover,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !busy) {
                        pickCover.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pickCover.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) { Text("Đổi ảnh bìa") }
                if (newCoverPreview != null || (profile.coverPath != null && !removeCover)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            newCoverBytes = null
                            newCoverPreview = null
                            removeCover = profile.coverPath != null
                        },
                    ) { Text("Xóa ảnh bìa", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Avatar: the newly picked picture if there is one, else the saved one.
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !busy) {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val preview = newAvatarPreview
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Ảnh đại diện mới",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                } else {
                    Avatar(
                        name = profile.displayName,
                        online = false,
                        size = 128.dp,
                        avatarPath = profile.avatarPath,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                enabled = !busy,
                onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) { Text("Đổi ảnh đại diện") }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Tên hiển thị") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = "@${profile.username}",
                onValueChange = {},
                label = { Text("Tên người dùng") },
                supportingText = { Text("Không đổi được, vì bạn bè tìm bạn bằng tên này.") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= 300) bio = it },
                label = { Text("Giới thiệu") },
                placeholder = { Text("Vài dòng về bạn…") },
                supportingText = { Text("${bio.length}/300") },
                minLines = 2,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "🎂  Ngày sinh: " + birthdayLabel(birthday).ifEmpty { "chưa có" },
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !busy, onClick = { pickingDay = true }) {
                    Text(if (birthday.isEmpty()) "Chọn" else "Đổi")
                }
                if (birthday.isNotEmpty()) {
                    TextButton(enabled = !busy, onClick = { birthday = "" }) {
                        Text("Xóa", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TextButton(enabled = !busy, onClick = { viewing = true }) { Text("Xem trang cá nhân của tôi") }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { save() },
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (busy) "Đang lưu…" else "Lưu thay đổi")
            }
        }
    }

    // Choosing the birthday on a calendar.
    if (pickingDay) {
        val start = runCatching {
            java.time.LocalDate.parse(birthday).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val dayState = rememberDatePickerState(initialSelectedDateMillis = start)
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = dayState.selectedDateMillis
                        if (picked != null) {
                            val day = java.time.Instant.ofEpochMilli(picked)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            if (day.isAfter(java.time.LocalDate.now())) {
                                error = "Ngày sinh không thể ở tương lai."
                            } else {
                                birthday = day.toString()
                                error = null
                            }
                        }
                        pickingDay = false
                    },
                ) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Hủy") } },
        ) {
            DatePicker(state = dayState)
        }
    }

    // My page as other people see it (what is saved, not what is typed).
    if (viewing) {
        ProfileViewDialog(userId = myId, known = me, onDismiss = { viewing = false })
    }
}
