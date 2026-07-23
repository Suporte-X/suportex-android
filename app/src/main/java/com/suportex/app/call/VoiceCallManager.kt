package com.suportex.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.suportex.app.data.AuthRepository
import com.suportex.app.data.FirebaseDataSource
import com.suportex.app.data.SessionRepository
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
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

internal fun RtcIceConfig.toPeerConnectionIceServers(): List<PeerConnection.IceServer> {
    return iceServers.mapNotNull { server ->
        if (server.urls.isEmpty()) return@mapNotNull null
        runCatching {
            PeerConnection.IceServer.builder(server.urls)
                .apply {
                    server.username?.let(::setUsername)
                    server.credential?.let(::setPassword)
                }
                .createIceServer()
        }.getOrNull()
    }
}

internal data class CallOperationToken(
    val generation: Long,
    val sessionId: String,
    val callId: String,
    val direction: CallDirection
)

internal fun verifiedCallSdp(
    sdp: String?,
    sdpCallId: String?,
    activeCallId: String
): String? = sdp
    ?.takeIf { it.isNotBlank() }
    ?.takeIf { sdpCallId == activeCallId }

/**
 * Compatibilidade temporária com o painel publicado antes dos campos
 * offerCallId/answerCallId, sem reabrir a aceitação de SDP residual.
 *
 * Um SDP etiquetado continua exigindo o callId ativo. Um SDP legado sem etiqueta
 * só é aceito quando mudou em relação ao valor que já existia no início da chamada.
 */
internal class CallSdpCompatibilityGuard {
    private var activeToken: CallOperationToken? = null
    private var initialOfferSdp: String? = null
    private var initialAnswerSdp: String? = null

    fun begin(
        token: CallOperationToken,
        initialOfferSdp: String?,
        initialAnswerSdp: String?
    ) {
        activeToken = token
        this.initialOfferSdp = initialOfferSdp.normalizedSdp()
        this.initialAnswerSdp = initialAnswerSdp.normalizedSdp()
    }

    fun verifyOffer(
        token: CallOperationToken,
        sdp: String?,
        sdpCallId: String?
    ): String? = verify(
        token = token,
        sdp = sdp,
        sdpCallId = sdpCallId,
        initialSdp = initialOfferSdp
    )

    fun verifyAnswer(
        token: CallOperationToken,
        sdp: String?,
        sdpCallId: String?
    ): String? = verify(
        token = token,
        sdp = sdp,
        sdpCallId = sdpCallId,
        initialSdp = initialAnswerSdp
    )

    fun reset() {
        activeToken = null
        initialOfferSdp = null
        initialAnswerSdp = null
    }

    private fun verify(
        token: CallOperationToken,
        sdp: String?,
        sdpCallId: String?,
        initialSdp: String?
    ): String? {
        if (activeToken != token) return null
        val normalizedSdp = sdp.normalizedSdp() ?: return null
        val normalizedCallId = sdpCallId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedCallId != null) {
            return normalizedSdp.takeIf { normalizedCallId == token.callId }
        }
        return normalizedSdp.takeIf { it != initialSdp }
    }

    private fun String?.normalizedSdp(): String? =
        this?.takeIf { it.isNotBlank() }
}

/**
 * Mantém uma identidade imutável para cada chamada.
 *
 * Toda continuação assíncrona precisa apresentar o mesmo token antes de alterar estado,
 * impedindo que autenticação, SDP, ICE ou listeners atrasados reativem uma chamada encerrada.
 */
internal class CallOperationTracker {
    private var generation = 0L
    private var active: CallOperationToken? = null
    private val terminatedKeys = LinkedHashSet<String>()

    @Synchronized
    fun begin(
        sessionId: String,
        callId: String,
        direction: CallDirection
    ): CallOperationToken {
        generation += 1
        return CallOperationToken(
            generation = generation,
            sessionId = sessionId,
            callId = callId,
            direction = direction
        ).also { active = it }
    }

    @Synchronized
    fun current(): CallOperationToken? = active

    @Synchronized
    fun isActive(token: CallOperationToken): Boolean = active == token

    @Synchronized
    fun finish(token: CallOperationToken): Boolean {
        if (active != token) return false
        rememberTerminated(token.sessionId, token.callId)
        active = null
        generation += 1
        return true
    }

    @Synchronized
    fun reset(clearTerminatedHistory: Boolean = true): CallOperationToken? {
        val previous = active
        active = null
        generation += 1
        if (clearTerminatedHistory) terminatedKeys.clear()
        return previous
    }

    @Synchronized
    fun wasTerminated(sessionId: String, callId: String): Boolean =
        terminatedKeys.contains(key(sessionId, callId))

    private fun rememberTerminated(sessionId: String, callId: String) {
        terminatedKeys.add(key(sessionId, callId))
        while (terminatedKeys.size > MAX_TERMINATED_CALLS) {
            val oldest = terminatedKeys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    private fun key(sessionId: String, callId: String): String = "$sessionId\u0000$callId"

    private companion object {
        const val MAX_TERMINATED_CALLS = 16
    }
}

class VoiceCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUpdate: (CallUiUpdate) -> Unit
) {
    private val authRepository = AuthRepository()
    private val sessionRepository = SessionRepository()
    private val rtcIceConfigRepository = RtcIceConfigRepository()
    private val db = FirebaseDataSource.db
    private val callOperations = CallOperationTracker()
    private val sdpCompatibilityGuard = CallSdpCompatibilityGuard()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var sessionReady = false

    @Volatile
    private var bindingGeneration = 0L

    private var callListener: ListenerRegistration? = null
    private var remoteIceListener: ListenerRegistration? = null
    private var bindJob: Job? = null
    private var timeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null

    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var sessionIceConfig: RtcIceConfig? = null

    private var state: CallState = CallState.IDLE
    private var displayedDirection: CallDirection? = null
    private var localAccepted = false
    private var localMicrophoneAuthorized = false
    private var offerSent = false
    private var remoteOfferApplied = false
    private var remoteAnswerApplied = false
    private var callDocumentSeen = false
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    fun bindSession(sessionId: String?) {
        onMain {
            val normalized = sessionId?.trim()?.takeIf { it.isNotBlank() }
            if (this.sessionId == normalized) return@onMain

            releaseOnMain()
            this.sessionId = normalized
            sessionReady = false
            if (normalized == null) return@onMain

            bindingGeneration += 1
            val binding = bindingGeneration
            bindJob = scope.launch(Dispatchers.IO) {
                val ready = ensureSessionReady(normalized, binding)
                if (!ready || !isBoundSession(normalized, binding)) return@launch
                val iceConfig = try {
                    rtcIceConfigRepository.getIceConfig(normalized)
                } catch (error: CancellationException) {
                    if (!isBoundSession(normalized, binding)) throw error
                    RtcIceConfig.safeStunFallback(System.currentTimeMillis())
                } catch (_: Throwable) {
                    RtcIceConfig.safeStunFallback(System.currentTimeMillis())
                }
                if (!isBoundSession(normalized, binding)) return@launch
                onMain {
                    if (isBoundSession(normalized, binding)) {
                        sessionIceConfig = iceConfig
                        listenToCallDocument(normalized, binding)
                    }
                }
            }
        }
    }

    fun startOutgoingCall() {
        onMain {
            val sid = sessionId ?: return@onMain
            if (callOperations.current() != null || state !in RETRYABLE_STATES) return@onMain

            val token = callOperations.begin(
                sessionId = sid,
                callId = UUID.randomUUID().toString(),
                direction = CallDirection.CLIENT_TO_TECH
            )
            resetPerCallState()
            sdpCompatibilityGuard.begin(
                token = token,
                initialOfferSdp = null,
                initialAnswerSdp = null
            )
            if (!VoiceCallForegroundService.startForUserCall(context)) {
                updateState(
                    CallState.FAILED,
                    token.direction,
                    reason = "microphone_service_unavailable"
                )
                finishCall(token)
                return@onMain
            }
            localMicrophoneAuthorized = true
            updateState(CallState.OUTGOING_RINGING, token.direction)
            scheduleTimeout(token)

            val binding = bindingGeneration
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    if (!callOperations.isActive(token)) {
                        return@runCatching
                    }
                    if (!ensureSessionReady(sid, binding)) {
                        if (callOperations.isActive(token)) error("session_not_ready")
                        return@runCatching
                    }
                    val iceConfig = rtcIceConfigRepository.getIceConfig(sid)
                    val iceConfigInstalled = withContext(Dispatchers.Main.immediate) {
                        if (
                            callOperations.isActive(token) &&
                            isBoundSession(sid, binding)
                        ) {
                            sessionIceConfig = iceConfig
                            true
                        } else {
                            false
                        }
                    }
                    if (!iceConfigInstalled) return@runCatching
                    val uid = authRepository.ensureAnonAuth()
                    if (!callOperations.isActive(token) || !isBoundSession(sid, binding)) {
                        return@runCatching
                    }
                    val createdAt = System.currentTimeMillis()
                    val payload = mapOf<String, Any>(
                        "status" to "ringing",
                        "direction" to token.direction.raw,
                        "callId" to token.callId,
                        "fromUid" to uid,
                        "createdAt" to createdAt,
                        "updatedAt" to createdAt,
                        "offerSdp" to FieldValue.delete(),
                        "offerCallId" to FieldValue.delete(),
                        "answerSdp" to FieldValue.delete(),
                        "answerCallId" to FieldValue.delete(),
                        "acceptedAt" to FieldValue.delete(),
                        "reason" to FieldValue.delete(),
                        "endedAt" to FieldValue.delete(),
                        "fromName" to FieldValue.delete(),
                        "toUid" to FieldValue.delete()
                    )
                    callDocument(token).set(payload, SetOptions.merge()).await()
                    onMain {
                        if (callOperations.isActive(token)) callDocumentSeen = true
                    }
                }
                result.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "CALL failed to start", error)
                    onMain { failCall(token, "start_failed") }
                }
            }
        }
    }

    fun acceptIncomingCall() {
        onMain {
            val token = callOperations.current() ?: return@onMain
            if (token.direction != CallDirection.TECH_TO_CLIENT) return@onMain
            if (state != CallState.INCOMING_RINGING && state != CallState.CONNECTING) return@onMain

            if (!VoiceCallForegroundService.startForUserCall(context)) {
                failCall(token, reason = "microphone_service_unavailable")
                return@onMain
            }
            localMicrophoneAuthorized = true
            localAccepted = true
            updateState(CallState.CONNECTING, token.direction)
            cancelRingTimeout()
            val binding = bindingGeneration

            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    if (!callOperations.isActive(token)) {
                        return@runCatching
                    }
                    if (!ensureSessionReady(token.sessionId, binding)) {
                        if (callOperations.isActive(token)) error("session_not_ready")
                        return@runCatching
                    }
                    val iceConfig = rtcIceConfigRepository.getIceConfig(token.sessionId)
                    val iceConfigInstalled = withContext(Dispatchers.Main.immediate) {
                        if (
                            callOperations.isActive(token) &&
                            isBoundSession(token.sessionId, binding)
                        ) {
                            sessionIceConfig = iceConfig
                            true
                        } else {
                            false
                        }
                    }
                    if (!iceConfigInstalled) return@runCatching
                    authRepository.ensureAnonAuth()
                    if (!callOperations.isActive(token)) return@runCatching
                    val updated = updateCallDocumentIfCurrent(
                        token,
                        mapOf(
                            "status" to "accepted",
                            "acceptedAt" to System.currentTimeMillis(),
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                    if (!updated && callOperations.isActive(token)) {
                        error("call_not_current")
                    }
                }
                result.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "CALL failed to accept", error)
                    onMain { failCall(token, "accept_failed") }
                }
            }
        }
    }

    fun declineIncomingCall() {
        onMain {
            val token = callOperations.current() ?: return@onMain
            if (
                token.direction != CallDirection.TECH_TO_CLIENT ||
                state != CallState.INCOMING_RINGING
            ) {
                return@onMain
            }
            updateState(CallState.DECLINED, token.direction, reason = "declined")
            finishCall(token)
            writeTerminalStatus(token, status = "declined", reason = "declined")
        }
    }

    fun endCall() {
        onMain {
            val token = callOperations.current() ?: return@onMain
            updateState(CallState.ENDED, token.direction, reason = "ended")
            finishCall(token)
            writeTerminalStatus(token, status = "ended", reason = "ended")
        }
    }

    fun release() {
        onMain { releaseOnMain() }
    }

    private fun releaseOnMain() {
        val releasedSessionId = sessionId
        bindingGeneration += 1
        bindJob?.cancel()
        bindJob = null
        callListener?.remove()
        callListener = null
        sessionId = null
        sessionReady = false
        sessionIceConfig = null
        VoiceCallForegroundService.stop(context)
        releasedSessionId?.let { releasedSession ->
            clearCachedIceConfig(releasedSession)
        }

        cancelRingTimeout()
        cancelDisconnectGrace()
        callOperations.reset(clearTerminatedHistory = true)
        resetPerCallState()
        cleanupPeerConnection()
        updateState(CallState.IDLE, null)
    }

    private suspend fun ensureSessionReady(sessionId: String, binding: Long): Boolean {
        if (sessionReady && isBoundSession(sessionId, binding)) return true
        val ready = runCatching {
            sessionRepository.ensureClientMembership(sessionId)
        }.getOrElse { error ->
            Log.w(TAG, "CALL session membership not ready", error)
            false
        }
        if (ready && isBoundSession(sessionId, binding)) {
            sessionReady = true
            return true
        }
        return false
    }

    private fun isBoundSession(expectedSessionId: String, binding: Long): Boolean =
        sessionId == expectedSessionId && bindingGeneration == binding

    private fun listenToCallDocument(sessionId: String, binding: Long) {
        callListener?.remove()
        callListener = callDocument(sessionId)
            .addSnapshotListener { snapshot, error ->
                onMain {
                    if (!isBoundSession(sessionId, binding)) return@onMain
                    if (error != null) {
                        Log.e(TAG, "CALL listen error", error)
                        return@onMain
                    }
                    if (snapshot == null || !snapshot.exists()) {
                        val active = callOperations.current()
                        if (
                            active != null &&
                            active.sessionId == sessionId &&
                            callDocumentSeen
                        ) {
                            updateState(CallState.IDLE, null)
                            finishCall(active)
                        }
                        return@onMain
                    }

                    val status = snapshot.getString("status")?.lowercase() ?: return@onMain
                    val activeBeforeSnapshot = callOperations.current()
                    val callId = snapshot.getString("callId")?.trim()?.takeIf { it.isNotBlank() }
                        ?: activeBeforeSnapshot?.callId
                        ?: return@onMain
                    val snapshotDirection = CallDirection.fromRaw(snapshot.getString("direction"))
                        ?: activeBeforeSnapshot?.direction
                        ?: return@onMain

                    if (
                        activeBeforeSnapshot != null &&
                        (
                            activeBeforeSnapshot.sessionId != sessionId ||
                                activeBeforeSnapshot.callId != callId ||
                                activeBeforeSnapshot.direction != snapshotDirection
                            )
                    ) {
                        Log.w(TAG, "CALL ignored snapshot from another active call")
                        return@onMain
                    }

                    if (status in TERMINAL_DOCUMENT_STATES) {
                        if (
                            activeBeforeSnapshot != null &&
                            activeBeforeSnapshot.callId == callId
                        ) {
                            handleRemoteTermination(activeBeforeSnapshot, status)
                        }
                        return@onMain
                    }
                    if (status != "ringing" && status != "accepted") return@onMain
                    if (callOperations.wasTerminated(sessionId, callId)) {
                        Log.d(TAG, "CALL ignored stale snapshot for terminated call")
                        return@onMain
                    }

                    val rawOfferSdp = snapshot.getString("offerSdp")
                    val rawAnswerSdp = snapshot.getString("answerSdp")
                    val token = activeBeforeSnapshot ?: callOperations.begin(
                        sessionId = sessionId,
                        callId = callId,
                        direction = snapshotDirection
                    ).also { newToken ->
                        resetPerCallState()
                        sdpCompatibilityGuard.begin(
                            token = newToken,
                            initialOfferSdp = rawOfferSdp,
                            initialAnswerSdp = rawAnswerSdp
                        )
                    }
                    callDocumentSeen = true

                    val offerCallId = snapshot.getString("offerCallId")
                    val answerCallId = snapshot.getString("answerCallId")
                    val offerSdp = sdpCompatibilityGuard.verifyOffer(
                        token = token,
                        sdp = rawOfferSdp,
                        sdpCallId = offerCallId
                    )
                    val answerSdp = sdpCompatibilityGuard.verifyAnswer(
                        token = token,
                        sdp = rawAnswerSdp,
                        sdpCallId = answerCallId
                    )
                    if (offerSdp != null && offerCallId.isNullOrBlank()) {
                        Log.i(TAG, "CALL accepted compatible untagged offer for active call")
                    }
                    if (answerSdp != null && answerCallId.isNullOrBlank()) {
                        Log.i(TAG, "CALL accepted compatible untagged answer for active call")
                    }
                    when (status) {
                        "ringing" -> handleRinging(token)
                        "accepted" -> handleAccepted(
                            token = token,
                            offerSdp = offerSdp,
                            answerSdp = answerSdp
                        )
                    }
                }
            }
    }

    private fun handleRinging(token: CallOperationToken) {
        if (!callOperations.isActive(token)) return
        when (token.direction) {
            CallDirection.CLIENT_TO_TECH -> {
                if (state in RETRYABLE_STATES) {
                    updateState(CallState.OUTGOING_RINGING, token.direction)
                }
                if (state == CallState.OUTGOING_RINGING) scheduleTimeout(token)
            }
            CallDirection.TECH_TO_CLIENT -> {
                if (state in RETRYABLE_STATES) {
                    updateState(CallState.INCOMING_RINGING, token.direction)
                }
                if (state == CallState.INCOMING_RINGING) scheduleTimeout(token)
            }
        }
    }

    private fun handleAccepted(
        token: CallOperationToken,
        offerSdp: String?,
        answerSdp: String?
    ) {
        if (!callOperations.isActive(token)) return
        when (token.direction) {
            CallDirection.CLIENT_TO_TECH -> {
                if (state == CallState.OUTGOING_RINGING || state == CallState.CONNECTING) {
                    if (state != CallState.IN_CALL) {
                        updateState(CallState.CONNECTING, token.direction)
                    }
                    cancelRingTimeout()
                    startOffererFlowIfReady(token)
                    if (!answerSdp.isNullOrBlank()) {
                        applyAnswer(token, answerSdp)
                    }
                }
            }
            CallDirection.TECH_TO_CLIENT -> {
                if (localAccepted) {
                    if (state != CallState.IN_CALL) {
                        updateState(CallState.CONNECTING, token.direction)
                    }
                    cancelRingTimeout()
                    startAnswererFlowIfReady(token, offerSdp)
                }
            }
        }
    }

    private fun handleRemoteTermination(token: CallOperationToken, status: String) {
        if (!callOperations.isActive(token)) return
        when (status) {
            "declined" -> updateState(CallState.DECLINED, token.direction, status)
            "timeout" -> updateState(CallState.TIMEOUT, token.direction, status)
            else -> updateState(CallState.ENDED, token.direction, status)
        }
        finishCall(token)
    }

    private fun scheduleTimeout(token: CallOperationToken) {
        cancelRingTimeout()
        timeoutJob = scope.launch(Dispatchers.Main) {
            delay(TIMEOUT_MS)
            if (!callOperations.isActive(token)) return@launch
            if (state != CallState.OUTGOING_RINGING && state != CallState.INCOMING_RINGING) {
                return@launch
            }
            updateState(CallState.TIMEOUT, token.direction, reason = "timeout")
            finishCall(token)
            writeTerminalStatus(token, status = "timeout", reason = "timeout")
        }
    }

    private fun cancelRingTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun scheduleDisconnectFail(token: CallOperationToken) {
        cancelDisconnectGrace()
        disconnectGraceJob = scope.launch(Dispatchers.Main) {
            delay(DISCONNECT_GRACE_MS)
            if (!callOperations.isActive(token)) return@launch
            if (state != CallState.IN_CALL && state != CallState.CONNECTING) return@launch
            failCall(token, reason = "webrtc_disconnected")
        }
    }

    private fun cancelDisconnectGrace() {
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null
    }

    private fun failCall(token: CallOperationToken, reason: String) {
        if (!callOperations.isActive(token)) return
        updateState(CallState.FAILED, token.direction, reason)
        finishCall(token)
        writeTerminalStatus(token, status = "ended", reason = reason)
    }

    private fun finishCall(token: CallOperationToken): Boolean {
        if (!callOperations.finish(token)) return false
        cancelRingTimeout()
        cancelDisconnectGrace()
        VoiceCallForegroundService.stop(context)
        if (sessionId == token.sessionId) {
            sessionIceConfig = null
        }
        clearCachedIceConfig(token.sessionId)
        resetPerCallState()
        cleanupPeerConnection()
        return true
    }

    private fun resetPerCallState() {
        sdpCompatibilityGuard.reset()
        localAccepted = false
        localMicrophoneAuthorized = false
        offerSent = false
        remoteOfferApplied = false
        remoteAnswerApplied = false
        callDocumentSeen = false
        pendingRemoteIceCandidates.clear()
    }

    private fun startOffererFlowIfReady(token: CallOperationToken) {
        if (!callOperations.isActive(token) || offerSent) return
        val pc = ensurePeerConnection(token) ?: return
        startRemoteIceListener(token)
        addAudioTrackIfNeeded(token)
        offerSent = true
        createOffer(token, pc)
    }

    private fun startAnswererFlowIfReady(
        token: CallOperationToken,
        offerSdp: String? = null
    ) {
        if (!callOperations.isActive(token) || !localAccepted) return
        ensurePeerConnection(token) ?: return
        startRemoteIceListener(token)
        addAudioTrackIfNeeded(token)
        if (!offerSdp.isNullOrBlank()) {
            applyOfferAndAnswer(token, offerSdp)
        }
    }

    private fun ensurePeerConnection(token: CallOperationToken): PeerConnection? {
        if (!callOperations.isActive(token)) return null
        if (
            !VoiceCallForegroundPolicy.canCapture(
                locallyAuthorizedForCall = localMicrophoneAuthorized,
                recordAudioGranted =
                    VoiceCallForegroundService.hasRecordAudioPermission(context)
            )
        ) {
            failCall(token, reason = "microphone_not_authorized")
            return null
        }
        peerConnection?.let { return it }

        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .createInitializationOptions()
            )
            val audioModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
            factory = try {
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioModule)
                    .createPeerConnectionFactory()
            } finally {
                audioModule.release()
            }
        }

        val now = System.currentTimeMillis()
        val iceConfig = sessionIceConfig
            ?.takeIf { config ->
                config.expiresAtEpochMillis - now > ICE_EXPIRY_SAFETY_MILLIS
            }
            ?: RtcIceConfig.safeStunFallback(now)
        val iceServers = iceConfig.toPeerConnectionIceServers()
        val createdPeerConnection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            },
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    writeIceCandidate(token, candidate)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    onMain {
                        if (!callOperations.isActive(token)) return@onMain
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                cancelDisconnectGrace()
                                Log.d(TAG, "CALL connected")
                                updateState(CallState.IN_CALL, token.direction)
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "CALL disconnected (grace)")
                                scheduleDisconnectFail(token)
                            }
                            PeerConnection.PeerConnectionState.FAILED -> {
                                cancelDisconnectGrace()
                                Log.w(TAG, "CALL failed")
                                failCall(token, reason = "webrtc_failed")
                            }
                            else -> Unit
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (
                        state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED
                    ) {
                        onMain {
                            if (!callOperations.isActive(token)) return@onMain
                            cancelDisconnectGrace()
                            updateState(CallState.IN_CALL, token.direction)
                        }
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
        if (createdPeerConnection == null) {
            failCall(token, reason = "peer_connection_unavailable")
            return null
        }
        peerConnection = createdPeerConnection
        Log.d(TAG, "CALL peer connection created")
        configureAudioMode(true)
        return createdPeerConnection
    }

    private fun addAudioTrackIfNeeded(token: CallOperationToken) {
        if (!callOperations.isActive(token) || audioTrack != null) return
        val activeFactory = factory ?: return
        val activePeerConnection = peerConnection ?: return
        audioSource = activeFactory.createAudioSource(MediaConstraints())
        audioTrack = activeFactory.createAudioTrack("audio", audioSource)
        activePeerConnection.addTrack(audioTrack, listOf("sxs-audio"))
        Log.d(TAG, "CALL audio track added")
    }

    private fun createOffer(token: CallOperationToken, pc: PeerConnection) {
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                if (!callOperations.isActive(token)) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        if (!callOperations.isActive(token)) return
                        Log.d(TAG, "CALL offer saved")
                        writeOffer(token, desc)
                    }

                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "CALL setLocalDescription failed: $error")
                        onMain { failCall(token, error) }
                    }

                    override fun onCreateFailure(error: String) = Unit
                    override fun onCreateSuccess(desc: SessionDescription) = Unit
                }, desc)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "CALL createOffer failed: $error")
                onMain { failCall(token, error) }
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private fun applyOfferAndAnswer(token: CallOperationToken, offer: String) {
        if (!callOperations.isActive(token) || remoteOfferApplied) return
        val pc = peerConnection ?: return
        remoteOfferApplied = true
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, offer)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                onMain {
                    if (!callOperations.isActive(token)) return@onMain
                    Log.d(TAG, "CALL offer applied")
                    flushPendingRemoteIceCandidates(token)
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            if (!callOperations.isActive(token)) return
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    if (!callOperations.isActive(token)) return
                                    Log.d(TAG, "CALL answer saved")
                                    writeAnswer(token, desc)
                                }

                                override fun onSetFailure(error: String) {
                                    Log.e(TAG, "CALL setLocalDescription failed: $error")
                                    onMain { failCall(token, error) }
                                }

                                override fun onCreateFailure(error: String) = Unit
                                override fun onCreateSuccess(desc: SessionDescription) = Unit
                            }, desc)
                        }

                        override fun onCreateFailure(error: String) {
                            Log.e(TAG, "CALL createAnswer failed: $error")
                            onMain { failCall(token, error) }
                        }

                        override fun onSetSuccess() = Unit
                        override fun onSetFailure(error: String) = Unit
                    }, MediaConstraints())
                }
            }

            override fun onSetFailure(error: String) {
                Log.e(TAG, "CALL setRemoteDescription failed: $error")
                onMain { failCall(token, error) }
            }

            override fun onCreateFailure(error: String) = Unit
            override fun onCreateSuccess(desc: SessionDescription) = Unit
        }, offerDesc)
    }

    private fun applyAnswer(token: CallOperationToken, answer: String) {
        if (!callOperations.isActive(token) || remoteAnswerApplied) return
        val pc = peerConnection ?: return
        remoteAnswerApplied = true
        val answerDescription = SessionDescription(SessionDescription.Type.ANSWER, answer)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                onMain {
                    if (!callOperations.isActive(token)) return@onMain
                    Log.d(TAG, "CALL answer applied")
                    flushPendingRemoteIceCandidates(token)
                }
            }

            override fun onSetFailure(error: String) {
                Log.e(TAG, "CALL apply answer failed: $error")
                onMain { failCall(token, error) }
            }

            override fun onCreateFailure(error: String) = Unit
            override fun onCreateSuccess(desc: SessionDescription) = Unit
        }, answerDescription)
    }

    private fun startRemoteIceListener(token: CallOperationToken) {
        if (!callOperations.isActive(token) || remoteIceListener != null) return
        val remoteCollection = if (token.direction == CallDirection.CLIENT_TO_TECH) {
            "call_ice_tech"
        } else {
            "call_ice_client"
        }
        remoteIceListener = db.collection("sessions").document(token.sessionId)
            .collection(remoteCollection)
            .whereEqualTo("callId", token.callId)
            .addSnapshotListener { snapshot, error ->
                onMain {
                    if (!callOperations.isActive(token)) return@onMain
                    if (error != null) {
                        Log.e(TAG, "CALL ICE listen error", error)
                        return@onMain
                    }
                    snapshot?.documentChanges?.forEach { change ->
                        if (change.type != DocumentChange.Type.ADDED) return@forEach
                        val data = change.document
                        val sdp = data.getString("sdp") ?: return@forEach
                        val sdpMid = data.getString("sdpMid")
                        val sdpMLineIndex = data.getLong("sdpMLineIndex")?.toInt() ?: 0
                        handleRemoteIceCandidate(
                            token,
                            IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        )
                    }
                }
            }
    }

    private fun handleRemoteIceCandidate(
        token: CallOperationToken,
        candidate: IceCandidate
    ) {
        if (!callOperations.isActive(token)) return
        val pc = peerConnection ?: return
        if (pc.remoteDescription == null) {
            pendingRemoteIceCandidates.add(candidate)
            Log.d(TAG, "CALL ICE remote buffered")
            return
        }
        pc.addIceCandidate(candidate)
        Log.d(TAG, "CALL ICE remote applied")
    }

    private fun flushPendingRemoteIceCandidates(token: CallOperationToken) {
        if (!callOperations.isActive(token)) return
        val pc = peerConnection ?: return
        if (pc.remoteDescription == null || pendingRemoteIceCandidates.isEmpty()) return
        pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingRemoteIceCandidates.clear()
        Log.d(TAG, "CALL ICE remote flush complete")
    }

    private fun writeOffer(token: CallOperationToken, desc: SessionDescription) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (!callOperations.isActive(token)) return@runCatching
                authRepository.ensureAnonAuth()
                if (!callOperations.isActive(token)) return@runCatching
                if (
                    !updateCallDocumentIfCurrent(
                        token,
                        mapOf(
                            "offerSdp" to desc.description,
                            "offerCallId" to token.callId,
                            "status" to "accepted",
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                ) {
                    error("offer_not_current")
                }
            }
            result.exceptionOrNull()?.let { error ->
                Log.e(TAG, "CALL failed to write offer", error)
                onMain { failCall(token, "offer_write_failed") }
            }
        }
    }

    private fun writeAnswer(token: CallOperationToken, desc: SessionDescription) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (!callOperations.isActive(token)) return@runCatching
                authRepository.ensureAnonAuth()
                if (!callOperations.isActive(token)) return@runCatching
                if (
                    !updateCallDocumentIfCurrent(
                        token,
                        mapOf(
                            "answerSdp" to desc.description,
                            "answerCallId" to token.callId,
                            "status" to "accepted",
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                ) {
                    error("answer_not_current")
                }
            }
            result.exceptionOrNull()?.let { error ->
                Log.e(TAG, "CALL failed to write answer", error)
                onMain { failCall(token, "answer_write_failed") }
            }
        }
    }

    private fun writeIceCandidate(
        token: CallOperationToken,
        candidate: IceCandidate
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (!callOperations.isActive(token)) return@runCatching
                authRepository.ensureAnonAuth()
                if (!callOperations.isActive(token)) return@runCatching
                val localCollection =
                    if (token.direction == CallDirection.CLIENT_TO_TECH) {
                        "call_ice_client"
                    } else {
                        "call_ice_tech"
                    }
                val payload = mapOf(
                    "callId" to token.callId,
                    "sdp" to candidate.sdp,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "createdAt" to System.currentTimeMillis()
                )
                db.collection("sessions").document(token.sessionId)
                    .collection(localCollection)
                    .add(payload)
                    .await()
            }.onFailure { error ->
                Log.w(TAG, "CALL failed to write ICE", error)
            }
        }
    }

    private fun writeTerminalStatus(
        token: CallOperationToken,
        status: String,
        reason: String
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                authRepository.ensureAnonAuth()
                updateCallDocumentIfCurrent(
                    token,
                    mapOf(
                        "status" to status,
                        "reason" to reason,
                        "endedAt" to System.currentTimeMillis(),
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
            }.onFailure { error ->
                Log.w(TAG, "CALL failed to write terminal status", error)
            }
        }
    }

    private suspend fun updateCallDocumentIfCurrent(
        token: CallOperationToken,
        payload: Map<String, Any>
    ): Boolean {
        return runCatching {
            db.runTransaction { transaction ->
                val reference = callDocument(token)
                val snapshot = transaction.get(reference)
                if (
                    !snapshot.exists() ||
                    snapshot.getString("callId") != token.callId
                ) {
                    false
                } else {
                    transaction.set(reference, payload, SetOptions.merge())
                    true
                }
            }.await()
        }.getOrElse { error ->
            Log.w(TAG, "CALL conditional update failed", error)
            false
        }
    }

    private fun callDocument(token: CallOperationToken) =
        callDocument(token.sessionId)

    private fun callDocument(sessionId: String) =
        db.collection("sessions").document(sessionId)
            .collection("call")
            .document("active")

    private fun updateState(
        newState: CallState,
        direction: CallDirection?,
        reason: String? = null
    ) {
        if (
            state == newState &&
            displayedDirection == direction &&
            reason == null
        ) {
            return
        }
        state = newState
        displayedDirection = direction
        if (VoiceCallForegroundPolicy.shouldStopFor(newState)) {
            VoiceCallForegroundService.stop(context)
        }
        Log.d(TAG, "CALL state -> $newState")
        onUpdate(CallUiUpdate(newState, direction, reason))
    }

    private fun cleanupPeerConnection() {
        cancelDisconnectGrace()
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

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            scope.launch(Dispatchers.Main.immediate) { block() }
        }
    }

    private fun clearCachedIceConfig(sessionId: String) {
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            rtcIceConfigRepository.clearSession(sessionId)
        }
    }

    private companion object {
        const val TAG = "SXS/Call"
        const val TIMEOUT_MS = 20_000L
        const val DISCONNECT_GRACE_MS = 10_000L
        const val ICE_EXPIRY_SAFETY_MILLIS = 60_000L

        val RETRYABLE_STATES = setOf(
            CallState.IDLE,
            CallState.ENDED,
            CallState.FAILED,
            CallState.DECLINED,
            CallState.TIMEOUT
        )
        val TERMINAL_DOCUMENT_STATES = setOf(
            "declined",
            "ended",
            "timeout"
        )
    }
}
