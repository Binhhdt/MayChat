package com.maychat.app.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.maychat.app.R
import com.maychat.app.ui.common.Avatar
import kotlinx.coroutines.delay

// Colors of the call screen (the same in light and dark mode).
private val CallGround = Color(0xFF083F40)
private val CallSoftText = Color(0xFFCFE9E6)
private val CallAmber = Color(0xFFF2A93B)
private val CallAmberText = Color(0xFF2B1B00)
private val AcceptGreen = Color(0xFF1E8E4A)
private val DeclineRed = Color(0xFFC8372D)

// Full-screen call screen, drawn on top of whatever screen is open.
// Shown for outgoing, incoming and running calls.
@Composable
fun CallScreen(call: CallUi) {
    val context = LocalContext.current
    val view = LocalView.current
    var micDenied by remember { mutableStateOf(false) }

    // The Back button must not close the call screen by accident.
    BackHandler(enabled = true) { }

    // Keep the display on while the call screen is showing.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Accepting needs the microphone permission.
    val askMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) CallManager.accept() else micDenied = true
    }

    // Call length, counted while connected.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.phase, call.connectedAtMs) {
        while (call.phase == CallPhase.CONNECTED) {
            elapsedMs = System.currentTimeMillis() - call.connectedAtMs
            delay(500)
        }
    }

    val statusText = when (call.phase) {
        CallPhase.CONNECTED -> formatCallTime(elapsedMs)
        CallPhase.INCOMING -> "đang gọi cho bạn…"
        else -> call.message
    }

    Surface(modifier = Modifier.fillMaxSize(), color = CallGround, contentColor = Color.White) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            // Small label at the top.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Cuộc gọi thoại MayChat",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }

            Spacer(Modifier.height(36.dp))

            // Avatar with a soft ring around it: the person's picture when
            // they have one, otherwise their initial on an amber disc.
            Box(
                modifier = Modifier
                    .size(176.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (call.peer.avatarPath != null) {
                    Avatar(
                        name = call.peer.displayName,
                        online = false,
                        size = 136.dp,
                        avatarPath = call.peer.avatarPath,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .clip(CircleShape)
                            .background(CallAmber),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            call.peer.displayName.trim().take(1).uppercase().ifEmpty { "?" },
                            color = CallAmberText,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(36.dp))
            Text(
                call.peer.displayName,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.titleMedium,
                color = CallSoftText,
                textAlign = TextAlign.Center,
            )

            if (micDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Cần quyền micro để nghe máy. Bạn có thể bật trong Cài đặt của điện thoại.",
                    color = CallAmber,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            // Small diagnosis line: where the call set-up is right now.
            if (CallManager.debugLine.isNotEmpty() && call.phase != CallPhase.INCOMING) {
                Text(
                    CallManager.debugLine,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            when (call.phase) {
                CallPhase.INCOMING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    RoundCallButton(
                        label = "Từ chối",
                        color = DeclineRed,
                        hangUpIcon = true,
                        onClick = { CallManager.reject() },
                    )
                    RoundCallButton(
                        label = "Nghe máy",
                        color = AcceptGreen,
                        hangUpIcon = false,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) CallManager.accept() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    )
                }

                CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.CONNECTED -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ToggleChip(
                            label = if (CallManager.muted) "Mic đang tắt" else "Tắt mic",
                            active = CallManager.muted,
                            onClick = { CallManager.toggleMute() },
                        )
                        ToggleChip(
                            label = if (CallManager.speakerOn) "Loa ngoài đang bật" else "Loa ngoài",
                            active = CallManager.speakerOn,
                            onClick = { CallManager.toggleSpeaker() },
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    RoundCallButton(
                        label = "Kết thúc",
                        color = DeclineRed,
                        hangUpIcon = true,
                        onClick = { CallManager.hangUp() },
                    )
                }

                CallPhase.ENDED -> {}
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// Big round button with a phone icon and a label under it.
@Composable
private fun RoundCallButton(label: String, color: Color, hangUpIcon: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = label,
                    tint = Color.White,
                    // The same handset, turned face-down, means "hang up".
                    modifier = Modifier.size(32.dp).rotate(if (hangUpIcon) 135f else 0f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

// Pill-shaped switch for "mute" and "loudspeaker". White when switched on.
@Composable
private fun ToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) Color.White else Color.White.copy(alpha = 0.14f),
        contentColor = if (active) CallGround else Color.White,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

private fun formatCallTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
