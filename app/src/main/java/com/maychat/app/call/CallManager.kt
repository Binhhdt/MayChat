package com.maychat.app.call

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maychat.app.data.CallSignaling
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
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
)

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

    private var callId: String? = null
    private var peerId: String? = null
    private var iAmCaller = false
    private var offerFromPeer: String? = null   // waiting for me to accept
    private var myAnswer: String? = null        // kept so it can be re-sent
    private var callJob: Job? = null
    private var watchdogJob: Job? = null
    private var ringtone: Ringtone? = null

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
    fun startCall(peer: Profile, conversationId: String) {
        if (ui != null || myId.isBlank()) return
        val id = UUID.randomUUID().toString()
        callId = id
        peerId = peer.id
        iAmCaller = true
        ui = CallUi(CallPhase.OUTGOING, peer, "Đang gọi…")

        callJob = main.launch {
            try {
                // A line in the chat: call history, and it also triggers the
                // normal message notification on the other phone.
                launch { attempt { ChatRepository.sendMessage(conversationId, "📞 Cuộc gọi thoại") } }

                val pc = createPeer()
                val offer = pc.createOfferSuspend()
                pc.setLocalSuspend(offer)
                // Wait until the phone has found its addresses, so the offer
                // is complete and can simply be re-sent.
                withTimeoutOrNull(2_500) { gatheringDone.await() }
                val sdp = pc.localDescription?.description ?: offer.description
                val payload = signal("offer", id, sdp)

                // Keep offering for 45 seconds: the other phone may need time
                // to open the app from the notification.
                var waited = 0
                while (ui?.phase == CallPhase.OUTGOING && callId == id && waited < 45) {
                    attempt { CallSignaling.send(peer.id, payload) }
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

    // Answer the incoming call. The microphone permission must be granted.
    fun accept() {
        val current = ui ?: return
        val id = callId ?: return
        val peer = peerId ?: return
        val offer = offerFromPeer ?: return
        if (current.phase != CallPhase.INCOMING) return
        stopRingtone()
        ui = current.copy(phase = CallPhase.CONNECTING, message = "Đang kết nối…")

        callJob = main.launch {
            try {
                val pc = createPeer()
                pc.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, offer))
                val answer = pc.createAnswerSuspend()
                pc.setLocalSuspend(answer)
                withTimeoutOrNull(2_500) { gatheringDone.await() }
                val sdp = pc.localDescription?.description ?: answer.description
                myAnswer = sdp
                CallSignaling.send(peer, signal("answer", id, sdp))
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
    }

    // ------------------------------------------------------------------
    // Messages from the other phone
    // ------------------------------------------------------------------

    private fun text(json: JsonObject, key: String): String? =
        json[key]?.jsonPrimitive?.contentOrNull

    private fun onSignal(json: JsonObject) {
        val type = text(json, "type") ?: return
        val id = text(json, "call_id") ?: return
        val from = text(json, "from") ?: return
        if (from == myId) return

        when (type) {
            "offer" -> {
                val sdp = text(json, "sdp") ?: return
                if (ui == null) {
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
                        "Cuộc gọi thoại đến",
                    )
                    startRingtone()
                    main.launch { attempt { CallSignaling.send(from, signal("ringing", id)) } }
                } else if (id == callId && !iAmCaller) {
                    // The caller repeats the offer; repeat my answer if I have one.
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
                    ui = current.copy(phase = CallPhase.CONNECTING, message = "Đang kết nối…")
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

            "end" -> if (id == callId) finish("Cuộc gọi đã kết thúc")
        }
    }

    private fun signal(type: String, id: String, sdp: String? = null): JsonObject = buildJsonObject {
        put("type", type)
        put("call_id", id)
        put("from", myId)
        put("from_name", myName)
        if (sdp != null) put("sdp", sdp)
    }

    // ------------------------------------------------------------------
    // WebRTC
    // ------------------------------------------------------------------

    private fun createPeer(): PeerConnection {
        val context = appContext ?: throw IllegalStateException("not attached")

        val f = factory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
        }

        gatheringDone = CompletableDeferred()
        muted = false
        speakerOn = false

        // Phone-call audio mode: earpiece by default, echo cancellation on.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker()

        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
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
        return pc
    }

    // WebRTC calls these on its own thread; everything is moved to the main thread.
    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringDone.complete(Unit)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            main.launch { onConnectionState(state) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
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

        stopRingtone()
        callJob?.cancel()
        callJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        val pc = peerConnection
        val source = audioSource
        peerConnection = null
        audioSource = null
        audioTrack = null
        runCatching { pc?.dispose() }
        runCatching { source?.dispose() }

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
