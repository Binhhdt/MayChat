package com.maychat.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.SupabaseProvider
import com.maychat.app.data.attempt
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// =====================================================================
// Group calls (voice or video).
//
// There is no server in the middle: every phone in the call is connected
// DIRECTLY to every other phone (3 people = 3 connections, 4 people = 6).
// That is why a call is limited to a few people.
//
// How phones find each other: everybody in the call joins the live
// channel "gcall-<group id>" and says "hello" there every 2 seconds. Two
// phones that hear each other set up a connection; of the two, the one
// with the smaller user id makes the offer, so they never both do.
//
// The call is announced in the group by an ordinary text message whose
// "extra" is "gcall:<call id>:v" (video) or "gcall:<call id>:a" (voice).
// When the last person leaves, "gcallend:<call id>" is sent the same way.
//
// The one-to-one calls (CallManager) are not touched by any of this.
// =====================================================================

const val GROUP_CALL_VOICE_TEXT = "☎️ Cuộc gọi thoại nhóm"
const val GROUP_CALL_VIDEO_TEXT = "🎥 Cuộc gọi video nhóm"
const val GROUP_CALL_END_TEXT = "Cuộc gọi nhóm đã kết thúc"

// How many people at most (including me).
const val GROUP_CALL_MAX_VOICE = 6
const val GROUP_CALL_MAX_VIDEO = 4

// What a group message says about a call: its id and whether it is video.
data class GroupCallLink(val callId: String, val video: Boolean)

// "gcall:<id>:v" -> the call it announces; null for any other message.
fun groupCallLink(kind: String, extra: String?): GroupCallLink? {
    if (kind != "text" || extra == null || !extra.startsWith("gcall:")) return null
    val parts = extra.split(":")
    if (parts.size < 3 || parts[1].isBlank()) return null
    return GroupCallLink(parts[1], parts[2] == "v")
}

// "gcallend:<id>" -> the id of the call that is over; null otherwise.
fun groupCallEndId(kind: String, extra: String?): String? {
    if (kind != "text" || extra == null || !extra.startsWith("gcallend:")) return null
    return extra.removePrefix("gcallend:").takeIf { it.isNotBlank() }
}

// The running group call on this phone.
data class GroupCallUi(
    val groupId: String,
    val groupName: String,
    val callId: String,
    val video: Boolean,
    val joinedAtMs: Long,
)

// A group call that is ringing on this phone.
data class GroupRing(
    val groupId: String,
    val groupName: String,
    val callId: String,
    val video: Boolean,
    val callerName: String,
)

// One other person in the call, as the screen shows them.
data class GroupPeer(
    val id: String,
    val name: String,
    val avatarPath: String?,
    val connected: Boolean = false,
    val hasVideo: Boolean = false,   // their picture is arriving
    val micOn: Boolean = true,
    val camOn: Boolean = true,
)

object GroupCallManager {
    private val supabase get() = SupabaseProvider.client
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var ui by mutableStateOf<GroupCallUi?>(null)
        private set
    var ring by mutableStateOf<GroupRing?>(null)
        private set

    // The others in the call, in the order they were found.
    val peers = mutableStateListOf<GroupPeer>()

    var muted by mutableStateOf(false)
        private set
    var cameraOn by mutableStateOf(false)
        private set
    var frontCamera by mutableStateOf(true)
        private set
    var speakerOn by mutableStateOf(false)
        private set

    // The call screen is put aside to use the rest of the app.
    var minimized by mutableStateOf(false)
        private set

    // A short line shown after a call ("Không ai tham gia"...).
    var notice by mutableStateOf<String?>(null)
        private set

    fun minimize() {
        minimized = true
    }

    fun restore() {
        minimized = false
    }

    private var appContext: Context? = null
    private var myId = ""
    private var myName = ""

    // Called when somebody is logged in (see MayChatApp).
    fun attach(context: Context, userId: String, displayName: String) {
        appContext = context.applicationContext
        myId = userId
        myName = displayName
        // A call notification was tapped before the login was ready.
        val waiting = pendingRing
        pendingRing = null
        if (waiting != null && System.currentTimeMillis() - waiting.second < 45_000) startRing(waiting.first)
    }

    private var pendingRing: Pair<GroupRing, Long>? = null

    // Logged out: leave any call and stop ringing.
    fun detach() {
        leave()
        clearRing()
        pendingRing = null
        myId = ""
    }

    // ------------------------------------------------------------------
    // Everything of one connection to one other person
    // ------------------------------------------------------------------
    private class PeerLink(val id: String) {
        var pc: PeerConnection? = null
        var remoteTrack: VideoTrack? = null
        val sink = ProxySink()
        var lastSeenMs = System.currentTimeMillis()
        var remoteSet = false
        val waitingIce = ArrayList<IceCandidate>()
        var watchdog: Job? = null
        var tries = 0
        // I told this person "hello" directly after first hearing them.
        var greeted = false
    }

    private val links = HashMap<String, PeerLink>()
    private var memberProfiles: Map<String, Profile> = emptyMap()
    private var membersKnown = false

    // The video view of one person attaches to this.
    fun sinkOf(peerId: String): ProxySink? = links[peerId]?.sink
    val localSink = ProxySink()

    private var egl: EglBase? = null
    val eglContext: EglBase.Context? get() = egl?.eglBaseContext
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var channel: RealtimeChannel? = null
    private var channelJob: Job? = null
    private var helloJob: Job? = null
    private var iStarted = false
    private var everHadPeer = false
    private var aloneSinceMs = 0L

    // ------------------------------------------------------------------
    // Starting, joining, leaving
    // ------------------------------------------------------------------

    // Starts a NEW call in the group and tells the group about it.
    // The microphone permission must be granted.
    fun start(groupId: String, groupName: String, video: Boolean) {
        val callId = UUID.randomUUID().toString()
        if (!enter(groupId, groupName, callId, video, starter = true)) return
        main.launch {
            val sent = attempt {
                ChatRepository.sendGroupSpecial(
                    groupId,
                    "text",
                    if (video) GROUP_CALL_VIDEO_TEXT else GROUP_CALL_VOICE_TEXT,
                    "gcall:$callId:" + if (video) "v" else "a",
                )
            }
            if (sent.isFailure && ui?.callId == callId) {
                leave()
                showNotice("Không bắt đầu được cuộc gọi nhóm. Kiểm tra mạng rồi thử lại.")
            }
        }
    }

    // Joins a call that is already running in the group.
    fun join(groupId: String, groupName: String, callId: String, video: Boolean) {
        enter(groupId, groupName, callId, video, starter = false)
    }

    private fun enter(groupId: String, groupName: String, callId: String, video: Boolean, starter: Boolean): Boolean {
        val context = appContext ?: return false
        if (ui != null) {
            if (ui?.groupId == groupId) restore() else showNotice("Bạn đang ở trong một cuộc gọi nhóm khác.")
            return false
        }
        if (CallManager.ui != null) {
            showNotice("Bạn đang ở trong một cuộc gọi khác.")
            return false
        }
        clearRing()
        notice = null
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
            val eglBase = egl ?: EglBase.create().also { egl = it }
            val f = factory ?: PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
                .also { factory = it }

            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            muted = false
            // A group call is listened to on the loudspeaker.
            speakerOn = true
            applySpeaker()

            val source = f.createAudioSource(MediaConstraints())
            audioSource = source
            audioTrack = f.createAudioTrack("maychat-g-audio", source)

            cameraOn = false
            if (video) {
                val cameraAllowed = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (cameraAllowed) runCatching { startCamera(context, f, eglBase) }
            }
        } catch (e: Throwable) {
            releaseMedia()
            showNotice("Điện thoại không bắt đầu được cuộc gọi.")
            return false
        }

        iStarted = starter
        everHadPeer = false
        aloneSinceMs = System.currentTimeMillis()
        minimized = false
        membersKnown = false
        memberProfiles = emptyMap()
        ui = GroupCallUi(groupId, groupName, callId, video, System.currentTimeMillis())
        CallService.start(context, groupName)

        // Who is in the group: names and pictures for the screen, and
        // nobody else is listened to on the call channel.
        main.launch {
            attempt { ChatRepository.loadGroupMembers(groupId) }.onSuccess { list ->
                if (ui?.callId == callId) {
                    memberProfiles = list.associateBy { it.id }
                    membersKnown = true
                    for (i in peers.indices) {
                        val known = memberProfiles[peers[i].id] ?: continue
                        peers[i] = peers[i].copy(name = known.displayName, avatarPath = known.avatarPath)
                    }
                }
            }
        }
        openChannel(groupId)
        startHello(callId)
        return true
    }

    // Leaves the call (it goes on for the others).
    fun leave() {
        val current = ui ?: return
        val lastOne = links.isEmpty()
        sendSignal(buildJsonObject {
            put("t", "bye")
            put("from", myId)
        })
        // The last person out tells the group that the call is over.
        if (lastOne) {
            main.launch {
                attempt {
                    ChatRepository.sendGroupSpecial(
                        current.groupId,
                        "text",
                        GROUP_CALL_END_TEXT,
                        "gcallend:${current.callId}",
                    )
                }
            }
        }
        helloJob?.cancel()
        helloJob = null
        links.values.toList().forEach { closeLink(it) }
        links.clear()
        peers.clear()

        // Let the "bye" go out before the channel is closed.
        val oldChannel = channel
        val oldJob = channelJob
        channel = null
        channelJob = null
        main.launch {
            delay(400)
            oldJob?.cancel()
            oldChannel?.let { ch -> runCatching { supabase.realtime.removeChannel(ch) } }
        }

        releaseMedia()
        appContext?.let { CallService.stop(it) }
        ui = null
        minimized = false
    }

    private fun releaseMedia() {
        runCatching { videoTrack?.removeSink(localSink) }
        val camera = capturer
        val helper = captureHelper
        val cameraSource = videoSource
        val cameraTrack = videoTrack
        val microphone = audioSource
        val microphoneTrack = audioTrack
        capturer = null
        captureHelper = null
        videoSource = null
        videoTrack = null
        audioSource = null
        audioTrack = null
        cameraOn = false
        runCatching { camera?.stopCapture() }
        runCatching { cameraTrack?.dispose() }
        runCatching { microphoneTrack?.dispose() }
        runCatching { camera?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { microphone?.dispose() }

        appContext?.let { context ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                audio.isSpeakerphoneOn = false
                audio.mode = AudioManager.MODE_NORMAL
            }
        }
        muted = false
        speakerOn = false
    }

    private fun showNotice(text: String) {
        notice = text
        main.launch {
            delay(4_000)
            if (notice == text) notice = null
        }
    }

    // ------------------------------------------------------------------
    // My microphone, camera, loudspeaker
    // ------------------------------------------------------------------

    private fun startCamera(context: Context, f: PeerConnectionFactory, eglBase: EglBase) {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        val camera = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("maychat-g-camera", eglBase.eglBaseContext)
        val source = f.createVideoSource(false)
        camera.initialize(helper, context, source.capturerObserver)
        // Smaller than in a one-to-one call: the picture is sent to
        // everybody separately.
        camera.startCapture(480, 360, 15)
        val track = f.createVideoTrack("maychat-g-video", source)
        track.addSink(localSink)
        capturer = camera
        captureHelper = helper
        videoSource = source
        videoTrack = track
        frontCamera = enumerator.isFrontFacing(name)
        cameraOn = true
    }

    fun toggleMute() {
        muted = !muted
        runCatching { audioTrack?.setEnabled(!muted) }
        sayHello()
    }

    fun toggleCamera() {
        val track = videoTrack ?: return
        cameraOn = !cameraOn
        runCatching { track.setEnabled(cameraOn) }
        sayHello()
    }

    // True when my camera is part of this call (a video call, permission given).
    val hasCamera: Boolean get() = videoTrack != null

    fun switchCamera() {
        val camera = capturer ?: return
        runCatching {
            camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    main.launch { frontCamera = isFront }
                }

                override fun onCameraSwitchError(error: String?) {}
            })
        }
    }

    fun toggleSpeaker() {
        speakerOn = !speakerOn
        applySpeaker()
    }

    private fun applySpeaker() {
        val context = appContext ?: return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                if (speakerOn) {
                    val speaker = audio.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) audio.setCommunicationDevice(speaker)
                } else {
                    audio.clearCommunicationDevice()
                }
            }
            audio.isSpeakerphoneOn = speakerOn
        }
    }

    // ------------------------------------------------------------------
    // The call channel
    // ------------------------------------------------------------------

    private fun openChannel(groupId: String) {
        val ch = supabase.channel("gcall-$groupId")
        channel = ch
        channelJob = main.launch {
            val signals = ch.broadcastFlow<JsonObject>(event = "s")
            launch { signals.collect { runCatching { onSignal(it) } } }
            delay(200)
            // Keep the channel joined: join now, and again whenever it drops.
            while (isActive) {
                if (ch.status.value != RealtimeChannel.Status.SUBSCRIBED) {
                    runCatching { withTimeoutOrNull(8_000) { ch.subscribe(blockUntilSubscribed = true) } }
                    if (ch.status.value == RealtimeChannel.Status.SUBSCRIBED) sayHello()
                }
                delay(3_000)
            }
        }
    }

    private fun sendSignal(payload: JsonObject) {
        val ch = channel ?: return
        main.launch { runCatching { ch.broadcast(event = "s", message = payload) } }
    }

    private fun sayHello() {
        val current = ui ?: return
        sendSignal(buildJsonObject {
            put("t", "hello")
            put("from", myId)
            put("name", myName)
            put("call", current.callId)
            put("mic", !muted)
            put("cam", cameraOn)
        })
    }

    // "Hello" every 2 seconds; and every time: who has gone silent, and
    // have I been alone for too long.
    private fun startHello(callId: String) {
        helloJob?.cancel()
        helloJob = main.launch {
            while (isActive && ui?.callId == callId) {
                sayHello()
                val now = System.currentTimeMillis()
                links.values.filter { now - it.lastSeenMs > 12_000 }.forEach { removePeer(it.id) }
                if (links.isNotEmpty()) {
                    aloneSinceMs = now
                } else {
                    // Nobody (else) here: the one who started waits a minute
                    // for the first person, everybody else half a minute.
                    val patience = if (iStarted && !everHadPeer) 60_000 else 30_000
                    if (now - aloneSinceMs > patience) {
                        val text = when {
                            everHadPeer -> "Mọi người đã rời cuộc gọi nhóm."
                            iStarted -> "Không ai tham gia cuộc gọi nhóm."
                            else -> "Cuộc gọi nhóm đã kết thúc."
                        }
                        leave()
                        showNotice(text)
                        return@launch
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun text(json: JsonObject, key: String): String? =
        runCatching { json[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun onSignal(json: JsonObject) {
        val current = ui ?: return
        val from = text(json, "from") ?: return
        if (from == myId || from.isBlank()) return
        // Only members of the group are listened to.
        if (membersKnown && from !in memberProfiles) return
        val to = text(json, "to")
        if (to != null && to != myId) return

        when (text(json, "t")) {
            "hello" -> {
                var link = links[from]
                if (link == null) {
                    val limit = if (current.video) GROUP_CALL_MAX_VIDEO else GROUP_CALL_MAX_VOICE
                    if (links.size + 1 >= limit) return   // full: this person is not connected
                    link = addPeer(from, text(json, "name"))
                    // The one with the smaller id makes the offer.
                    if (myId < from) makeOffer(link)
                }
                link.lastSeenMs = System.currentTimeMillis()
                if (!link.greeted) {
                    // So that they hear of me at once, not at my next turn.
                    link.greeted = true
                    sayHello()
                }
                updatePeer(from) {
                    it.copy(
                        micOn = text(json, "mic") != "false",
                        camOn = text(json, "cam") != "false",
                    )
                }
            }

            "offer" -> {
                val sdp = text(json, "sdp") ?: return
                val link = links[from] ?: addPeer(from, text(json, "name"))
                link.lastSeenMs = System.currentTimeMillis()
                main.launch {
                    runCatching {
                        // A second offer means the first try did not work:
                        // start this connection again from nothing.
                        if (link.remoteSet || link.pc == null) resetConnection(link)
                        val pc = link.pc ?: return@launch
                        pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, sdp))
                        link.remoteSet = true
                        drainIce(link)
                        val answer = pc.createAnswerSuspend()
                        pc.setLocalSuspend(answer)
                        sendSignal(buildJsonObject {
                            put("t", "answer")
                            put("from", myId)
                            put("to", from)
                            put("sdp", answer.description)
                        })
                    }
                }
            }

            "answer" -> {
                val sdp = text(json, "sdp") ?: return
                val link = links[from] ?: return
                val pc = link.pc ?: return
                if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                main.launch {
                    runCatching {
                        pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                        link.remoteSet = true
                        drainIce(link)
                    }
                }
            }

            "ice" -> {
                val link = links[from] ?: return
                val candidate = IceCandidate(
                    text(json, "mid"),
                    text(json, "line")?.toIntOrNull() ?: 0,
                    text(json, "cand") ?: return,
                )
                if (link.remoteSet) {
                    runCatching { link.pc?.addIceCandidate(candidate) }
                } else {
                    link.waitingIce.add(candidate)
                }
            }

            "bye" -> removePeer(from)
        }
    }

    // ------------------------------------------------------------------
    // Connections
    // ------------------------------------------------------------------

    private fun addPeer(id: String, saidName: String?): PeerLink {
        val link = PeerLink(id)
        links[id] = link
        val known = memberProfiles[id]
        peers.add(
            GroupPeer(
                id = id,
                name = known?.displayName ?: saidName?.take(50) ?: "Thành viên",
                avatarPath = known?.avatarPath,
            ),
        )
        everHadPeer = true
        aloneSinceMs = System.currentTimeMillis()
        runCatching { link.pc = createConnection(link) }
        return link
    }

    private fun updatePeer(id: String, change: (GroupPeer) -> GroupPeer) {
        val index = peers.indexOfFirst { it.id == id }
        if (index < 0) return
        val changed = change(peers[index])
        if (changed != peers[index]) peers[index] = changed
    }

    private fun removePeer(id: String) {
        val link = links.remove(id) ?: return
        closeLink(link)
        peers.removeAll { it.id == id }
        aloneSinceMs = System.currentTimeMillis()
    }

    private fun closeLink(link: PeerLink) {
        link.watchdog?.cancel()
        link.watchdog = null
        runCatching { link.remoteTrack?.removeSink(link.sink) }
        link.remoteTrack = null
        val pc = link.pc
        link.pc = null
        link.remoteSet = false
        link.waitingIce.clear()
        runCatching { pc?.dispose() }
    }

    private fun resetConnection(link: PeerLink) {
        closeLink(link)
        updatePeer(link.id) { it.copy(connected = false, hasVideo = false) }
        link.pc = createConnection(link)
    }

    private fun drainIce(link: PeerLink) {
        val pc = link.pc ?: return
        link.waitingIce.forEach { runCatching { pc.addIceCandidate(it) } }
        link.waitingIce.clear()
    }

    private fun createConnection(link: PeerLink): PeerConnection {
        val f = factory ?: throw IllegalStateException("no factory")
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        )
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = f.createPeerConnection(config, observerFor(link))
            ?: throw IllegalStateException("cannot create peer connection")
        audioTrack?.let { pc.addTrack(it, listOf("maychat-g")) }
        val camera = videoTrack
        if (camera != null) {
            pc.addTrack(camera, listOf("maychat-g"))
        } else if (ui?.video == true && myId < link.id) {
            // No camera on my side, and I make the offer: still ask to
            // receive the other person's picture.
            runCatching {
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
            }
        }
        return pc
    }

    // Makes the offer for this connection, and tries again (up to 3 times)
    // when it is still not connected 10 seconds later.
    private fun makeOffer(link: PeerLink) {
        link.watchdog?.cancel()
        link.watchdog = main.launch {
            while (isActive && links[link.id] === link && link.tries < 4) {
                link.tries++
                runCatching {
                    if (link.tries > 1 || link.pc == null) resetConnection(link)
                    val pc = link.pc ?: return@runCatching
                    val offer = pc.createOfferSuspend()
                    pc.setLocalSuspend(offer)
                    sendSignal(buildJsonObject {
                        put("t", "offer")
                        put("from", myId)
                        put("to", link.id)
                        put("name", myName)
                        put("sdp", offer.description)
                    })
                }
                delay(10_000)
                if (peers.firstOrNull { it.id == link.id }?.connected == true) return@launch
            }
        }
    }

    private fun observerFor(link: PeerLink) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            main.launch {
                if (links[link.id] !== link) return@launch
                sendSignal(buildJsonObject {
                    put("t", "ice")
                    put("from", myId)
                    put("to", link.id)
                    put("mid", candidate.sdpMid ?: "")
                    put("line", candidate.sdpMLineIndex)
                    put("cand", candidate.sdp)
                })
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            main.launch {
                if (links[link.id] !== link) return@launch
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED,
                    -> {
                        link.tries = 0
                        updatePeer(link.id) { it.copy(connected = true) }
                    }

                    PeerConnection.IceConnectionState.FAILED -> {
                        updatePeer(link.id) { it.copy(connected = false) }
                        // The side that makes the offer tries again.
                        if (myId < link.id) {
                            link.tries = 1
                            makeOffer(link)
                        }
                    }

                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        updatePeer(link.id) { it.copy(connected = false) }

                    else -> {}
                }
            }
        }

        // The other person's picture starts arriving.
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            main.launch {
                if (links[link.id] !== link || link.pc == null) return@launch
                runCatching { link.remoteTrack?.removeSink(link.sink) }
                link.remoteTrack = track
                runCatching { track.addSink(link.sink) }
                updatePeer(link.id) { it.copy(hasVideo = true) }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    // ------------------------------------------------------------------
    // Ringing
    // ------------------------------------------------------------------

    private var ringtone: Ringtone? = null
    private var ringJob: Job? = null
    private val seenCalls = LinkedHashSet<String>()

    // Something new happened in this group (the app is open): if it is a
    // call that just started, ring; if the ringing call ended, stop.
    fun onGroupEvent(groupId: String) {
        if (myId.isBlank()) return
        main.launch {
            val latest = attempt { ChatRepository.loadLatestGroupMessage(groupId) }.getOrNull() ?: return@launch
            val ended = groupCallEndId(latest.kind, latest.extra)
            if (ended != null && ring?.callId == ended) {
                clearRing()
                return@launch
            }
            val call = groupCallLink(latest.kind, latest.extra) ?: return@launch
            if (latest.senderId == myId || !isFresh(latest.createdAt)) return@launch
            val group = attempt { ChatRepository.loadGroup(groupId) }.getOrNull() ?: return@launch
            val caller = attempt { ChatRepository.loadProfile(latest.senderId) }.getOrNull()
            startRing(GroupRing(groupId, group.name, call.callId, call.video, caller?.displayName ?: "Một thành viên"))
        }
    }

    // A call message is "fresh" for one minute.
    fun isFresh(createdAt: String?): Boolean {
        val at = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(System.currentTimeMillis() - at) < 60_000
    }

    // Shows the "incoming group call" screen and rings (also used when the
    // call's notification was tapped).
    fun startRing(incoming: GroupRing) {
        if (myId.isBlank()) {
            // Nothing is shown before the login is confirmed.
            pendingRing = incoming to System.currentTimeMillis()
            return
        }
        if (ui != null || CallManager.ui != null) return
        if (ring?.callId == incoming.callId) return
        if (!seenCalls.add(incoming.callId)) return
        if (seenCalls.size > 30) seenCalls.remove(seenCalls.first())
        ring = incoming
        val context = appContext
        if (context != null) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val tone = RingtoneManager.getRingtone(context, uri)
                if (tone != null) {
                    if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
                    tone.play()
                    ringtone = tone
                }
            }
        }
        ringJob?.cancel()
        ringJob = main.launch {
            delay(40_000)
            if (ring?.callId == incoming.callId) clearRing()
        }
    }

    // "Từ chối", or the ringing is over.
    fun clearRing() {
        ringJob?.cancel()
        ringJob = null
        runCatching { ringtone?.stop() }
        ringtone = null
        ring = null
    }

    // "Tham gia" on the ringing screen (the microphone is allowed).
    fun acceptRing() {
        val incoming = ring ?: return
        clearRing()
        join(incoming.groupId, incoming.groupName, incoming.callId, incoming.video)
    }

    // ------------------------------------------------------------------
    // Small helpers to wait for WebRTC's answers
    // ------------------------------------------------------------------

    private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null) cont.resume(description)
                    else cont.resumeWithException(IllegalStateException("no offer"))
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "offer failed"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createAnswer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null) cont.resume(description)
                    else cont.resumeWithException(IllegalStateException("no answer"))
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "answer failed"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

    private fun setObserver(cont: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SdpObserver {
        override fun onSetSuccess() {
            if (cont.isActive) cont.resume(Unit)
        }

        override fun onSetFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "set failed"))
        }

        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onCreateFailure(error: String?) {}
    }

    private suspend fun PeerConnection.setLocalSuspend(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont -> setLocalDescription(setObserver(cont), description) }

    private suspend fun PeerConnection.setRemoteSuspend(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont -> setRemoteDescription(setObserver(cont), description) }
}
