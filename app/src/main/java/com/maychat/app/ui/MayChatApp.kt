package com.maychat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.maychat.app.call.CallManager
import com.maychat.app.call.CallScreen
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.DeviceId
import com.maychat.app.data.Profile
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.push.Push
import com.maychat.app.ui.auth.AuthScreen
import com.maychat.app.ui.chat.ChatScreen
import com.maychat.app.ui.common.SplashScreen
import com.maychat.app.ui.main.ConversationsScreen
import com.maychat.app.ui.main.EditProfileScreen
import com.maychat.app.ui.main.FriendsScreen
import com.maychat.app.ui.main.FriendsState
import com.maychat.app.ui.main.MainBottomBar
import com.maychat.app.ui.main.MainTab
import com.maychat.app.ui.main.SearchScreen
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A screen shown on top of the two main tabs.
private sealed interface Overlay {
    data object Search : Overlay
    data object EditProfile : Overlay
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

        // Give the repository this installation's id (used for the
        // "one device at a time" rule).
        val context = LocalContext.current
        remember { ChatRepository.deviceId = DeviceId.get(context.applicationContext) }

        val status by ChatRepository.sessionStatus.collectAsState()
        when (status) {
            is SessionStatus.Authenticated -> {
                val myId = ChatRepository.currentUserId()
                if (myId == null) SplashScreen() else SessionGate(myId)
            }
            is SessionStatus.NotAuthenticated -> {
                // Nobody is logged in, so a call announced by a notification
                // has no screen to appear on: stop its ringing.
                LaunchedEffect(Unit) { CallManager.dismissAny() }
                AuthScreen()
            }
            else -> SplashScreen()
        }

        // The call screen is drawn above everything else, including the
        // start-up screen, so an incoming call is visible at once.
        CallManager.ui?.let { call -> CallScreen(call) }
    }
}

// Runs right after login and every time the app starts while logged in.
// NOTHING of the account (no conversation, no message) is shown until the
// server has confirmed that no other device is using it. While waiting, the
// user sees the start-up screen with the logo instead of a "checking" text.
// An incoming call is not delayed by this: the call screen is drawn above
// everything (see MayChatApp).
@Composable
private fun SessionGate(myId: String) {
    var allowed by remember(myId) { mutableStateOf(false) }

    LaunchedEffect(myId) {
        // null = the check itself failed (offline, or migration 05 not run).
        // In that case the user is let in, so a network problem never locks
        // anyone out of their own account.
        val mine = attempt { ChatRepository.claimSession() }.getOrNull()
        if (mine == false) {
            ChatRepository.signOutWithNotice(
                "Tài khoản này hiện đang có người sử dụng trên một thiết bị khác. " +
                    "Hãy đăng xuất ở thiết bị kia trước. " +
                    "Nếu bạn vừa cài lại ứng dụng, hãy chờ 2 phút rồi đăng nhập lại.",
            )
        } else {
            allowed = true
        }
    }

    if (allowed) MainScreens(myId, sessionChecked = true) else SplashScreen()
}

@Composable
private fun MainScreens(myId: String, sessionChecked: Boolean) {
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

    val lifecycleOwner = LocalLifecycleOwner.current

    // While the app is on screen, tell the server every 30 seconds that this
    // device is still using the account. If the answer is "another device
    // has it now" (possible after this phone was idle), sign out here.
    LaunchedEffect(myId, lifecycleOwner, sessionChecked) {
        // Wait for the first check in SessionGate, so that only one of the
        // two ever reports "account in use".
        if (!sessionChecked) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val mine = attempt { ChatRepository.claimSession() }.getOrNull()
                if (mine == false) {
                    ChatRepository.signOutWithNotice(
                        "Tài khoản của bạn vừa được đăng nhập trên một thiết bị khác, " +
                            "nên thiết bị này đã đăng xuất.",
                    )
                    break
                }
                delay(30_000)
            }
        }
    }

    // Safety net: refresh friend requests every 20 seconds while the app is
    // on screen, in case the live connection has silently stopped.
    LaunchedEffect(myId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(20_000)
                friends.reload()
            }
        }
    }

    // ----- Push notifications -------------------------------------------
    val context = LocalContext.current

    // Android 13+: notifications need the user's permission. Asked once per
    // app start if not granted yet; the app works either way.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(myId) {
        if (Push.isConfigured && Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Tell the server this phone's address, so it can be notified.
    LaunchedEffect(myId) {
        val token = Push.currentToken()
        if (token != null) attempt { ChatRepository.registerPushToken(token) }
    }

    // A notification was tapped: open that conversation.
    val chatToOpen by Push.chatToOpen.collectAsState()
    LaunchedEffect(chatToOpen) {
        val target = chatToOpen ?: return@LaunchedEffect
        Push.clearOpenChat()
        val person = attempt { ChatRepository.loadProfile(target.senderId) }.getOrNull()
            ?: Profile(id = target.senderId, username = "", displayName = target.senderName)
        overlay = Overlay.Chat(target.conversationId, person)
    }

    // ----- "Offline for how long" --------------------------------------
    // While the app is on screen, tell the server once a minute that I am
    // active, and once more at the moment the app leaves the screen.
    LaunchedEffect(myId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                attempt { ChatRepository.touchLastSeen() }
                delay(60_000)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        ChatRepository.touchLastSeenInBackground()
    }

    // ----- Voice calls ---------------------------------------------------
    // Listen for incoming calls while logged in.
    DisposableEffect(myId) {
        onDispose { CallManager.detach() }
    }
    LaunchedEffect(myId) {
        val me = attempt { ChatRepository.loadProfile(myId) }.getOrNull()
        CallManager.attach(context, myId, me?.displayName ?: "MayChat")
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
                onOpenProfile = { overlay = Overlay.EditProfile },
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
        Overlay.EditProfile -> EditProfileScreen(
            myId = myId,
            onBack = { overlay = null },
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
