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
import android.os.StatFs
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
import com.suportex.app.data.PhonePnvVerificationResult
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
import com.suportex.app.remote.RemoteControlService
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

    // (mantido so para cancelar request por HTTP, se desejar)
    private val http = OkHttpClient()

    private val sessionRepository = SessionRepository()
    private val authRepository = AuthRepository()
    private val clientSupportRepository = ClientSupportRepository()
    private val phoneIdentityProvider by lazy { FirebasePhoneIdentityProvider(applicationContext) }
    private val supportFlowFlags = SupportFlowFlags()

    // Bridges Activity -> Compose (ja existiam)
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
    private var pnvFlowInProgress = false

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

    private fun launchPnvVerificationFlow(
        clientUid: String,
        startContext: SupportStartContext,
        localSupportSessionId: String?
    ) {
        if (pnvFlowInProgress) return
        lifecycleScope.launch {
            val cachedPhone = runCatching { phoneIdentityProvider.getVerifiedPhoneNumber() }.getOrNull()
            if (!cachedPhone.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        clientSupportRepository.registerPnvSuccess(
                            clientUid = clientUid,
                            verifiedPhone = cachedPhone,
                            token = null,
                            localSupportSessionId = localSupportSessionId
                        )
                    }
                }
                return@launch
            }

            pnvFlowInProgress = true
            try {
                setSystemMessageFromLauncher?.invoke("Validando numero com a operadora...")
                val supportInfo = runCatching { phoneIdentityProvider.checkPnvSupport() }
                    .getOrNull()
                if (supportInfo == null || !supportInfo.hasSupportedSim) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            clientSupportRepository.registerPnvAttempt(
                                clientUid = clientUid,
                                clientId = startContext.clientId,
                                fallbackPhone = startContext.phone,
                                status = "pending",
                                manualFallback = false,
                                reason = supportInfo?.failureReason ?: "pnv_unsupported"
                            )
                        }
                    }
                    setSystemMessageFromLauncher?.invoke(
                        "Verificacao automatica indisponivel neste aparelho. Atendimento segue normalmente."
                    )
                    return@launch
                }

                when (val verificationResult = phoneIdentityProvider.verifyWithPnv(this@MainActivity)) {
                    is PhonePnvVerificationResult.Success -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                clientSupportRepository.registerPnvSuccess(
                                    clientUid = clientUid,
                                    verifiedPhone = verificationResult.phoneNumber,
                                    token = verificationResult.token,
                                    localSupportSessionId = localSupportSessionId
                                )
                            }
                        }
                        setSystemMessageFromLauncher?.invoke("Numero verificado automaticamente.")
                        loadHomeSnapshot { }
                    }
                    is PhonePnvVerificationResult.Failure -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                clientSupportRepository.registerPnvAttempt(
                                    clientUid = clientUid,
                                    clientId = startContext.clientId,
                                    fallbackPhone = startContext.phone,
                                    status = "pending",
                                    manualFallback = false,
                                    reason = if (verificationResult.userCancelled) {
                                        "pnv_user_cancelled"
                                    } else {
                                        verificationResult.reason
                                    }
                                )
                            }
                        }
                        setSystemMessageFromLauncher?.invoke(
                            "Nao foi possivel concluir a verificacao automatica agora. Atendimento segue normalmente."
                        )
                    }
                }
            } finally {
                pnvFlowInProgress = false
            }
        }
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

    private data class RuntimeTelemetrySnapshot(
        val batteryLevel: Int?,
        val batteryCharging: Boolean,
        val temperatureC: Double?,
        val networkLabel: String,
        val storageFreeBytes: Long?,
        val storageTotalBytes: Long?,
        val permissionsSummary: String,
        val permissionsMap: Map<String, Any>,
        val health: String,
        val alerts: String
    )

    private fun buildTelemetrySnapshot(snapshot: RuntimeTelemetrySnapshot): SessionTelemetry = SessionTelemetry(
        battery = snapshot.batteryLevel,
        net = snapshot.networkLabel,
        network = snapshot.networkLabel,
        batteryLevel = snapshot.batteryLevel,
        batteryCharging = snapshot.batteryCharging,
        temperatureC = snapshot.temperatureC,
        storageFreeBytes = snapshot.storageFreeBytes,
        storageTotalBytes = snapshot.storageTotalBytes,
        health = snapshot.health,
        permissionsSummary = snapshot.permissionsSummary,
        permissions = snapshot.permissionsMap,
        alerts = snapshot.alerts,
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
                Log.e("SXS/Main", "Falha ao registrar inicio da sessao $sessionId", err)
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
            Para que o suporte tecnico possa ser realizado, o aplicativo Suporte X pode solicitar permissoes temporarias como:
            
            - compartilhamento de tela
            - acesso assistido ao dispositivo
            - envio de arquivos e informacoes tecnicas
            
            Essas permissoes sao utilizadas exclusivamente durante a sessao de suporte tecnico.
            
            O acesso remoto somente sera iniciado apos sua autorizacao explicita e pode ser interrompido a qualquer momento diretamente no aplicativo.
            
            Nenhuma acao sera realizada no dispositivo sem a autorizacao do usuario.
            
            Ao continuar, voce confirma que esta solicitando suporte tecnico e autoriza temporariamente o acesso necessario para a realizacao do atendimento.
        """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("Autorizacao de Acesso Remoto")
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
                    setSystemMessageFromLauncher?.invoke("Ative em Acessibilidade > SuporteX > Ativar para permitir o controle remoto.")
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

        val telemetry = collectRuntimeTelemetry()
        val permissionsJson = JSONObject().apply {
            telemetry.permissionsMap.forEach { (key, value) -> put(key, value) }
            put("summary", telemetry.permissionsSummary)
        }
        val data = JSONObject().apply {
            put("sessionId", sid)
            put("from", "client")
            val status = JSONObject()
            status.put("battery", telemetry.batteryLevel ?: JSONObject.NULL)
            status.put("batteryLevel", telemetry.batteryLevel ?: JSONObject.NULL)
            status.put("batteryCharging", telemetry.batteryCharging)
            status.put("temperatureC", telemetry.temperatureC ?: JSONObject.NULL)
            status.put("network", telemetry.networkLabel)
            status.put("net", telemetry.networkLabel)
            status.put("storageFreeBytes", telemetry.storageFreeBytes ?: JSONObject.NULL)
            status.put("storageTotalBytes", telemetry.storageTotalBytes ?: JSONObject.NULL)
            status.put("permissions", permissionsJson)
            status.put("health", telemetry.health)
            status.put("alerts", telemetry.alerts)
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
                "battery" to telemetry.batteryLevel,
                "batteryCharging" to telemetry.batteryCharging,
                "temperatureC" to telemetry.temperatureC,
                "network" to telemetry.networkLabel,
                "storageFreeBytes" to telemetry.storageFreeBytes,
                "storageTotalBytes" to telemetry.storageTotalBytes,
                "permissions" to telemetry.permissionsSummary,
                "health" to telemetry.health,
                "alerts" to telemetry.alerts,
                "sharing" to isSharingActive,
                "remoteEnabled" to remoteEnabledActive,
                "calling" to callingActive,
                "callConnected" to callConnectedActive
            )
        )
        pushSessionState(buildTelemetrySnapshot(telemetry))
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

    private fun collectRuntimeTelemetry(): RuntimeTelemetrySnapshot {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val temperatureRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temperatureC = if (temperatureRaw != Int.MIN_VALUE) {
            (temperatureRaw / 10.0)
        } else {
            null
        }

        val networkLabel = getNetworkLabel()
        val (storageFreeBytes, storageTotalBytes) = getStorageSnapshot()

        val accessibilityEnabled = AccessibilityUtils.isServiceEnabled(
            this,
            RemoteControlService::class.java
        )
        val microphoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val overlayEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        val permissionsSummary = buildPermissionsSummary(
            accessibilityEnabled = accessibilityEnabled,
            microphoneGranted = microphoneGranted,
            overlayEnabled = overlayEnabled
        )
        val health = buildDeviceHealthLabel(
            batteryLevel = batteryLevel,
            temperatureC = temperatureC,
            storageFreeBytes = storageFreeBytes,
            storageTotalBytes = storageTotalBytes
        )
        val alerts = buildDeviceAlertsLabel(
            batteryLevel = batteryLevel,
            temperatureC = temperatureC,
            storageFreeBytes = storageFreeBytes,
            storageTotalBytes = storageTotalBytes
        )

        return RuntimeTelemetrySnapshot(
            batteryLevel = batteryLevel,
            batteryCharging = batteryCharging,
            temperatureC = temperatureC?.let { kotlin.math.round(it * 10.0) / 10.0 },
            networkLabel = networkLabel,
            storageFreeBytes = storageFreeBytes,
            storageTotalBytes = storageTotalBytes,
            permissionsSummary = permissionsSummary,
            permissionsMap = mapOf(
                "accessibilityEnabled" to accessibilityEnabled,
                "microphoneGranted" to microphoneGranted,
                "overlayEnabled" to overlayEnabled
            ),
            health = health,
            alerts = alerts
        )
    }

    private fun getStorageSnapshot(): Pair<Long?, Long?> {
        return runCatching {
            val stat = StatFs(filesDir.absolutePath)
            Pair(stat.availableBytes, stat.totalBytes)
        }.getOrElse {
            Pair(null, null)
        }
    }

    private fun getNetworkLabel(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "Desconhecida"
        val network = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "Desconhecida"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rede móvel"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Desconhecida"
        }
    }

    private fun buildPermissionsSummary(
        accessibilityEnabled: Boolean,
        microphoneGranted: Boolean,
        overlayEnabled: Boolean
    ): String {
        val rows = listOf(
            "Acessibilidade: ${if (accessibilityEnabled) "ok" else "pendente"}",
            "Microfone: ${if (microphoneGranted) "ok" else "pendente"}",
            "Sobreposição: ${if (overlayEnabled) "ok" else "pendente"}"
        )
        return rows.joinToString(" • ")
    }

    private fun buildDeviceHealthLabel(
        batteryLevel: Int?,
        temperatureC: Double?,
        storageFreeBytes: Long?,
        storageTotalBytes: Long?
    ): String {
        val storageRatio = if (storageFreeBytes != null && storageTotalBytes != null && storageTotalBytes > 0) {
            storageFreeBytes.toDouble() / storageTotalBytes.toDouble()
        } else {
            null
        }
        if ((batteryLevel != null && batteryLevel <= 10) ||
            (temperatureC != null && temperatureC >= 43.0) ||
            (storageRatio != null && storageRatio <= 0.05)
        ) {
            return "Crítico"
        }
        if ((batteryLevel != null && batteryLevel <= 20) ||
            (temperatureC != null && temperatureC >= 39.0) ||
            (storageRatio != null && storageRatio <= 0.12)
        ) {
            return "Atenção"
        }
        return "Bom"
    }

    private fun buildDeviceAlertsLabel(
        batteryLevel: Int?,
        temperatureC: Double?,
        storageFreeBytes: Long?,
        storageTotalBytes: Long?
    ): String {
        val alerts = mutableListOf<String>()
        if (batteryLevel != null && batteryLevel <= 15) {
            alerts.add("Bateria baixa")
        }
        if (temperatureC != null && temperatureC >= 40.0) {
            alerts.add("Temperatura elevada")
        }
        if (storageFreeBytes != null && storageTotalBytes != null && storageTotalBytes > 0) {
            val ratio = storageFreeBytes.toDouble() / storageTotalBytes.toDouble()
            if (ratio <= 0.1) alerts.add("Armazenamento quase cheio")
        }
        return if (alerts.isEmpty()) "Sem alertas" else alerts.joinToString(" • ")
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
                        setSystemMessageFromLauncher?.invoke("O tecnico solicitou iniciar o compartilhamento de tela.")
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
                            "O tecnico solicitou acesso remoto. Ative \"Permitir Acesso Remoto\" para continuar."
                        )
                    }
                }
            }
            "remote_disable", "remote_revoke" -> updateRemoteState(enabled = false, origin = "tech")
            // A chamada agora e dirigida pelo VoiceCallManager (Firestore/WebRTC),
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
            setSystemMessageFromLauncher?.invoke("Sessao ainda nao aceita pelo tecnico.")
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
            setSystemMessageFromLauncher?.invoke("Sessao ainda nao aceita pelo tecnico.")
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
            setSystemMessageFromLauncher?.invoke("Gravando audio... Toque novamente para enviar.")
        }.onFailure {
            mediaRecorder = null
            audioTempFile = null
            setRecordingAudioFromActivity?.invoke(false)
            setSystemMessageFromLauncher?.invoke("Falha ao iniciar gravacao de audio.")
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
            setSystemMessageFromLauncher?.invoke("Arquivo de audio nao encontrado.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                Conn.chatRepository?.sendAudio(sid, from = "client", localUri = Uri.fromFile(file))
            }
            runOnUiThread {
                if (result.isSuccess) {
                    setSystemMessageFromLauncher?.invoke("Audio enviado.")
                } else {
                    setSystemMessageFromLauncher?.invoke("Falha ao enviar audio.")
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
            // server.js ja tem allowEIO3: true
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

        // tecnico aceitou -> recebemos sessionId e (opcional) techName
        socket.on("support:accepted") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sid = data.optString("sessionId", "")
            val tname = data.optString("techName", "Tecnico")
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
            val verifiedPhone = runCatching { phoneIdentityProvider.getVerifiedPhoneNumber() }.getOrNull()
            val effectivePhone = verifiedPhone ?: startContext.phone
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
                    startContext = startContext.copy(phone = effectivePhone),
                    clientName = clientName,
                    clientUid = uid,
                    deviceBrand = Build.BRAND,
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
                )
            }.getOrNull()

            val payload = JSONObject().apply {
                put("clientName", clientName ?: "Cliente em atendimento")
                put("clientPhone", effectivePhone ?: "")
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

            if (!verifiedPhone.isNullOrBlank()) {
                runCatching {
                    clientSupportRepository.registerPnvSuccess(
                        clientUid = uid,
                        verifiedPhone = verifiedPhone,
                        token = null,
                        localSupportSessionId = pendingSupportSessionId
                    )
                }
            } else {
                runOnUiThread {
                    launchPnvVerificationFlow(
                        clientUid = uid,
                        startContext = startContext,
                        localSupportSessionId = pendingSupportSessionId
                    )
                }
            }
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

    // Cancelar (opcional) pelo endpoint HTTP do servidor
    private fun cancelRequest(requestId: String, onDone: () -> Unit = {}) {
        val localSupportId = pendingSupportSessionId
        if (!localSupportId.isNullOrBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { clientSupportRepository.cancelSupportRequest(localSupportId) }
            }
        }
        pendingSupportSessionId = null
        pendingSupportStartContext = null

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (::socket.isInitialized) {
                    socket.emit("support:cancel", JSONObject().apply {
                        put("requestId", requestId)
                    })
                }
            }

            val idToken = runCatching { authRepository.ensureAnonIdToken(forceRefresh = false) }
                .getOrDefault("")
            val reqBuilder = Request.Builder()
                .url("${Conn.SERVER_BASE}/api/client/requests/$requestId")
                .delete()
            if (idToken.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $idToken")
                reqBuilder.addHeader("x-id-token", idToken)
            }

            runCatching {
                http.newCall(reqBuilder.build()).execute().use { _ -> }
            }.onFailure { error ->
                Log.w("SXS/Main", "Falha ao cancelar request no backend", error)
            }
            onDone()
        }
    }

    private fun startScreenShareFlow(fromCommand: Boolean = false) {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            shareRequestFromCommand = false
            setSystemMessageFromLauncher?.invoke("Sessao ainda nao aceita pelo tecnico.")
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
                    setSystemMessageFromLauncher?.invoke("Sessao ainda nao aceita pelo tecnico.")
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
                setSystemMessageFromLauncher?.invoke("Permissao de captura negada.")
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
                setSystemMessageFromLauncher?.invoke("Permissao de microfone negada.")
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

        connectSocket() // conecta o Socket.IO assim que abrir o app

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
                                        "Sem credito disponivel",
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
                                                "Sem credito disponivel",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onBlockedSupportRequest = {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Sem credito disponivel",
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
                            onAccepted = { /* nao usamos; aceitacao vem do socket */ },
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
                                    systemMessage = "Sessao ainda nao aceita pelo tecnico."
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
        Text("Tempo medio de atendimento: 2-5 min", color = textMuted, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajuda", color = textMuted, modifier = Modifier.clickable { onOpenHelp() })
            Text("  |  ", color = textMuted)
            Text("Privacidade", color = textMuted, modifier = Modifier.clickable { onOpenPrivacy() })
            Text("  |  ", color = textMuted)
            Text("Termos", color = textMuted, modifier = Modifier.clickable { onOpenTerms() })
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
                Text("X", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Ajuda", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Guia rapido de atendimento", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Como iniciar um atendimento",
                body = "Toque em SOLICITAR SUPORTE e aguarde a conexao com um tecnico. Quando o atendimento for aceito, a sessao sera aberta automaticamente no aplicativo."
            )
            PolicySection(
                title = "2. Permissoes durante o suporte",
                body = "Alguns atendimentos podem exigir permissoes temporarias, como compartilhamento de tela, envio de arquivos e acesso assistido. Essas permissoes sao opcionais e voce controla todas elas no app."
            )
            PolicySection(
                title = "3. Seguranca da sessao",
                body = "Nenhuma acao remota e iniciada sem sua autorizacao explicita. Voce pode interromper o compartilhamento e revogar permissoes a qualquer momento."
            )
            PolicySection(
                title = "4. Encerrar atendimento",
                body = "Quando desejar, finalize o suporte pelo botao de encerramento na tela de sessao. Voce tambem pode interromper permissoes diretamente nas configuracoes do seu dispositivo."
            )
            PolicySection(
                title = "5. Problemas comuns",
                body = "Se a conexao estiver instavel, verifique internet, bateria e permissoes do app. Em caso de falha de acesso remoto, confira se o servico de acessibilidade do Suporte X esta ativado."
            )
            PolicySection(
                title = "6. Canais oficiais",
                body = "Para suporte administrativo, duvidas sobre privacidade ou uso da plataforma, utilize os canais oficiais da Suporte X informados no aplicativo."
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
                Text("X", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Politica de Privacidade", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Ultima atualizacao: 11 de marco de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "A Suporte X valoriza a privacidade e a seguranca dos dados dos usuarios. Esta Politica de Privacidade descreve como coletamos, utilizamos, armazenamos e protegemos as informacoes durante a utilizacao do aplicativo e dos servicos de suporte tecnico remoto.",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            PolicySection(
                title = "1. Dados que coletamos",
                body = "Para a prestacao adequada do servico de suporte tecnico remoto, podemos coletar as seguintes informacoes:\n\n- Nome informado pelo usuario no aplicativo\n- Identificadores de sessao e atendimento\n- Informacoes tecnicas do dispositivo utilizado\n- Mensagens trocadas no chat de atendimento\n- Arquivos enviados durante o suporte tecnico\n- Registros tecnicos necessarios para diagnostico e resolucao de problemas\n\nEssas informacoes sao coletadas exclusivamente para viabilizar o funcionamento do servico de suporte."
            )
            PolicySection(
                title = "2. Finalidade do uso dos dados",
                body = "Os dados coletados sao utilizados para:\n\n- Identificar o usuario durante o atendimento\n- Realizar suporte tecnico remoto\n- Registrar o historico de sessoes de atendimento\n- Diagnosticar e solucionar problemas tecnicos\n- Melhorar a qualidade e eficiencia do servico\n- Garantir a seguranca e integridade da plataforma"
            )
            PolicySection(
                title = "3. Compartilhamento de dados",
                body = "A Suporte X nao vende, aluga ou comercializa dados pessoais dos usuarios.\n\nO compartilhamento de informacoes pode ocorrer apenas quando necessario com:\n\n- Provedores de infraestrutura e hospedagem\n- Servicos de armazenamento de dados\n- Ferramentas necessarias para o funcionamento da plataforma\n\nTodos os parceiros seguem padroes de seguranca e protecao de dados compativeis com a legislacao aplicavel."
            )
            PolicySection(
                title = "4. Permissoes e controle do usuario",
                body = "Alguns recursos do aplicativo podem solicitar permissoes especificas do dispositivo, como:\n\n- Compartilhamento de tela\n- Acesso remoto ao dispositivo\n- Envio de arquivos ou imagens\n\nEssas permissoes sao sempre solicitadas com autorizacao explicita do usuario e podem ser interrompidas ou revogadas a qualquer momento diretamente no aplicativo."
            )
            PolicySection(
                title = "5. Armazenamento e retencao de dados",
                body = "Os dados coletados sao armazenados apenas pelo periodo necessario para:\n\n- Prestacao do suporte tecnico\n- Cumprimento de obrigacoes legais\n- Garantia da seguranca operacional do servico\n\nApos esse periodo, os dados podem ser excluidos ou anonimizados."
            )
            PolicySection(
                title = "6. Direitos do usuario",
                body = "Em conformidade com a Lei Geral de Protecao de Dados (LGPD - Lei no 13.709/2018), o usuario possui o direito de:\n\n- Solicitar acesso aos seus dados\n- Corrigir dados incompletos ou desatualizados\n- Solicitar exclusao de dados quando aplicavel\n- Solicitar informacoes sobre o tratamento de dados\n\nAs solicitacoes podem ser feitas pelos canais de contato oficiais."
            )
            PolicySection(
                title = "7. Seguranca das informacoes",
                body = "A Suporte X adota medidas tecnicas e organizacionais para proteger os dados contra acesso nao autorizado, alteracao, divulgacao ou destruicao indevida.\n\nEntre as medidas aplicadas estao:\n\n- Controle de acesso aos dados\n- Autenticacao segura\n- Registros de auditoria\n- Protecao da infraestrutura da plataforma"
            )
            PolicySection(
                title = "8. Contato",
                body = "Para duvidas, solicitacoes ou questoes relacionadas a privacidade e protecao de dados, entre em contato com o suporte oficial da Suporte X pelos canais disponibilizados no aplicativo."
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
                Text("X", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Termos de Uso", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Operado por Xavier Assessoria Digital", color = textMuted, fontSize = 13.sp)
        Text("CNPJ: 45.765.097/0001-61", color = textMuted, fontSize = 13.sp)
        Text("Ultima atualizacao: 11 de marco de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Sobre o servico",
                body = "O Suporte X e uma plataforma desenvolvida pela Xavier Assessoria Digital que permite a realizacao de suporte tecnico remoto entre tecnicos e usuarios, por meio de comunicacao em tempo real, compartilhamento de tela e ferramentas de assistencia remota.\n\nO servico tem como objetivo facilitar o diagnostico e a resolucao de problemas tecnicos diretamente no dispositivo do usuario, mediante autorizacao expressa do mesmo."
            )
            PolicySection(
                title = "2. Aceitacao dos termos",
                body = "Ao utilizar o aplicativo Suporte X, o usuario declara que:\n\n- leu e compreendeu estes Termos de Uso\n- concorda com as condicoes aqui estabelecidas\n- autoriza o funcionamento das funcionalidades necessarias para o suporte tecnico remoto\n\nCaso o usuario nao concorde com estes termos, nao devera utilizar o aplicativo."
            )
            PolicySection(
                title = "3. Funcionamento do suporte remoto",
                body = "Durante uma sessao de suporte, o usuario podera autorizar funcionalidades como:\n\n- compartilhamento de tela\n- envio de arquivos\n- comunicacao por chat ou audio\n- controle remoto assistido do dispositivo\n\nEssas funcionalidades so sao ativadas com autorizacao explicita do usuario e podem ser interrompidas a qualquer momento pelo proprio aplicativo."
            )
            PolicySection(
                title = "4. Responsabilidades do usuario",
                body = "O usuario e responsavel por:\n\n- conceder permissoes apenas quando desejar iniciar um atendimento\n- encerrar a sessao de suporte quando considerar necessario\n- nao utilizar o aplicativo para atividades ilegais ou abusivas"
            )
            PolicySection(
                title = "5. Limitacao de responsabilidade",
                body = "A Xavier Assessoria Digital nao se responsabiliza por:\n\n- falhas causadas por terceiros\n- problemas decorrentes de uso indevido do dispositivo\n- indisponibilidade temporaria de servicos externos"
            )
            PolicySection(
                title = "6. Modificacoes do servico",
                body = "A Xavier Assessoria Digital pode modificar, atualizar ou interromper funcionalidades do aplicativo a qualquer momento, visando melhorias de seguranca, desempenho ou conformidade legal."
            )
            PolicySection(
                title = "7. Legislacao aplicavel",
                body = "Este servico e regido pelas leis da Republica Federativa do Brasil, especialmente pela:\n\n- Lei Geral de Protecao de Dados (LGPD - Lei no 13.709/2018)"
            )
            PolicySection(
                title = "8. Contato",
                body = "Empresa responsavel:\nXavier Assessoria Digital\n\nCNPJ:\n45.765.097/0001-61\n\nEndereco:\nRua dos Jequitibas, 1895w\nResidencial Paraiso\nNova Mutum - MT\nCEP: 78.454-528\n\nEmail:\nsuportex@xavierassessoriadigital.com.br\n\nTelefone / WhatsApp:\n+55 65 99649-7550"
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
        Text("Acionando tecnico, aguarde...", fontSize = 18.sp)
        Text("Tempo medio: ~2-5 min", color = textMuted)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("CANCELAR SOLICITACAO", color = Color.White) }
    }
}
