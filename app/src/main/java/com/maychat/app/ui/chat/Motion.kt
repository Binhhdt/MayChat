package com.maychat.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// =====================================================================
// "Is typing": three dots that hop one after the other, then the text,
// in a small pill above the text box (like Zalo).
// =====================================================================

@Composable
fun TypingLine(text: String) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                // One hop every 900 ms; each dot starts 150 ms after the one before.
                val lift by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 900
                            0f at index * 150
                            1f at index * 150 + 180
                            0f at index * 150 + 360
                            0f at 900
                        },
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "dot$index",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 1.5.dp)
                        .size(6.dp)
                        .graphicsLayer {
                            translationY = -lift * 4.dp.toPx()
                            alpha = 0.45f + 0.55f * lift
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// =====================================================================
// Reactions that fly: every tap on a reaction lets one emoji rise from
// the message to the top of the screen, swaying and fading (like the
// hearts in Zalo).
// =====================================================================

// One flying emoji. start: where it begins, in the coordinates of the
// whole screen. sway: -1..1, how far and to which side it swings.
class ReactionBurst(val id: Long, val emoji: String, val start: Offset, val sway: Float)

// Adds one flying emoji to the list (each gets its own id and swing).
fun SnapshotStateList<ReactionBurst>.fly(emoji: String, start: Offset) {
    if (start == Offset.Zero) return
    // Not more than 40 at a time, however fast the tapping is.
    if (size >= 40) removeAt(0)
    val id = System.nanoTime()
    add(ReactionBurst(id, emoji, start, ((id / 1000) % 200 - 100) / 100f))
}

// Draws the flying emojis over everything else on the screen. Put it last
// in a screen, so it lies above the chat.
@Composable
fun ReactionBurstLayer(bursts: SnapshotStateList<ReactionBurst>) {
    if (bursts.isEmpty()) return
    val density = LocalDensity.current
    val swing = with(density) { 26.dp.toPx() }
    val half = with(density) { 16.dp.toPx() }
    Box(modifier = Modifier.fillMaxSize()) {
        bursts.toList().forEach { burst ->
            key(burst.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    progress.animateTo(1f, tween(durationMillis = 1500, easing = LinearOutSlowInEasing))
                    bursts.remove(burst)
                }
                val p = progress.value
                Text(
                    burst.emoji,
                    fontSize = 26.sp,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (burst.start.x - half + burst.sway * swing * sin(p * 2.5f * PI.toFloat())).roundToInt(),
                                // Rises almost to the top edge.
                                (burst.start.y - half - p * burst.start.y * 0.92f).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            alpha = (1f - p * p).coerceIn(0f, 1f)
                            scaleX = 0.7f + 0.7f * p
                            scaleY = 0.7f + 0.7f * p
                        },
                )
            }
        }
    }
}
