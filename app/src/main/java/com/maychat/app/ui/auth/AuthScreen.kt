package com.maychat.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.launch

private val usernameRule = Regex("^[a-z0-9_]{3,20}$")

// Login and registration on one screen. A link at the bottom switches mode.
// On success nothing needs to navigate: the session changes and MayChatApp
// shows the main screens by itself.
@Composable
fun AuthScreen() {
    val scope = rememberCoroutineScope()

    var registerMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val cleanEmail = email.trim()
        val cleanUsername = username.trim().lowercase()
        val cleanName = displayName.trim()

        // Check the form on the phone first, to avoid useless network calls.
        error = when {
            !cleanEmail.contains("@") || !cleanEmail.contains(".") -> "Nhập địa chỉ email hợp lệ."
            password.length < 6 -> "Mật khẩu cần ít nhất 6 ký tự."
            registerMode && !usernameRule.matches(cleanUsername) ->
                "Tên người dùng gồm 3 đến 20 ký tự: chữ thường không dấu, số hoặc dấu gạch dưới."
            registerMode && cleanName.isEmpty() -> "Nhập tên hiển thị."
            registerMode && cleanName.length > 50 -> "Tên hiển thị tối đa 50 ký tự."
            else -> null
        }
        if (error != null) return

        busy = true
        scope.launch {
            val result = attempt {
                if (registerMode) {
                    if (!ChatRepository.isUsernameAvailable(cleanUsername)) {
                        throw IllegalStateException("invalid_username")
                    }
                    ChatRepository.signUp(cleanEmail, password, cleanUsername, cleanName)
                    if (ChatRepository.currentUserId() == null) {
                        // Happens only if "Confirm email" is still switched on in Supabase.
                        throw IllegalStateException(
                            "Tài khoản đã tạo nhưng chưa đăng nhập được. " +
                                "Kiểm tra lại mục Confirm email trong Supabase.",
                        )
                    }
                } else {
                    ChatRepository.signIn(cleanEmail, password)
                }
            }
            busy = false
            result.onFailure { error = it.toUserMessage() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MayChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (registerMode) "Tạo tài khoản mới" else "Đăng nhập",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mật khẩu") },
            singleLine = true,
            enabled = !busy,
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Ẩn" else "Hiện")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        if (registerMode) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.lowercase().filter { c -> c != ' ' } },
                label = { Text("Tên người dùng (để người khác tìm bạn)") },
                supportingText = { Text("Ví dụ: binh_01. Không đổi được sau khi tạo.") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Tên hiển thị") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (registerMode) "Đăng ký" else "Đăng nhập")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                registerMode = !registerMode
                error = null
            },
            enabled = !busy,
        ) {
            Text(if (registerMode) "Đã có tài khoản? Đăng nhập" else "Chưa có tài khoản? Đăng ký")
        }
    }
}
