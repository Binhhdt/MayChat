package com.maychat.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen

enum class MainTab { CHATS, FRIENDS }

// Bar at the bottom of the two main screens.
@Composable
fun MainBottomBar(selected: MainTab, incomingRequests: Int, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.CHATS,
            onClick = { onSelect(MainTab.CHATS) },
            icon = { Text("💬") },
            label = { Text("Trò chuyện") },
        )
        NavigationBarItem(
            selected = selected == MainTab.FRIENDS,
            onClick = { onSelect(MainTab.FRIENDS) },
            icon = { Text("👥") },
            label = { Text(if (incomingRequests > 0) "Bạn bè ($incomingRequests)" else "Bạn bè") },
        )
    }
}

// One person in a list: avatar, name, username, and buttons on the right.
@Composable
fun PersonRow(
    profile: Profile,
    online: Boolean,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = profile.displayName, online = online)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${profile.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// The "Bạn bè" tab: incoming requests, sent requests, friends, blocked users.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    friends: FriendsState,
    onOpenSearch: () -> Unit,
    onOpenChat: (Profile) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val online by ChatRepository.onlineUsers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bạn bè", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = onOpenSearch) { Text("Tìm bạn") } },
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            friends.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (!friends.loaded) {
                LoadingScreen()
                return@Column
            }

            val incoming = friends.incoming
            val sent = friends.sent
            val accepted = friends.friends
            val blocked = friends.blocked

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (incoming.isNotEmpty()) {
                    item(key = "h-incoming") { SectionTitle("Lời mời kết bạn (${incoming.size})") }
                    items(incoming, key = { "in-${it.id}" }) { person ->
                        PersonRow(profile = person, online = person.id in online) {
                            Button(onClick = { friends.accept(person.id) }) { Text("Chấp nhận") }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { friends.reject(person.id) }) { Text("Từ chối") }
                        }
                        HorizontalDivider()
                    }
                }

                if (sent.isNotEmpty()) {
                    item(key = "h-sent") { SectionTitle("Lời mời đã gửi (${sent.size})") }
                    items(sent, key = { "out-${it.id}" }) { person ->
                        PersonRow(profile = person, online = person.id in online) {
                            OutlinedButton(onClick = { friends.remove(person.id) }) { Text("Hủy") }
                        }
                        HorizontalDivider()
                    }
                }

                item(key = "h-friends") { SectionTitle("Bạn bè (${accepted.size})") }
                if (accepted.isEmpty()) {
                    item(key = "no-friends") {
                        Text(
                            "Chưa có bạn bè. Bấm \"Tìm bạn\" ở góc trên để gửi lời mời kết bạn.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                items(accepted, key = { "fr-${it.id}" }) { person ->
                    PersonRow(
                        profile = person,
                        online = person.id in online,
                        onClick = { onOpenChat(person) },
                    ) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Nhắn tin") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenChat(person)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Hủy kết bạn") },
                                    onClick = {
                                        menuOpen = false
                                        friends.remove(person.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Chặn") },
                                    onClick = {
                                        menuOpen = false
                                        friends.block(person.id)
                                    },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                if (blocked.isNotEmpty()) {
                    item(key = "h-blocked") { SectionTitle("Đã chặn (${blocked.size})") }
                    items(blocked, key = { "bl-${it.id}" }) { person ->
                        PersonRow(profile = person, online = false) {
                            OutlinedButton(onClick = { friends.unblock(person.id) }) { Text("Bỏ chặn") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
