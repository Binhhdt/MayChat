package com.maychat.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.maychat.app.R
import com.maychat.app.call.CallManager
import com.maychat.app.data.CallItem
import com.maychat.app.data.ChatMemory
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.ListCache
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.common.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// "Cuộc gọi" tab: the calls I made and received, newest first. Tap a row to
// open the chat with that person, tap the handset to call them back.
@Composable
fun CallsScreen(
    myId: String,
    onOpenChat: (Profile) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The list as last shown is there at once; the spinner only appears
    // the first time after the app started.
    val rememberedCalls = remember { if (ListCache.trusted(myId)) ChatMemory.calls else null }
    var calls by remember { mutableStateOf(rememberedCalls ?: emptyList()) }
    var loading by remember { mutableStateOf(rememberedCalls == null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        attempt { ChatRepository.loadCalls(myId) }
            .onSuccess {
                calls = it
                ChatMemory.calls = it
                error = null
            }
            .onFailure { error = it.toUserMessage() }
        loading = false
    }

    LaunchedEffect(myId) { reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { reload() } }

    // A call just ended: its row was completed a moment ago, read the list again.
    val inCall = CallManager.ui != null
    LaunchedEffect(inCall) {
        if (!inCall) {
            delay(1_500)
            reload()
        }
    }

    // Calling back: needs the microphone permission and the conversation.
    var personToCall by remember { mutableStateOf<Profile?>(null) }
    fun call(person: Profile) {
        scope.launch {
            attempt { ChatRepository.openConversation(person.id) }
                .onSuccess { conversationId -> CallManager.startCall(person, conversationId) }
                .onFailure { error = it.toUserMessage() }
        }
    }
    val askMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val person = personToCall
        personToCall = null
        if (granted && person != null) {
            call(person)
        } else if (!granted) {
            error = "Cần quyền micro để gọi. Bạn có thể bật trong Cài đặt của điện thoại."
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Text("Cuộc gọi", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            when {
                loading -> LoadingScreen()

                calls.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Chưa có cuộc gọi nào", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Các cuộc gọi đi và đến từ bây giờ sẽ được ghi lại ở đây.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(calls, key = { it.call.id }) { item ->
                        CallRow(
                            item = item,
                            onClick = { onOpenChat(item.other) },
                            onCall = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    call(item.other)
                                } else {
                                    personToCall = item.other
                                    askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRow(item: CallItem, onClick: () -> Unit, onCall: () -> Unit) {
    val status = item.call.status
    // A call I did not pick up (the caller gave up, or nobody answered).
    val missed = !item.outgoing && (status == "missed" || status == "cancelled")
    val length = formatCallLength(item.call.durationS)
    val line = when {
        missed -> "Cuộc gọi nhỡ"
        item.outgoing -> when (status) {
            "answered" -> "Gọi đi · $length"
            "missed" -> "Gọi đi · Không trả lời"
            "declined" -> "Gọi đi · Bị từ chối"
            "cancelled" -> "Gọi đi · Đã hủy"
            "failed" -> "Gọi đi · Không kết nối được"
            else -> "Gọi đi"
        }
        else -> when (status) {
            "answered" -> "Gọi đến · $length"
            "declined" -> "Gọi đến · Đã từ chối"
            "failed" -> "Gọi đến · Không kết nối được"
            else -> "Gọi đến"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = item.other.displayName,
            online = false,
            size = 48.dp,
            avatarPath = item.other.avatarPath,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.other.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$line · ${formatTime(item.call.startedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCall) {
            Icon(
                painter = painterResource(R.drawable.ic_call),
                contentDescription = "Gọi lại",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// 75 -> "1:15"
private fun formatCallLength(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
