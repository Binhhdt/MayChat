package com.maychat.app.ui.auth

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.R
import com.maychat.app.data.UserFacingException
import com.maychat.app.data.attempt
import com.maychat.app.data.isInvalidCredentials
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val usernameRule = Regex("^[a-z0-9_]{3,20}$")

// Login and registration on one screen. A link at the bottom switches mode.
// On success nothing needs to navigate: the session changes and MayChatApp
// shows the main screens by itself.
@Composable
fun AuthScreen() {
    val scope = rememberCoroutineScope()

    var registerMode by remember { mutableStateOf(false) }
    // The last email typed on this phone is remembered, so it does not have
    // to be typed again after a failed or refused login. The password is
    // never stored.
    val context = LocalContext.current
    val loginPrefs = remember {
        context.applicationContext.getSharedPreferences("maychat_login", Context.MODE_PRIVATE)
    }
    var email by remember { mutableStateOf(loginPrefs.getString("email", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A message left by the app, for example "this account is in use on
    // another device".
    val notice by ChatRepository.authNotice.collectAsState()

    fun submit() {
        ChatRepository.clearAuthNotice()
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

        loginPrefs.edit().putString("email", cleanEmail).apply()
        busy = true
        scope.launch {
            val result = attempt {
                if (registerMode) {
                    if (!ChatRepository.isUsernameAvailable(cleanUsername)) {
                        throw UserFacingException("Tên người dùng này đã có người dùng. Hãy chọn tên khác.")
                    }
                    ChatRepository.signUp(cleanEmail, password, cleanUsername, cleanName)
                    if (ChatRepository.currentUserId() == null) {
                        // Happens only if "Confirm email" is still switched on in Supabase.
                        throw UserFacingException(
                            "Tài khoản đã tạo nhưng chưa đăng nhập được. " +
                                "Kiểm tra lại mục Confirm email trong Supabase.",
                        )
                    }
                } else {
                    try {
                        ChatRepository.signIn(cleanEmail, password)
                    } catch (e: Exception) {
                        if (e is CancellationException || !e.isInvalidCredentials()) throw e
                        // Find out which of the two it is. If this check itself
                        // fails (for example migration 04 was not run), fall
                        // back to the general "email or password is wrong".
                        val registered = attempt { ChatRepository.isEmailRegistered(cleanEmail) }.getOrNull()
                        throw when (registered) {
                            false -> UserFacingException(
                                "Email này chưa đăng ký tài khoản. Bấm \"Chưa có tài khoản? Đăng ký\" ở dưới để tạo.",
                            )
                            true -> UserFacingException("Mật khẩu không đúng.")
                            null -> e
                        }
                    }
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
        // Logo: white chat bubble on a teal rounded tile.
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
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
            shape = RoundedCornerShape(16.dp),
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
            shape = RoundedCornerShape(16.dp),
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
                shape = RoundedCornerShape(16.dp),
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
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (notice != null && error == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                notice ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
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
        Button(
            onClick = { submit() },
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
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
