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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.maychat.app.R
import com.maychat.app.ui.common.Avatar
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

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
    // Accepting a VIDEO call asks for microphone and camera together. The
    // microphone is required; without the camera the call still works, the
    // other person just does not see me.
    val askMicrophoneAndCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) CallManager.accept() else micDenied = true
    }

    // A video call that is being set up or running has its own screen.
    if (call.video && call.phase != CallPhase.INCOMING && call.phase != CallPhase.ENDED) {
        VideoCallScreen(call)
        return
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
        CallPhase.INCOMING -> if (call.video) "đang gọi video cho bạn…" else "đang gọi cho bạn…"
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
                    // A camera for a video call, a handset for a voice call.
                    painter = painterResource(if (call.video) R.drawable.ic_videocam else R.drawable.ic_call),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (call.video) 20.dp else 16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (call.video) "Cuộc gọi video MayChat" else "Cuộc gọi thoại MayChat",
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

            if (call.video) {
                // Large camera mark, so a video call is not mistaken for a voice call.
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CallAmber)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_videocam),
                        contentDescription = null,
                        tint = CallAmberText,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("GỌI VIDEO", color = CallAmberText, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
            } else {
                Spacer(Modifier.height(36.dp))
            }
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
                        label = if (call.video) "Nghe video" else "Nghe máy",
                        color = AcceptGreen,
                        hangUpIcon = false,
                        icon = if (call.video) R.drawable.ic_videocam else R.drawable.ic_call,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            val cameraGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            when {
                                call.video && !(granted && cameraGranted) -> askMicrophoneAndCamera.launch(
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                                )
                                granted -> CallManager.accept()
                                else -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            }
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
private fun RoundCallButton(
    label: String,
    color: Color,
    hangUpIcon: Boolean,
    icon: Int = R.drawable.ic_call,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(icon),
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

// ---------------------------------------------------------------------
// Video call
// ---------------------------------------------------------------------

// Screen of a video call: the other person fills the screen, my own camera
// is the small picture in the corner, the buttons are at the bottom.
@Composable
private fun VideoCallScreen(call: CallUi) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.phase, call.connectedAtMs) {
        while (call.phase == CallPhase.CONNECTED) {
            elapsedMs = System.currentTimeMillis() - call.connectedAtMs
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // The other person's picture.
        if (CallManager.remoteVideo) {
            CallVideoView(
                sink = CallManager.remoteSink,
                mirror = false,
                onTop = false,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No picture (yet): name and what is happening, like a voice call.
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(
                    name = call.peer.displayName,
                    online = false,
                    size = 120.dp,
                    avatarPath = call.peer.avatarPath,
                )
                Spacer(Modifier.height(20.dp))
                Text(call.peer.displayName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (call.phase == CallPhase.CONNECTED) "Đang chờ hình ảnh…" else call.message,
                    color = CallSoftText,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Name and call length at the top.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(call.peer.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                if (call.phase == CallPhase.CONNECTED) formatCallTime(elapsedMs) else call.message,
                color = CallSoftText,
                fontSize = 13.sp,
            )
        }

        // My own camera, small, in the top right corner.
        if (CallManager.cameraOn) {
            CallVideoView(
                sink = CallManager.localSink,
                mirror = CallManager.frontCamera,
                onTop = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .size(width = 108.dp, height = 150.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (CallManager.debugLine.isNotEmpty()) {
                Text(
                    CallManager.debugLine,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToggleChip(
                    label = if (CallManager.muted) "Mic tắt" else "Tắt mic",
                    active = CallManager.muted,
                    onClick = { CallManager.toggleMute() },
                )
                ToggleChip(
                    label = if (CallManager.cameraOn) "Tắt camera" else "Camera tắt",
                    active = !CallManager.cameraOn,
                    onClick = { CallManager.toggleCamera() },
                )
                ToggleChip(
                    label = "Đổi camera",
                    active = false,
                    onClick = { CallManager.switchCamera() },
                )
            }
            Spacer(Modifier.height(16.dp))
            RoundCallButton(
                label = "Kết thúc",
                color = DeclineRed,
                hangUpIcon = true,
                onClick = { CallManager.hangUp() },
            )
        }
    }
}

// One video picture. The view is created once, attached to the given sink
// while it is on screen, and released when it leaves.
@Composable
private fun CallVideoView(sink: ProxySink, mirror: Boolean, onTop: Boolean, modifier: Modifier) {
    val egl = CallManager.eglContext ?: return
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            // The small picture must be drawn above the large one.
            if (onTop) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(renderer) {
        sink.target = renderer
        onDispose {
            sink.target = null
            runCatching { renderer.release() }
        }
    }
    AndroidView(
        factory = { renderer },
        update = { it.setMirror(mirror) },
        modifier = modifier,
    )
}
