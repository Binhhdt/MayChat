package com.maychat.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.LoadingScreen

enum class MainTab { CHATS, FRIENDS, GROUPS, CALLS }

// Bar at the bottom of the four main screens.
@Composable
// chatUnread: unread messages in all my chats and groups together (red
// number on "Trò chuyện"); groupUnread: those in groups (on "Nhóm").
fun MainBottomBar(
    selected: MainTab,
    incomingRequests: Int,
    onSelect: (MainTab) -> Unit,
    chatUnread: Int = 0,
    groupUnread: Int = 0,
    // The farm game (version 0.35.0). Not a tab of this screen: tapping it
    // opens the game in its own landscape screen, so the tab stays as it was.
    onGame: (() -> Unit)? = null,
    gameNew: Boolean = false,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.CHATS,
            onClick = { onSelect(MainTab.CHATS) },
            icon = {
                UnreadBadge(chatUnread) {
                    Icon(
                        painter = painterResource(R.drawable.ic_notification),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            label = { Text("Trò chuyện") },
        )
        NavigationBarItem(
            selected = selected == MainTab.FRIENDS,
            onClick = { onSelect(MainTab.FRIENDS) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_group),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text(if (incomingRequests > 0) "Bạn bè ($incomingRequests)" else "Bạn bè") },
        )
        // My groups, right next to "Bạn bè".
        NavigationBarItem(
            selected = selected == MainTab.GROUPS,
            onClick = { onSelect(MainTab.GROUPS) },
            icon = {
                UnreadBadge(groupUnread) {
                    Icon(
                        painter = painterResource(R.drawable.ic_groups),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            label = { Text("Nhóm") },
        )
        if (onGame != null) {
            NavigationBarItem(
                selected = false,
                onClick = onGame,
                icon = {
                    BadgedBox(
                        badge = { if (gameNew) Badge { Text("Mới") } },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_game),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                label = { Text("Game") },
            )
        }
        NavigationBarItem(
            selected = selected == MainTab.CALLS,
            onClick = { onSelect(MainTab.CALLS) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text("Cuộc gọi") },
        )
    }
}

// An icon with a red number at its corner (nothing when the number is 0).
@Composable
private fun UnreadBadge(count: Int, icon: @Composable () -> Unit) {
    BadgedBox(
        badge = {
            if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) }
        },
    ) { icon() }
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
        Avatar(name = profile.displayName, online = online, avatarPath = profile.avatarPath)
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
@Composable
fun FriendsScreen(
    friends: FriendsState,
    onOpenSearch: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenChat: (Profile) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val online by ChatRepository.onlineUsers.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Same header style as the conversation list: big title and the
            // "find a friend" bar (tapping it opens the Find Friends screen).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Bạn bè",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    // My QR code, and reading a friend's code.
                    OutlinedButton(onClick = onOpenQr) { Text("Mã QR") }
                }
                Spacer(Modifier.height(16.dp))
                Surface(
                    onClick = onOpenSearch,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Tìm bạn theo tên người dùng",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
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
