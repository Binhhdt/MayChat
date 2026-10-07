package com.maychat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.ui.common.Avatar

// Who put which emoji on one message.
data class Reactor(val userId: String, val emoji: String)

// Sheet that slides over the bottom of the chat when the reactions of a
// message are tapped, like in Zalo: the quick reactions on top, then tabs
// ("Tất cả 2", one per emoji) and the people who reacted.
@Composable
fun ReactionsSheet(
    reactors: List<Reactor>,
    myId: String,
    nameOf: (String) -> String,
    avatarOf: (String) -> String?,
    onReact: (String) -> Unit,
    onClose: () -> Unit,
) {
    // null = "Tất cả"; otherwise only the people who chose this emoji.
    var tab by remember { mutableStateOf<String?>(null) }
    val counts = reactors.groupBy { it.emoji }.map { it.key to it.value.size }.sortedByDescending { it.second }
    val activeTab = tab?.takeIf { wanted -> counts.any { it.first == wanted } }
    val shown = (if (activeTab == null) reactors else reactors.filter { it.emoji == activeTab })
        // Me first, then the others.
        .sortedByDescending { it.userId == myId }
    val mine = reactors.firstOrNull { it.userId == myId }?.emoji

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Taps on the sheet itself must not close it.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // Quick reactions: tap to set mine, tap mine again to remove it.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        QUICK_REACTIONS.forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (emoji == mine) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable { onReact(emoji) }
                                    .padding(7.dp),
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        // Tabs.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SheetTab("Tất cả ${reactors.size}", activeTab == null) { tab = null }
                            counts.forEach { (emoji, count) ->
                                SheetTab("$emoji $count", activeTab == emoji) { tab = emoji }
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                            items(shown, key = { it.userId }) { reactor ->
                                val isMe = reactor.userId == myId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // My own line: tap to take my reaction back.
                                        .clickable(enabled = isMe) { onReact(reactor.emoji) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(
                                        name = nameOf(reactor.userId),
                                        online = false,
                                        size = 44.dp,
                                        avatarPath = avatarOf(reactor.userId),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (isMe) "Bạn" else nameOf(reactor.userId),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isMe) {
                                            Text(
                                                "Chạm để gỡ cảm xúc",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Text(reactor.emoji, fontSize = 22.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else null,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
