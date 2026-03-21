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
import com.suportex.app.data.ClientIdentityStore
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
import com.suportex.app.data.model.ClientHomeSnapshot
import com.suportex.app.data.model.ClientRecord
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
import com.suportex.app.ui.screens.PhoneIdentityDialog
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

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // (mantido sÃ³ para cancelar request por HTTP, se desejar)
    private val http = OkHttpClient()

    private val sessionRepository = SessionRepository()
    private val authRepository = AuthRepository()
    private val clientSupportRepository = ClientSupportRepository()
    private val phoneIdentityProvider = FirebasePhoneIdentityProvider()
    private lateinit var clientIdentityStore: ClientIdentityStore

    // Bridges Activity -> Compose (jÃ¡ existiam)
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
    private var pendingClientRecord: ClientRecord? = null

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
                Log.e("SXS/Main", "Falha ao registrar inÃ­cio da sessÃ£o $sessionId", err)
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
            Para que o suporte tÃ©cnico possa ser realizado, o aplicativo Suporte X pode solicitar permissÃµes temporÃ¡rias como:
            
            â€¢ compartilhamento de tela
            â€¢ acesso assistido ao dispositivo
            â€¢ envio de arquivos e informaÃ§Ãµes tÃ©cnicas
            
            Essas permissÃµes sÃ£o utilizadas exclusivamente durante a sessÃ£o de suporte tÃ©cnico.
            
            O acesso remoto somente serÃ¡ iniciado apÃ³s sua autorizaÃ§Ã£o explÃ­cita e pode ser interrompido a qualquer momento diretamente no aplicativo.
            
            Nenhuma aÃ§Ã£o serÃ¡ realizada no dispositivo sem a autorizaÃ§Ã£o do usuÃ¡rio.
            
            Ao continuar, vocÃª confirma que estÃ¡ solicitando suporte tÃ©cnico e autoriza temporariamente o acesso necessÃ¡rio para a realizaÃ§Ã£o do atendimento.
        """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("AutorizaÃ§Ã£o de Acesso Remoto")
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
                        "Ative em Acessibilidade â†’ SuporteX â†’ Ativar para permitir o controle remoto."
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
                        setSystemMessageFromLauncher?.invoke("O tÃ©cnico solicitou iniciar o compartilhamento de tela.")
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
                            "O tÃ©cnico solicitou acesso remoto. Ative \"Permitir Acesso Remoto\" para continuar."
                        )
                    }
                }
            }
            "remote_disable", "remote_revoke" -> updateRemoteState(enabled = false, origin = "tech")
            // A chamada agora Ã© dirigida pelo VoiceCallManager (Firestore/WebRTC),
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
            setSystemMessageFromLauncher?.invoke("SessÃ£o ainda nÃ£o aceita pelo tÃ©cnico.")
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
            setSystemMessageFromLauncher?.invoke("SessÃ£o ainda nÃ£o aceita pelo tÃ©cnico.")
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
            setSystemMessageFromLauncher?.invoke("Gravando Ã¡udioâ€¦ Toque novamente para enviar.")
        }.onFailure {
            mediaRecorder = null
            audioTempFile = null
            setRecordingAudioFromActivity?.invoke(false)
            setSystemMessageFromLauncher?.invoke("Falha ao iniciar gravaÃ§Ã£o de Ã¡udio.")
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
            setSystemMessageFromLauncher?.invoke("Arquivo de Ã¡udio nÃ£o encontrado.")
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
        pendingClientRecord = null
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
            // server.js jÃ¡ tem allowEIO3: true
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

        // tÃ©cnico aceitou â†’ recebemos sessionId e (opcional) techName
        socket.on("support:accepted") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sid = data.optString("sessionId", "")
            val tname = data.optString("techName", "TÃ©cnico")
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

    private fun loadHomeSnapshot(
        rawPhone: String?,
        onResult: (ClientHomeSnapshot) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val firebasePhone = runCatching { phoneIdentityProvider.getVerifiedPhoneNumber() }.getOrNull()
            val effectivePhone = rawPhone?.takeIf { it.isNotBlank() } ?: firebasePhone
            val snapshot = runCatching {
                clientSupportRepository.seedDefaultPackagesIfNeeded()
                clientSupportRepository.loadHomeSnapshot(effectivePhone)
            }.getOrElse {
                ClientHomeSnapshot(
                    phone = effectivePhone,
                    client = null,
                    clientMeta = null,
                    packages = SupportBillingConfig.defaultCreditPackages
                )
            }
            runOnUiThread { onResult(snapshot) }
        }
    }

    private fun evaluateSupportEntry(
        rawPhone: String,
        displayName: String?,
        onDecision: (SupportAccessDecision) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val decision = runCatching {
                clientSupportRepository.seedDefaultPackagesIfNeeded()
                clientSupportRepository.evaluateSupportAccess(rawPhone = rawPhone, displayName = displayName)
            }.getOrElse {
                Log.e("SXS/Main", "Falha ao verificar acesso de suporte", it)
                SupportAccessDecision.NeedsPhone
            }
            if (decision is SupportAccessDecision.Allowed) {
                pendingSupportStartContext = decision.startContext
                pendingClientRecord = decision.client
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
                put("clientName", clientName ?: "Cliente")
                put("clientPhone", startContext.phone)
                put("clientId", startContext.clientId)
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

    // Cancelar (opcional) â€“ pelo endpoint HTTP do servidor
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
            setSystemMessageFromLauncher?.invoke("SessÃ£o ainda nÃ£o aceita pelo tÃ©cnico.")
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
                    setSystemMessageFromLauncher?.invoke("SessÃ£o ainda nÃ£o aceita pelo tÃ©cnico.")
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
                setSystemMessageFromLauncher?.invoke("PermissÃ£o de captura negada.")
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
                setSystemMessageFromLauncher?.invoke("PermissÃ£o de microfone negada.")
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
        clientIdentityStore = ClientIdentityStore(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { clientSupportRepository.seedDefaultPackagesIfNeeded() }
        }
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        voiceCallManager = VoiceCallManager(
            context = this,
            scope = lifecycleScope,
            onUpdate = ::handleCallUpdate
        )

        connectSocket() // ðŸ”Œ conecta o Socket.IO assim que abrir o app

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
                            phone = null,
                            client = null,
                            clientMeta = null,
                            packages = SupportBillingConfig.defaultCreditPackages
                        )
                    )
                }
                var selectedPackage by remember { mutableStateOf<CreditPackageRecord?>(null) }
                var showIdentityDialog by remember { mutableStateOf(false) }
                var identityPhone by remember { mutableStateOf(clientIdentityStore.getPhone().orEmpty()) }
                var identityName by remember { mutableStateOf(clientIdentityStore.getDisplayName().orEmpty()) }

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
                    loadHomeSnapshot(identityPhone) { snapshot ->
                        homeSnapshot = snapshot
                        if (selectedPackage == null) {
                            selectedPackage = snapshot.packages.firstOrNull()
                        }
                    }
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (current) {
                        Screen.HOME -> SupportHomeScreen(
                            homeSnapshot = homeSnapshot,
                            onRequestSupport = {
                                if (identityPhone.isBlank()) {
                                    showIdentityDialog = true
                                    systemMessage = "Informe seu telefone para continuar."
                                } else {
                                    evaluateSupportEntry(identityPhone, identityName.ifBlank { null }) { decision ->
                                        when (decision) {
                                            is SupportAccessDecision.Allowed -> {
                                                current = Screen.WAITING
                                                requestSupport(
                                                    startContext = decision.startContext,
                                                    clientName = identityName.ifBlank {
                                                        decision.client.name ?: "Cliente"
                                                    }
                                                )
                                                loadHomeSnapshot(identityPhone) { snapshot ->
                                                    homeSnapshot = snapshot
                                                    selectedPackage = selectedPackage
                                                        ?: snapshot.packages.firstOrNull()
                                                }
                                            }

                                            is SupportAccessDecision.BlockedNeedsCredit -> {
                                                homeSnapshot = homeSnapshot.copy(
                                                    phone = identityPhone,
                                                    client = decision.client,
                                                    clientMeta = homeSnapshot.clientMeta,
                                                    packages = decision.packages
                                                )
                                                selectedPackage = decision.packages.firstOrNull()
                                                systemMessage = "Sem creditos disponiveis. Escolha um plano."
                                                current = Screen.PURCHASE_CREDITS
                                            }

                                            SupportAccessDecision.NeedsPhone -> {
                                                showIdentityDialog = true
                                                systemMessage = "Informe seu telefone para continuar."
                                            }
                                        }
                                    }
                                }
                            },
                            onOpenPurchase = {
                                if (selectedPackage == null) {
                                    selectedPackage = homeSnapshot.packages.firstOrNull()
                                }
                                current = Screen.PURCHASE_CREDITS
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
                            onAccepted = { /* nÃ£o usamos; aceitaÃ§Ã£o vem do socket */ },
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
                                    systemMessage = "SessÃ£o ainda nÃ£o aceita pelo tÃ©cnico."
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

                PhoneIdentityDialog(
                    show = showIdentityDialog,
                    initialPhone = identityPhone,
                    initialName = identityName,
                    onDismiss = { showIdentityDialog = false },
                    onConfirm = { phone, name ->
                        val sanitizedPhone = phone.trim()
                        if (sanitizedPhone.isBlank()) {
                            systemMessage = "Informe seu telefone para continuar."
                        } else {
                            identityPhone = sanitizedPhone
                            identityName = name.orEmpty()
                            clientIdentityStore.save(
                                phone = sanitizedPhone,
                                displayName = name
                            )
                            showIdentityDialog = false
                            loadHomeSnapshot(sanitizedPhone) { snapshot ->
                                homeSnapshot = snapshot
                                selectedPackage = snapshot.packages.firstOrNull()
                            }
                            evaluateSupportEntry(sanitizedPhone, name) { decision ->
                                when (decision) {
                                    is SupportAccessDecision.Allowed -> {
                                        current = Screen.WAITING
                                        requestSupport(
                                            startContext = decision.startContext,
                                            clientName = name ?: decision.client.name
                                        )
                                    }

                                    is SupportAccessDecision.BlockedNeedsCredit -> {
                                        homeSnapshot = homeSnapshot.copy(
                                            phone = sanitizedPhone,
                                            client = decision.client,
                                            clientMeta = homeSnapshot.clientMeta,
                                            packages = decision.packages
                                        )
                                        selectedPackage = decision.packages.firstOrNull()
                                        current = Screen.PURCHASE_CREDITS
                                    }

                                    SupportAccessDecision.NeedsPhone -> {
                                        systemMessage = "Telefone invalido. Verifique e tente novamente."
                                    }
                                }
                            }
                        }
                    }
                )
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
        Text("Tempo mÃ©dio de atendimento: 2â€“5 min", color = textMuted, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajuda", color = textMuted,
                modifier = Modifier.clickable {
                    onOpenHelp()
                })
            Text("  Â·  ", color = textMuted)
            Text("Privacidade", color = textMuted,
                modifier = Modifier.clickable {
                    onOpenPrivacy()
                })
            Text("  Â·  ", color = textMuted)
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
                Text("âœ•", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Ajuda", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Guia rÃ¡pido de atendimento", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Como iniciar um atendimento",
                body = "Toque em SOLICITAR SUPORTE e aguarde a conexÃ£o com um tÃ©cnico. Quando o atendimento for aceito, a sessÃ£o serÃ¡ aberta automaticamente no aplicativo."
            )
            PolicySection(
                title = "2. PermissÃµes durante o suporte",
                body = "Alguns atendimentos podem exigir permissÃµes temporÃ¡rias, como compartilhamento de tela, envio de arquivos e acesso assistido. Essas permissÃµes sÃ£o opcionais e vocÃª controla todas elas no app."
            )
            PolicySection(
                title = "3. SeguranÃ§a da sessÃ£o",
                body = "Nenhuma aÃ§Ã£o remota Ã© iniciada sem sua autorizaÃ§Ã£o explÃ­cita. VocÃª pode interromper o compartilhamento e revogar permissÃµes a qualquer momento."
            )
            PolicySection(
                title = "4. Encerrar atendimento",
                body = "Quando desejar, finalize o suporte pelo botÃ£o de encerramento na tela de sessÃ£o. VocÃª tambÃ©m pode interromper permissÃµes diretamente nas configuraÃ§Ãµes do seu dispositivo."
            )
            PolicySection(
                title = "5. Problemas comuns",
                body = "Se a conexÃ£o estiver instÃ¡vel, verifique internet, bateria e permissÃµes do app. Em caso de falha de acesso remoto, confira se o serviÃ§o de acessibilidade do Suporte X estÃ¡ ativado."
            )
            PolicySection(
                title = "6. Canais oficiais",
                body = "Para suporte administrativo, dÃºvidas sobre privacidade ou uso da plataforma, utilize os canais oficiais da Suporte X informados no aplicativo."
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
                Text("âœ•", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("PolÃ­tica de Privacidade", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Ãšltima atualizaÃ§Ã£o: 11 de marÃ§o de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "A Suporte X valoriza a privacidade e a seguranÃ§a dos dados dos usuÃ¡rios. Esta PolÃ­tica de Privacidade descreve como coletamos, utilizamos, armazenamos e protegemos as informaÃ§Ãµes durante a utilizaÃ§Ã£o do aplicativo e dos serviÃ§os de suporte tÃ©cnico remoto.",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            PolicySection(
                title = "1. Dados que coletamos",
                body = "Para a prestaÃ§Ã£o adequada do serviÃ§o de suporte tÃ©cnico remoto, podemos coletar as seguintes informaÃ§Ãµes:\n\nâ€¢ Nome informado pelo usuÃ¡rio no aplicativo\nâ€¢ Identificadores de sessÃ£o e atendimento\nâ€¢ InformaÃ§Ãµes tÃ©cnicas do dispositivo utilizado\nâ€¢ Mensagens trocadas no chat de atendimento\nâ€¢ Arquivos enviados durante o suporte tÃ©cnico\nâ€¢ Registros tÃ©cnicos necessÃ¡rios para diagnÃ³stico e resoluÃ§Ã£o de problemas\n\nEssas informaÃ§Ãµes sÃ£o coletadas exclusivamente para viabilizar o funcionamento do serviÃ§o de suporte."
            )
            PolicySection(
                title = "2. Finalidade do uso dos dados",
                body = "Os dados coletados sÃ£o utilizados para:\n\nâ€¢ Identificar o usuÃ¡rio durante o atendimento\nâ€¢ Realizar suporte tÃ©cnico remoto\nâ€¢ Registrar o histÃ³rico de sessÃµes de atendimento\nâ€¢ Diagnosticar e solucionar problemas tÃ©cnicos\nâ€¢ Melhorar a qualidade e eficiÃªncia do serviÃ§o\nâ€¢ Garantir a seguranÃ§a e integridade da plataforma"
            )
            PolicySection(
                title = "3. Compartilhamento de dados",
                body = "A Suporte X nÃ£o vende, aluga ou comercializa dados pessoais dos usuÃ¡rios.\n\nO compartilhamento de informaÃ§Ãµes pode ocorrer apenas quando necessÃ¡rio com:\n\nâ€¢ Provedores de infraestrutura e hospedagem\nâ€¢ ServiÃ§os de armazenamento de dados\nâ€¢ Ferramentas necessÃ¡rias para o funcionamento da plataforma\n\nTodos os parceiros seguem padrÃµes de seguranÃ§a e proteÃ§Ã£o de dados compatÃ­veis com a legislaÃ§Ã£o aplicÃ¡vel."
            )
            PolicySection(
                title = "4. PermissÃµes e controle do usuÃ¡rio",
                body = "Alguns recursos do aplicativo podem solicitar permissÃµes especÃ­ficas do dispositivo, como:\n\nâ€¢ Compartilhamento de tela\nâ€¢ Acesso remoto ao dispositivo\nâ€¢ Envio de arquivos ou imagens\n\nEssas permissÃµes sÃ£o sempre solicitadas com autorizaÃ§Ã£o explÃ­cita do usuÃ¡rio e podem ser interrompidas ou revogadas a qualquer momento diretamente no aplicativo."
            )
            PolicySection(
                title = "5. Armazenamento e retenÃ§Ã£o de dados",
                body = "Os dados coletados sÃ£o armazenados apenas pelo perÃ­odo necessÃ¡rio para:\n\nâ€¢ PrestaÃ§Ã£o do suporte tÃ©cnico\nâ€¢ Cumprimento de obrigaÃ§Ãµes legais\nâ€¢ Garantia da seguranÃ§a operacional do serviÃ§o\n\nApÃ³s esse perÃ­odo, os dados podem ser excluÃ­dos ou anonimizados."
            )
            PolicySection(
                title = "6. Direitos do usuÃ¡rio",
                body = "Em conformidade com a Lei Geral de ProteÃ§Ã£o de Dados (LGPD â€“ Lei nÂº 13.709/2018), o usuÃ¡rio possui o direito de:\n\nâ€¢ Solicitar acesso aos seus dados\nâ€¢ Corrigir dados incompletos ou desatualizados\nâ€¢ Solicitar exclusÃ£o de dados quando aplicÃ¡vel\nâ€¢ Solicitar informaÃ§Ãµes sobre o tratamento de dados\n\nAs solicitaÃ§Ãµes podem ser feitas pelos canais de contato oficiais."
            )
            PolicySection(
                title = "7. SeguranÃ§a das informaÃ§Ãµes",
                body = "A Suporte X adota medidas tÃ©cnicas e organizacionais para proteger os dados contra acesso nÃ£o autorizado, alteraÃ§Ã£o, divulgaÃ§Ã£o ou destruiÃ§Ã£o indevida.\n\nEntre as medidas aplicadas estÃ£o:\n\nâ€¢ Controle de acesso aos dados\nâ€¢ AutenticaÃ§Ã£o segura\nâ€¢ Registros de auditoria\nâ€¢ ProteÃ§Ã£o da infraestrutura da plataforma"
            )
            PolicySection(
                title = "8. Contato",
                body = "Para dÃºvidas, solicitaÃ§Ãµes ou questÃµes relacionadas Ã  privacidade e proteÃ§Ã£o de dados, entre em contato com o suporte oficial da Suporte X pelos canais disponibilizados no aplicativo."
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
                Text("âœ•", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Termos de Uso", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Operado por Xavier Assessoria Digital", color = textMuted, fontSize = 13.sp)
        Text("CNPJ: 45.765.097/0001-61", color = textMuted, fontSize = 13.sp)
        Text("Ãšltima atualizaÃ§Ã£o: 11 de marÃ§o de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Sobre o serviÃ§o",
                body = "O Suporte X Ã© uma plataforma desenvolvida pela Xavier Assessoria Digital que permite a realizaÃ§Ã£o de suporte tÃ©cnico remoto entre tÃ©cnicos e usuÃ¡rios, por meio de comunicaÃ§Ã£o em tempo real, compartilhamento de tela e ferramentas de assistÃªncia remota.\n\nO serviÃ§o tem como objetivo facilitar o diagnÃ³stico e a resoluÃ§Ã£o de problemas tÃ©cnicos diretamente no dispositivo do usuÃ¡rio, mediante autorizaÃ§Ã£o expressa do mesmo."
            )
            PolicySection(
                title = "2. AceitaÃ§Ã£o dos termos",
                body = "Ao utilizar o aplicativo Suporte X, o usuÃ¡rio declara que:\n\nâ€¢ leu e compreendeu estes Termos de Uso\nâ€¢ concorda com as condiÃ§Ãµes aqui estabelecidas\nâ€¢ autoriza o funcionamento das funcionalidades necessÃ¡rias para o suporte tÃ©cnico remoto\n\nCaso o usuÃ¡rio nÃ£o concorde com estes termos, nÃ£o deverÃ¡ utilizar o aplicativo."
            )
            PolicySection(
                title = "3. Funcionamento do suporte remoto",
                body = "Durante uma sessÃ£o de suporte, o usuÃ¡rio poderÃ¡ autorizar funcionalidades como:\n\nâ€¢ compartilhamento de tela\nâ€¢ envio de arquivos\nâ€¢ comunicaÃ§Ã£o por chat ou Ã¡udio\nâ€¢ controle remoto assistido do dispositivo\n\nEssas funcionalidades sÃ³ sÃ£o ativadas com autorizaÃ§Ã£o explÃ­cita do usuÃ¡rio e podem ser interrompidas a qualquer momento pelo prÃ³prio aplicativo."
            )
            PolicySection(
                title = "4. Responsabilidades do usuÃ¡rio",
                body = "O usuÃ¡rio Ã© responsÃ¡vel por:\n\nâ€¢ conceder permissÃµes apenas quando desejar iniciar um atendimento\nâ€¢ encerrar a sessÃ£o de suporte quando considerar necessÃ¡rio\nâ€¢ nÃ£o utilizar o aplicativo para atividades ilegais ou abusivas"
            )
            PolicySection(
                title = "5. LimitaÃ§Ã£o de responsabilidade",
                body = "A Xavier Assessoria Digital nÃ£o se responsabiliza por:\n\nâ€¢ falhas causadas por terceiros\nâ€¢ problemas decorrentes de uso indevido do dispositivo\nâ€¢ indisponibilidade temporÃ¡ria de serviÃ§os externos"
            )
            PolicySection(
                title = "6. ModificaÃ§Ãµes do serviÃ§o",
                body = "A Xavier Assessoria Digital pode modificar, atualizar ou interromper funcionalidades do aplicativo a qualquer momento, visando melhorias de seguranÃ§a, desempenho ou conformidade legal."
            )
            PolicySection(
                title = "7. LegislaÃ§Ã£o aplicÃ¡vel",
                body = "Este serviÃ§o Ã© regido pelas leis da RepÃºblica Federativa do Brasil, especialmente pela:\n\nâ€¢ Lei Geral de ProteÃ§Ã£o de Dados (LGPD â€“ Lei nÂº 13.709/2018)"
            )
            PolicySection(
                title = "8. Contato",
                body = "Empresa responsÃ¡vel:\nXavier Assessoria Digital\n\nCNPJ:\n45.765.097/0001-61\n\nEndereÃ§o:\nRua dos JequitibÃ¡s, 1895w\nResidencial ParaÃ­so\nNova Mutum â€“ MT\nCEP: 78.454-528\n\nEmail:\nsuportex@xavierassessoriadigital.com.br\n\nTelefone / WhatsApp:\n+55 65 99649-7550"
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
        Text("Acionando tÃ©cnico, aguardeâ€¦", fontSize = 18.sp)
        Text("Tempo mÃ©dio: ~2â€“5 min", color = textMuted)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("CANCELAR SOLICITAÃ‡ÃƒO", color = Color.White) }
    }
}

