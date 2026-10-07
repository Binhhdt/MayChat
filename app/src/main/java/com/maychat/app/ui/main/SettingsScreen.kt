package com.maychat.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.maychat.app.BuildConfig
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.BackButton
import kotlinx.coroutines.launch

// "Cài đặt": message notifications on/off, change password, app version.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenProfile: () -> Unit) {
    val scope = rememberCoroutineScope()

    // ----- Notifications -------------------------------------------------
    // null until the current setting has been read from the server.
    var notifyMessages by remember { mutableStateOf<Boolean?>(null) }
    var notifyError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        attempt { ChatRepository.loadMuteMessages() }
            .onSuccess { notifyMessages = !it }
            .onFailure { notifyError = it.toUserMessage() }
    }

    // ----- Password ------------------------------------------------------
    var newPassword by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var passwordBusy by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var passwordDone by remember { mutableStateOf(false) }

    fun savePassword() {
        passwordDone = false
        passwordError = when {
            newPassword.length < 6 -> "Mật khẩu mới cần ít nhất 6 ký tự."
            newPassword != repeatPassword -> "Hai ô mật khẩu chưa giống nhau."
            else -> null
        }
        if (passwordError != null) return
        passwordBusy = true
        scope.launch {
            attempt { ChatRepository.changePassword(newPassword) }
                .onSuccess {
                    passwordDone = true
                    newPassword = ""
                    repeatPassword = ""
                }
                .onFailure { passwordError = it.toUserMessage() }
            passwordBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            SettingsTitle("Tài khoản")
            Text(
                "Sửa hồ sơ (ảnh đại diện, tên hiển thị)",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfile)
                    .padding(vertical = 14.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsTitle("Thông báo")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Thông báo tin nhắn", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Khi tắt, điện thoại không báo tin nhắn mới. Cuộc gọi đến vẫn đổ chuông.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = notifyMessages ?: true,
                    enabled = notifyMessages != null,
                    onCheckedChange = { wanted ->
                        val before = notifyMessages
                        notifyMessages = wanted
                        notifyError = null
                        scope.launch {
                            attempt { ChatRepository.setMuteMessages(!wanted) }
                                .onFailure {
                                    // Not saved: show the switch as it was.
                                    notifyMessages = before
                                    notifyError = it.toUserMessage()
                                }
                        }
                    },
                )
            }
            notifyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SettingsTitle("Đổi mật khẩu")
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("Mật khẩu mới") },
                singleLine = true,
                enabled = !passwordBusy,
                shape = RoundedCornerShape(16.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = repeatPassword,
                onValueChange = { repeatPassword = it },
                label = { Text("Nhập lại mật khẩu mới") },
                singleLine = true,
                enabled = !passwordBusy,
                shape = RoundedCornerShape(16.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            passwordError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (passwordDone) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Đã đổi mật khẩu. Lần đăng nhập sau hãy dùng mật khẩu mới.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { savePassword() },
                enabled = !passwordBusy && newPassword.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (passwordBusy) "Đang lưu…" else "Đổi mật khẩu")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SettingsTitle("Thông tin")
            Text(
                "MayChat phiên bản ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
