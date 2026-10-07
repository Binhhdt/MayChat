package com.maychat.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.maychat.app.data.CallServer
import com.maychat.app.data.CallSignaling
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.push.Push
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallPhase { OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

// What the call screen shows. null means "no call".
data class CallUi(
    val phase: CallPhase,
    val peer: Profile,
    val message: String,
    val connectedAtMs: Long = 0L,
    val video: Boolean = false,     // a video call (false = voice only)
)

// Stands between a video track and the view that draws it. The view can be
// attached and taken away at any time without touching the track itself.
class ProxySink : VideoSink {
    @Volatile
    var target: VideoSink? = null

    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}

// One-to-one voice calls with WebRTC.
//
// How a call is set up:
//   caller makes an "offer" (a text describing how to reach its audio)
//   -> sent through CallSignaling -> callee answers with an "answer"
//   -> the two phones then send audio DIRECTLY to each other.
// STUN servers only help each phone learn its public address; no audio
// passes through them. On some mobile networks a direct connection is
// impossible; that needs a TURN relay, which is not used yet.
object CallManager {

    var ui by mutableStateOf<CallUi?>(null)
        private set

    // True while the call screen is put aside so the rest of the app can be
    // used (read and write messages); the call itself goes on. A small bar
    // then leads back to the call screen.
    var minimized by mutableStateOf(false)
        private set

    fun minimize() {
        minimized = true
    }

    fun restore() {
        minimized = false
    }
    var muted by mutableStateOf(false)
        private set
    var speakerOn by mutableStateOf(false)
        private set

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var myId: String = ""
    private var myName: String = ""
    private var listenJob: Job? = null

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var gatheringDone = CompletableDeferred<Unit>()

    // ------------------------------------------------------------------
    // Video (only used by video calls; voice calls never touch any of it)
    // ------------------------------------------------------------------

    // My camera is sending (false = switched off, or no camera permission).
    var cameraOn by mutableStateOf(false)
        private set
    // The front camera is the one in use (its picture is shown mirrored).
    var frontCamera by mutableStateOf(true)
        private set
    // The other person's picture is arriving.
    var remoteVideo by mutableStateOf(false)
        private set

    // The call screen attaches its two video views to these.
    val localSink = ProxySink()
    val remoteSink = ProxySink()

    private var egl: EglBase? = null
    val eglContext: EglBase.Context? get() = egl?.eglBaseContext

    // A separate factory with video support, created on the first video
    // call. Voice calls keep using the plain one, exactly as before.
    private var videoFactory: PeerConnectionFactory? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    // ----- Screen sharing, background blur, floating window --------------

    // My screen is being sent instead of my camera.
    var sharingScreen by mutableStateOf(false)
        private set
    // The background behind me is blurred before my camera picture is sent.
    var blurOn by mutableStateOf(false)
        private set
    // The app is shown as a small floating window (set by MainActivity).
    var inPip by mutableStateOf(false)
    // True while Android's "share your screen?" question is open: leaving
    // the app for that question must not turn it into a floating window.
    var pipBlocked by mutableStateOf(false)
    // A short line for the video call screen ("Không chia sẻ được…").
    var videoNotice by mutableStateOf<String?>(null)
        private set

    // The part of the call that sends my picture. Its content can be
    // swapped (camera <-> screen) without setting the call up again.
    private var videoSender: RtpSender? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var blur: BackgroundBlur? = null

    // Screen sharing needs my picture to be part of the call, which it is
    // when the camera was allowed at the start of the call.
    val canShareScreen: Boolean get() = videoSender != null

    private fun showVideoNotice(text: String) {
        videoNotice = text
        main.launch {
            delay(4_000)
            if (videoNotice == text) videoNotice = null
        }
    }

    fun explainNoScreenShare() {
        showVideoNotice("Không chia sẻ được màn hình vì cuộc gọi này bắt đầu khi chưa cấp quyền camera.")
    }

    // permission: what Android returned after the user agreed to share.
    fun startScreenShare(permission: Intent) {
        val context = appContext ?: return
        val sender = videoSender
        val f = videoFactory
        val eglContext = egl?.eglBaseContext
        if (sharingScreen || ui?.video != true || peerConnection == null) return
        if (sender == null || f == null || eglContext == null) {
            explainNoScreenShare()
            return
        }
        main.launch {
            // Android only allows capturing the screen while the call's
            // permanent notification says so.
            val allowed = CallService.beginScreenShare(context)
            if (!allowed || peerConnection == null) {
                CallService.endScreenShare(context)
                showVideoNotice("Điện thoại không cho chia sẻ màn hình lúc này.")
                return@launch
            }
            var helper: SurfaceTextureHelper? = null
            var source: VideoSource? = null
            var screen: ScreenCapturerAndroid? = null
            try {
                val capture = ScreenCapturerAndroid(
                    permission,
                    object : MediaProjection.Callback() {
                        // Stopped from outside (Android's own "stop" button).
                        override fun onStop() {
                            main.launch { stopScreenShare() }
                        }
                    },
                )
                screen = capture
                val captureHelper = SurfaceTextureHelper.create("maychat-screen", eglContext)
                helper = captureHelper
                val captureSource = f.createVideoSource(true)
                source = captureSource
                capture.initialize(captureHelper, context, captureSource.capturerObserver)
                // At most 1280 points on the long side, even numbers.
                val metrics = context.resources.displayMetrics
                val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
                val scale = minOf(1f, 1280f / longSide)
                val width = ((metrics.widthPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val height = ((metrics.heightPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
                capture.startCapture(width, height, 15)
                val track = f.createVideoTrack("maychat-screen", captureSource)
                if (!sender.setTrack(track, false)) throw IllegalStateException("cannot switch to screen")
                screenCapturer = capture
                screenHelper = captureHelper
                screenSource = captureSource
                screenTrack = track
                sharingScreen = true
            } catch (e: Throwable) {
                runCatching { screen?.stopCapture() }
                runCatching { screen?.dispose() }
                runCatching { source?.dispose() }
                runCatching { helper?.dispose() }
                CallService.endScreenShare(context)
                showVideoNotice("Không bắt đầu chia sẻ màn hình được.")
            }
        }
    }

    // Back to my camera.
    fun stopScreenShare() {
        if (!sharingScreen) return
        sharingScreen = false
        runCatching { videoSender?.setTrack(localVideoTrack, false) }
        releaseScreenCapture()
        appContext?.let { CallService.endScreenShare(it) }
    }

    private fun releaseScreenCapture() {
        val screen = screenCapturer
        val helper = screenHelper
        val source = screenSource
        val track = screenTrack
        screenCapturer = null
        screenHelper = null
        screenSource = null
        screenTrack = null
        runCatching { screen?.stopCapture() }
        runCatching { screen?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { helper?.dispose() }
    }

    // Blur the background behind me on / off (camera picture only).
    fun toggleBlur() {
        val source = videoSource ?: return
        if (blurOn) {
            runCatching { source.setVideoProcessor(null) }
            blur?.close()
            blur = null
            blurOn = false
        } else {
            try {
                val processor = BackgroundBlur()
                source.setVideoProcessor(processor)
                blur = processor
                blurOn = true
            } catch (e: Throwable) {
                showVideoNotice("Điện thoại này không làm mờ nền được.")
            }
        }
    }

    fun toggleCamera() {
        val track = localVideoTrack ?: return
        cameraOn = !cameraOn
        runCatching { track.setEnabled(cameraOn) }
    }

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

    // Starts my camera and adds its picture to the call.
    private fun startCamera(context: Context, f: PeerConnectionFactory, pc: PeerConnection) {
        val eglContext = egl?.eglBaseContext ?: return
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        val camera = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("maychat-camera", eglContext)
        val source = f.createVideoSource(false)
        camera.initialize(helper, context, source.capturerObserver)
        camera.startCapture(640, 480, 24)
        val track = f.createVideoTrack("maychat-video", source)
        track.addSink(localSink)
        videoSender = pc.addTrack(track, listOf("maychat"))

        capturer = camera
        captureHelper = helper
        videoSource = source
        localVideoTrack = track
        frontCamera = enumerator.isFrontFacing(name)
        cameraOn = true
    }

    // ------------------------------------------------------------------
    // Diagnosis line (small text on the call screen)
    // Shows how far the call set-up got, so a screenshot tells where a call
    // that does not connect is stuck.
    // ------------------------------------------------------------------

    var debugLine by mutableStateOf("")
        private set

    private var dbgStep = "-"
    private var dbgIce = "-"
    // Counts of addresses: [inside the local network, public, relay].
    private val dbgMine = IntArray(3)
    private var dbgTheirs = IntArray(3)

    // Relay servers read from the database (empty = direct connection only).
    private var relayServers: List<CallServer> = emptyList()

    private fun countAddresses(sdp: String): IntArray = intArrayOf(
        sdp.split(" typ host").size - 1,
        (sdp.split(" typ srflx").size - 1) + (sdp.split(" typ prflx").size - 1),
        sdp.split(" typ relay").size - 1,
    )

    private fun debugStep(step: String) {
        dbgStep = step
        refreshDebug()
    }

    private fun refreshDebug() {
        main.launch {
            debugLine = "Chẩn đoán · bước: $dbgStep · kênh: ${CallSignaling.inboxStatus()} · " +
                "máy này ${dbgMine[0]}/${dbgMine[1]}/${dbgMine[2]} · " +
                "máy kia ${dbgTheirs[0]}/${dbgTheirs[1]}/${dbgTheirs[2]} · " +
                "ICE $dbgIce · gửi: ${CallSignaling.lastSend} · " +
                "trung chuyển: ${if (relayServers.isEmpty()) "không" else "có"}"
        }
    }

    // Completed when this phone has learned its PUBLIC address (the one the
    // other phone can reach it on from a different network).
    private var publicAddressFound = CompletableDeferred<Unit>()

    // Waits until this phone knows its addresses, so the call data sent to
    // the other phone is complete. Sending too early (only the address
    // inside the home Wi-Fi) is what makes a call hang on "Đang kết nối"
    // when the two phones are on different networks. Waits at most 6 seconds.
    private suspend fun awaitAddresses() {
        val done = gatheringDone
        val found = publicAddressFound
        withTimeoutOrNull(6_000) {
            while (!done.isCompleted) {
                if (found.isCompleted) {
                    // Public address known: a short moment for the rest.
                    withTimeoutOrNull(700) { done.await() }
                    break
                }
                delay(100)
            }
        }
    }

    private var callId: String? = null
    private var peerId: String? = null
    private var iAmCaller = false

    // The call history row being written for the call I started (null when
    // I am not the caller). Resolves to the row's id, or null if it failed.
    private var callLogJob: Deferred<String?>? = null
    private var offerFromPeer: String? = null   // waiting for me to accept
    private var myAnswer: String? = null        // kept so it can be re-sent
    private var callJob: Job? = null
    private var watchdogJob: Job? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null   // the "tuu... tuu..." the caller hears

    // A call announced by a push notification, before the real call data has
    // arrived over the live connection (see prepareIncoming).
    private var acceptWhenOfferArrives = false
    private var pendingTimeoutJob: Job? = null
    private var ignorePeerId: String? = null      // I rejected before the offer arrived
    private var ignoreUntilMs = 0L

    // ------------------------------------------------------------------
    // Start / stop listening for calls (after login / on logout)
    // ------------------------------------------------------------------

    fun attach(context: Context, userId: String, displayName: String) {
        appContext = context.applicationContext
        myId = userId
        myName = displayName
        listenJob?.cancel()
        listenJob = main.launch {
            launch { CallSignaling.incoming.collect { onSignal(it) } }
            // Second path for call messages: fetch them from the database.
            // About once a second while a call is being set up, every few
            // seconds otherwise. This is what makes calls get through when
            // the live connection of this phone is not healthy.
            launch {
                while (isActive) {
                    val current = ui
                    val settingUp = current != null &&
                        current.phase != CallPhase.CONNECTED && current.phase != CallPhase.ENDED
                    delay(
                        when {
                            settingUp -> 1_000L
                            current != null -> 3_000L
                            else -> 4_000L
                        },
                    )
                    // Nothing going on and the app is not on screen: a
                    // notification wakes the app for a call, no need to ask.
                    if (ui == null && !Push.appVisible) continue
                    val fetched = attempt { ChatRepository.takeCallSignals() }
                    fetched.onSuccess { list -> list.forEach { onSignal(it) } }
                    // Function missing (migration 20 not run) or offline: ask less often.
                    if (fetched.isFailure) delay(8_000)
                }
            }
            // Relay servers, if any were set up (table may not exist: then none).
            launch { attempt { ChatRepository.loadCallServers() }.onSuccess { relayServers = it } }
            attempt { CallSignaling.start(userId) }
        }
    }

    fun detach() {
        if (ui != null) hangUp()
        listenJob?.cancel()
        listenJob = null
        main.launch { attempt { CallSignaling.stop() } }
    }

    // ------------------------------------------------------------------
    // Actions from the screen
    // ------------------------------------------------------------------

    // Call someone. The microphone permission must already be granted.
    // video = true starts a video call (camera permission should be granted;
    // without it the call still works, I just send no picture).
    fun startCall(peer: Profile, conversationId: String, video: Boolean = false) {
        if (ui != null || myId.isBlank()) return
        val id = UUID.randomUUID().toString()
        callId = id
        peerId = peer.id
        iAmCaller = true
        ui = CallUi(CallPhase.OUTGOING, peer, "Đang gọi…", video = video)

        callJob = main.launch {
            try {
                // A line in the chat: call history, and it also triggers the
                // normal message notification on the other phone.
                val chatLine = if (video) "📞 Cuộc gọi video" else "📞 Cuộc gọi thoại"
                launch { attempt { ChatRepository.sendMessage(conversationId, chatLine) } }
                // A row in the call history. Started on its own, so ending
                // the call does not cancel it (see finish()).
                callLogJob = main.async { attempt { ChatRepository.logCallStart(peer.id) }.getOrNull() }

                val pc = createPeer(video)
                onCallStarted()
                // Let the caller hear the usual waiting tone until the other
                // side answers.
                startRingback()
                val offer = pc.createOfferSuspend()
                pc.setLocalSuspend(offer)
                // Wait until the phone has found its addresses, so the offer
                // is complete and can simply be re-sent.
                debugStep("đang tìm địa chỉ")
                awaitAddresses()

                // Keep offering for 45 seconds: the other phone may need time
                // to open the app from the notification.
                var waited = 0
                while (ui?.phase == CallPhase.OUTGOING && callId == id && waited < 45) {
                    // Built again each time: addresses found a little
                    // later are included in the next repeat.
                    val sdp = pc.localDescription?.description ?: offer.description
                    val sent = attempt { CallSignaling.send(peer.id, signal("offer", id, sdp)) }.isSuccess
                    debugStep(if (sent) "đã gửi lời gọi (${waited / 3 + 1})" else "GỬI LỜI GỌI LỖI (${waited / 3 + 1})")
                    delay(3_000)
                    waited += 3
                }
                if (ui?.phase == CallPhase.OUTGOING && callId == id) {
                    attempt { CallSignaling.send(peer.id, signal("end", id)) }
                    finish("Không có trả lời")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finish("Không gọi được. Kiểm tra mạng và quyền micro.")
            }
        }
    }

    // A push notification says this person is calling. Show the incoming call
    // screen and ring IMMEDIATELY, without waiting for the app to reconnect.
    // The real call data (the "offer") arrives a few seconds later.
    fun prepareIncoming(context: Context, senderId: String, senderName: String) {
        if (ui != null) return
        appContext = context.applicationContext
        callId = null
        peerId = senderId
        iAmCaller = false
        offerFromPeer = null
        myAnswer = null
        acceptWhenOfferArrives = false
        ui = CallUi(
            CallPhase.INCOMING,
            Profile(id = senderId, username = "", displayName = senderName),
            "Cuộc gọi thoại đến",
        )
        startRingtone()
        loadPeerPicture(senderId)
        debugStep("có thông báo, chờ dữ liệu cuộc gọi")
        CallSignaling.kick()
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = main.launch {
            // The caller gives up after 45 seconds.
            delay(45_000)
            if (callId == null && peerId == senderId && ui != null) finish("Cuộc gọi nhỡ")
        }
    }

    // An incoming call only carries the caller's id and name. Fetch the
    // full profile so the call screen can show their picture.
    private fun loadPeerPicture(peer: String) {
        main.launch {
            val profile = attempt { ChatRepository.loadProfile(peer) }.getOrNull() ?: return@launch
            val current = ui ?: return@launch
            if (peerId == peer && current.peer.id == peer && current.peer.avatarPath == null) {
                ui = current.copy(peer = current.peer.copy(avatarPath = profile.avatarPath))
            }
        }
    }

    // Used when nobody is logged in: there is no screen to show a call on.
    fun dismissAny() {
        if (ui != null) finish("")
    }

    // Answer the incoming call. The microphone permission must be granted.
    fun accept() {
        val current = ui ?: return
        if (current.phase != CallPhase.INCOMING) return
        val peer = peerId ?: return
        val id = callId
        val offer = offerFromPeer
        if (id == null || offer == null) {
            // Accepted before the call data arrived: continue as soon as it does.
            stopRingtone()
            acceptWhenOfferArrives = true
            ui = current.copy(phase = CallPhase.CONNECTING, message = "Đang kết nối…")
            debugStep("đã bấm nghe, chờ dữ liệu cuộc gọi")
            CallSignaling.kick()
            return
        }
        stopRingtone()
        ui = current.copy(phase = CallPhase.CONNECTING, message = "Đang kết nối…")

        callJob = main.launch {
            try {
                val pc = createPeer(current.video)
                onCallStarted()
                dbgTheirs = countAddresses(offer)
                debugStep("đang tìm địa chỉ")
                pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, offer))
                val answer = pc.createAnswerSuspend()
                pc.setLocalSuspend(answer)
                awaitAddresses()
                val sdp = pc.localDescription?.description ?: answer.description
                myAnswer = sdp
                CallSignaling.send(peer, signal("answer", id, sdp))
                debugStep("đã gửi trả lời")
                startConnectWatchdog(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt { CallSignaling.send(peer, signal("end", id)) }
                finish("Không kết nối được")
            }
        }
    }

    fun reject() {
        val id = callId
        val peer = peerId
        if (id != null && peer != null) {
            main.launch { attempt { CallSignaling.send(peer, signal("reject", id)) } }
        } else if (peer != null) {
            // Rejected before the call data arrived: remember it, so the
            // offer that is still on its way does not ring again.
            ignorePeerId = peer
            ignoreUntilMs = System.currentTimeMillis() + 60_000
        }
        finish("Đã từ chối")
    }

    fun hangUp() {
        val id = callId
        val peer = peerId
        if (id != null && peer != null) {
            main.launch { attempt { CallSignaling.send(peer, signal("end", id)) } }
        }
        finish("Đã kết thúc cuộc gọi")
    }

    fun toggleMute() {
        muted = !muted
        runCatching { audioTrack?.setEnabled(!muted) }
    }

    fun toggleSpeaker() {
        speakerOn = !speakerOn
        applySpeaker()
        updateProximityLock()
    }

    // ------------------------------------------------------------------
    // Keeping the call alive, and the screen off at the ear
    // ------------------------------------------------------------------

    private var proximityLock: PowerManager.WakeLock? = null

    // The audio connection exists now: keep it alive when the app leaves
    // the screen, and darken the screen when the phone is at the ear.
    private fun onCallStarted() {
        val context = appContext ?: return
        CallService.start(context, ui?.peer?.displayName ?: "MayChat")
        updateProximityLock()
    }

    // The screen switches off near the ear only while a call is running
    // and the loudspeaker is off (with the loudspeaker on, the phone is in
    // the hand or on the table).
    private fun updateProximityLock() {
        val context = appContext ?: return
        val wanted = peerConnection != null && !speakerOn
        runCatching {
            if (wanted) {
                val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (proximityLock == null &&
                    power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
                ) {
                    proximityLock = power.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "maychat:call",
                    )
                }
                val lock = proximityLock
                // Released after 2 hours at the latest, as a safety net.
                if (lock != null && !lock.isHeld) lock.acquire(2 * 60 * 60 * 1000L)
            } else {
                val lock = proximityLock
                if (lock != null && lock.isHeld) lock.release()
            }
        }
    }

    // "Từ chối" pressed on the incoming-call notification, possibly while
    // the app was closed. Stops any ringing here and tries to tell the
    // caller. onDone is always called, at the latest after 8 seconds.
    fun declineFromNotification(context: Context, senderId: String, onDone: () -> Unit) {
        appContext = context.applicationContext
        // If the call data arrives later anyway, do not ring for it.
        ignorePeerId = senderId
        ignoreUntilMs = System.currentTimeMillis() + 60_000
        if (ui != null && peerId == senderId && !iAmCaller) finish("Đã từ chối")

        main.launch {
            withTimeoutOrNull(8_000) {
                attempt {
                    ChatRepository.sessionStatus.first { it is SessionStatus.Authenticated }
                    val me = ChatRepository.currentUserId() ?: return@attempt
                    CallSignaling.send(
                        senderId,
                        buildJsonObject {
                            put("type", "decline")
                            put("call_id", "none")
                            put("from", me)
                        },
                    )
                }
            }
            onDone()
        }
    }

    // ------------------------------------------------------------------
    // Messages from the other phone
    // ------------------------------------------------------------------

    private fun text(json: JsonObject, key: String): String? =
        json[key]?.jsonPrimitive?.contentOrNull

    // Ids of calls that are over. Their late copies (every call message now
    // arrives over two paths) must not make the phone ring again.
    private val recentlyEnded = LinkedHashSet<String>()

    // Fetch waiting call messages right now (used when a call notification
    // arrives while the app is on screen).
    fun pokeSignals() {
        main.launch {
            attempt { ChatRepository.takeCallSignals() }.onSuccess { list -> list.forEach { onSignal(it) } }
        }
    }

    private fun onSignal(json: JsonObject) {
        val type = text(json, "type") ?: return
        val id = text(json, "call_id") ?: return
        val from = text(json, "from") ?: return
        if (from == myId) return
        if (type == "offer" && id in recentlyEnded) return

        when (type) {
            "offer" -> {
                val sdp = text(json, "sdp") ?: return
                val isVideo = text(json, "video") == "true"
                val current = ui
                if (from == ignorePeerId && System.currentTimeMillis() < ignoreUntilMs) {
                    // I already rejected this call from its notification.
                    main.launch { attempt { CallSignaling.send(from, signal("reject", id)) } }
                } else if (current != null && callId == null && !iAmCaller && from == peerId &&
                    (current.phase == CallPhase.INCOMING || current.phase == CallPhase.CONNECTING)
                ) {
                    // The call announced by the notification: its data is here now.
                    callId = id
                    offerFromPeer = sdp
                    pendingTimeoutJob?.cancel()
                    // Now it is known whether this is a video call.
                    val announced = if (isVideo) {
                        current.copy(video = true, message = "Cuộc gọi video đến")
                    } else {
                        current
                    }
                    ui = announced
                    main.launch { attempt { CallSignaling.send(from, signal("ringing", id)) } }
                    if (acceptWhenOfferArrives) {
                        acceptWhenOfferArrives = false
                        ui = announced.copy(phase = CallPhase.INCOMING)
                        accept()
                    }
                } else if (ui == null) {
                    // A new incoming call.
                    callId = id
                    peerId = from
                    iAmCaller = false
                    offerFromPeer = sdp
                    myAnswer = null
                    val name = text(json, "from_name") ?: "Người gọi"
                    ui = CallUi(
                        CallPhase.INCOMING,
                        Profile(id = from, username = "", displayName = name),
                        if (isVideo) "Cuộc gọi video đến" else "Cuộc gọi thoại đến",
                        video = isVideo,
                    )
                    startRingtone()
                    loadPeerPicture(from)
                    main.launch { attempt { CallSignaling.send(from, signal("ringing", id)) } }
                } else if (id == callId && !iAmCaller) {
                    // The caller repeats the offer. While I have not answered
                    // yet, keep the newest copy: it may list more addresses.
                    if (myAnswer == null && ui?.phase == CallPhase.INCOMING) offerFromPeer = sdp
                    // Repeat my answer if I have one.
                    val answer = myAnswer
                    if (answer != null) {
                        main.launch { attempt { CallSignaling.send(from, signal("answer", id, answer)) } }
                    }
                }
                // An offer from a third person during a call is ignored.
            }

            "ringing" -> {
                val current = ui
                if (id == callId && iAmCaller && current?.phase == CallPhase.OUTGOING) {
                    ui = current.copy(message = "Đang đổ chuông…")
                }
            }

            "answer" -> {
                val sdp = text(json, "sdp") ?: return
                val current = ui
                if (id == callId && iAmCaller && current?.phase == CallPhase.OUTGOING) {
                    stopRingback()
                    ui = current.copy(phase = CallPhase.CONNECTING, message = "Đang kết nối…")
                    dbgTheirs = countAddresses(sdp)
                    debugStep("đã nhận trả lời")
                    main.launch {
                        try {
                            peerConnection?.setRemoteSuspend(
                                SessionDescription(SessionDescription.Type.ANSWER, sdp),
                            )
                            startConnectWatchdog(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            finish("Không kết nối được")
                        }
                    }
                }
            }

            "reject" -> if (id == callId) finish("Người nhận đã từ chối")

            // Declined from the notification, before the callee's app knew
            // the id of the call.
            "decline" -> {
                if (iAmCaller && from == peerId && ui?.phase == CallPhase.OUTGOING) {
                    finish("Người nhận đã từ chối")
                }
            }

            "end" -> {
                if (id == callId) {
                    finish("Cuộc gọi đã kết thúc")
                } else if (callId == null && !iAmCaller && from == peerId && ui != null) {
                    // The caller hung up before the call data reached me.
                    finish("Cuộc gọi nhỡ")
                }
            }
        }
    }

    private fun signal(type: String, id: String, sdp: String? = null): JsonObject = buildJsonObject {
        put("type", type)
        put("call_id", id)
        put("from", myId)
        put("from_name", myName)
        if (ui?.video == true) put("video", true)
        if (sdp != null) put("sdp", sdp)
    }

    // ------------------------------------------------------------------
    // WebRTC
    // ------------------------------------------------------------------

    private fun createPeer(video: Boolean = false): PeerConnection {
        val context = appContext ?: throw IllegalStateException("not attached")

        val f = if (video) {
            // Video calls: a factory that can also encode and decode pictures.
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
            val eglBase = egl ?: EglBase.create().also { egl = it }
            videoFactory ?: PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
                .also { videoFactory = it }
        } else {
            factory ?: run {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
                )
                PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
            }
        }

        gatheringDone = CompletableDeferred()
        publicAddressFound = CompletableDeferred()
        dbgMine.fill(0)
        dbgTheirs = IntArray(3)
        dbgIce = "NEW"
        muted = false
        speakerOn = false

        // Phone-call audio mode: earpiece by default, echo cancellation on.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker()

        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            // A second, independent provider in case Google's does not answer.
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        ) + relayServers.mapNotNull { server ->
            // Relay servers from the database, used only when a direct
            // connection between the two phones is not possible.
            val urls = server.urls.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (urls.isEmpty()) {
                null
            } else {
                runCatching {
                    PeerConnection.IceServer.builder(urls)
                        .setUsername(server.username)
                        .setPassword(server.credential)
                        .createIceServer()
                }.getOrNull()
            }
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = f.createPeerConnection(config, observer)
            ?: throw IllegalStateException("cannot create peer connection")

        val source = f.createAudioSource(MediaConstraints())
        val track = f.createAudioTrack("maychat-audio", source)
        pc.addTrack(track, listOf("maychat"))

        audioSource = source
        audioTrack = track
        peerConnection = pc

        if (video) {
            cameraOn = false
            remoteVideo = false
            val cameraAllowed = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (cameraAllowed) runCatching { startCamera(context, f, pc) }
            // No camera on my side: as the caller, still ask to RECEIVE the
            // other person's picture. (An answering phone needs nothing
            // here; the caller's request already contains the video part.)
            if (localVideoTrack == null && iAmCaller) {
                runCatching {
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                    )
                }
            }
            // A video call is held in front of the face: use the loudspeaker.
            speakerOn = true
            applySpeaker()
        }
        return pc
    }

    // WebRTC calls these on its own thread; everything is moved to the main thread.
    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringDone.complete(Unit)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            dbgIce = state?.name ?: "-"
            refreshDebug()
            main.launch { onConnectionState(state) }
        }

        // The other person's picture starts arriving (video calls only).
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            main.launch {
                if (peerConnection != null && ui?.video == true) {
                    remoteVideoTrack = track
                    runCatching { track.addSink(remoteSink) }
                    remoteVideo = true
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            // "srflx" / "relay" = an address reachable from outside my network.
            val line = candidate?.sdp ?: return
            if (" typ srflx" in line || " typ relay" in line) publicAddressFound.complete(Unit)
            when {
                " typ host" in line -> dbgMine[0]++
                " typ relay" in line -> dbgMine[2]++
                else -> dbgMine[1]++
            }
            refreshDebug()
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun onConnectionState(state: PeerConnection.IceConnectionState?) {
        val current = ui ?: return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                if (current.phase == CallPhase.CONNECTING || current.phase == CallPhase.OUTGOING) {
                    watchdogJob?.cancel()
                    ui = current.copy(
                        phase = CallPhase.CONNECTED,
                        message = "",
                        connectedAtMs = System.currentTimeMillis(),
                    )
                }
            }

            PeerConnection.IceConnectionState.FAILED -> {
                if (current.phase != CallPhase.ENDED) {
                    hangUpWithReason("Mất kết nối cuộc gọi")
                }
            }

            PeerConnection.IceConnectionState.DISCONNECTED -> {
                // Often recovers by itself; give it 10 seconds.
                val id = callId
                if (current.phase == CallPhase.CONNECTED) {
                    watchdogJob?.cancel()
                    watchdogJob = main.launch {
                        delay(10_000)
                        val pc = peerConnection
                        val stillBad = pc == null ||
                            pc.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED ||
                            pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED
                        if (callId == id && stillBad) hangUpWithReason("Mất kết nối cuộc gọi")
                    }
                }
            }

            else -> {}
        }
    }

    // After offer and answer are exchanged the audio should connect within
    // seconds. If it does not, the two networks do not allow a direct link.
    private fun startConnectWatchdog(id: String) {
        watchdogJob?.cancel()
        watchdogJob = main.launch {
            delay(20_000)
            if (callId == id && ui?.phase == CallPhase.CONNECTING) {
                hangUpWithReason("Không kết nối được. Mạng của một trong hai máy có thể đang chặn kết nối trực tiếp.")
            }
        }
    }

    private fun hangUpWithReason(reason: String) {
        val id = callId
        val peer = peerId
        if (id != null && peer != null) {
            main.launch { attempt { CallSignaling.send(peer, signal("end", id)) } }
        }
        finish(reason)
    }

    // Ends the call on this phone and shows the reason for two seconds.
    private fun finish(reason: String) {
        val current = ui ?: return
        if (current.phase == CallPhase.ENDED) return
        val endedId = callId
        if (endedId != null) {
            recentlyEnded.add(endedId)
            if (recentlyEnded.size > 30) recentlyEnded.remove(recentlyEnded.first())
        }

        // Complete the call history row (only the caller's phone writes it).
        val logJob = callLogJob
        callLogJob = null
        if (logJob != null) {
            val connectedAt = current.connectedAtMs
            val status = when {
                connectedAt > 0L -> "answered"
                reason == "Người nhận đã từ chối" -> "declined"
                reason == "Không có trả lời" -> "missed"
                reason == "Đã kết thúc cuộc gọi" -> "cancelled"
                else -> "failed"
            }
            val seconds = if (connectedAt > 0L) {
                ((System.currentTimeMillis() - connectedAt) / 1000).toInt().coerceAtLeast(0)
            } else {
                0
            }
            main.launch {
                val logId = logJob.await() ?: return@launch
                attempt { ChatRepository.logCallEnd(logId, status, seconds) }
            }
        }

        stopRingtone()
        stopRingback()
        appContext?.let { CallService.stop(it) }
        runCatching {
            val lock = proximityLock
            if (lock != null && lock.isHeld) lock.release()
        }
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = null
        acceptWhenOfferArrives = false
        callJob?.cancel()
        callJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        // Screen sharing and background blur end with the call.
        sharingScreen = false
        videoNotice = null
        runCatching { videoSource?.setVideoProcessor(null) }
        blur?.close()
        blur = null
        blurOn = false
        videoSender = null
        runCatching { screenCapturer?.stopCapture() }

        // Video: detach the views and stop the camera before the connection goes.
        runCatching { remoteVideoTrack?.removeSink(remoteSink) }
        runCatching { localVideoTrack?.removeSink(localSink) }
        remoteVideoTrack = null
        localVideoTrack = null
        remoteVideo = false
        cameraOn = false
        val camera = capturer
        val cameraHelper = captureHelper
        val cameraSource = videoSource
        capturer = null
        captureHelper = null
        videoSource = null
        runCatching { camera?.stopCapture() }

        val pc = peerConnection
        val source = audioSource
        peerConnection = null
        audioSource = null
        audioTrack = null
        runCatching { pc?.dispose() }
        releaseScreenCapture()
        runCatching { source?.dispose() }
        runCatching { camera?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { cameraHelper?.dispose() }

        appContext?.let { context ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                audio.isSpeakerphoneOn = false
                audio.mode = AudioManager.MODE_NORMAL
            }
        }

        offerFromPeer = null
        myAnswer = null
        muted = false
        speakerOn = false
        ui = current.copy(phase = CallPhase.ENDED, message = reason)

        main.launch {
            delay(2_000)
            // Only clear if no new call started in the meantime.
            if (callId == endedId && ui?.phase == CallPhase.ENDED) {
                ui = null
                callId = null
                peerId = null
                attempt { CallSignaling.closeOutbox() }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sound
    // ------------------------------------------------------------------

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

    private fun startRingtone() {
        val context = appContext ?: return
        stopRingtone()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val tone = RingtoneManager.getRingtone(context, uri) ?: return
            if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
            tone.play()
            ringtone = tone
        }
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    // The waiting tone on the CALLER's phone. It is made by the phone itself
    // (the standard telephone ring-back tone) and plays through the earpiece,
    // or the loudspeaker if that is switched on.
    private fun startRingback() {
        stopRingback()
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            tone.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            ringback = tone
        }
    }

    private fun stopRingback() {
        val tone = ringback
        ringback = null
        runCatching {
            tone?.stopTone()
            tone?.release()
        }
    }

    // ------------------------------------------------------------------
    // Turn WebRTC's callbacks into suspend functions
    // ------------------------------------------------------------------

    private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (!cont.isActive) return
                    if (description != null) cont.resume(description)
                    else cont.resumeWithException(IllegalStateException("empty offer"))
                }
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "offer failed"))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createAnswer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (!cont.isActive) return
                    if (description != null) cont.resume(description)
                    else cont.resumeWithException(IllegalStateException("empty answer"))
                }
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "answer failed"))
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
