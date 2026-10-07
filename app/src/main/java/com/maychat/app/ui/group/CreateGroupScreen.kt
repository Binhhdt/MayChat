package com.maychat.app.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import kotlinx.coroutines.launch

// "Tạo nhóm": a name and a tick list of my friends.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    friends: List<Profile>,
    onCreated: (groupId: String, name: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tạo nhóm") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Tên nhóm") },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Chọn thành viên từ danh sách bạn bè (đã chọn ${picked.size}, tối đa 49)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (friends.isEmpty()) {
                Text(
                    "Bạn chưa có bạn bè nào. Hãy kết bạn trước rồi quay lại tạo nhóm.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(friends, key = { it.id }) { person ->
                        val ticked = person.id in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    picked = when {
                                        ticked -> picked - person.id
                                        picked.size >= 49 -> picked
                                        else -> picked + person.id
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = person.displayName,
                                online = false,
                                size = 44.dp,
                                avatarPath = person.avatarPath,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                person.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            RadioButton(selected = ticked, onClick = null)
                        }
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Button(
                onClick = {
                    val clean = name.trim()
                    busy = true
                    error = null
                    scope.launch {
                        attempt { ChatRepository.createGroup(clean, picked.toList()) }
                            .onSuccess { id -> onCreated(id, clean) }
                            .onFailure { error = it.toUserMessage() }
                        busy = false
                    }
                },
                enabled = !busy && name.isNotBlank() && picked.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            ) {
                Text(if (busy) "Đang tạo…" else "Tạo nhóm")
            }
        }
    }
}
