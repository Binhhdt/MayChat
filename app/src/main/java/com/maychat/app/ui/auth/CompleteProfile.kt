package com.maychat.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.UserFacingException
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.launch

private val usernameRule = Regex("^[a-z0-9_]{3,20}$")

// A username suggested from the email: "Nguyen.Binh+1@gmail.com" -> "nguyen_binh1".
private fun suggestUsername(email: String): String {
    val head = email.substringBefore("@").lowercase()
        .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(20)
    return if (head.length >= 3) head else ""
}

// "Hoàn tất hồ sơ": shown once, after the first login with Google, because
// Google gives a name but no username. Nothing of the app opens until the
// profile exists. "Đăng xuất" leaves without making it.
@Composable
fun CompleteProfileScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hints = remember { ChatRepository.googleHints() }
    var username by remember { mutableStateOf(suggestUsername(hints.second)) }
    var displayName by remember { mutableStateOf(hints.first.take(50)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val cleanUsername = username.trim().lowercase()
        val cleanName = displayName.trim()
        error = when {
            !usernameRule.matches(cleanUsername) ->
                "Tên người dùng gồm 3 đến 20 ký tự: chữ thường không dấu, số hoặc dấu gạch dưới."
            cleanName.isEmpty() -> "Nhập tên hiển thị."
            cleanName.length > 50 -> "Tên hiển thị tối đa 50 ký tự."
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            val result = attempt {
                if (!ChatRepository.isUsernameAvailable(cleanUsername)) {
                    throw UserFacingException("Tên người dùng này đã có người dùng. Hãy chọn tên khác.")
                }
                ChatRepository.completeProfile(cleanUsername, cleanName)
            }
            busy = false
            result.onSuccess { onDone() }.onFailure { error = it.toUserMessage() }
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
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("Hoàn tất hồ sơ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (hints.second.isNotEmpty()) "Bạn đăng nhập bằng Google (${hints.second}). Chọn tên người dùng để bạn bè tìm thấy bạn."
            else "Chọn tên người dùng để bạn bè tìm thấy bạn.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

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
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Bắt đầu dùng MayChat")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { scope.launch { attempt { ChatRepository.signOut() } } },
            enabled = !busy,
        ) {
            Text("Đăng xuất")
        }
    }
}
