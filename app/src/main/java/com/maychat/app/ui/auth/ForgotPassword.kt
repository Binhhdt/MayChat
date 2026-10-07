package com.maychat.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.launch

// "Quên mật khẩu": two steps in one window.
//   1. Type the account's email -> the server emails a code.
//   2. Type that code and a new password -> the password is changed and
//      the user is logged in.
@Composable
fun ForgotPasswordDialog(initialEmail: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf(initialEmail) }
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun sendCode() {
        val clean = email.trim()
        if (!clean.contains("@")) {
            error = "Nhập email của tài khoản."
            return
        }
        busy = true
        error = null
        scope.launch {
            attempt { ChatRepository.sendPasswordResetCode(clean) }
                .onSuccess { codeSent = true }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    fun finish() {
        val cleanCode = code.filter { it.isDigit() }
        error = when {
            cleanCode.length < 6 -> "Nhập mã gồm các chữ số trong email."
            newPassword.length < 6 -> "Mật khẩu mới cần ít nhất 6 ký tự."
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            // On success the app logs in by itself and this window goes away.
            attempt { ChatRepository.resetPasswordWithCode(email.trim(), cleanCode, newPassword) }
                .onFailure {
                    error = "Mã không đúng hoặc đã hết hạn. Hãy kiểm tra lại, hoặc gửi mã mới."
                }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Quên mật khẩu") },
        text = {
            Column {
                if (!codeSent) {
                    Text("Nhập email bạn đã dùng để đăng ký. MayChat sẽ gửi một mã số tới email đó.")
                    Spacer(Modifier.height(12.dp))
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
                } else {
                    Text(
                        "Đã gửi mã tới ${email.trim()}. Mở hộp thư (xem cả mục Thư rác), " +
                            "rồi nhập mã và mật khẩu mới vào đây.",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(10) },
                        label = { Text("Mã trong email") },
                        singleLine = true,
                        enabled = !busy,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Mật khẩu mới") },
                        singleLine = true,
                        enabled = !busy,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { sendCode() }, enabled = !busy) { Text("Gửi lại mã") }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (codeSent) finish() else sendCode() }, enabled = !busy) {
                Text(
                    when {
                        busy -> "Đang xử lý…"
                        codeSent -> "Đổi mật khẩu"
                        else -> "Gửi mã"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy) { Text("Đóng") }
        },
    )
}
