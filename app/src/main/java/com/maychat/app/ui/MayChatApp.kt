package com.maychat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.auth.AuthScreen
import com.maychat.app.ui.chat.ChatScreen
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.main.ConversationsScreen
import com.maychat.app.ui.main.FriendsScreen
import com.maychat.app.ui.main.FriendsState
import com.maychat.app.ui.main.MainBottomBar
import com.maychat.app.ui.main.MainTab
import com.maychat.app.ui.main.SearchScreen
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

// A screen shown on top of the two main tabs.
private sealed interface Overlay {
    data object Search : Overlay
    data class Chat(val conversationId: String, val other: Profile) : Overlay
}

// Top of the app: decides between "not configured", login, and the main screens.
@Composable
fun MayChatApp() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!SupabaseProvider.isConfigured) {
            NotConfiguredScreen()
            return@Surface
        }

        val status by ChatRepository.sessionStatus.collectAsState()
        when (status) {
            is SessionStatus.Authenticated -> {
                val myId = ChatRepository.currentUserId()
                if (myId == null) LoadingScreen("Đang tải tài khoản…") else MainScreens(myId)
            }
            is SessionStatus.NotAuthenticated -> AuthScreen()
            else -> LoadingScreen("Đang kết nối…")
        }
    }
}

@Composable
private fun MainScreens(myId: String) {
    val scope = rememberCoroutineScope()
    val friends = remember(myId) { FriendsState(myId, scope) }
    val connectionCount by ChatRepository.connectionCount.collectAsState()

    var tab by remember(myId) { mutableStateOf(MainTab.CHATS) }
    var overlay by remember(myId) { mutableStateOf<Overlay?>(null) }

    // Open the live connection once per logged-in user.
    LaunchedEffect(myId) {
        ChatRepository.startRealtime(myId)
    }

    // Load friends at start, and again whenever the live connection comes back.
    LaunchedEffect(myId, connectionCount) {
        friends.reload()
    }

    // A friend request arrived or was answered on another phone.
    LaunchedEffect(myId) {
        ChatRepository.friendEvents.collect { friends.reload() }
    }

    // The phone's Back button closes the chat or search screen.
    BackHandler(enabled = overlay != null) {
        overlay = null
    }

    // Finds (or creates) my conversation with this person, then shows it.
    fun openChat(person: Profile) {
        scope.launch {
            attempt { ChatRepository.openConversation(person.id) }
                .onSuccess { id -> overlay = Overlay.Chat(id, person) }
                .onFailure { friends.error = it.toUserMessage() }
        }
    }

    val bottomBar: @Composable () -> Unit = {
        MainBottomBar(
            selected = tab,
            incomingRequests = friends.incoming.size,
            onSelect = { tab = it },
        )
    }

    when (val current = overlay) {
        null -> when (tab) {
            MainTab.CHATS -> ConversationsScreen(
                myId = myId,
                onOpenSearch = { overlay = Overlay.Search },
                onOpenChat = { id, other -> overlay = Overlay.Chat(id, other) },
                bottomBar = bottomBar,
            )
            MainTab.FRIENDS -> FriendsScreen(
                friends = friends,
                onOpenSearch = { overlay = Overlay.Search },
                onOpenChat = { openChat(it) },
                bottomBar = bottomBar,
            )
        }
        Overlay.Search -> SearchScreen(
            myId = myId,
            friends = friends,
            onBack = { overlay = null },
            onOpenChat = { openChat(it) },
        )
        is Overlay.Chat -> ChatScreen(
            myId = myId,
            conversationId = current.conversationId,
            other = current.other,
            friends = friends,
            onBack = { overlay = null },
        )
    }
}

// Shown when the APK was built without the two GitHub Secrets.
@Composable
private fun NotConfiguredScreen() {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Text("Chưa cấu hình Supabase", style = MaterialTheme.typography.headlineSmall)
        Text(
            "APK này được build khi chưa có hai GitHub Secrets SUPABASE_URL và SUPABASE_KEY. " +
                "Hãy thêm hai secret đó trong GitHub (Settings → Secrets and variables → Actions) " +
                "rồi chạy lại workflow Build APK.",
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
