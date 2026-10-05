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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.ui.auth.AuthScreen
import com.maychat.app.ui.chat.ChatScreen
import com.maychat.app.ui.common.LoadingScreen
import com.maychat.app.ui.main.ConversationsScreen
import com.maychat.app.ui.main.SearchScreen
import io.github.jan.supabase.auth.status.SessionStatus

// Which screen is showing after login.
private sealed interface Screen {
    data object Conversations : Screen
    data object Search : Screen
    data class Chat(val conversationId: String, val other: Profile) : Screen
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
    var screen by remember(myId) { mutableStateOf<Screen>(Screen.Conversations) }

    // Open the live connection once per logged-in user.
    LaunchedEffect(myId) {
        ChatRepository.startRealtime(myId)
    }

    // The phone's Back button goes back to the conversation list.
    BackHandler(enabled = screen != Screen.Conversations) {
        screen = Screen.Conversations
    }

    when (val current = screen) {
        Screen.Conversations -> ConversationsScreen(
            myId = myId,
            onOpenSearch = { screen = Screen.Search },
            onOpenChat = { id, other -> screen = Screen.Chat(id, other) },
        )
        Screen.Search -> SearchScreen(
            myId = myId,
            onBack = { screen = Screen.Conversations },
            onOpenChat = { id, other -> screen = Screen.Chat(id, other) },
        )
        is Screen.Chat -> ChatScreen(
            myId = myId,
            conversationId = current.conversationId,
            other = current.other,
            onBack = { screen = Screen.Conversations },
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
