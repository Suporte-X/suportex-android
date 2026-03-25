package com.suportex.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.suportex.app.remote.RemoteCommandBus
import com.suportex.app.remote.RemoteExecutor
import com.suportex.app.data.FirebaseDataSource
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.suportex.app.action.START_CAPTURE"
        const val ACTION_STOP  = "com.suportex.app.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ROOM_CODE   = "extra_room_code"

        private const val NOTIF_ID = 1
        private const val NOTIF_CHANNEL_ID = "screen_capture"
        private const val TAG = "SXS/Service"
    }

    private var started = false

    // WebRTC / captura
    private var capturer: ScreenCapturerAndroid? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var peerConnection: PeerConnection? = null
    private var videoSender: RtpSender? = null
    private var dataChannel: DataChannel? = null
    private var ctrlChannel: DataChannel? = null
    private var certificates: RtcCertificatePem? = null

    // Sinalização (Firestore)
    private val db = FirebaseDataSource.db
    private var eventsListener: ListenerRegistration? = null
    private var roomCode: String = ""
    private val handledEventIds = mutableSetOf<String>()
    private var signalListenStartedAtMs: Long = 0L

    // Keep-alive
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastPongAt = AtomicLong(0L)
    private var pingRunnable: Runnable? = null
    private var statsRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (started) return START_NOT_STICKY

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val room = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""

                if (resultCode != Activity.RESULT_OK || resultData == null || room.isBlank()) {
                    stopSelfSafe()
                    return START_NOT_STICKY
                }

                try {
                    roomCode = room

                    val projectionCallback = object : MediaProjection.Callback() {
                        override fun onStop() {
                            sendStatus("stopped", "mediaProjection.onStop")
                            stopSelfSafe()
                        }
                    }
                    capturer = ScreenCapturerAndroid(resultData, projectionCallback)

                    // === EGL antes da factory
                    eglBase = EglBase.create()

                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions
                            .builder(applicationContext)
                            .createInitializationOptions()
                    )
                    factory = PeerConnectionFactory.builder()
                        .setVideoEncoderFactory(
                            DefaultVideoEncoderFactory(
                                eglBase!!.eglBaseContext,
                                /* enableIntelVp8Encoder */ true,
                                /* enableH264HighProfile */ true
                            )
                        )
                        .setVideoDecoderFactory(
                            DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                        )
                        .createPeerConnectionFactory()

                    surfaceTextureHelper = SurfaceTextureHelper.create(
                        "CaptureThread",
                        eglBase!!.eglBaseContext
                    )

                    videoSource = factory!!.createVideoSource(true)
                    capturer!!.initialize(
                        surfaceTextureHelper,
                        applicationContext,
                        videoSource!!.capturerObserver
                    )
                    val (captureWidth, captureHeight) = resolveCaptureSize()
                    capturer!!.startCapture(captureWidth, captureHeight, 30)
                    RemoteExecutor.setCaptureFrameSize(captureWidth, captureHeight)

                    val iceServers = listOf(
                        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                        // Coloque seu TURN em produção
                    )

                    if (certificates == null) {
                        certificates = RtcCertificatePem.generateCertificate()
                    }

                    peerConnection = factory!!.createPeerConnection(
                        PeerConnection.RTCConfiguration(iceServers).apply {
                            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                        },
                        object : PeerConnection.Observer {
                            override fun onIceCandidate(c: IceCandidate) {
                                writeIceCandidateEvent(c)
                            }
                            override fun onAddStream(p0: MediaStream?) {}
                            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                                when (newState) {
                                    PeerConnection.PeerConnectionState.CONNECTED -> sendStatus("connected")
                                    PeerConnection.PeerConnectionState.DISCONNECTED -> sendStatus("disconnected")
                                    PeerConnection.PeerConnectionState.FAILED -> {
                                        sendStatus("failed")
                                        renegotiate(iceRestart = true)
                                    }
                                    else -> {}
                                }
                            }
                            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                            override fun onDataChannel(p0: DataChannel?) {
                                val channel = p0 ?: return
                                when (channel.label()) {
                                    "control" -> registerControlChannel(channel)
                                    "ctrl" -> registerCtrlChannel(channel)
                                }
                            }
                            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                            override fun onRemoveStream(p0: MediaStream?) {}
                            override fun onRenegotiationNeeded() {}
                            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                            override fun onTrack(p0: RtpTransceiver?) {}
                            override fun onIceConnectionReceivingChange(p0: Boolean) {}
                        }
                    )

                    videoTrack = factory!!.createVideoTrack("video", videoSource!!)
                    videoSender = peerConnection!!.addTrack(videoTrack!!, listOf("screen"))

                    preferH264Codec()
                    applyEncoderQuality(scaleDownBy = 1.0, maxFps = 30)
                    applySenderParams(maxKbps = 2500, minKbps = 600, maxFps = 30, scaleDown = null)
                    startDebugStatsLoop()

                    registerControlChannel(
                        peerConnection!!.createDataChannel("control", DataChannel.Init())
                    )

                    val ctrlInit = DataChannel.Init().apply { ordered = true }
                    registerCtrlChannel(peerConnection!!.createDataChannel("ctrl", ctrlInit))

                    listenToSignalEvents()
                    renegotiate(iceRestart = false)

                    started = true
                    RemoteCommandBus.setSessionActive(true)
                } catch (_: SecurityException) {
                    stopSelfSafe()
                    return START_NOT_STICKY
                } catch (t: Throwable) {
                    Log.e(TAG, "Start error", t)
                    stopSelfSafe()
                    return START_NOT_STICKY
                }
            }

            ACTION_STOP -> {
                sendStatus("stopped", "action_stop")
                stopSelfSafe()
            }
        }

        return START_NOT_STICKY
    }

    private fun applySenderParams(
        maxKbps: Int,
        minKbps: Int?,
        maxFps: Int?,
        scaleDown: Double?
    ) {
        val sender = videoSender ?: return
        try {
            val params = sender.parameters
            if (params.encodings.isEmpty()) {
                Log.w(TAG, "applySenderParams: encodings vazio; nada a aplicar")
                return
            }
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            val e = params.encodings[0]
            e.maxBitrateBps = maxKbps * 1000
            if (minKbps != null) e.minBitrateBps = minKbps * 1000
            if (maxFps   != null) e.maxFramerate  = maxFps
            if (scaleDown != null) e.scaleResolutionDownBy = scaleDown

            val ok = sender.setParameters(params)
            if (!ok) Log.w(TAG, "applySenderParams: setParameters=false")
        } catch (t: Throwable) {
            Log.w(TAG, "applySenderParams error", t)
        }
    }

    // ---------- Sinalização / SDP / ICE ----------
    private var remoteDescriptionSet = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    private fun renegotiate(iceRestart: Boolean) {
        val pc = peerConnection ?: return
        remoteDescriptionSet = false
        pendingRemoteIce.clear()

        val mediaConstraints = MediaConstraints().apply {
            if (iceRestart) optional.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        writeOfferEvent(sdp)
                        Log.d(TAG, "Offer enviada (${sdp.description.length} chars)")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "setLocalDescription fail: $error")
                    }
                }, sdp)
            }
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "createOffer fail: $error")
            }
        }, mediaConstraints)
    }

    private fun safeAddIce(cand: IceCandidate) {
        try { peerConnection?.addIceCandidate(cand) } catch (t: Throwable) {
            Log.e(TAG, "addIceCandidate error", t)
        }
    }

    private fun handleSignalAnswer(sdp: String) {
        if (sdp.isBlank()) return
        val pc = peerConnection ?: return
        val signalingState = pc.signalingState()
        if (signalingState == PeerConnection.SignalingState.CLOSED) {
            Log.w(TAG, "ANSWER ignorada: PeerConnection fechada")
            return
        }
        if (remoteDescriptionSet && signalingState == PeerConnection.SignalingState.STABLE) {
            Log.d(TAG, "ANSWER duplicada ignorada (estado estável)")
            return
        }
        if (
            signalingState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER &&
            signalingState != PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER
        ) {
            Log.w(TAG, "ANSWER ignorada por estado inválido: $signalingState")
            return
        }
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d(TAG, "ANSWER aplicada")
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { safeAddIce(it) }
                    pendingRemoteIce.clear()
                }

                override fun onSetFailure(error: String) {
                    Log.e(TAG, "setRemoteDescription fail: $error")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun handleSignalOffer(sdp: String) {
        if (sdp.isBlank()) return
        val pc = peerConnection ?: return
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d(TAG, "OFFER aplicada")
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { safeAddIce(it) }
                    pendingRemoteIce.clear()
                    pc.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            val answer = sdp
                            pc.setLocalDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    writeAnswerEvent(answer)
                                    Log.d(TAG, "ANSWER enviada (${answer.description.length} chars)")
                                }

                                override fun onSetFailure(error: String) {
                                    Log.e(TAG, "setLocalDescription fail: $error")
                                }
                            }, answer)
                        }

                        override fun onCreateFailure(error: String) {
                            Log.e(TAG, "createAnswer fail: $error")
                        }
                    }, MediaConstraints())
                }

                override fun onSetFailure(error: String) {
                    Log.e(TAG, "setRemoteDescription fail: $error")
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    private fun handleSignalCandidate(cand: IceCandidate?) {
        cand?.let {
            if (remoteDescriptionSet) safeAddIce(cand) else pendingRemoteIce.add(cand)
        }
    }

    private fun applyEncoderQuality(scaleDownBy: Double?, maxFps: Int?) {
        val sender = videoSender ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        val enc = params.encodings[0]
        enc.scaleResolutionDownBy = scaleDownBy
        enc.maxFramerate = maxFps
        sender.parameters = params
    }

    private fun preferH264Codec() {
        val pc = peerConnection ?: return
        val sender = videoSender ?: return
        try {
            val transceiver = pc.transceivers.firstOrNull { it.sender == sender } ?: return
            val caps = runCatching {
                val receiverClass = Class.forName("org.webrtc.RtpReceiver")
                val getCaps = receiverClass.methods.firstOrNull {
                    it.name == "getCapabilities" && it.parameterTypes.size == 1
                } ?: return@runCatching null
                getCaps.invoke(null, MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            }.getOrNull() ?: return
            val codecs = extractCodecCapabilities(caps) ?: return
            val (h264, others) = codecs.partition { codec ->
                val name = runCatching {
                    codec.javaClass.methods.firstOrNull { it.name == "getName" }?.invoke(codec) as? String
                }.getOrNull()
                name?.equals("H264", ignoreCase = true) == true
            }
            if (h264.isEmpty()) return
            val ordered = ArrayList<Any>(codecs.size).apply {
                addAll(h264)
                addAll(others)
            }
            val applied = runCatching {
                transceiver.javaClass.methods.firstOrNull {
                    it.name == "setCodecPreferences" && it.parameterTypes.size == 1
                }?.invoke(transceiver, ordered) as? Boolean
            }.getOrNull()
            Log.d(TAG, "Prefer H264: applied=$applied codecs=${h264.size}")
        } catch (t: Throwable) {
            Log.w(TAG, "preferH264Codec error", t)
        }
    }

    private fun extractCodecCapabilities(caps: Any): List<Any>? {
        return runCatching {
            val getter = caps.javaClass.methods.firstOrNull { it.name == "getCodecs" }
            val codecs = getter?.invoke(caps)
                ?: caps.javaClass.fields.firstOrNull { it.name == "codecs" }?.get(caps)
            @Suppress("UNCHECKED_CAST")
            codecs as? List<Any>
        }.getOrNull()
    }

    private fun iceFrom(
        candidate: String?,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ): IceCandidate? {
        return try {
            if (candidate.isNullOrBlank() || sdpMLineIndex == null) return null
            val mid = sdpMid ?: ""
            IceCandidate(mid, sdpMLineIndex, candidate)
        } catch (_: Throwable) { null }
    }

    private fun writeOfferEvent(sdp: SessionDescription) {
        val sid = roomCode
        if (sid.isBlank()) return
        val data = mapOf(
            "type" to "offer",
            "from" to "client",
            "sdp" to sdp.description,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("sessions").document(sid).collection("events").add(data)
    }

    private fun writeIceCandidateEvent(cand: IceCandidate) {
        val sid = roomCode
        if (sid.isBlank()) return
        val data = mapOf(
            "type" to "ice",
            "from" to "client",
            "candidate" to (cand.sdp ?: ""),
            "sdpMid" to cand.sdpMid,
            "sdpMLineIndex" to cand.sdpMLineIndex,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("sessions").document(sid).collection("events").add(data)
    }

    private fun writeAnswerEvent(sdp: SessionDescription) {
        val sid = roomCode
        if (sid.isBlank()) return
        val data = mapOf(
            "type" to "answer",
            "from" to "client",
            "sdp" to sdp.description,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("sessions").document(sid).collection("events").add(data)
    }

    private fun listenToSignalEvents() {
        val sid = roomCode
        if (sid.isBlank()) return
        eventsListener?.remove()
        handledEventIds.clear()
        signalListenStartedAtMs = System.currentTimeMillis() - 2000L
        eventsListener = db.collection("sessions")
            .document(sid)
            .collection("events")
            .orderBy("createdAt")
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                for (change in snap.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    if (!handledEventIds.add(doc.id)) continue
                    val type = doc.getString("type") ?: continue
                    val from = doc.getString("from") ?: ""
                    if (from == "client") continue
                    val createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time
                    if (createdAtMs != null && createdAtMs + 1000L < signalListenStartedAtMs) {
                        continue
                    }
                    when (type) {
                        "offer" -> {
                            val sdp = doc.getString("sdp") ?: ""
                            handleSignalOffer(sdp)
                        }
                        "answer" -> {
                            val sdp = doc.getString("sdp") ?: ""
                            handleSignalAnswer(sdp)
                        }
                        "ice" -> {
                            val candidate = doc.getString("candidate")
                            val sdpMid = doc.getString("sdpMid")
                            val sdpMLineIndex = (doc.get("sdpMLineIndex") as? Number)?.toInt()
                            handleSignalCandidate(iceFrom(candidate, sdpMid, sdpMLineIndex))
                        }
                        "hangup" -> {
                            sendStatus("stopped", "remote_hangup")
                            stopSelfSafe()
                        }
                    }
                }
            }
    }

    // ---------- DataChannel & util ----------
    private fun startPingLoop() {
        lastPongAt.set(System.currentTimeMillis())
        if (pingRunnable != null) return

        pingRunnable = object : Runnable {
            override fun run() {
                try { sendDc(JSONObject().put("t", "ping")) } catch (_: Throwable) {}
                val silentFor = System.currentTimeMillis() - lastPongAt.get()
                if (silentFor > 25_000) {
                    renegotiate(iceRestart = true)
                    lastPongAt.set(System.currentTimeMillis())
                }
                mainHandler.postDelayed(this, 10_000)
            }
        }
        mainHandler.postDelayed(pingRunnable!!, 1_000)
    }

    private fun handleDcMessage(buffer: DataChannel.Buffer) {
        val data = if (buffer.data.hasRemaining()) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            String(bytes, Charsets.UTF_8)
        } else ""
        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return

        when (obj.optString("t")) {
            "pong" -> lastPongAt.set(System.currentTimeMillis())
            else -> when (obj.optString("cmd")) {
                "stop" -> { sendStatus("stopping", "remote_stop"); stopSelfSafe() }
                "quality" -> {
                    when (obj.optString("level")) {
                        "low" -> {
                            applyEncoderQuality(scaleDownBy = 2.0, maxFps = 20)
                            applySenderParams(maxKbps = 700,  minKbps = 200, maxFps = 20, scaleDown = 2.0)
                        }
                        "mid" -> {
                            applyEncoderQuality(scaleDownBy = 1.33, maxFps = 30)
                            applySenderParams(maxKbps = 1800, minKbps = 600, maxFps = 30, scaleDown = 1.33)
                        }
                        "high" -> {
                            applyEncoderQuality(scaleDownBy = 1.0, maxFps = 30)
                            applySenderParams(maxKbps = 2500, minKbps = 600, maxFps = 30, scaleDown = 1.0)
                        }
                    }
                }
                "request-stats" -> sendStatsOnce()
                "ice-restart"   -> renegotiate(iceRestart = true)
            }
        }
    }

    private fun registerControlChannel(channel: DataChannel?) {
        if (channel == null) return
        dataChannel = channel
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dataChannel?.state() == DataChannel.State.OPEN) {
                    startPingLoop()
                    sendStatus("ready")
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                handleDcMessage(buffer)
            }
        })
    }

    private fun registerCtrlChannel(channel: DataChannel?) {
        if (channel == null) return
        ctrlChannel = channel
        ctrlChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "CTRL channel state=${ctrlChannel?.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = if (buffer.data.hasRemaining()) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    String(bytes, Charsets.UTF_8)
                } else ""
                RemoteCommandBus.onMessage(data)
            }
        })
    }

    private fun sendStatsOnce() {
        val pc = peerConnection ?: return
        pc.getStats { report ->
            var bytesSent = 0L
            var framesEncoded = 0L
            var fps: Int? = null
            var rttMs: Double? = null
            var qlim: String? = null
            var encoderImpl: String? = null
            var packetsLost: Long? = null

            for (s in report.statsMap.values) {
                when (s.type) {
                    "outbound-rtp" -> {
                        (s.members["bytesSent"] as? Number)?.toLong()?.let { bytesSent = it }
                        (s.members["framesEncoded"] as? Number)?.toLong()?.let { framesEncoded = it }
                        (s.members["framesPerSecond"] as? Number)?.toDouble()?.let { fps = it.toInt() }
                        (s.members["qualityLimitationReason"] as? String)?.let { qlim = it }
                        (s.members["encoderImplementation"] as? String)?.let { encoderImpl = it }
                        (s.members["packetsLost"] as? Number)?.toLong()?.let { packetsLost = it }
                    }
                    "remote-inbound-rtp" -> {
                        (s.members["packetsLost"] as? Number)?.toLong()?.let { packetsLost = it }
                    }
                    "candidate-pair" -> {
                        if (s.members["selected"] == true) {
                            (s.members["currentRoundTripTime"] as? Number)?.toDouble()?.let { rttMs = it * 1000.0 }
                        }
                    }
                }
            }
            val js = JSONObject()
                .put("t", "stats")
                .put("bytesSent", bytesSent)
                .put("framesEncoded", framesEncoded)
                .put("fps", fps ?: JSONObject.NULL)
                .put("rttMs", rttMs ?: JSONObject.NULL)
                .put("packetsLost", packetsLost ?: JSONObject.NULL)
                .put("qualityLimitation", qlim ?: JSONObject.NULL)
                .put("encoderImpl", encoderImpl ?: JSONObject.NULL)
            sendDc(js)
        }
    }

    private fun startDebugStatsLoop() {
        if (!isDebugBuild()) return
        if (statsRunnable != null) return
        statsRunnable = object : Runnable {
            override fun run() {
                logDebugStatsOnce()
                mainHandler.postDelayed(this, 5_000)
            }
        }
        mainHandler.postDelayed(statsRunnable!!, 5_000)
    }

    private fun logDebugStatsOnce() {
        val pc = peerConnection ?: return
        pc.getStats { report ->
            var bytesSent = 0L
            var fps: Int? = null
            var rttMs: Double? = null
            var qlim: String? = null
            var packetsLost: Long? = null

            for (s in report.statsMap.values) {
                when (s.type) {
                    "outbound-rtp" -> {
                        (s.members["bytesSent"] as? Number)?.toLong()?.let { bytesSent = it }
                        (s.members["framesPerSecond"] as? Number)?.toDouble()?.let { fps = it.toInt() }
                        (s.members["qualityLimitationReason"] as? String)?.let { qlim = it }
                        (s.members["packetsLost"] as? Number)?.toLong()?.let { packetsLost = it }
                    }
                    "remote-inbound-rtp" -> {
                        (s.members["packetsLost"] as? Number)?.toLong()?.let { packetsLost = it }
                    }
                    "candidate-pair" -> {
                        if (s.members["selected"] == true) {
                            (s.members["currentRoundTripTime"] as? Number)?.toDouble()?.let { rttMs = it * 1000.0 }
                        }
                    }
                }
            }

            Log.d(
                TAG,
                "stats fps=${fps ?: "-"} bytesSent=$bytesSent packetsLost=${packetsLost ?: "-"} " +
                    "rttMs=${rttMs ?: "-"} qualityLimitation=${qlim ?: "-"}"
            )
        }
    }

    private fun sendStatus(status: String, reason: String? = null) {
        val js = JSONObject().put("t", "status").put("status", status)
        if (!reason.isNullOrBlank()) js.put("reason", reason)
        sendDc(js)
    }
    private fun sendDc(obj: JSONObject) {
        val dc = dataChannel ?: return
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    // ---------- Foreground & cleanup ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)

        // Cria o canal **somente** no Android 8.0+ (API 26)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Captura de Tela",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Compartilhamento de tela para suporte remoto"
                }
                nm.createNotificationChannel(ch)
            }
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPending = PendingIntent.getService(this, 0, stopIntent, stopFlags)

        // Pode passar o channelId mesmo em <26; o sistema apenas ignora
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Suporte X")
            .setContentText("Capturando a tela…")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Parar",
                    stopPending
                )
            )
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun stopSelfSafe() {
        pingRunnable?.let { mainHandler.removeCallbacks(it) }
        pingRunnable = null
        statsRunnable?.let { mainHandler.removeCallbacks(it) }
        statsRunnable = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { dataChannel?.close() } catch (_: Exception) {}
        dataChannel = null
        try { ctrlChannel?.close() } catch (_: Exception) {}
        ctrlChannel = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
        try { peerConnection?.close() } catch (_: Exception) {}
        peerConnection = null
        try { eventsListener?.remove() } catch (_: Exception) {}
        eventsListener = null
        signalListenStartedAtMs = 0L
        handledEventIds.clear()
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null
        started = false
        RemoteCommandBus.setSessionActive(false)
        RemoteExecutor.clearCaptureFrameSize()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfSafe()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveCaptureSize(): Pair<Int, Int> {
        val (rawWidth, rawHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(WindowManager::class.java)
            if (windowManager != null) {
                val bounds = windowManager.currentWindowMetrics.bounds
                val width = (bounds.right - bounds.left).coerceAtLeast(1)
                val height = (bounds.bottom - bounds.top).coerceAtLeast(1)
                width to height
            } else {
                val dm = resources.displayMetrics
                dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
            }
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }

        val maxLongSide = if (isHighEndDevice()) 1600 else 1280
        val longSide = maxOf(rawWidth, rawHeight).toDouble()
        val scale = minOf(1.0, maxLongSide / longSide)
        var targetWidth = (rawWidth * scale).toInt().coerceAtLeast(1)
        var targetHeight = (rawHeight * scale).toInt().coerceAtLeast(1)
        if (targetWidth % 2 != 0) targetWidth = (targetWidth - 1).coerceAtLeast(1)
        if (targetHeight % 2 != 0) targetHeight = (targetHeight - 1).coerceAtLeast(1)

        Log.d(TAG, "Capture size raw=${rawWidth}x${rawHeight} target=${targetWidth}x${targetHeight}")
        return targetWidth to targetHeight
    }

    private fun isHighEndDevice(): Boolean {
        val am = getSystemService(ActivityManager::class.java) ?: return false
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMemGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        return totalMemGb >= 6.0 && cores >= 8
    }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
