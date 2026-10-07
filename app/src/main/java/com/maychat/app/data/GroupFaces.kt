package com.maychat.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The members whose pictures make up the picture of a group that has no
// picture of its own (like Zalo): the first four, and how many there are.
data class GroupFace(val name: String, val avatarPath: String?)

data class GroupFaceSet(val faces: List<GroupFace>, val memberCount: Int)

// Kept in memory only, read from the server; erased on sign-out.
object GroupFaces {
    private val _all = MutableStateFlow<Map<String, GroupFaceSet>>(emptyMap())
    val all: StateFlow<Map<String, GroupFaceSet>> = _all.asStateFlow()

    private val loadedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lock = Mutex()

    // Groups asked for within the same moment (a whole list being drawn)
    // are read from the server together, in one go.
    private val waiting = LinkedHashSet<String>()

    // Makes sure the faces of these groups are known. A group is read again
    // when what is known is older than a minute (members come and go), or
    // at once with force = true.
    suspend fun ensure(groupIds: List<String>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val wanted = groupIds.distinct().filter { force || now - (loadedAt[it] ?: 0L) > 60_000 }
        if (wanted.isEmpty()) return
        synchronized(waiting) { waiting.addAll(wanted) }
        kotlinx.coroutines.delay(80)
        lock.withLock {
            val batch = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
            if (batch.isEmpty()) return
            attempt {
                @Suppress("NAME_SHADOWING")
                val wanted = batch
                val members = ChatRepository.loadGroupMemberIds(wanted)
                val shown = members.values.flatMap { it.take(4) }.distinct()
                val profiles = ChatRepository.loadProfiles(shown).associateBy { it.id }
                val fresh = wanted.associateWith { id ->
                    val ids = members[id] ?: emptyList()
                    GroupFaceSet(
                        faces = ids.take(4).mapNotNull { profiles[it] }.map { GroupFace(it.displayName, it.avatarPath) },
                        memberCount = ids.size,
                    )
                }
                _all.value = _all.value + fresh
                val stamp = System.currentTimeMillis()
                wanted.forEach { loadedAt[it] = stamp }
            }
        }
    }

    fun clear() {
        loadedAt.clear()
        _all.value = emptyMap()
    }
}
