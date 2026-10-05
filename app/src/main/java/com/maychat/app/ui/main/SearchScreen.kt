package com.maychat.app.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.delay

// Find another user by the start of their username. From the result you can
// send a friend request, or tap the row to open a chat.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    myId: String,
    friends: FriendsState,
    onBack: () -> Unit,
    onOpenChat: (Profile) -> Unit,
) {
    val online by ChatRepository.onlineUsers.collectAsState()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Search a short moment after the user stops typing, not on every key press.
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(400)
        attempt { ChatRepository.searchUsers(query, myId) }
            .onSuccess {
                results = it
                searched = true
                error = null
            }
            .onFailure { error = it.toUserMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tìm bạn") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Quay lại") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Tên người dùng") },
                placeholder = { Text("Nhập ít nhất 2 ký tự") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            (error ?: friends.error)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (searched && results.isEmpty() && error == null) {
                Text(
                    "Không tìm thấy ai có tên người dùng bắt đầu bằng \"${query.trim()}\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { person ->
                    val relation = friends.relation(person.id)
                    PersonRow(
                        profile = person,
                        online = person.id in online,
                        onClick = if (relation == Relation.BLOCKED) null else ({ onOpenChat(person) }),
                    ) {
                        when (relation) {
                            Relation.NONE ->
                                Button(onClick = { friends.sendRequest(person.id) }) { Text("Kết bạn") }
                            Relation.REQUEST_SENT ->
                                OutlinedButton(onClick = { friends.remove(person.id) }) { Text("Hủy lời mời") }
                            Relation.REQUEST_RECEIVED ->
                                Button(onClick = { friends.accept(person.id) }) { Text("Chấp nhận") }
                            Relation.FRIEND ->
                                Text("Bạn bè", color = MaterialTheme.colorScheme.primary)
                            Relation.BLOCKED ->
                                OutlinedButton(onClick = { friends.unblock(person.id) }) { Text("Bỏ chặn") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
