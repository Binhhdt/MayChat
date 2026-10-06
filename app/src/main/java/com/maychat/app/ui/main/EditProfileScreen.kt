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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.maychat.app.ui.common.LoadingScreen
import kotlinx.coroutines.launch
import java.util.UUID

// "My profile": change the avatar picture and the display name.
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

    LaunchedEffect(myId) {
        attempt { ChatRepository.loadProfile(myId) }
            .onSuccess {
                me = it
                displayName = it?.displayName ?: ""
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
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Quay lại") } },
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
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )

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
            Button(onClick = { save() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Đang lưu…" else "Lưu thay đổi")
            }
        }
    }
}
