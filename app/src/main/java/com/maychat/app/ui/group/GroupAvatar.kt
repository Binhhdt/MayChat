package com.maychat.app.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maychat.app.data.GroupFaces
import com.maychat.app.ui.common.Avatar

// The picture of a group. When the group has its own picture, that one.
// Otherwise it is put together from the pictures of its members, like in
// Zalo: 2 side by side, 3 as a triangle, 4 as a square; with more than 4
// members the last place shows how many more there are.
@Composable
fun GroupAvatar(groupId: String, name: String, avatarPath: String?, size: Dp = 48.dp) {
    if (avatarPath != null) {
        Avatar(name = name, online = false, size = size, avatarPath = avatarPath)
        return
    }
    val all by GroupFaces.all.collectAsState()
    LaunchedEffect(groupId) { GroupFaces.ensure(listOf(groupId)) }
    val set = all[groupId]
    val faces = set?.faces ?: emptyList()
    if (set == null || faces.size < 2) {
        // Not known yet, or a group of one: the letter (or that one face).
        val only = faces.firstOrNull()
        Avatar(name = only?.name ?: name, online = false, size = size, avatarPath = only?.avatarPath)
        return
    }

    // With more than four members: three faces and "+N".
    val more = if (set.memberCount > 4) set.memberCount - 3 else 0
    val shown = if (more > 0) faces.take(3) else faces.take(4)
    val places = shown.size + if (more > 0) 1 else 0
    val small = if (places == 2) size * 0.56f else size * 0.5f

    Box(modifier = Modifier.size(size)) {
        // Where each small circle sits inside the big square.
        val spots: List<Alignment> = when (places) {
            2 -> listOf(Alignment.CenterStart, Alignment.CenterEnd)
            3 -> listOf(Alignment.TopCenter, Alignment.BottomStart, Alignment.BottomEnd)
            else -> listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)
        }
        shown.forEachIndexed { index, face ->
            Box(
                modifier = Modifier
                    .align(spots[index])
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Avatar(name = face.name, online = false, size = small, avatarPath = face.avatarPath)
            }
        }
        if (more > 0) {
            Box(
                modifier = Modifier
                    .align(spots[3])
                    .size(small)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (more > 99) "99+" else more.toString(),
                    fontSize = (small.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
