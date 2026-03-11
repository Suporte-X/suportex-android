package com.suportex.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.media.projection.MediaProjectionManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.suportex.app.data.model.Message
import com.suportex.app.data.SessionClientInfo
import com.suportex.app.data.SessionRepository
import com.suportex.app.data.SessionState
import com.suportex.app.data.SessionTelemetry
import com.suportex.app.data.SessionTechInfo
import com.suportex.app.call.CallDirection
import com.suportex.app.call.CallState
import com.suportex.app.call.CallUiUpdate
import com.suportex.app.call.VoiceCallManager
import com.suportex.app.remote.AccessibilityUtils
import com.suportex.app.remote.RemoteCommandBus
import com.suportex.app.ui.screens.SessionScreen
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { HOME, PRIVACY, WAITING, SESSION }

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // (mantido só para cancelar request por HTTP, se desejar)
    private val http = OkHttpClient()

    private val sessionRepository = SessionRepository()

    // Bridges Activity -> Compose (já existiam)
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
    private var pendingAudioAction: (() -> Unit)? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioTempFile: File? = null

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
            runCatching { sessionRepository.startSession(sessionId, clientInfo, techInfo) }
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

    private fun requestRemoteStateChange(enabled: Boolean) {
        if (enabled && !isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
            setSystemMessageFromLauncher?.invoke(
                "Ative em Acessibilidade → SuporteX → Ativar para permitir o controle remoto."
            )
        }
        updateRemoteState(enabled, origin = "client")
        sendCommand(if (enabled) "remote_enable" else "remote_revoke")
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
                if (!isSharingActive && !origin.equals("client", ignoreCase = true)) {
                    runOnUiThread {
                        setSystemMessageFromLauncher?.invoke("O técnico solicitou iniciar o compartilhamento de tela.")
                        startScreenShareFlow(fromCommand = true)
                    }
                }
            }
            "share_stop" -> {
                if (isSharingActive) runOnUiThread { stopScreenShare(fromCommand = true) }
            }
            "remote_enable" -> {
                if (!remoteEnabledActive) {
                    runOnUiThread {
                        setSystemMessageFromLauncher?.invoke(
                            "O técnico solicitou acesso remoto. Ative \"Permitir Acesso Remoto\" para continuar."
                        )
                    }
                }
            }
            "remote_disable", "remote_revoke" -> updateRemoteState(enabled = false, origin = "tech")
            "call_start" -> {
                val payload = obj.optJSONObject("payload")
                val connected = payload?.optBoolean("connected")
                    ?: obj.optBoolean("connected", true)
                updateCallState(calling = true, connected = connected, origin = "tech")
            }
            "call_end" -> updateCallState(calling = false, connected = false, origin = "tech")
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
            setSystemMessageFromLauncher?.invoke("Sessão ainda não aceita pelo técnico.")
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
            setSystemMessageFromLauncher?.invoke("Sessão ainda não aceita pelo técnico.")
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
            setSystemMessageFromLauncher?.invoke("Gravando áudio… Toque novamente para enviar.")
        }.onFailure {
            mediaRecorder = null
            audioTempFile = null
            setRecordingAudioFromActivity?.invoke(false)
            setSystemMessageFromLauncher?.invoke("Falha ao iniciar gravação de áudio.")
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
            setSystemMessageFromLauncher?.invoke("Arquivo de áudio não encontrado.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                Conn.chatRepository?.sendAudio(sid, from = "client", localUri = Uri.fromFile(file))
            }
            runOnUiThread {
                if (result.isSuccess) {
                    setSystemMessageFromLauncher?.invoke("Áudio enviado.")
                } else {
                    setSystemMessageFromLauncher?.invoke("Falha ao enviar áudio.")
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
        Conn.sessionId = null
        Conn.techName = null
    }

    private fun handleSessionEnded(fromCommand: Boolean = true, reason: String? = null) {
        val sid = currentSessionId
        val origin = if (fromCommand) "tech" else "client"
        if (!fromCommand) sendCommand("end")

        sid?.let {
            logSessionEvent("end", origin = origin, extras = mapOf("reason" to reason))
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { sessionRepository.markSessionClosed(it) }
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
        val opts = IO.Options().apply {
            forceNew = true
            reconnection = true
            // server.js já tem allowEIO3: true
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
            val any  = args.getOrNull(0) ?: return@on
            val data = (any as? JSONObject) ?: return@on
            // optString precisa de String default; usamos "" e depois tratamos vazio como null
            val reqId = data.optString("requestId", "").takeIf { it.isNotBlank() }
            runOnUiThread { setRequestIdFromSocket?.invoke(reqId) }
        }

        // técnico aceitou → recebemos sessionId e (opcional) techName
        socket.on("support:accepted") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sid = data.optString("sessionId", "")
            val tname = data.optString("techName", "Técnico")
                if (sid.isNotBlank()) {
                    Conn.sessionId = sid
                    Conn.techName = tname
                    currentSessionId = sid
                    resetSessionState()
                    voiceCallManager.bindSession(sid)
                    registerSessionStart(sid, tname)
                    pushSessionState()
                    val joinPayload = JSONObject().apply {
                        put("sessionId", sid)
                        put("role", "client")
                    }
                    socket.emit("session:join", joinPayload)
                    socket.emit("join", joinPayload)
                    startTelemetryLoop()
                    runOnUiThread {
                        setSessionIdFromSocket?.invoke(sid)
                        setScreenFromSocket?.invoke(Screen.SESSION)
                    }
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

    // Disparar pedido de suporte
    private fun requestSupport() {
        val payload = JSONObject(
            mapOf(
                "clientName" to "Android ${Build.MODEL ?: ""}".trim(),
                "brand" to (Build.BRAND ?: "Android"),
                "model" to (Build.MODEL ?: "")
            )
        )
        socket.emit("support:request", payload)
    }

    // Cancelar (opcional) – pelo endpoint HTTP do servidor
    private fun cancelRequest(requestId: String, onDone: () -> Unit = {}) {
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
            setSystemMessageFromLauncher?.invoke("Sessão ainda não aceita pelo técnico.")
            return
        }
        shareRequestFromCommand = fromCommand
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    // -------- Screen share launcher --------
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val sid = currentSessionId
                if (sid.isNullOrBlank()) {
                    setSystemMessageFromLauncher?.invoke("Sessão ainda não aceita pelo técnico.")
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
                setSystemMessageFromLauncher?.invoke("Permissão de captura negada.")
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
                setSystemMessageFromLauncher?.invoke("Permissão de microfone negada.")
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
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        voiceCallManager = VoiceCallManager(
            context = this,
            scope = lifecycleScope,
            onUpdate = ::handleCallUpdate
        )

        connectSocket() // 🔌 conecta o Socket.IO assim que abrir o app

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
                var systemMessage by remember { mutableStateOf<String?>(null) }
                var isRecordingAudio by remember { mutableStateOf(false) }

                BackHandler {
                    when (current) {
                        Screen.PRIVACY -> current = Screen.HOME
                        Screen.WAITING -> {
                            requestId?.let { cancelRequest(it) }
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
                    setRecordingAudioFromActivity = { isRecordingAudio = it }
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (current) {
                        Screen.HOME -> HomeScreen(
                            onRequestSupport = {
                                // Vai para esperando e dispara o evento de suporte
                                current = Screen.WAITING
                                requestSupport()
                            },
                            onOpenPrivacy = { current = Screen.PRIVACY },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.PRIVACY -> PrivacyPolicyScreen(
                            onClose = { current = Screen.HOME },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.WAITING -> WaitingScreen(
                            onCancel = {
                                requestId?.let { cancelRequest(it) }
                                requestId = null
                                sessionId = null
                                currentSessionId = null
                                current = Screen.HOME
                            },
                            onAccepted = { /* não usamos; aceitação vem do socket */ },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.SESSION -> SessionScreen(
                            sessionId = sessionId,
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
                                    systemMessage = "Sessão ainda não aceita pelo técnico."
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
    onOpenPrivacy: () -> Unit,
    textMuted: Color
) {
    val ctx = LocalContext.current

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
        Text("Tempo médio de atendimento: 2–5 min", color = textMuted, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajuda", color = textMuted,
                modifier = Modifier.clickable {
                    Toast.makeText(ctx, "Ajuda (em breve)", Toast.LENGTH_SHORT).show()
                })
            Text("  ·  ", color = textMuted)
            Text("Privacidade", color = textMuted,
                modifier = Modifier.clickable {
                    onOpenPrivacy()
                })
            Text("  ·  ", color = textMuted)
            Text("Termos", color = textMuted,
                modifier = Modifier.clickable {
                    Toast.makeText(ctx, "Termos de uso (em breve)", Toast.LENGTH_SHORT).show()
                })
        }
        Spacer(Modifier.height(24.dp))
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
                Text("✕", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Politica de Privacidade", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X • Atualizado em 10/03/2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Dados que coletamos",
                body = "Coletamos dados necessarios para atendimento tecnico remoto, como nome informado no app, identificadores de sessao, dados tecnicos do dispositivo, mensagens trocadas no chat e arquivos enviados durante o suporte."
            )
            PolicySection(
                title = "2. Finalidade de uso",
                body = "As informacoes sao usadas para identificar o atendimento, prestar suporte, registrar historico da sessao, melhorar a qualidade do servico e manter a seguranca operacional da plataforma."
            )
            PolicySection(
                title = "3. Compartilhamento e seguranca",
                body = "Nao vendemos dados pessoais. O compartilhamento ocorre apenas com provedores essenciais de infraestrutura e armazenamento, com controles de acesso, autenticacao e registros de auditoria."
            )
            PolicySection(
                title = "4. Permissoes e controle do usuario",
                body = "Recursos sensiveis, como compartilhamento de tela e acesso remoto, dependem de autorizacao explicita do usuario e podem ser interrompidos a qualquer momento no proprio aplicativo."
            )
            PolicySection(
                title = "5. Retencao e direitos",
                body = "Mantemos dados pelo periodo necessario para suporte, obrigacoes legais e seguranca. O usuario pode solicitar atualizacao, revisao ou exclusao de dados conforme a legislacao aplicavel."
            )
            PolicySection(
                title = "6. Contato",
                body = "Para duvidas sobre privacidade, seguranca ou tratamento de dados, entre em contato com o suporte oficial da Suporte X pelos canais disponibilizados no aplicativo."
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
        Text("Acionando técnico, aguarde…", fontSize = 18.sp)
        Text("Tempo médio: ~2–5 min", color = textMuted)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("CANCELAR SOLICITAÇÃO", color = Color.White) }
    }
}
