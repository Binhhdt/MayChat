package com.maychat.app.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Friendship
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// How another user relates to me.
enum class Relation { NONE, FRIEND, REQUEST_SENT, REQUEST_RECEIVED, BLOCKED }

// Holds my friends, friend requests and block list, and performs the
// friend/block actions. One instance is shared by all screens after login.
class FriendsState(
    private val myId: String,
    private val scope: CoroutineScope,
) {
    private var friendships by mutableStateOf<List<Friendship>>(emptyList())
    private var profiles by mutableStateOf<Map<String, Profile>>(emptyMap())
    private var blockedIds by mutableStateOf<Set<String>>(emptySet())

    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private fun otherId(f: Friendship): String = if (f.userA == myId) f.userB else f.userA

    private fun profilesWhere(test: (Friendship) -> Boolean): List<Profile> =
        friendships.filter(test)
            .mapNotNull { profiles[otherId(it)] }
            .sortedBy { it.displayName.lowercase() }

    val friends: List<Profile>
        get() = profilesWhere { it.status == "accepted" }

    // Requests other people sent to me.
    val incoming: List<Profile>
        get() = profilesWhere { it.status == "pending" && it.requestedBy != myId }

    // Requests I sent that are not answered yet.
    val sent: List<Profile>
        get() = profilesWhere { it.status == "pending" && it.requestedBy == myId }

    val blocked: List<Profile>
        get() = blockedIds.mapNotNull { profiles[it] }.sortedBy { it.displayName.lowercase() }

    fun relation(userId: String): Relation {
        if (userId in blockedIds) return Relation.BLOCKED
        val f = friendships.firstOrNull { otherId(it) == userId } ?: return Relation.NONE
        return when {
            f.status == "accepted" -> Relation.FRIEND
            f.requestedBy == myId -> Relation.REQUEST_SENT
            else -> Relation.REQUEST_RECEIVED
        }
    }

    suspend fun reload() {
        attempt {
            val rows = ChatRepository.loadFriendships()
            val blockedList = ChatRepository.loadBlockedIds()
            val ids = (rows.map { otherId(it) } + blockedList).distinct()
            val people = ChatRepository.loadProfiles(ids).associateBy { it.id }
            Triple(rows, blockedList.toSet(), people)
        }.onSuccess { (rows, blockedSet, people) ->
            friendships = rows
            blockedIds = blockedSet
            profiles = people
            loaded = true
        }.onFailure {
            error = it.toUserMessage()
        }
    }

    // Runs one action on the server, then reloads so the screen shows the truth.
    private fun act(action: suspend () -> Unit) {
        scope.launch {
            attempt { action() }
                .onSuccess { error = null }
                .onFailure { error = it.toUserMessage() }
            reload()
        }
    }

    fun sendRequest(userId: String) = act { ChatRepository.sendFriendRequest(userId) }
    fun accept(userId: String) = act { ChatRepository.respondFriendRequest(userId, accept = true) }
    fun reject(userId: String) = act { ChatRepository.respondFriendRequest(userId, accept = false) }
    fun remove(userId: String) = act { ChatRepository.removeFriend(userId) }
    fun block(userId: String) = act { ChatRepository.blockUser(userId) }
    fun unblock(userId: String) = act { ChatRepository.unblockUser(userId) }
}
