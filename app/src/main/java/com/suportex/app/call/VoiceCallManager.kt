package com.suportex.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.suportex.app.data.AuthRepository
import com.suportex.app.data.FirebaseDataSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.audio.JavaAudioDeviceModule

enum class CallDirection(val raw: String) {
    CLIENT_TO_TECH("client_to_tech"),
    TECH_TO_CLIENT("tech_to_client");

    companion object {
        fun fromRaw(raw: String?): CallDirection? =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) }
    }
}

enum class CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    IN_CALL,
    ENDED,
    FAILED,
    DECLINED,
    TIMEOUT
}

data class CallUiUpdate(
    val state: CallState,
    val direction: CallDirection?,
    val reason: String? = null
)

class VoiceCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUpdate: (CallUiUpdate) -> Unit
) {
    private val authRepository = AuthRepository()
    private val db = FirebaseDataSource.db

    private var sessionId: String? = null
    private var currentCallId: String? = null
    private var callListener: ListenerRegistration? = null
    private var remoteIceListener: ListenerRegistration? = null
    private var timeoutJob: Job? = null

    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var audioManager: AudioManager? = null

    private var state: CallState = CallState.IDLE
    private var direction: CallDirection? = null
    private var localAccepted = false
    private var offerSent = false
    private var remoteOfferApplied = false
    private var remoteAnswerApplied = false
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    fun bindSession(sessionId: String?) {
        if (this.sessionId == sessionId) return
        release()
        this.sessionId = sessionId
        if (sessionId.isNullOrBlank()) return
        listenToCallDocument(sessionId)
    }

    fun startOutgoingCall() {
        val sid = sessionId ?: return
        if (state != CallState.IDLE) return
        val callId = UUID.randomUUID().toString()
        currentCallId = callId
        direction = CallDirection.CLIENT_TO_TECH
        localAccepted = false
        offerSent = false
        remoteOfferApplied = false
        remoteAnswerApplied = false
        updateState(CallState.OUTGOING_RINGING, direction)
        scheduleTimeout()

        scope.launch(Dispatchers.IO) {
            val uid = authRepository.ensureAnonAuth()
            val payload = mapOf(
                "status" to "ringing",
                "direction" to direction!!.raw,
                "callId" to callId,
                "fromUid" to uid,
                "createdAt" to System.currentTimeMillis()
            )
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .set(payload, SetOptions.merge())
        }
    }

    fun acceptIncomingCall() {
        val sid = sessionId ?: return
        if (state != CallState.INCOMING_RINGING && state != CallState.CONNECTING) return
        localAccepted = true
        updateState(CallState.CONNECTING, direction)
        scheduleTimeout(cancelOnly = true)
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            val payload = mapOf(
                "status" to "accepted",
                "acceptedAt" to System.currentTimeMillis()
            )
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .set(payload, SetOptions.merge())
        }
        startAnswererFlowIfReady()
    }

    fun declineIncomingCall() {
        val sid = sessionId ?: return
        if (state != CallState.INCOMING_RINGING) return
        updateState(CallState.DECLINED, direction, reason = "declined")
        scheduleTimeout(cancelOnly = true)
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            val payload = mapOf(
                "status" to "declined",
                "endedAt" to System.currentTimeMillis()
            )
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .set(payload, SetOptions.merge())
        }
        cleanupCall()
    }

    fun endCall() {
        val sid = sessionId ?: return
        if (state == CallState.IDLE) return
        updateState(CallState.ENDED, direction, reason = "ended")
        scheduleTimeout(cancelOnly = true)
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            val payload = mapOf(
                "status" to "ended",
                "endedAt" to System.currentTimeMillis()
            )
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .set(payload, SetOptions.merge())
        }
        cleanupCall()
        updateState(CallState.IDLE, null)
    }

    fun release() {
        scheduleTimeout(cancelOnly = true)
        cleanupCall()
        callListener?.remove()
        callListener = null
        sessionId = null
    }

    private fun listenToCallDocument(sessionId: String) {
        callListener = db.collection("sessions").document(sessionId)
            .collection("call")
            .document("active")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "CALL listen error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    updateState(CallState.IDLE, direction)
                    cleanupCall()
                    return@addSnapshotListener
                }

                val status = snapshot.getString("status")?.lowercase()
                val directionRaw = snapshot.getString("direction")
                direction = CallDirection.fromRaw(directionRaw)
                val callId = snapshot.getString("callId")
                if (!callId.isNullOrBlank() && callId != currentCallId) {
                    currentCallId = callId
                    offerSent = false
                    remoteOfferApplied = false
                    remoteAnswerApplied = false
                    localAccepted = false
                    pendingRemoteIceCandidates.clear()
                    cleanupPeerConnection()
                }

                when (status) {
                    "ringing" -> handleRinging()
                    "accepted" -> handleAccepted(snapshot.getString("offerSdp"), snapshot.getString("answerSdp"))
                    "declined" -> handleDeclined("declined")
                    "ended" -> handleDeclined("ended")
                    "timeout" -> handleDeclined("timeout")
                }

                if (status == "accepted") {
                    if (direction == CallDirection.CLIENT_TO_TECH) {
                        val answer = snapshot.getString("answerSdp")
                        if (!answer.isNullOrBlank()) {
                            applyAnswer(answer)
                        }
                    } else {
                        val offer = snapshot.getString("offerSdp")
                        if (!offer.isNullOrBlank()) {
                            startAnswererFlowIfReady(offer)
                        }
                    }
                }
            }
    }

    private fun handleRinging() {
        when (direction) {
            CallDirection.CLIENT_TO_TECH -> {
                if (state != CallState.OUTGOING_RINGING && state != CallState.CONNECTING) {
                    updateState(CallState.OUTGOING_RINGING, direction)
                    scheduleTimeout()
                }
            }
            CallDirection.TECH_TO_CLIENT -> {
                if (state != CallState.INCOMING_RINGING) {
                    updateState(CallState.INCOMING_RINGING, direction)
                    scheduleTimeout()
                }
            }
            null -> Unit
        }
    }

    private fun handleAccepted(offerSdp: String?, answerSdp: String?) {
        when (direction) {
            CallDirection.CLIENT_TO_TECH -> {
                if (state == CallState.OUTGOING_RINGING || state == CallState.CONNECTING) {
                    updateState(CallState.CONNECTING, direction)
                    scheduleTimeout(cancelOnly = true)
                    startOffererFlowIfReady()
                    if (!answerSdp.isNullOrBlank()) {
                        applyAnswer(answerSdp)
                    }
                }
            }
            CallDirection.TECH_TO_CLIENT -> {
                if (localAccepted) {
                    // Evita regressão de estado: uma chamada já conectada nunca deve
                    // voltar para CONNECTING por novo snapshot "accepted".
                    if (state != CallState.IN_CALL) {
                        updateState(CallState.CONNECTING, direction)
                    }
                    scheduleTimeout(cancelOnly = true)
                    startAnswererFlowIfReady(offerSdp)
                }
            }
            null -> Unit
        }
    }

    private fun handleDeclined(reason: String) {
        when (reason) {
            "declined" -> updateState(CallState.DECLINED, direction, reason)
            "timeout" -> updateState(CallState.TIMEOUT, direction, reason)
            else -> updateState(CallState.ENDED, direction, reason)
        }
        scheduleTimeout(cancelOnly = true)
        cleanupCall()
        updateState(CallState.IDLE, null)
        clearActiveCallDocument()
    }

    private fun scheduleTimeout(cancelOnly: Boolean = false) {
        timeoutJob?.cancel()
        timeoutJob = null
        if (cancelOnly) return
        timeoutJob = scope.launch(Dispatchers.Main) {
            delay(TIMEOUT_MS)
            if (state == CallState.OUTGOING_RINGING || state == CallState.INCOMING_RINGING) {
                val sid = sessionId ?: return@launch
                updateState(CallState.TIMEOUT, direction, reason = "timeout")
                scope.launch(Dispatchers.IO) {
                    authRepository.ensureAnonAuth()
                    val payload = mapOf(
                        "status" to "timeout",
                        "endedAt" to System.currentTimeMillis()
                    )
                    db.collection("sessions").document(sid)
                        .collection("call")
                        .document("active")
                        .set(payload, SetOptions.merge())
                }
                cleanupCall()
            }
        }
    }

    private fun startOffererFlowIfReady() {
        if (offerSent) return
        ensurePeerConnection()
        startRemoteIceListener()
        addAudioTrackIfNeeded()
        createOffer()
    }

    private fun startAnswererFlowIfReady(offerSdp: String? = null) {
        if (!localAccepted) return
        ensurePeerConnection()
        startRemoteIceListener()
        addAudioTrackIfNeeded()
        if (!offerSdp.isNullOrBlank()) {
            applyOfferAndAnswer(offerSdp)
        }
    }

    private fun ensurePeerConnection() {
        if (peerConnection != null) return
        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .createInitializationOptions()
            )
            val audioModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory()
        }

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = factory!!.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            },
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    writeIceCandidate(candidate)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            Log.d(TAG, "CALL connected")
                            updateState(CallState.IN_CALL, direction)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.w(TAG, "CALL disconnected")
                            updateState(CallState.FAILED, direction, reason = "webrtc_failed")
                            cleanupCall()
                        }
                        else -> Unit
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            updateState(CallState.IN_CALL, direction)
                        }
                        else -> Unit
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
            }
        )
        Log.d(TAG, "CALL peer connection created")
        configureAudioMode(true)
    }

    private fun addAudioTrackIfNeeded() {
        if (audioTrack != null) return
        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("audio", audioSource)
        peerConnection?.addTrack(audioTrack, listOf("sxs-audio"))
        Log.d(TAG, "CALL audio track added")
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        offerSent = true
                        Log.d(TAG, "CALL offer saved")
                        writeOffer(desc)
                    }

                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "CALL setLocalDescription failed: $error")
                        updateState(CallState.FAILED, direction, reason = error)
                    }

                    override fun onCreateFailure(error: String) = Unit
                    override fun onCreateSuccess(p0: SessionDescription) = Unit
                }, desc)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "CALL createOffer failed: $error")
                updateState(CallState.FAILED, direction, reason = error)
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private fun applyOfferAndAnswer(offer: String) {
        if (remoteOfferApplied) return
        val pc = peerConnection ?: return
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, offer)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteOfferApplied = true
                Log.d(TAG, "CALL offer applied")
                flushPendingRemoteIceCandidates()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "CALL answer saved")
                                writeAnswer(desc)
                            }

                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "CALL setLocalDescription failed: $error")
                            }

                            override fun onCreateFailure(error: String) = Unit
                            override fun onCreateSuccess(p0: SessionDescription) = Unit
                        }, desc)
                    }

                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "CALL createAnswer failed: $error")
                    }

                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(error: String) = Unit
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String) {
                Log.e(TAG, "CALL setRemoteDescription failed: $error")
            }

            override fun onCreateFailure(error: String) = Unit
            override fun onCreateSuccess(p0: SessionDescription) = Unit
        }, offerDesc)
    }

    private fun applyAnswer(answer: String) {
        if (remoteAnswerApplied) return
        val pc = peerConnection ?: return
        val desc = SessionDescription(SessionDescription.Type.ANSWER, answer)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteAnswerApplied = true
                Log.d(TAG, "CALL answer applied")
                flushPendingRemoteIceCandidates()
            }

            override fun onSetFailure(error: String) {
                Log.e(TAG, "CALL apply answer failed: $error")
            }

            override fun onCreateFailure(error: String) = Unit
            override fun onCreateSuccess(p0: SessionDescription) = Unit
        }, desc)
    }

    private fun startRemoteIceListener() {
        val sid = sessionId ?: return
        val callId = currentCallId ?: return
        if (remoteIceListener != null) return
        val remoteCollection = if (direction == CallDirection.CLIENT_TO_TECH) {
            "call_ice_tech"
        } else {
            "call_ice_client"
        }
        remoteIceListener = db.collection("sessions").document(sid)
            .collection(remoteCollection)
            .whereEqualTo("callId", callId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "CALL ICE listen error", error)
                    return@addSnapshotListener
                }
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val data = change.document
                        val sdp = data.getString("sdp") ?: return@forEach
                        val sdpMid = data.getString("sdpMid")
                        val sdpMLineIndex = data.getLong("sdpMLineIndex")?.toInt() ?: 0
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        handleRemoteIceCandidate(candidate)
                    }
                }
            }
    }

    private fun handleRemoteIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        if (pc.remoteDescription == null) {
            pendingRemoteIceCandidates.add(candidate)
            Log.d(TAG, "CALL ICE remote buffered")
            return
        }
        pc.addIceCandidate(candidate)
        Log.d(TAG, "CALL ICE remote applied")
    }

    private fun flushPendingRemoteIceCandidates() {
        val pc = peerConnection ?: return
        if (pc.remoteDescription == null || pendingRemoteIceCandidates.isEmpty()) return
        pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingRemoteIceCandidates.clear()
        Log.d(TAG, "CALL ICE remote flush complete")
    }

    private fun writeOffer(desc: SessionDescription) {
        val sid = sessionId ?: return
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            val payload = mapOf(
                "offerSdp" to desc.description,
                "status" to "accepted"
            )
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .set(payload, SetOptions.merge())
        }
    }

    private fun writeAnswer(desc: SessionDescription) {
        val sid = sessionId ?: return
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            val payload = mapOf(
                "answerSdp" to desc.description,
                "status" to "accepted"
            )
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .set(payload, SetOptions.merge())
        }
    }

    private fun writeIceCandidate(candidate: IceCandidate) {
        val sid = sessionId ?: return
        val callId = currentCallId ?: return
        val localCollection = if (direction == CallDirection.CLIENT_TO_TECH) {
            "call_ice_client"
        } else {
            "call_ice_tech"
        }
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            val payload = mapOf(
                "callId" to callId,
                "sdp" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "createdAt" to System.currentTimeMillis()
            )
            db.collection("sessions").document(sid)
                .collection(localCollection)
                .add(payload)
        }
    }

    private fun updateState(newState: CallState, direction: CallDirection?, reason: String? = null) {
        if (state == newState && reason == null) return
        state = newState
        Log.d(TAG, "CALL state -> $newState")
        onUpdate(CallUiUpdate(newState, direction, reason))
    }

    private fun cleanupCall() {
        currentCallId = null
        localAccepted = false
        offerSent = false
        remoteOfferApplied = false
        remoteAnswerApplied = false
        pendingRemoteIceCandidates.clear()
        direction = null
        cleanupPeerConnection()
    }

    private fun clearActiveCallDocument() {
        val sid = sessionId ?: return
        scope.launch(Dispatchers.IO) {
            authRepository.ensureAnonAuth()
            db.collection("sessions").document(sid)
                .collection("call")
                .document("active")
                .delete()
        }
    }

    private fun cleanupPeerConnection() {
        remoteIceListener?.remove()
        remoteIceListener = null
        peerConnection?.close()
        peerConnection = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        configureAudioMode(false)
    }

    private fun configureAudioMode(enable: Boolean) {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        audioManager?.let {
            if (enable) {
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speakerDevice = it.availableCommunicationDevices.firstOrNull { device ->
                        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        it.setCommunicationDevice(speakerDevice)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    it.isSpeakerphoneOn = true
                }
            } else {
                it.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    it.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    it.isSpeakerphoneOn = false
                }
            }
        }
    }

    private companion object {
        const val TAG = "SXS/Call"
        const val TIMEOUT_MS = 20_000L
    }
}
