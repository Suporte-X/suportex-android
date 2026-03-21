package com.suportex.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.media.projection.MediaProjectionManager
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.suportex.app.data.ClientSupportRepository
import com.suportex.app.data.FirebasePhoneIdentityProvider
import com.suportex.app.data.SupportBillingConfig
import com.suportex.app.data.model.Message
import com.suportex.app.data.AuthRepository
import com.suportex.app.data.SessionClientInfo
import com.suportex.app.data.SessionRepository
import com.suportex.app.data.SessionState
import com.suportex.app.data.SessionTelemetry
import com.suportex.app.data.SessionTechInfo
import com.suportex.app.data.model.ClientFinancialStatus
import com.suportex.app.data.model.ClientHomeSnapshot
import com.suportex.app.data.model.CreditPackageRecord
import com.suportex.app.data.model.SupportAccessDecision
import com.suportex.app.data.model.SupportStartContext
import com.suportex.app.call.CallDirection
import com.suportex.app.call.CallState
import com.suportex.app.call.CallUiUpdate
import com.suportex.app.call.VoiceCallManager
import com.suportex.app.remote.AccessibilityUtils
import com.suportex.app.remote.RemoteCommandBus
import com.suportex.app.ui.screens.CardPlaceholderScreen
import com.suportex.app.ui.screens.PixPlaceholderScreen
import com.suportex.app.ui.screens.PurchaseCreditsScreen
import com.suportex.app.ui.screens.SessionScreen
import com.suportex.app.ui.screens.SupportHomeScreen
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { HOME, HELP, PRIVACY, TERMS, WAITING, SESSION, PURCHASE_CREDITS, PAYMENT_CARD, PAYMENT_PIX }

private data class SupportFlowFlags(
    val showCreditPanelOnlyForRegisteredClients: Boolean = true,
    val disableQuickIdentificationModal: Boolean = true,
    val technicianDrivenRegistrationEnabled: Boolean = true,
    val pnvPostRegistrationFlow: Boolean = true
)

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // (mantido sÃƒÆ’Ã‚Â³ para cancelar request por HTTP, se desejar)
    private val http = OkHttpClient()

    private val sessionRepository = SessionRepository()
    private val authRepository = AuthRepository()
    private val clientSupportRepository = ClientSupportRepository()
    private val phoneIdentityProvider = FirebasePhoneIdentityProvider()
    private val supportFlowFlags = SupportFlowFlags()

    // Bridges Activity -> Compose (jÃƒÆ’Ã‚Â¡ existiam)
    private var setIsSharingFromLauncher: ((Boolean) -> Unit)? = null
    private var setSystemMessageFromLauncher: ((String?) -> Unit)? = null

    // Bridges Socket -> Compose (novos)
    private var setRequestIdFromSocket: ((String?) -> Unit)? = null
    private var setSessionIdFromSocket: ((String?) -> Unit)? = null
    private var setScreenFromSocket: ((Screen) -> Unit)? = null
    private var setRemoteEnabledFromSocket: ((Boolean) -> Unit)? = null
    private var setCallingFromSocket: ((Boolean) -> Unit)? = null
    private var setCallConnectedFromSocket: ((Boolean) -> Unit)? = null
    private var setCallStateFromManager: ((CallState) -> Unit)? = null
    private var setCallDirectionFromManager: ((CallDirection?) -> Unit)? = null
    private var setTechNameFromSocket: ((String) -> Unit)? = null
    private var setRecordingAudioFromActivity: ((Boolean) -> Unit)? = null

    private var currentSessionId: String? = null
    private lateinit var socket: Socket
    private lateinit var voiceCallManager: VoiceCallManager

    private var telemetryJob: Job? = null
    private var isSharingActive = false
    private var remoteEnabledActive = false
    private var callingActive = false
    private var callConnectedActive = false
    private var shareRequestFromCommand = false
    private var remoteConsentAcceptedForCurrentSession = false
    private var pendingAudioAction: (() -> Unit)? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioTempFile: File? = null
    private var pendingSupportStartContext: SupportStartContext? = null
    private var pendingSupportSessionId: String? = null

    // -------- Helpers --------
    @Suppress("unused")
    private fun deviceId(): String {
        val p = getSharedPreferences("app", MODE_PRIVATE)
        val cur = p.getString("device_id", null)
        if (cur != null) return cur
        val gen = UUID.randomUUID().toString()
        p.edit { putString("device_id", gen) }
        return gen
    }
    @Suppress("unused")
    private fun copyToClipboard(label: String, text: String) {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun sendCommand(type: String, payload: JSONObject? = null) {
        val sid = currentSessionId ?: return
        if (!this::socket.isInitialized) return

        val command = JSONObject().apply {
            put("sessionId", sid)
            put("from", "client")
            put("type", type)
            payload?.let { put("payload", it) }
        }
        socket.emit("session:command", command)
    }

    private fun buildSessionStateSnapshot(): SessionState = SessionState(
        sharing = isSharingActive,
        remoteEnabled = remoteEnabledActive,
        calling = callingActive,
        callConnected = callConnectedActive
    )

    private fun buildTelemetrySnapshot(battery: Int?, net: String?): SessionTelemetry = SessionTelemetry(
        battery = battery,
        net = net,
        sharing = isSharingActive,
        remoteEnabled = remoteEnabledActive,
        calling = callingActive,
        callConnected = callConnectedActive
    )

    private fun pushSessionState(telemetry: SessionTelemetry? = null) {
        val sid = currentSessionId ?: return
        val state = buildSessionStateSnapshot()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { sessionRepository.updateRealtimeState(sid, state, telemetry) }
        }
    }

    private fun logSessionEvent(
        type: String,
        origin: String? = null,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val sid = currentSessionId ?: return
        val payload = buildMap<String, Any?> {
            origin?.let { put("origin", it) }
            extras.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }.takeIf { it.isNotEmpty() }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { sessionRepository.addEvent(sid, type, payload = payload) }
        }
    }

    private fun registerSessionStart(sessionId: String, techName: String?) {
        val clientInfo = SessionClientInfo(
            deviceModel = (Build.MODEL ?: Build.DEVICE ?: "Android"),
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
        )
        val techInfo = techName?.takeIf { it.isNotBlank() }?.let { SessionTechInfo(name = it) }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                sessionRepository.bindClient(sessionId)
                sessionRepository.startSession(sessionId, clientInfo, techInfo)
            }.onFailure { err ->
                Log.e("SXS/Main", "Falha ao registrar inÃƒÆ’Ã‚Â­cio da sessÃƒÆ’Ã‚Â£o $sessionId", err)
            }
        }
    }

    private fun updateSharingState(active: Boolean, origin: String) {
        val previous = isSharingActive
        isSharingActive = active
        runOnUiThread { setIsSharingFromLauncher?.invoke(active) }
        if (previous != active) {
            logSessionEvent(if (active) "share_start" else "share_stop", origin = origin)
        }
        pushSessionState()
        emitTelemetry()
    }

    private fun updateRemoteState(enabled: Boolean, origin: String) {
        val previous = remoteEnabledActive
        remoteEnabledActive = enabled
        RemoteCommandBus.setRemoteEnabled(enabled)
        runOnUiThread { setRemoteEnabledFromSocket?.invoke(enabled) }
        if (previous != enabled) {
            logSessionEvent(if (enabled) "remote_enable" else "remote_revoke", origin = origin)
        }
        pushSessionState()
        emitTelemetry()
    }

    private fun ensureRemoteAccessConsent(onApproved: () -> Unit) {
        if (remoteConsentAcceptedForCurrentSession) {
            onApproved()
            return
        }

        val message = """
            Para que o suporte tÃƒÆ’Ã‚Â©cnico possa ser realizado, o aplicativo Suporte X pode solicitar permissÃƒÆ’Ã‚Âµes temporÃƒÆ’Ã‚Â¡rias como:
            
            ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ compartilhamento de tela
            ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ acesso assistido ao dispositivo
            ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ envio de arquivos e informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes tÃƒÆ’Ã‚Â©cnicas
            
            Essas permissÃƒÆ’Ã‚Âµes sÃƒÆ’Ã‚Â£o utilizadas exclusivamente durante a sessÃƒÆ’Ã‚Â£o de suporte tÃƒÆ’Ã‚Â©cnico.
            
            O acesso remoto somente serÃƒÆ’Ã‚Â¡ iniciado apÃƒÆ’Ã‚Â³s sua autorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o explÃƒÆ’Ã‚Â­cita e pode ser interrompido a qualquer momento diretamente no aplicativo.
            
            Nenhuma aÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o serÃƒÆ’Ã‚Â¡ realizada no dispositivo sem a autorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o do usuÃƒÆ’Ã‚Â¡rio.
            
            Ao continuar, vocÃƒÆ’Ã‚Âª confirma que estÃƒÆ’Ã‚Â¡ solicitando suporte tÃƒÆ’Ã‚Â©cnico e autoriza temporariamente o acesso necessÃƒÆ’Ã‚Â¡rio para a realizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o do atendimento.
        """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("AutorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de Acesso Remoto")
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Continuar") { dialog, _ ->
                remoteConsentAcceptedForCurrentSession = true
                dialog.dismiss()
                onApproved()
            }
            .show()
    }

    private fun requestRemoteStateChange(enabled: Boolean) {
        if (enabled) {
            ensureRemoteAccessConsent {
                if (!isAccessibilityServiceEnabled()) {
                    openAccessibilitySettings()
                    setSystemMessageFromLauncher?.invoke(
                        "Ative em Acessibilidade ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ SuporteX ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ Ativar para permitir o controle remoto."
                    )
                }
                updateRemoteState(enabled = true, origin = "client")
                sendCommand("remote_enable")
            }
            return
        }

        updateRemoteState(enabled = false, origin = "client")
        sendCommand("remote_revoke")
    }

    private fun updateCallState(calling: Boolean, connected: Boolean, origin: String) {
        val previousCalling = callingActive
        callingActive = calling
        callConnectedActive = connected
        runOnUiThread {
            setCallingFromSocket?.invoke(calling)
            setCallConnectedFromSocket?.invoke(connected)
        }
        if (!previousCalling && calling) {
            logSessionEvent("call_start", origin = origin, extras = mapOf("connected" to connected))
        } else if (previousCalling && !calling) {
            logSessionEvent("call_end", origin = origin)
        }
        pushSessionState()
        emitTelemetry()
    }

    private fun handleCallUpdate(update: CallUiUpdate) {
        val calling = update.state in setOf(
            CallState.OUTGOING_RINGING,
            CallState.INCOMING_RINGING,
            CallState.CONNECTING,
            CallState.IN_CALL
        )
        val connected = update.state == CallState.IN_CALL
        updateCallState(calling = calling, connected = connected, origin = "webrtc")
        runOnUiThread {
            setCallStateFromManager?.invoke(update.state)
            setCallDirectionFromManager?.invoke(update.direction)
        }
        when (update.state) {
            CallState.DECLINED -> setSystemMessageFromLauncher?.invoke("Chamada recusada.")
            CallState.TIMEOUT -> setSystemMessageFromLauncher?.invoke("Sem resposta.")
            CallState.FAILED -> setSystemMessageFromLauncher?.invoke("Falha na chamada.")
            CallState.ENDED -> setSystemMessageFromLauncher?.invoke("Chamada encerrada.")
            else -> Unit
        }
    }

    private fun emitTelemetry() {
        val sid = currentSessionId ?: return
        if (!this::socket.isInitialized) return

        val battery = getBatteryLevel()
        val network = getNetworkType()
        val data = JSONObject().apply {
            put("sessionId", sid)
            put("from", "client")
            val status = JSONObject()
            status.put("battery", battery ?: JSONObject.NULL)
            status.put("net", network)
            status.put("sharing", isSharingActive)
            status.put("remoteEnabled", remoteEnabledActive)
            status.put("calling", callingActive)
            status.put("callConnected", callConnectedActive)
            put("data", status)
        }
        socket.emit("session:telemetry", data)
        logSessionEvent(
            type = "telemetry",
            origin = "client",
            extras = mapOf(
                "battery" to battery,
                "net" to network,
                "sharing" to isSharingActive,
                "remoteEnabled" to remoteEnabledActive,
                "calling" to callingActive,
                "callConnected" to callConnectedActive
            )
        )
        pushSessionState(buildTelemetrySnapshot(battery, network))
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                emitTelemetry()
                delay(5_000)
            }
        }
    }

    private fun stopTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    private fun getBatteryLevel(): Int? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "unknown"
        }
    }

    private fun handleIncomingChat(obj: JSONObject) {
        val sid = currentSessionId ?: return
        val text = obj.optString("text", "").takeIf { it.isNotBlank() }
        val imageUrl = obj.optString("imageUrl", "").takeIf { it.isNotBlank() }
        val fileUrl = obj.optString("fileUrl", "").takeIf { it.isNotBlank() }
        val audioUrl = obj.optString("audioUrl", "").takeIf { it.isNotBlank() }
        val createdAt = obj.optLong("ts", obj.optLong("createdAt", System.currentTimeMillis()))
        val stableId = obj.optString("id", "").takeIf { it.isNotBlank() }
            ?: "${sid}:${obj.optString("from", "")}:${createdAt}:${text ?: imageUrl ?: fileUrl ?: audioUrl ?: ""}"
        Log.d("ChatDedup", "origin=socket id=$stableId sessionId=$sid")
        val incomingType = obj.optString("type", "").takeIf { it.isNotBlank() } ?: when {
            audioUrl != null -> "audio"
            imageUrl != null || fileUrl != null -> "image"
            text != null -> "text"
            else -> "file"
        }
        val message = Message(
            id = stableId,
            sessionId = obj.optString("sessionId", sid).takeIf { it.isNotBlank() } ?: sid,
            from = obj.optString("from", ""),
            fromName = obj.optString("fromName").takeIf { it.isNotBlank() },
            text = text,
            imageUrl = imageUrl ?: fileUrl,
            fileUrl = fileUrl ?: imageUrl,
            audioUrl = audioUrl,
            type = incomingType,
            createdAt = createdAt
        )
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Conn.chatRepository?.upsertIncoming(sid, message) }
        }
    }

    private fun handleSessionCommand(obj: JSONObject) {
        val origin = obj.optString("from", "")
        when (obj.optString("type", "")) {
            "share_start" -> {
                if (!origin.equals("client", ignoreCase = true)) {
                    val serviceRunning = isScreenCaptureServiceRunning()
                    val shouldStartFlow = !isSharingActive || !serviceRunning
                    if (isSharingActive && !serviceRunning) {
                        updateSharingState(active = false, origin = "system")
                    }
                    if (shouldStartFlow) runOnUiThread {
                        setSystemMessageFromLauncher?.invoke("O tÃƒÆ’Ã‚Â©cnico solicitou iniciar o compartilhamento de tela.")
                        startScreenShareFlow(fromCommand = true)
                    }
                }
            }
            "share_stop" -> {
                val serviceRunning = isScreenCaptureServiceRunning()
                if (isSharingActive || serviceRunning) runOnUiThread { stopScreenShare(fromCommand = true) }
            }
            "remote_enable" -> {
                if (!remoteEnabledActive) {
                    runOnUiThread {
                        setSystemMessageFromLauncher?.invoke(
                            "O tÃƒÆ’Ã‚Â©cnico solicitou acesso remoto. Ative \"Permitir Acesso Remoto\" para continuar."
                        )
                    }
                }
            }
            "remote_disable", "remote_revoke" -> updateRemoteState(enabled = false, origin = "tech")
            // A chamada agora ÃƒÆ’Ã‚Â© dirigida pelo VoiceCallManager (Firestore/WebRTC),
            // evitando conflito de estado com comandos legados.
            "call_start", "call_end" -> Unit
            "session_end", "end" -> handleSessionEnded(reason = obj.optString("reason", "Atendimento encerrado."))
        }
    }

    private fun resetSessionState() {
        isSharingActive = false
        remoteEnabledActive = false
        RemoteCommandBus.setRemoteEnabled(false)
        callingActive = false
        callConnectedActive = false
        runOnUiThread {
            setIsSharingFromLauncher?.invoke(false)
            setRemoteEnabledFromSocket?.invoke(false)
            setCallingFromSocket?.invoke(false)
            setCallConnectedFromSocket?.invoke(false)
            setCallStateFromManager?.invoke(CallState.IDLE)
            setCallDirectionFromManager?.invoke(null)
        }
    }

    private fun requestCallStart() {
        ensureAudioPermission {
            voiceCallManager.startOutgoingCall()
        }
    }

    private fun requestCallEnd() {
        voiceCallManager.endCall()
    }

    private fun handleAttachmentPick(uri: Uri) {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            setSystemMessageFromLauncher?.invoke("SessÃƒÆ’Ã‚Â£o ainda nÃƒÆ’Ã‚Â£o aceita pelo tÃƒÆ’Ã‚Â©cnico.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                Conn.chatRepository?.sendAttachment(sid, from = "client", localUri = uri)
            }
            runOnUiThread {
                if (result.isSuccess) {
                    setSystemMessageFromLauncher?.invoke("Imagem enviada.")
                } else {
                    setSystemMessageFromLauncher?.invoke("Falha ao enviar anexo.")
                }
            }
        }
    }

    private fun startAudioRecording() {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            setSystemMessageFromLauncher?.invoke("SessÃƒÆ’Ã‚Â£o ainda nÃƒÆ’Ã‚Â£o aceita pelo tÃƒÆ’Ã‚Â©cnico.")
            return
        }
        runCatching {
            val outFile = File.createTempFile("sx_audio_", ".m4a", cacheDir)
            audioTempFile = outFile
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            setRecordingAudioFromActivity?.invoke(true)
            setSystemMessageFromLauncher?.invoke("Gravando ÃƒÆ’Ã‚Â¡udioÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦ Toque novamente para enviar.")
        }.onFailure {
            mediaRecorder = null
            audioTempFile = null
            setRecordingAudioFromActivity?.invoke(false)
            setSystemMessageFromLauncher?.invoke("Falha ao iniciar gravaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de ÃƒÆ’Ã‚Â¡udio.")
        }
    }

    private fun stopAudioRecordingAndSend() {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            setRecordingAudioFromActivity?.invoke(false)
            return
        }
        val recorder = mediaRecorder ?: return
        val outFile = audioTempFile
        runCatching {
            recorder.stop()
            recorder.reset()
            recorder.release()
        }
        mediaRecorder = null
        setRecordingAudioFromActivity?.invoke(false)

        val file = outFile
        if (file == null || !file.exists()) {
            setSystemMessageFromLauncher?.invoke("Arquivo de ÃƒÆ’Ã‚Â¡udio nÃƒÆ’Ã‚Â£o encontrado.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                Conn.chatRepository?.sendAudio(sid, from = "client", localUri = Uri.fromFile(file))
            }
            runOnUiThread {
                if (result.isSuccess) {
                    setSystemMessageFromLauncher?.invoke("Ãudio enviado.")
                } else {
                    setSystemMessageFromLauncher?.invoke("Falha ao enviar Ã¡udio.")
                }
            }
            runCatching { file.delete() }
            audioTempFile = null
        }
    }

    private fun finalizeSession() {
        stopTelemetryLoop()
        voiceCallManager.release()
        resetSessionState()
        currentSessionId = null
        remoteConsentAcceptedForCurrentSession = false
        Conn.sessionId = null
        Conn.techName = null
        runOnUiThread { setTechNameFromSocket?.invoke("T\u00e9cnico") }
    }

    private fun handleSessionEnded(fromCommand: Boolean = true, reason: String? = null) {
        val sid = currentSessionId
        val origin = if (fromCommand) "tech" else "client"
        if (!fromCommand) sendCommand("end")

        sid?.let {
            logSessionEvent("end", origin = origin, extras = mapOf("reason" to reason))
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { sessionRepository.markSessionClosed(it) }
                runCatching {
                    clientSupportRepository.completeSupportSession(
                        localSupportSessionId = pendingSupportSessionId,
                        realtimeSessionId = it,
                        techId = null,
                        techName = Conn.techName,
                        problemSummary = reason,
                        solutionSummary = null,
                        internalNotes = "Encerrado via app Android ($origin)",
                        reportSummary = reason
                    )
                }
            }
        }

        val shareWasActive = isSharingActive
        val remoteWasActive = remoteEnabledActive
        val callWasActive = callingActive || callConnectedActive

        if (callWasActive) {
            updateCallState(calling = false, connected = false, origin = origin)
        }
        if (remoteWasActive) {
            updateRemoteState(enabled = false, origin = origin)
        }
        if (shareWasActive) {
            stopScreenShare(fromCommand = true, originOverride = origin)
        }

        if (!shareWasActive && !remoteWasActive && !callWasActive) {
            emitTelemetry()
        }

        shareRequestFromCommand = false
        pendingSupportStartContext = null
        pendingSupportSessionId = null
        finalizeSession()
        runOnUiThread {
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(null)
            setScreenFromSocket?.invoke(Screen.HOME)
            setSystemMessageFromLauncher?.invoke(reason)
        }
    }

    // -------- Socket.IO --------
    private fun connectSocket() {
        lifecycleScope.launch(Dispatchers.IO) {
            val idToken = runCatching { authRepository.ensureAnonIdToken(forceRefresh = true) }.getOrDefault("")
            runOnUiThread { connectSocketInternal(idToken) }
        }
    }

    private fun connectSocketInternal(idToken: String) {
        val opts = IO.Options().apply {
            forceNew = true
            reconnection = true
            if (idToken.isNotBlank()) {
                extraHeaders = mapOf(
                    "Authorization" to listOf("Bearer $idToken"),
                    "x-id-token" to listOf(idToken)
                )
            }
            // server.js jÃƒÆ’Ã‚Â¡ tem allowEIO3: true
        }
        socket = IO.socket(Conn.SERVER_BASE, opts)
        Conn.socket = socket

        socket.on(Socket.EVENT_CONNECT) {
            runOnUiThread {
                // opcional: Toast.makeText(this, "Conectado", Toast.LENGTH_SHORT).show()
            }
        }

        // cliente entrou na fila
        socket.on("support:enqueued") { args ->
            val any = args.getOrNull(0) ?: return@on
            val data = (any as? JSONObject) ?: return@on
            val reqId = data.optString("requestId", "").takeIf { it.isNotBlank() }
            runOnUiThread { setRequestIdFromSocket?.invoke(reqId) }
        }

        // tÃƒÆ’Ã‚Â©cnico aceitou ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ recebemos sessionId e (opcional) techName
        socket.on("support:accepted") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sid = data.optString("sessionId", "")
            val tname = data.optString("techName", "TÃƒÆ’Ã‚Â©cnico")
            if (sid.isBlank()) return@on

            Conn.sessionId = sid
            Conn.techName = tname
            runOnUiThread { setTechNameFromSocket?.invoke(tname.ifBlank { "T\u00e9cnico" }) }
            currentSessionId = sid
            remoteConsentAcceptedForCurrentSession = false
            resetSessionState()
            voiceCallManager.bindSession(sid)
            registerSessionStart(sid, tname)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    clientSupportRepository.attachRealtimeSession(
                        localSupportSessionId = pendingSupportSessionId,
                        realtimeSessionId = sid,
                        techName = tname
                    )
                }
            }
            pushSessionState()

            lifecycleScope.launch(Dispatchers.IO) {
                val joinToken = runCatching { authRepository.ensureAnonIdToken(forceRefresh = false) }.getOrDefault("")
                val joinPayload = JSONObject().apply {
                    put("sessionId", sid)
                    put("role", "client")
                    if (joinToken.isNotBlank()) put("idToken", joinToken)
                }
                socket.emit("session:join", joinPayload)
                socket.emit("join", joinPayload)
            }

            startTelemetryLoop()
            runOnUiThread {
                setSessionIdFromSocket?.invoke(sid)
                setScreenFromSocket?.invoke(Screen.SESSION)
            }
        }

        socket.on("session:chat:new") { args ->
            val obj = args.getOrNull(0) as? JSONObject ?: return@on
            handleIncomingChat(obj)
        }

        socket.on("session:command") { args ->
            val obj = args.getOrNull(0) as? JSONObject ?: return@on
            handleSessionCommand(obj)
        }

        socket.on("session:ended") { args ->
            val reason = (args.getOrNull(0) as? JSONObject)?.optString("reason")
            handleSessionEnded(reason = reason ?: "Atendimento encerrado.")
        }

        socket.connect()
    }

    private fun loadHomeSnapshot(onResult: (ClientHomeSnapshot) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val clientUid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
            val firebasePhone = runCatching { phoneIdentityProvider.getVerifiedPhoneNumber() }.getOrNull()
            val snapshot = runCatching {
                clientSupportRepository.seedDefaultPackagesIfNeeded()
                clientSupportRepository.loadHomeSnapshot(
                    clientUid = clientUid,
                    rawPhone = firebasePhone
                )
            }.getOrElse {
                ClientHomeSnapshot(
                    clientUid = clientUid,
                    phone = firebasePhone,
                    client = null,
                    clientMeta = null,
                    verification = null,
                    packages = SupportBillingConfig.defaultCreditPackages
                )
            }
            runOnUiThread { onResult(snapshot) }
        }
    }

    private fun evaluateSupportEntry(onDecision: (SupportAccessDecision) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val clientUid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
            val firebasePhone = runCatching { phoneIdentityProvider.getVerifiedPhoneNumber() }.getOrNull()
            val decision = runCatching {
                clientSupportRepository.seedDefaultPackagesIfNeeded()
                clientSupportRepository.evaluateSupportAccess(
                    clientUid = clientUid,
                    fallbackVerifiedPhone = firebasePhone
                )
            }.getOrElse {
                Log.e("SXS/Main", "Falha ao verificar acesso de suporte", it)
                SupportAccessDecision.Allowed(
                    startContext = SupportStartContext(
                        clientId = null,
                        phone = firebasePhone,
                        isNewClient = true,
                        isFreeFirstSupport = true,
                        creditsToConsume = 0
                    ),
                    financialStatus = ClientFinancialStatus.UNREGISTERED_NEW_CLIENT,
                    client = null
                )
            }
            if (decision is SupportAccessDecision.Allowed) {
                pendingSupportStartContext = decision.startContext
            }
            runOnUiThread { onDecision(decision) }
        }
    }

    private fun requestSupport(
        startContext: SupportStartContext,
        clientName: String?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
            val idToken = runCatching { authRepository.ensureAnonIdToken(forceRefresh = false) }.getOrDefault("")
            if (uid.isNullOrBlank()) {
                Log.e("SXS/Main", "Falha ao autenticar cliente antes de support:request")
                runOnUiThread {
                    setScreenFromSocket?.invoke(Screen.HOME)
                    setSystemMessageFromLauncher?.invoke("Falha de autenticacao. Tente novamente em alguns segundos.")
                }
                return@launch
            }

            pendingSupportSessionId = runCatching {
                clientSupportRepository.registerSupportRequest(
                    startContext = startContext,
                    clientName = clientName,
                    clientUid = uid,
                    deviceBrand = Build.BRAND,
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
                )
            }.getOrNull()

            val payload = JSONObject().apply {
                put("clientName", clientName ?: "Cliente em atendimento")
                put("clientPhone", startContext.phone ?: "")
                put("clientId", startContext.clientId ?: "")
                put("brand", Build.BRAND ?: "Android")
                put("model", Build.MODEL ?: "")
                put("device", JSONObject().apply {
                    put("brand", Build.BRAND ?: "Android")
                    put("model", Build.MODEL ?: "")
                    put("osVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
                })
                put("clientUid", uid)
                put("uid", uid)
                put("supportProfile", JSONObject().apply {
                    put("isNewClient", startContext.isNewClient)
                    put("isFreeFirstSupport", startContext.isFreeFirstSupport)
                    put("creditsToConsume", startContext.creditsToConsume)
                    put("localSupportSessionId", pendingSupportSessionId)
                    put("disableQuickIdentificationModal", supportFlowFlags.disableQuickIdentificationModal)
                    put("technicianDrivenRegistrationEnabled", supportFlowFlags.technicianDrivenRegistrationEnabled)
                    put("pnvPostRegistrationFlow", supportFlowFlags.pnvPostRegistrationFlow)
                })
                if (idToken.isNotBlank()) put("idToken", idToken)
            }
            socket.emit("support:request", payload)
        }
    }

    private fun requestCreditPurchaseByWhatsapp(
        selectedPackage: CreditPackageRecord?,
        currentClientId: String?
    ) {
        if (selectedPackage == null) {
            setSystemMessageFromLauncher?.invoke("Escolha um plano primeiro.")
            return
        }
        val text = "Ola, quero comprar creditos no Suporte X. " +
            "Plano escolhido: ${selectedPackage.name} por ${formatPriceLabel(selectedPackage.priceCents)}."
        val encoded = URLEncoder.encode(text, Charsets.UTF_8.name())
        val url = "https://wa.me/${SupportBillingConfig.OFFICIAL_WHATSAPP_NUMBER}?text=$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            if (!currentClientId.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        clientSupportRepository.registerCreditOrderIntent(
                            clientId = currentClientId,
                            packageId = selectedPackage.id,
                            paymentMethod = "whatsapp",
                            whatsappRequested = true,
                            pixPlaceholder = false,
                            cardPlaceholder = false
                        )
                    }
                }
            }
        } catch (_: ActivityNotFoundException) {
            setSystemMessageFromLauncher?.invoke("Nao foi possivel abrir o WhatsApp neste dispositivo.")
        }
    }

    private fun registerPlaceholderOrder(
        currentClientId: String?,
        selectedPackage: CreditPackageRecord?,
        method: String
    ) {
        if (currentClientId.isNullOrBlank() || selectedPackage == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                clientSupportRepository.registerCreditOrderIntent(
                    clientId = currentClientId,
                    packageId = selectedPackage.id,
                    paymentMethod = method,
                    whatsappRequested = false,
                    pixPlaceholder = method == "pix",
                    cardPlaceholder = method == "card"
                )
            }
        }
    }

    private fun formatPriceLabel(priceCents: Int): String {
        val reais = priceCents / 100
        val cents = (priceCents % 100).toString().padStart(2, '0')
        return "R$ $reais,$cents"
    }

    // Cancelar (opcional) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ pelo endpoint HTTP do servidor
    private fun cancelRequest(requestId: String, onDone: () -> Unit = {}) {
        val localSupportId = pendingSupportSessionId
        if (!localSupportId.isNullOrBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { clientSupportRepository.cancelSupportRequest(localSupportId) }
            }
        }
        pendingSupportSessionId = null
        pendingSupportStartContext = null

        val req = Request.Builder()
            .url("${Conn.SERVER_BASE}/api/requests/$requestId")
            .delete()
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onDone()
            override fun onResponse(call: Call, response: Response) = onDone()
        })
    }

    private fun startScreenShareFlow(fromCommand: Boolean = false) {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            shareRequestFromCommand = false
            setSystemMessageFromLauncher?.invoke("SessÃƒÆ’Ã‚Â£o ainda nÃƒÆ’Ã‚Â£o aceita pelo tÃƒÆ’Ã‚Â©cnico.")
            return
        }
        if (isSharingActive && !isScreenCaptureServiceRunning()) {
            updateSharingState(active = false, origin = "system")
        }
        shareRequestFromCommand = fromCommand
        ensureRemoteAccessConsent {
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(intent)
        }
    }

    private fun isScreenCaptureServiceRunning(): Boolean {
        val manager = getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ScreenCaptureService::class.java.name }
    }

    // -------- Screen share launcher --------
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val sid = currentSessionId
                if (sid.isNullOrBlank()) {
                    setSystemMessageFromLauncher?.invoke("SessÃƒÆ’Ã‚Â£o ainda nÃƒÆ’Ã‚Â£o aceita pelo tÃƒÆ’Ã‚Â©cnico.")
                    return@registerForActivityResult
                }
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                    putExtra(ScreenCaptureService.EXTRA_ROOM_CODE, sid)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                val origin = if (shareRequestFromCommand) "tech" else "client"
                updateSharingState(active = true, origin = origin)
                shareRequestFromCommand = false
            } else {
                setSystemMessageFromLauncher?.invoke("PermissÃƒÆ’Ã‚Â£o de captura negada.")
                shareRequestFromCommand = false
            }
        }

    private fun stopScreenShare(fromCommand: Boolean = false, originOverride: String? = null) {
        val stop = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, stop)
        val origin = originOverride ?: if (fromCommand) "tech" else "client"
        updateSharingState(active = false, origin = origin)
    }

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingAudioAction = onGranted
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingAudioAction?.invoke()
                pendingAudioAction = null
            } else {
                pendingAudioAction = null
                setSystemMessageFromLauncher?.invoke("PermissÃƒÆ’Ã‚Â£o de microfone negada.")
            }
        }

    private val attachmentPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleAttachmentPick(it) }
        }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return AccessibilityUtils.isServiceEnabled(this, com.suportex.app.remote.RemoteControlService::class.java)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { clientSupportRepository.seedDefaultPackagesIfNeeded() }
        }
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        voiceCallManager = VoiceCallManager(
            context = this,
            scope = lifecycleScope,
            onUpdate = ::handleCallUpdate
        )

        connectSocket() // ÃƒÆ’Ã‚Â°Ãƒâ€¦Ã‚Â¸ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒâ€¦Ã¢â‚¬â„¢ conecta o Socket.IO assim que abrir o app

        setContent {
            val brandPrimary = Color(0xFFFFCB19)
            val onPrimary = Color(0xFF111111)
            val secondary = Color(0xFF0A84FF)
            val error = Color(0xFFE63A3A)
            val background = Color(0xFFF4F6F8)
            val surfaceC = Color(0xFFFFFFFF)

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = brandPrimary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    onSecondary = Color.White,
                    error = error,
                    background = background,
                    surface = surfaceC
                ),
                typography = Typography()
            ) {
                var current by remember { mutableStateOf(Screen.HOME) }

                var requestId by remember { mutableStateOf<String?>(null) }
                var sessionId by remember { mutableStateOf<String?>(null) }
                var isSharing by remember { mutableStateOf(false) }
                var remoteEnabled by remember { mutableStateOf(false) }
                var calling by remember { mutableStateOf(false) }
                var callConnected by remember { mutableStateOf(false) }
                var callState by remember { mutableStateOf(CallState.IDLE) }
                var callDirection by remember { mutableStateOf<CallDirection?>(null) }
                var techName by remember { mutableStateOf("T\u00e9cnico") }
                var systemMessage by remember { mutableStateOf<String?>(null) }
                var isRecordingAudio by remember { mutableStateOf(false) }
                var homeSnapshot by remember {
                    mutableStateOf(
                        ClientHomeSnapshot(
                            clientUid = null,
                            phone = null,
                            client = null,
                            clientMeta = null,
                            verification = null,
                            packages = SupportBillingConfig.defaultCreditPackages
                        )
                    )
                }
                var selectedPackage by remember { mutableStateOf<CreditPackageRecord?>(null) }

                BackHandler {
                    when (current) {
                        Screen.HELP -> current = Screen.HOME
                        Screen.PRIVACY -> current = Screen.HOME
                        Screen.TERMS -> current = Screen.HOME
                        Screen.PURCHASE_CREDITS -> current = Screen.HOME
                        Screen.PAYMENT_CARD -> current = Screen.PURCHASE_CREDITS
                        Screen.PAYMENT_PIX -> current = Screen.PURCHASE_CREDITS
                        Screen.WAITING -> {
                            if (requestId != null) {
                                cancelRequest(requestId!!)
                            } else {
                                val localSupportId = pendingSupportSessionId
                                if (!localSupportId.isNullOrBlank()) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        runCatching { clientSupportRepository.cancelSupportRequest(localSupportId) }
                                    }
                                }
                                pendingSupportSessionId = null
                                pendingSupportStartContext = null
                            }
                            requestId = null
                            sessionId = null
                            currentSessionId = null
                            current = Screen.HOME
                        }
                        Screen.SESSION -> current = Screen.HOME
                        Screen.HOME -> finish()
                    }
                }

                // Bridges Activity -> Compose
                LaunchedEffect(Unit) {
                    setIsSharingFromLauncher = { isSharing = it }
                    setSystemMessageFromLauncher = { msg -> systemMessage = msg }

                    // Bridges Socket -> Compose
                    setRequestIdFromSocket = { req -> requestId = req }
                    setSessionIdFromSocket = { sid ->
                        sessionId = sid
                        currentSessionId = sid
                    }
                    setScreenFromSocket = { scr -> current = scr }
                    setRemoteEnabledFromSocket = { remoteEnabled = it }
                    setCallingFromSocket = { calling = it }
                    setCallConnectedFromSocket = { callConnected = it }
                    setCallStateFromManager = { callState = it }
                    setCallDirectionFromManager = { callDirection = it }
                    setTechNameFromSocket = { name -> techName = name.ifBlank { "T\u00e9cnico" } }
                    setRecordingAudioFromActivity = { isRecordingAudio = it }
                    loadHomeSnapshot { snapshot ->
                        homeSnapshot = snapshot
                        if (selectedPackage == null) {
                            selectedPackage = snapshot.packages.firstOrNull()
                        }
                    }
                }

                LaunchedEffect(current) {
                    if (current == Screen.HOME) {
                        loadHomeSnapshot { snapshot ->
                            homeSnapshot = snapshot
                            if (selectedPackage == null) {
                                selectedPackage = snapshot.packages.firstOrNull()
                            }
                        }
                    }
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (current) {
                        Screen.HOME -> SupportHomeScreen(
                            homeSnapshot = homeSnapshot,
                            onRequestSupport = {
                                if (!homeSnapshot.canRequestSupport &&
                                    supportFlowFlags.showCreditPanelOnlyForRegisteredClients
                                ) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Sem crÃƒÆ’Ã‚Â©dito disponÃƒÆ’Ã‚Â­vel",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@SupportHomeScreen
                                }

                                evaluateSupportEntry { decision ->
                                    when (decision) {
                                        is SupportAccessDecision.Allowed -> {
                                            current = Screen.WAITING
                                            requestSupport(
                                                startContext = decision.startContext,
                                                clientName = decision.client?.name ?: "Cliente"
                                            )
                                            loadHomeSnapshot { snapshot ->
                                                homeSnapshot = snapshot
                                                selectedPackage = selectedPackage
                                                    ?: snapshot.packages.firstOrNull()
                                            }
                                        }

                                        is SupportAccessDecision.BlockedNeedsCredit -> {
                                            homeSnapshot = homeSnapshot.copy(
                                                client = decision.client,
                                                clientMeta = homeSnapshot.clientMeta,
                                                packages = decision.packages
                                            )
                                            selectedPackage = decision.packages.firstOrNull()
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Sem crÃƒÆ’Ã‚Â©dito disponÃƒÆ’Ã‚Â­vel",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onBlockedSupportRequest = {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Sem crÃƒÆ’Ã‚Â©dito disponÃƒÆ’Ã‚Â­vel",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onOpenPurchase = {
                                if (homeSnapshot.isRegisteredClient) {
                                    if (selectedPackage == null) {
                                        selectedPackage = homeSnapshot.packages.firstOrNull()
                                    }
                                    current = Screen.PURCHASE_CREDITS
                                }
                            },
                            onOpenHelp = { current = Screen.HELP },
                            onOpenPrivacy = { current = Screen.PRIVACY },
                            onOpenTerms = { current = Screen.TERMS },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.HELP -> HelpScreen(
                            onClose = { current = Screen.HOME },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.PRIVACY -> PrivacyPolicyScreen(
                            onClose = { current = Screen.HOME },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.TERMS -> TermsOfUseScreen(
                            onClose = { current = Screen.HOME },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.PURCHASE_CREDITS -> PurchaseCreditsScreen(
                            plans = homeSnapshot.packages,
                            selectedPackageId = selectedPackage?.id,
                            onSelectPlan = { selectedPackage = it },
                            onBack = { current = Screen.HOME },
                            onPayCard = {
                                registerPlaceholderOrder(
                                    currentClientId = homeSnapshot.client?.id,
                                    selectedPackage = selectedPackage,
                                    method = "card"
                                )
                                current = Screen.PAYMENT_CARD
                            },
                            onPayPix = {
                                registerPlaceholderOrder(
                                    currentClientId = homeSnapshot.client?.id,
                                    selectedPackage = selectedPackage,
                                    method = "pix"
                                )
                                current = Screen.PAYMENT_PIX
                            },
                            onBuyWhatsapp = {
                                requestCreditPurchaseByWhatsapp(
                                    selectedPackage = selectedPackage,
                                    currentClientId = homeSnapshot.client?.id
                                )
                            }
                        )

                        Screen.PAYMENT_CARD -> CardPlaceholderScreen(
                            onBack = { current = Screen.PURCHASE_CREDITS }
                        )

                        Screen.PAYMENT_PIX -> PixPlaceholderScreen(
                            selectedPlan = selectedPackage,
                            onBack = { current = Screen.PURCHASE_CREDITS }
                        )

                        Screen.WAITING -> WaitingScreen(
                            onCancel = {
                                if (requestId != null) {
                                    cancelRequest(requestId!!)
                                } else {
                                    val localSupportId = pendingSupportSessionId
                                    if (!localSupportId.isNullOrBlank()) {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            runCatching { clientSupportRepository.cancelSupportRequest(localSupportId) }
                                        }
                                    }
                                    pendingSupportSessionId = null
                                    pendingSupportStartContext = null
                                }
                                requestId = null
                                sessionId = null
                                currentSessionId = null
                                current = Screen.HOME
                            },
                            onAccepted = { /* nÃƒÆ’Ã‚Â£o usamos; aceitaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o vem do socket */ },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.SESSION -> SessionScreen(
                            sessionId = sessionId,
                            technicianName = techName,
                            isSharing = isSharing,
                            remoteEnabled = remoteEnabled,
                            calling = calling,
                            callConnected = callConnected,
                            callState = callState,
                            callDirection = callDirection,
                            systemMessage = systemMessage,
                            onSystemMessageConsumed = { systemMessage = null },
                            onStartShare = { startScreenShareFlow() },
                            onStopShare = { stopScreenShare() },
                            onToggleRemote = { enable -> requestRemoteStateChange(enable) },
                            onStartCall = { requestCallStart() },
                            onEndCall = { requestCallEnd() },
                            onAcceptCall = {
                                ensureAudioPermission {
                                    voiceCallManager.acceptIncomingCall()
                                }
                            },
                            onDeclineCall = { voiceCallManager.declineIncomingCall() },
                            onAudioClick = {
                                ensureAudioPermission {
                                    if (mediaRecorder == null) {
                                        startAudioRecording()
                                    } else {
                                        stopAudioRecordingAndSend()
                                    }
                                }
                            },
                            onAttachmentClick = {
                                if (sessionId == null) {
                                    systemMessage = "SessÃƒÆ’Ã‚Â£o ainda nÃƒÆ’Ã‚Â£o aceita pelo tÃƒÆ’Ã‚Â©cnico."
                                } else {
                                    attachmentPickerLauncher.launch("image/*")
                                }
                            },
                            isRecordingAudio = isRecordingAudio,
                            onEndSupport = {
                                handleSessionEnded(fromCommand = false, reason = null)
                                requestId = null
                                sessionId = null
                                systemMessage = null
                                current = Screen.HOME
                            }
                        )
                    }
                }

            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            mediaRecorder?.apply {
                reset()
                release()
            }
        }
        mediaRecorder = null
        stopTelemetryLoop()
        voiceCallManager.release()
    }
}

/* ===================== Composables locais (Home/Waiting) ===================== */

@Composable
private fun HomeScreen(
    onRequestSupport: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    textMuted: Color
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Image(painterResource(R.drawable.ic_suportex_logo), null, modifier = Modifier.size(180.dp))
        Spacer(Modifier.height(100.dp))
        Button(
            onClick = onRequestSupport,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp)
        ) { Text("SOLICITAR SUPORTE", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
        Text("Tempo mÃƒÆ’Ã‚Â©dio de atendimento: 2ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“5 min", color = textMuted, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajuda", color = textMuted,
                modifier = Modifier.clickable {
                    onOpenHelp()
                })
            Text("  ·  ", color = textMuted)
            Text("Privacidade", color = textMuted,
                modifier = Modifier.clickable {
                    onOpenPrivacy()
                })
            Text("  ·  ", color = textMuted)
            Text("Termos", color = textMuted,
                modifier = Modifier.clickable {
                    onOpenTerms()
                })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HelpScreen(
    onClose: () -> Unit,
    textMuted: Color
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClose) {
                Text("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¢", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Ajuda", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Guia rÃƒÆ’Ã‚Â¡pido de atendimento", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Como iniciar um atendimento",
                body = "Toque em SOLICITAR SUPORTE e aguarde a conexÃƒÆ’Ã‚Â£o com um tÃƒÆ’Ã‚Â©cnico. Quando o atendimento for aceito, a sessÃƒÆ’Ã‚Â£o serÃƒÆ’Ã‚Â¡ aberta automaticamente no aplicativo."
            )
            PolicySection(
                title = "2. PermissÃƒÆ’Ã‚Âµes durante o suporte",
                body = "Alguns atendimentos podem exigir permissÃƒÆ’Ã‚Âµes temporÃƒÆ’Ã‚Â¡rias, como compartilhamento de tela, envio de arquivos e acesso assistido. Essas permissÃƒÆ’Ã‚Âµes sÃƒÆ’Ã‚Â£o opcionais e vocÃƒÆ’Ã‚Âª controla todas elas no app."
            )
            PolicySection(
                title = "3. SeguranÃƒÆ’Ã‚Â§a da sessÃƒÆ’Ã‚Â£o",
                body = "Nenhuma aÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o remota ÃƒÆ’Ã‚Â© iniciada sem sua autorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o explÃƒÆ’Ã‚Â­cita. VocÃƒÆ’Ã‚Âª pode interromper o compartilhamento e revogar permissÃƒÆ’Ã‚Âµes a qualquer momento."
            )
            PolicySection(
                title = "4. Encerrar atendimento",
                body = "Quando desejar, finalize o suporte pelo botÃƒÆ’Ã‚Â£o de encerramento na tela de sessÃƒÆ’Ã‚Â£o. VocÃƒÆ’Ã‚Âª tambÃƒÆ’Ã‚Â©m pode interromper permissÃƒÆ’Ã‚Âµes diretamente nas configuraÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes do seu dispositivo."
            )
            PolicySection(
                title = "5. Problemas comuns",
                body = "Se a conexÃƒÆ’Ã‚Â£o estiver instÃƒÆ’Ã‚Â¡vel, verifique internet, bateria e permissÃƒÆ’Ã‚Âµes do app. Em caso de falha de acesso remoto, confira se o serviÃƒÆ’Ã‚Â§o de acessibilidade do Suporte X estÃƒÆ’Ã‚Â¡ ativado."
            )
            PolicySection(
                title = "6. Canais oficiais",
                body = "Para suporte administrativo, dÃƒÆ’Ã‚Âºvidas sobre privacidade ou uso da plataforma, utilize os canais oficiais da Suporte X informados no aplicativo."
            )
        }
    }
}

@Composable
private fun PrivacyPolicyScreen(
    onClose: () -> Unit,
    textMuted: Color
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClose) {
                Text("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¢", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("PolÃƒÆ’Ã‚Â­tica de Privacidade", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("ÃƒÆ’Ã…Â¡ltima atualizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o: 11 de marÃƒÆ’Ã‚Â§o de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "A Suporte X valoriza a privacidade e a seguranÃƒÆ’Ã‚Â§a dos dados dos usuÃƒÆ’Ã‚Â¡rios. Esta PolÃƒÆ’Ã‚Â­tica de Privacidade descreve como coletamos, utilizamos, armazenamos e protegemos as informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes durante a utilizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o do aplicativo e dos serviÃƒÆ’Ã‚Â§os de suporte tÃƒÆ’Ã‚Â©cnico remoto.",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            PolicySection(
                title = "1. Dados que coletamos",
                body = "Para a prestaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o adequada do serviÃƒÆ’Ã‚Â§o de suporte tÃƒÆ’Ã‚Â©cnico remoto, podemos coletar as seguintes informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Nome informado pelo usuÃƒÆ’Ã‚Â¡rio no aplicativo\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Identificadores de sessÃƒÆ’Ã‚Â£o e atendimento\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ InformaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes tÃƒÆ’Ã‚Â©cnicas do dispositivo utilizado\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Mensagens trocadas no chat de atendimento\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Arquivos enviados durante o suporte tÃƒÆ’Ã‚Â©cnico\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Registros tÃƒÆ’Ã‚Â©cnicos necessÃƒÆ’Ã‚Â¡rios para diagnÃƒÆ’Ã‚Â³stico e resoluÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de problemas\n\nEssas informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes sÃƒÆ’Ã‚Â£o coletadas exclusivamente para viabilizar o funcionamento do serviÃƒÆ’Ã‚Â§o de suporte."
            )
            PolicySection(
                title = "2. Finalidade do uso dos dados",
                body = "Os dados coletados sÃƒÆ’Ã‚Â£o utilizados para:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Identificar o usuÃƒÆ’Ã‚Â¡rio durante o atendimento\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Realizar suporte tÃƒÆ’Ã‚Â©cnico remoto\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Registrar o histÃƒÆ’Ã‚Â³rico de sessÃƒÆ’Ã‚Âµes de atendimento\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Diagnosticar e solucionar problemas tÃƒÆ’Ã‚Â©cnicos\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Melhorar a qualidade e eficiÃƒÆ’Ã‚Âªncia do serviÃƒÆ’Ã‚Â§o\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Garantir a seguranÃƒÆ’Ã‚Â§a e integridade da plataforma"
            )
            PolicySection(
                title = "3. Compartilhamento de dados",
                body = "A Suporte X nÃƒÆ’Ã‚Â£o vende, aluga ou comercializa dados pessoais dos usuÃƒÆ’Ã‚Â¡rios.\n\nO compartilhamento de informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes pode ocorrer apenas quando necessÃƒÆ’Ã‚Â¡rio com:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Provedores de infraestrutura e hospedagem\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ ServiÃƒÆ’Ã‚Â§os de armazenamento de dados\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Ferramentas necessÃƒÆ’Ã‚Â¡rias para o funcionamento da plataforma\n\nTodos os parceiros seguem padrÃƒÆ’Ã‚Âµes de seguranÃƒÆ’Ã‚Â§a e proteÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de dados compatÃƒÆ’Ã‚Â­veis com a legislaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o aplicÃƒÆ’Ã‚Â¡vel."
            )
            PolicySection(
                title = "4. PermissÃƒÆ’Ã‚Âµes e controle do usuÃƒÆ’Ã‚Â¡rio",
                body = "Alguns recursos do aplicativo podem solicitar permissÃƒÆ’Ã‚Âµes especÃƒÆ’Ã‚Â­ficas do dispositivo, como:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Compartilhamento de tela\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Acesso remoto ao dispositivo\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Envio de arquivos ou imagens\n\nEssas permissÃƒÆ’Ã‚Âµes sÃƒÆ’Ã‚Â£o sempre solicitadas com autorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o explÃƒÆ’Ã‚Â­cita do usuÃƒÆ’Ã‚Â¡rio e podem ser interrompidas ou revogadas a qualquer momento diretamente no aplicativo."
            )
            PolicySection(
                title = "5. Armazenamento e retenÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de dados",
                body = "Os dados coletados sÃƒÆ’Ã‚Â£o armazenados apenas pelo perÃƒÆ’Ã‚Â­odo necessÃƒÆ’Ã‚Â¡rio para:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ PrestaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o do suporte tÃƒÆ’Ã‚Â©cnico\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Cumprimento de obrigaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes legais\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Garantia da seguranÃƒÆ’Ã‚Â§a operacional do serviÃƒÆ’Ã‚Â§o\n\nApÃƒÆ’Ã‚Â³s esse perÃƒÆ’Ã‚Â­odo, os dados podem ser excluÃƒÆ’Ã‚Â­dos ou anonimizados."
            )
            PolicySection(
                title = "6. Direitos do usuÃƒÆ’Ã‚Â¡rio",
                body = "Em conformidade com a Lei Geral de ProteÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de Dados (LGPD ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ Lei nÃƒâ€šÃ‚Âº 13.709/2018), o usuÃƒÆ’Ã‚Â¡rio possui o direito de:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Solicitar acesso aos seus dados\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Corrigir dados incompletos ou desatualizados\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Solicitar exclusÃƒÆ’Ã‚Â£o de dados quando aplicÃƒÆ’Ã‚Â¡vel\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Solicitar informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes sobre o tratamento de dados\n\nAs solicitaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes podem ser feitas pelos canais de contato oficiais."
            )
            PolicySection(
                title = "7. SeguranÃƒÆ’Ã‚Â§a das informaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes",
                body = "A Suporte X adota medidas tÃƒÆ’Ã‚Â©cnicas e organizacionais para proteger os dados contra acesso nÃƒÆ’Ã‚Â£o autorizado, alteraÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o, divulgaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o ou destruiÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o indevida.\n\nEntre as medidas aplicadas estÃƒÆ’Ã‚Â£o:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Controle de acesso aos dados\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ AutenticaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o segura\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Registros de auditoria\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ ProteÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o da infraestrutura da plataforma"
            )
            PolicySection(
                title = "8. Contato",
                body = "Para dÃƒÆ’Ã‚Âºvidas, solicitaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes ou questÃƒÆ’Ã‚Âµes relacionadas ÃƒÆ’Ã‚Â  privacidade e proteÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de dados, entre em contato com o suporte oficial da Suporte X pelos canais disponibilizados no aplicativo."
            )
        }
    }
}

@Composable
private fun TermsOfUseScreen(
    onClose: () -> Unit,
    textMuted: Color
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClose) {
                Text("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¢", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Termos de Uso", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Operado por Xavier Assessoria Digital", color = textMuted, fontSize = 13.sp)
        Text("CNPJ: 45.765.097/0001-61", color = textMuted, fontSize = 13.sp)
        Text("ÃƒÆ’Ã…Â¡ltima atualizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o: 11 de marÃƒÆ’Ã‚Â§o de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Sobre o serviÃƒÆ’Ã‚Â§o",
                body = "O Suporte X ÃƒÆ’Ã‚Â© uma plataforma desenvolvida pela Xavier Assessoria Digital que permite a realizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de suporte tÃƒÆ’Ã‚Â©cnico remoto entre tÃƒÆ’Ã‚Â©cnicos e usuÃƒÆ’Ã‚Â¡rios, por meio de comunicaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o em tempo real, compartilhamento de tela e ferramentas de assistÃƒÆ’Ã‚Âªncia remota.\n\nO serviÃƒÆ’Ã‚Â§o tem como objetivo facilitar o diagnÃƒÆ’Ã‚Â³stico e a resoluÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de problemas tÃƒÆ’Ã‚Â©cnicos diretamente no dispositivo do usuÃƒÆ’Ã‚Â¡rio, mediante autorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o expressa do mesmo."
            )
            PolicySection(
                title = "2. AceitaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o dos termos",
                body = "Ao utilizar o aplicativo Suporte X, o usuÃƒÆ’Ã‚Â¡rio declara que:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ leu e compreendeu estes Termos de Uso\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ concorda com as condiÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes aqui estabelecidas\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ autoriza o funcionamento das funcionalidades necessÃƒÆ’Ã‚Â¡rias para o suporte tÃƒÆ’Ã‚Â©cnico remoto\n\nCaso o usuÃƒÆ’Ã‚Â¡rio nÃƒÆ’Ã‚Â£o concorde com estes termos, nÃƒÆ’Ã‚Â£o deverÃƒÆ’Ã‚Â¡ utilizar o aplicativo."
            )
            PolicySection(
                title = "3. Funcionamento do suporte remoto",
                body = "Durante uma sessÃƒÆ’Ã‚Â£o de suporte, o usuÃƒÆ’Ã‚Â¡rio poderÃƒÆ’Ã‚Â¡ autorizar funcionalidades como:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ compartilhamento de tela\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ envio de arquivos\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ comunicaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o por chat ou ÃƒÆ’Ã‚Â¡udio\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ controle remoto assistido do dispositivo\n\nEssas funcionalidades sÃƒÆ’Ã‚Â³ sÃƒÆ’Ã‚Â£o ativadas com autorizaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o explÃƒÆ’Ã‚Â­cita do usuÃƒÆ’Ã‚Â¡rio e podem ser interrompidas a qualquer momento pelo prÃƒÆ’Ã‚Â³prio aplicativo."
            )
            PolicySection(
                title = "4. Responsabilidades do usuÃƒÆ’Ã‚Â¡rio",
                body = "O usuÃƒÆ’Ã‚Â¡rio ÃƒÆ’Ã‚Â© responsÃƒÆ’Ã‚Â¡vel por:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ conceder permissÃƒÆ’Ã‚Âµes apenas quando desejar iniciar um atendimento\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ encerrar a sessÃƒÆ’Ã‚Â£o de suporte quando considerar necessÃƒÆ’Ã‚Â¡rio\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ nÃƒÆ’Ã‚Â£o utilizar o aplicativo para atividades ilegais ou abusivas"
            )
            PolicySection(
                title = "5. LimitaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de responsabilidade",
                body = "A Xavier Assessoria Digital nÃƒÆ’Ã‚Â£o se responsabiliza por:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ falhas causadas por terceiros\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ problemas decorrentes de uso indevido do dispositivo\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ indisponibilidade temporÃƒÆ’Ã‚Â¡ria de serviÃƒÆ’Ã‚Â§os externos"
            )
            PolicySection(
                title = "6. ModificaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Âµes do serviÃƒÆ’Ã‚Â§o",
                body = "A Xavier Assessoria Digital pode modificar, atualizar ou interromper funcionalidades do aplicativo a qualquer momento, visando melhorias de seguranÃƒÆ’Ã‚Â§a, desempenho ou conformidade legal."
            )
            PolicySection(
                title = "7. LegislaÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o aplicÃƒÆ’Ã‚Â¡vel",
                body = "Este serviÃƒÆ’Ã‚Â§o ÃƒÆ’Ã‚Â© regido pelas leis da RepÃƒÆ’Ã‚Âºblica Federativa do Brasil, especialmente pela:\n\nÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢ Lei Geral de ProteÃƒÆ’Ã‚Â§ÃƒÆ’Ã‚Â£o de Dados (LGPD ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ Lei nÃƒâ€šÃ‚Âº 13.709/2018)"
            )
            PolicySection(
                title = "8. Contato",
                body = "Empresa responsÃƒÆ’Ã‚Â¡vel:\nXavier Assessoria Digital\n\nCNPJ:\n45.765.097/0001-61\n\nEndereÃƒÆ’Ã‚Â§o:\nRua dos JequitibÃƒÆ’Ã‚Â¡s, 1895w\nResidencial ParaÃƒÆ’Ã‚Â­so\nNova Mutum ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ MT\nCEP: 78.454-528\n\nEmail:\nsuportex@xavierassessoriadigital.com.br\n\nTelefone / WhatsApp:\n+55 65 99649-7550"
            )
        }
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(14.dp))
    }
}

@Suppress("unused")
@Composable
private fun WaitingScreen(
    onCancel: () -> Unit,
    onAccepted: () -> Unit,
    textMuted: Color
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(80.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Acionando tÃƒÆ’Ã‚Â©cnico, aguardeÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦", fontSize = 18.sp)
        Text("Tempo mÃƒÆ’Ã‚Â©dio: ~2ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“5 min", color = textMuted)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("CANCELAR SOLICITAÃƒÆ’Ã¢â‚¬Â¡ÃƒÆ’Ã†â€™O", color = Color.White) }
    }
}

