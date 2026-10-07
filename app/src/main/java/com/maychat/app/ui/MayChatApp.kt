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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.maychat.app.call.GroupCallLayer
import com.maychat.app.call.GroupCallManager
import com.maychat.app.call.FloatingCallView
import com.maychat.app.call.CallPhase
import com.maychat.app.call.MinimizeCallButton
import com.maychat.app.call.ReturnToCallBar
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.E2E
import com.maychat.app.data.DeviceId
import com.maychat.app.data.ListCache
import com.maychat.app.data.Profile
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.push.ChatToOpen
import com.maychat.app.push.Reminders
import com.maychat.app.push.Push
import com.maychat.app.ui.auth.AuthScreen
import com.maychat.app.ui.chat.ChatScreen
import com.maychat.app.ui.common.SplashScreen
import com.maychat.app.ui.group.CreateGroupScreen
import com.maychat.app.ui.group.GroupChatScreen
import com.maychat.app.ui.group.GroupsScreen
import com.maychat.app.ui.main.CallsScreen
import com.maychat.app.ui.main.ConversationsScreen
import com.maychat.app.ui.main.EditProfileScreen
import com.maychat.app.ui.main.FriendsScreen
import com.maychat.app.ui.main.FriendsState
import com.maychat.app.ui.main.MainBottomBar
import com.maychat.app.ui.main.MainTab
import com.maychat.app.ui.main.QrScreen
import com.maychat.app.ui.main.SearchScreen
import com.maychat.app.ui.main.SettingsScreen
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// A screen shown on top of the two main tabs.
private sealed interface Overlay {
    data object Search : Overlay
    data object EditProfile : Overlay
    data object Settings : Overlay
    data object Qr : Overlay
    data object CreateGroup : Overlay
    data class GroupChat(val groupId: String, val name: String) : Overlay
    data class Chat(val conversationId: String, val other: Profile) : Overlay
}

// Writes the open screen into a short list of texts and reads it back.
private val OverlaySaver = listSaver<Overlay?, String>(
    save = { value ->
        when (value) {
            null -> emptyList()
            Overlay.Search -> listOf("search")
            Overlay.EditProfile -> listOf("profile")
            Overlay.Settings -> listOf("settings")
            Overlay.Qr -> listOf("qr")
            Overlay.CreateGroup -> listOf("create-group")
            is Overlay.GroupChat -> listOf("group", value.groupId, value.name)
            is Overlay.Chat -> listOf(
                "chat",
                value.conversationId,
                value.other.id,
                value.other.username,
                value.other.displayName,
                value.other.avatarPath ?: "",
            )
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "search" -> Overlay.Search
            "profile" -> Overlay.EditProfile
            "settings" -> Overlay.Settings
            "qr" -> Overlay.Qr
            "create-group" -> Overlay.CreateGroup
            "group" -> if (saved.size >= 3) Overlay.GroupChat(saved[1], saved[2]) else null
            "chat" -> if (saved.size >= 6) {
                Overlay.Chat(
                    conversationId = saved[1],
                    other = Profile(
                        id = saved[2],
                        username = saved[3],
                        displayName = saved[4],
                        avatarPath = saved[5].ifEmpty { null },
                    ),
                )
            } else {
                null
            }
            else -> null
        }
    },
)

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
        // While a call is being set up or running, the call screen can be
        // put aside ("‹ Tin nhắn" or the Back button) to use the rest of the
        // app; a green bar then leads back to it.
        val call = CallManager.ui
        LaunchedEffect(call == null) {
            // No call (any more): the next one starts with its full screen.
            if (call == null) CallManager.restore()
        }
        // Group calls: ringing screen, call screen, bar back to the call.
        // Drawn under a one-to-one call screen (only one of them is ever
        // in a call at a time), and only while somebody is logged in.
        if (status is SessionStatus.Authenticated) GroupCallLayer()
        if (call != null) {
            val canPutAside = call.phase != CallPhase.INCOMING && call.phase != CallPhase.ENDED
            if (CallManager.inPip && call.video && canPutAside) {
                // The app is a small floating window: only the picture.
                FloatingCallView(call)
            } else if (CallManager.minimized && canPutAside) {
                ReturnToCallBar(call)
            } else {
                CallScreen(call)
                if (canPutAside) MinimizeCallButton(call)
            }
        }
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
    // Exception that keeps the same guarantee: the server confirmed less
    // than a minute ago that THIS phone holds the account. Another phone can
    // only take it after 90 seconds of silence from this one, so nobody else
    // can be using it yet; the start-up screen is skipped and the check
    // below runs alongside (and still signs out if it ever says no).
    var allowed by remember(myId) { mutableStateOf(ListCache.sessionConfirmedRecently(myId)) }

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

    // "Đã nhận": while the app is running, tell the server that messages
    // sent to me have arrived, once at start and again whenever a new
    // message from someone else comes in. If migration 15 was not run the
    // calls simply fail and nothing changes.
    LaunchedEffect(myId) {
        attempt { ChatRepository.markDelivered() }
        ChatRepository.messageEvents.collect { message ->
            if (message.senderId != myId && message.deliveredAt == null) {
                attempt { ChatRepository.markDelivered() }
            }
        }
    }

    // Saved by Android, so the same tab and the same open chat come back
    // when the system closes and re-creates the screen (for example while
    // the camera app is open).
    var tab by rememberSaveable(myId) { mutableStateOf(MainTab.CHATS) }
    var overlay by rememberSaveable(myId, stateSaver = OverlaySaver) { mutableStateOf<Overlay?>(null) }

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
    // First choice: find it in the lists remembered on the phone. That
    // needs no network, so the chat opens at once, without showing the
    // conversation list on the way.
    fun openFromMemory(target: ChatToOpen): Boolean {
        ListCache.groups(myId)?.firstOrNull { it.id == target.conversationId }?.let { group ->
            overlay = Overlay.GroupChat(group.id, group.name)
            return true
        }
        ListCache.conversations(myId)?.firstOrNull { it.conversation.id == target.conversationId }?.let { item ->
            tab = MainTab.CHATS
            overlay = Overlay.Chat(item.conversation.id, item.other)
            return true
        }
        return false
    }
    // The app was started BY the tap: decide before the first screen is drawn.
    remember(myId) {
        Push.chatToOpen.value?.let { if (openFromMemory(it)) Push.clearOpenChat() }
        true
    }
    val chatToOpen by Push.chatToOpen.collectAsState()
    LaunchedEffect(chatToOpen) {
        val target = chatToOpen ?: return@LaunchedEffect
        if (openFromMemory(target)) {
            Push.clearOpenChat()
            return@LaunchedEffect
        }
        // The work runs in the screen's own scope, NOT in this effect:
        // clearing the request (last line) restarts the effect, and that
        // used to cancel the work halfway, so the chat never opened.
        scope.launch {
            // The notification of a GROUP message carries the group's id in
            // the same place: open the group in that case.
            val group = attempt { ChatRepository.loadGroup(target.conversationId) }.getOrNull()
            if (group != null) {
                overlay = Overlay.GroupChat(group.id, group.name)
            } else {
                val person = attempt { ChatRepository.loadProfile(target.senderId) }.getOrNull()
                    ?: Profile(id = target.senderId, username = "", displayName = target.senderName)
                tab = MainTab.CHATS
                overlay = Overlay.Chat(target.conversationId, person)
            }
        }
        Push.clearOpenChat()
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
        onDispose {
            CallManager.detach()
            GroupCallManager.detach()
        }
    }
    LaunchedEffect(myId) {
        val me = attempt { ChatRepository.loadProfile(myId) }.getOrNull()
        CallManager.attach(context, myId, me?.displayName ?: "MayChat")
        GroupCallManager.attach(context, myId, me?.displayName ?: "MayChat")
        // The notification code needs my name to recognise "@my name".
        me?.displayName?.let { Push.rememberMyName(context, it) }
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

    // ----- Red numbers on the bottom bar: unread messages ----------------
    // Read from the server at start, whenever a message arrives, when a
    // chat is closed, and every 15 seconds while the app is on screen.
    var chatUnreadTotal by remember(myId) { mutableStateOf(ListCache.unread(myId).values.sum()) }
    var groupUnreadTotal by remember(myId) { mutableStateOf(ListCache.groupUnread(myId).values.sum()) }
    suspend fun reloadUnreadTotals() = coroutineScope {
        launch { attempt { ChatRepository.loadUnreadCounts() }.onSuccess { chatUnreadTotal = it.values.sum() } }
        launch { attempt { ChatRepository.loadGroupUnreadCounts() }.onSuccess { groupUnreadTotal = it.values.sum() } }
        Unit
    }
    LaunchedEffect(myId, connectionCount, overlay == null) { reloadUnreadTotals() }
    LaunchedEffect(myId) {
        ChatRepository.messageEvents.collectLatest {
            delay(400)
            reloadUnreadTotals()
        }
    }
    LaunchedEffect(myId) {
        ChatRepository.groupEvents.collectLatest {
            delay(400)
            reloadUnreadTotals()
            // A group message may announce a new reminder: set its alarm.
            Reminders.requestSync()
        }
    }
    // A group call that starts while the app is open rings here. (Not
    // delayed like the block above, and no event may be skipped.)
    LaunchedEffect(myId) {
        ChatRepository.groupEvents.collect { groupId -> GroupCallManager.onGroupEvent(groupId) }
    }
    // End-to-end encryption: this phone's key pair, its public half on the server.
    LaunchedEffect(myId) { E2E.start(myId) }
    // Group reminders: read them and set this phone's alarms at start.
    LaunchedEffect(myId) { Reminders.requestSync(force = true) }
    LaunchedEffect(myId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(15_000)
                reloadUnreadTotals()
            }
        }
    }

    val bottomBar: @Composable () -> Unit = {
        MainBottomBar(
            selected = tab,
            incomingRequests = friends.incoming.size,
            onSelect = { tab = it },
            // "Trò chuyện" lists chats and groups, so it counts both.
            chatUnread = chatUnreadTotal + groupUnreadTotal,
            groupUnread = groupUnreadTotal,
        )
    }

    when (val current = overlay) {
        null -> when (tab) {
            MainTab.CHATS -> ConversationsScreen(
                myId = myId,
                onOpenSearch = { overlay = Overlay.Search },
                onOpenChat = { id, other -> overlay = Overlay.Chat(id, other) },
                onOpenProfile = { overlay = Overlay.EditProfile },
                onOpenSettings = { overlay = Overlay.Settings },
                onCreateGroup = { overlay = Overlay.CreateGroup },
                onOpenGroup = { group -> overlay = Overlay.GroupChat(group.id, group.name) },
                bottomBar = bottomBar,
            )
            MainTab.FRIENDS -> FriendsScreen(
                friends = friends,
                onOpenSearch = { overlay = Overlay.Search },
                onOpenQr = { overlay = Overlay.Qr },
                onOpenChat = { openChat(it) },
                bottomBar = bottomBar,
            )
            MainTab.GROUPS -> GroupsScreen(
                myId = myId,
                onOpenGroup = { group -> overlay = Overlay.GroupChat(group.id, group.name) },
                onCreateGroup = { overlay = Overlay.CreateGroup },
                bottomBar = bottomBar,
            )
            MainTab.CALLS -> CallsScreen(
                myId = myId,
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
        Overlay.CreateGroup -> CreateGroupScreen(
            friends = friends.friends,
            onCreated = { id, name -> overlay = Overlay.GroupChat(id, name) },
            onBack = { overlay = null },
        )
        is Overlay.GroupChat -> GroupChatScreen(
            myId = myId,
            groupId = current.groupId,
            initialName = current.name,
            friends = friends.friends,
            onBack = { overlay = null },
            onOpenChat = { openChat(it) },
        )
        Overlay.Qr -> QrScreen(
            myId = myId,
            friends = friends,
            onOpenChat = { openChat(it) },
            onBack = { overlay = null },
        )
        Overlay.Settings -> SettingsScreen(
            onBack = { overlay = null },
            onOpenProfile = { overlay = Overlay.EditProfile },
        )
        is Overlay.Chat -> ChatScreen(
            myId = myId,
            conversationId = current.conversationId,
            other = current.other,
            friends = friends,
            onBack = { overlay = null },
            onOpenChat = { openChat(it) },
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
