package com.maychat.app.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.maychat.app.R
import com.maychat.app.ui.common.Avatar
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private val Ground = Color(0xFF10151C)
private val Tile = Color(0xFF1D2530)
private val Soft = Color(0xFFB8C2CF)
private val Green = Color(0xFF1E8E4A)
private val Red = Color(0xFFC8372D)

// Gives a function "make sure the call may use the microphone (and, for a
// video call, ask for the camera too), then do this". The microphone is
// required; without the camera a video call still works, the others just
// do not see me.
@Composable
fun rememberGroupCallGate(onRefused: () -> Unit = {}): (video: Boolean, then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val then = waiting
        waiting = null
        val micAllowed = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micAllowed) then?.invoke() else onRefused()
    }
    return { video, then ->
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) {
            then()
        } else {
            waiting = then
            ask.launch(wanted.toTypedArray())
        }
    }
}

// Everything of group calls that is drawn above the app: the ringing
// screen, the call screen (or the bar back to it), and a short notice.
@Composable
fun GroupCallLayer() {
    val call = GroupCallManager.ui
    val ring = GroupCallManager.ring
    if (call != null) {
        if (GroupCallManager.minimized) ReturnToGroupCallBar(call) else GroupCallScreen(call)
    } else if (ring != null) {
        IncomingGroupCallScreen(ring)
    }
    val notice = GroupCallManager.notice
    if (notice != null && call == null) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
            Text(
                notice,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun IncomingGroupCallScreen(ring: GroupRing) {
    var micRefused by remember { mutableStateOf(false) }
    val gate = rememberGroupCallGate(onRefused = { micRefused = true })
    // Back does nothing while it rings: use one of the two buttons.
    BackHandler(enabled = true) {}
    Column(
        modifier = Modifier.fillMaxSize().background(Ground).safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Avatar(name = ring.groupName, online = false, size = 120.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            ring.groupName,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${ring.callerName} đang gọi nhóm" + if (ring.video) " (video)…" else " (thoại)…",
            color = Soft,
            textAlign = TextAlign.Center,
        )
        if (micRefused) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Cần cho phép dùng micro để tham gia cuộc gọi.",
                color = Color(0xFFFFB4A9),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton("Từ chối", Red, R.drawable.ic_call) { GroupCallManager.clearRing() }
            RoundButton("Tham gia", Green, if (ring.video) R.drawable.ic_videocam else R.drawable.ic_call) {
                gate(ring.video) { GroupCallManager.acceptRing() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GroupCallScreen(call: GroupCallUi) {
    val view = LocalView.current
    // Back puts the call screen aside; the call goes on.
    BackHandler(enabled = true) { GroupCallManager.minimize() }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.callId) {
        while (true) {
            elapsedMs = System.currentTimeMillis() - call.joinedAtMs
            delay(500)
        }
    }

    val peers = GroupCallManager.peers
    Column(modifier = Modifier.fillMaxSize().background(Ground).safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹  Tin nhắn",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable { GroupCallManager.minimize() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    call.groupName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (peers.isEmpty()) {
                        "Đang chờ người khác tham gia…"
                    } else {
                        "${peers.size + 1} người · ${clock(elapsedMs)}"
                    },
                    color = Soft,
                    fontSize = 13.sp,
                )
            }
        }

        // The tiles: me first, then the others. One column up to two
        // people, two columns from three.
        val tiles: List<GroupPeer?> = listOf<GroupPeer?>(null) + peers
        val columns = if (tiles.size <= 2) 1 else 2
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tiles.chunked(columns).forEach { rowTiles ->
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowTiles.forEach { peer ->
                        key(peer?.id ?: "me") {
                            Box(modifier = Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp))) {
                                if (peer == null) MyTile(call) else PeerTile(peer)
                            }
                        }
                    }
                    // An odd number: keep the last tile the size of the others.
                    if (rowTiles.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Chip(if (GroupCallManager.muted) "Mic tắt" else "Tắt mic", GroupCallManager.muted) {
                GroupCallManager.toggleMute()
            }
            if (call.video && GroupCallManager.hasCamera) {
                Chip(if (GroupCallManager.cameraOn) "Tắt camera" else "Camera tắt", !GroupCallManager.cameraOn) {
                    GroupCallManager.toggleCamera()
                }
                Chip("Đổi camera", false) { GroupCallManager.switchCamera() }
            }
            Chip(if (GroupCallManager.speakerOn) "Loa ngoài" else "Loa trong", GroupCallManager.speakerOn) {
                GroupCallManager.toggleSpeaker()
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
            RoundButton("Rời cuộc gọi", Red, R.drawable.ic_call) { GroupCallManager.leave() }
        }
    }
}

@Composable
private fun MyTile(call: GroupCallUi) {
    Box(modifier = Modifier.fillMaxSize().background(Tile), contentAlignment = Alignment.Center) {
        if (call.video && GroupCallManager.cameraOn) {
            GroupVideoView(GroupCallManager.localSink, mirror = GroupCallManager.frontCamera)
        } else {
            Avatar(name = "Bạn", online = false, size = 72.dp)
        }
        TileLabel("Bạn", micOff = GroupCallManager.muted, waiting = false)
    }
}

@Composable
private fun PeerTile(peer: GroupPeer) {
    Box(modifier = Modifier.fillMaxSize().background(Tile), contentAlignment = Alignment.Center) {
        val sink = GroupCallManager.sinkOf(peer.id)
        if (peer.hasVideo && peer.camOn && peer.connected && sink != null) {
            GroupVideoView(sink, mirror = false)
        } else {
            Avatar(name = peer.name, online = false, size = 72.dp, avatarPath = peer.avatarPath)
        }
        TileLabel(peer.name, micOff = !peer.micOn, waiting = !peer.connected)
    }
}

// Name (and "mic off" / "connecting") at the bottom of a tile.
@Composable
private fun TileLabel(name: String, micOff: Boolean, waiting: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomStart) {
        Text(
            name.take(22) + (if (micOff) "  · mic tắt" else "") + (if (waiting) "  · đang kết nối…" else ""),
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// One video picture. The view is created once, attached to the sink while
// it is on screen, and released when it leaves.
@Composable
private fun GroupVideoView(sink: ProxySink, mirror: Boolean) {
    val egl = GroupCallManager.eglContext ?: return
    val context = LocalContext.current
    val renderer = remember(sink) {
        SurfaceViewRenderer(context).apply {
            init(egl, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(renderer) {
        sink.target = renderer
        onDispose {
            if (sink.target === renderer) sink.target = null
            runCatching { renderer.release() }
        }
    }
    AndroidView(factory = { renderer }, update = { it.setMirror(mirror) }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.Black else Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun RoundButton(label: String, color: Color, icon: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

// Shown over the app while the group call screen is put aside.
@Composable
private fun ReturnToGroupCallBar(call: GroupCallUi) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 60.dp)
                .clip(RoundedCornerShape(50))
                .background(Green)
                .clickable { GroupCallManager.restore() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (call.video) R.drawable.ic_videocam else R.drawable.ic_call),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                call.groupName.take(16) + " · ${GroupCallManager.peers.size + 1} người",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            Text("Trở lại cuộc gọi", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
