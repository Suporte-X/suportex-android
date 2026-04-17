package com.suportex.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.app.AlertDialog
import android.media.projection.MediaProjectionManager
import android.app.ActivityManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.suportex.app.data.ClientSupportRepository
import com.suportex.app.data.ClientSupportRepository.SupportQueueWaitStats
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
import com.suportex.app.ui.screens.SessionFeedbackScreen
import com.suportex.app.ui.screens.SessionScreen
import com.suportex.app.ui.screens.StartupLoadingScreen
import com.suportex.app.ui.screens.SupportHomeScreen
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

enum class Screen {
    HOME,
    HELP,
    PRIVACY,
    TERMS,
    WAITING,
    SESSION,
    SESSION_FEEDBACK,
    PURCHASE_CREDITS,
    PAYMENT_CARD,
    PAYMENT_PIX
}

private const val DEFAULT_AVERAGE_WAIT_FALLBACK_MILLIS = 90_000L
private const val PREFS_RUNTIME_PERMISSIONS = "runtime_permissions"
private const val KEY_INITIAL_PERMISSIONS_REQUESTED = "initial_permissions_requested"
private const val CHAT_NOTIFICATION_CHANNEL_ID = "suportex_chat_messages"
private const val SESSION_NOTIFICATION_CHANNEL_ID = "suportex_session_events"
private const val CALL_NOTIFICATION_CHANNEL_ID = "suportex_call_alerts"
private const val ACTION_OPEN_SESSION_CHAT = "com.suportex.app.action.OPEN_SESSION_CHAT"
private const val ACTION_OPEN_SESSION_FEEDBACK = "com.suportex.app.action.OPEN_SESSION_FEEDBACK"
private const val ACTION_WAITING_SUPPORT_ACCEPTED = "com.suportex.app.action.WAITING_SUPPORT_ACCEPTED"
private const val EXTRA_SESSION_ID = "extra_session_id"
private const val EXTRA_TECH_NAME = "extra_tech_name"
private const val EXTRA_LOCAL_SUPPORT_SESSION_ID = "extra_local_support_session_id"
private const val EXTRA_WAITING_ANCHOR_ID = "extra_waiting_anchor_id"
private const val PREFS_WAITING_SUPPORT = "waiting_support_runtime"
private const val KEY_PENDING_SUPPORT_SESSION_ID = "pending_support_session_id"
private const val WAITING_SESSION_RECOVERY_INTERVAL_MS = 4_000L

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
    private var setEndedSessionForFeedback: ((String?) -> Unit)? = null

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
    private var activeSupportRequestId: String? = null
    private var pendingSupportStartContext: SupportStartContext? = null
    private var pendingSupportSessionId: String? = null
    private var waitingSessionRecoveryJob: Job? = null
    private var pnvFlowInProgress = false
    private var appInForeground = false
    private var initialPermissionsPromptedThisLaunch = false
    private var pendingLaunchSessionFromNotification: String? = null
    private var pendingLaunchFeedbackSessionId: String? = null
    private var pendingLaunchAcceptedSessionId: String? = null
    private var pendingLaunchAcceptedTechName: String? = null
    private var pendingLaunchAcceptedLocalSupportSessionId: String? = null
    private var currentCallUiState: CallState = CallState.IDLE
    private var currentCallDirection: CallDirection? = null
    private var incomingCallRingtone: Ringtone? = null
    private var incomingCallAlertActive = false

    // -------- Helpers --------
    @Suppress("unused")
    private fun deviceId(): String {
        val p = getSharedPreferences("app", MODE_PRIVATE)
        val cur = p.getString("device_id", null)
        if (cur != null) return cur
        val gen = UUID.randomUUID().toString()
        p.edit().putString("device_id", gen).apply()
        return gen
    }

    private fun deviceAnchor(): String {
        val androidId = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
        val raw = androidId ?: deviceId()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    @Suppress("unused")
    private fun copyToClipboard(label: String, text: String) {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun readPendingSupportSessionFromPrefs(): String? {
        return getSharedPreferences(PREFS_WAITING_SUPPORT, MODE_PRIVATE)
            .getString(KEY_PENDING_SUPPORT_SESSION_ID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun writePendingSupportSessionToPrefs(localSupportSessionId: String?) {
        val prefs = getSharedPreferences(PREFS_WAITING_SUPPORT, MODE_PRIVATE)
        if (localSupportSessionId.isNullOrBlank()) {
            prefs.edit().remove(KEY_PENDING_SUPPORT_SESSION_ID).apply()
        } else {
            prefs.edit().putString(KEY_PENDING_SUPPORT_SESSION_ID, localSupportSessionId).apply()
        }
    }

    private fun startWaitingSupportForegroundService(anchorId: String, localSupportSessionId: String?) {
        val normalizedAnchorId = anchorId.trim()
        if (normalizedAnchorId.isBlank()) return
        val intent = Intent(this, WaitingSupportMonitorService::class.java).apply {
            action = WaitingSupportMonitorService.ACTION_START_MONITOR
            putExtra(EXTRA_WAITING_ANCHOR_ID, normalizedAnchorId)
            putExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID, localSupportSessionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { startForegroundService(intent) }
                .onFailure { err -> Log.w("SXS/Main", "Falha ao iniciar servico de fila", err) }
        } else {
            runCatching { startService(intent) }
                .onFailure { err -> Log.w("SXS/Main", "Falha ao iniciar servico de fila", err) }
        }
    }

    private fun stopWaitingSupportForegroundService() {
        runCatching {
            stopService(Intent(this, WaitingSupportMonitorService::class.java))
        }.onFailure { err ->
            Log.w("SXS/Main", "Falha ao parar servico de fila", err)
        }
    }

    private fun startSessionAnchorForegroundService(sessionId: String, techName: String?) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return
        val intent = Intent(this, SessionAnchorService::class.java).apply {
            action = SessionAnchorService.ACTION_START_SESSION_ANCHOR
            putExtra(EXTRA_SESSION_ID, normalizedSessionId)
            putExtra(EXTRA_TECH_NAME, techName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { startForegroundService(intent) }
                .onFailure { err ->
                    Log.w("SXS/Main", "Falha ao iniciar ancora de sessao via foreground", err)
                    runCatching { startService(intent) }
                        .onFailure { fallbackErr ->
                            Log.w("SXS/Main", "Falha ao iniciar ancora de sessao via fallback", fallbackErr)
                        }
                }
        } else {
            runCatching { startService(intent) }
                .onFailure { err -> Log.w("SXS/Main", "Falha ao iniciar ancora de sessao", err) }
        }
    }

    private fun stopSessionAnchorForegroundService() {
        runCatching {
            val intent = Intent(this, SessionAnchorService::class.java).apply {
                action = SessionAnchorService.ACTION_STOP_SESSION_ANCHOR
            }
            startService(intent)
        }.onFailure { err ->
            Log.w("SXS/Main", "Falha ao solicitar parada da ancora de sessao", err)
            runCatching { stopService(Intent(this, SessionAnchorService::class.java)) }
                .onFailure { stopErr ->
                    Log.w("SXS/Main", "Falha ao parar ancora de sessao via stopService", stopErr)
                }
        }
    }

    private fun syncSessionAnchorForegroundService() {
        val sessionId = currentSessionId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (sessionId.isNullOrBlank()) {
            stopSessionAnchorForegroundService()
            return
        }
        startSessionAnchorForegroundService(
            sessionId = sessionId,
            techName = Conn.techName
        )
    }

    private fun syncWaitingSupportForegroundService() {
        val anchorId = pendingSupportSessionId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: activeSupportRequestId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (anchorId.isNullOrBlank()) {
            stopWaitingSupportForegroundService()
            return
        }
        startWaitingSupportForegroundService(
            anchorId = anchorId,
            localSupportSessionId = pendingSupportSessionId
        )
    }

    private fun setPendingSupportSession(localSupportSessionId: String?) {
        val normalized = localSupportSessionId?.trim()?.takeIf { it.isNotBlank() }
        pendingSupportSessionId = normalized
        writePendingSupportSessionToPrefs(normalized)
        syncWaitingSupportForegroundService()
    }

    private fun loadSupportQueueWaitStats(onResult: (SupportQueueWaitStats) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val stats = runCatching { clientSupportRepository.loadSupportQueueWaitStats() }
                .getOrElse {
                    SupportQueueWaitStats(
                        queueDepth = 0,
                        targetSampleSize = 1,
                        usedSampleSize = 0,
                        averageWaitMillis = null
                    )
                }
            runOnUiThread { onResult(stats) }
        }
    }

    private fun formatHomeAverageWaitLabel(stats: SupportQueueWaitStats?): String {
        val averageWaitMillis = stats?.averageWaitMillis
        if (averageWaitMillis == null || stats.usedSampleSize <= 0) {
            return "Tempo médio de atendimento: ${formatDurationShort(DEFAULT_AVERAGE_WAIT_FALLBACK_MILLIS)}"
        }
        val durationLabel = formatDurationShort(averageWaitMillis)
        return "Tempo médio de atendimento: $durationLabel"
    }

    private fun formatWaitingAverageWaitLabel(stats: SupportQueueWaitStats?): String {
        val averageWaitMillis = stats?.averageWaitMillis
        if (averageWaitMillis == null || stats.usedSampleSize <= 0) {
            return "Tempo médio: ${formatDurationShort(DEFAULT_AVERAGE_WAIT_FALLBACK_MILLIS)}"
        }
        val durationLabel = formatDurationShort(averageWaitMillis)
        return "Tempo médio: $durationLabel"
    }

    private fun formatDurationShort(durationMillis: Long): String {
        val safeMillis = durationMillis.coerceAtLeast(0L)
        val totalSeconds = (safeMillis / 1000.0).roundToLong()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes <= 0L -> "menos de 1 min"
            minutes < 2L && seconds > 0L -> "$minutes min ${seconds}s"
            else -> "$minutes min"
        }
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
                            localSupportSessionId = localSupportSessionId,
                            deviceAnchor = deviceAnchor()
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
                                reason = supportInfo?.failureReason ?: "pnv_unsupported",
                                deviceAnchor = deviceAnchor()
                            )
                        }
                    }
                    setSystemMessageFromLauncher?.invoke(
                        "Verificacao automatica indisponivel neste aparelho/operadora. O tecnico pode validar por SMS no painel."
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
                                    localSupportSessionId = localSupportSessionId,
                                    deviceAnchor = deviceAnchor()
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
                                },
                                deviceAnchor = deviceAnchor()
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
                    setSystemMessageFromLauncher?.invoke("Ative em Acessibilidade > Suporte X > Ativar para permitir o controle remoto.")
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
        currentCallUiState = update.state
        currentCallDirection = update.direction
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

        val incomingFromTech = update.state == CallState.INCOMING_RINGING &&
            update.direction == CallDirection.TECH_TO_CLIENT
        val sid = currentSessionId
        if (incomingFromTech) {
            startIncomingCallAlert()
            if (appInForeground) {
                cancelIncomingCallNotification(sid)
            } else if (!sid.isNullOrBlank()) {
                notifyIncomingCallFromTech(sid)
            }
        } else {
            stopIncomingCallAlert()
            cancelIncomingCallNotification(sid)
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
        val notificationsGranted = isNotificationPermissionGranted()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val permissionsSummary = buildPermissionsSummary(
            accessibilityEnabled = accessibilityEnabled,
            microphoneGranted = microphoneGranted,
            notificationsGranted = notificationsGranted,
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
                "notificationsGranted" to notificationsGranted,
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
        notificationsGranted: Boolean,
        overlayEnabled: Boolean
    ): String {
        val rows = listOf(
            "Acessibilidade: ${if (accessibilityEnabled) "ok" else "pendente"}",
            "Microfone: ${if (microphoneGranted) "ok" else "pendente"}",
            "Notificacoes: ${if (notificationsGranted) "ok" else "pendente"}",
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

    private fun isNotificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureNotificationChannel(
        id: String,
        name: String,
        description: String,
        importance: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, importance).apply {
            this.description = description
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun maybePromptInitialCriticalPermissions() {
        if (initialPermissionsPromptedThisLaunch) return
        initialPermissionsPromptedThisLaunch = true

        val prefs = getSharedPreferences(PREFS_RUNTIME_PERMISSIONS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIAL_PERMISSIONS_REQUESTED, false)) return
        prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_REQUESTED, true).apply()

        val runtimePermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtimePermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runtimePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (runtimePermissions.isNotEmpty()) {
            initialRuntimePermissionsLauncher.launch(runtimePermissions.toTypedArray())
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val action = intent?.action.orEmpty()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty().ifBlank { null }
        when (action) {
            ACTION_OPEN_SESSION_CHAT -> pendingLaunchSessionFromNotification = sessionId
            ACTION_OPEN_SESSION_FEEDBACK -> pendingLaunchFeedbackSessionId = sessionId
            ACTION_WAITING_SUPPORT_ACCEPTED -> {
                pendingLaunchAcceptedSessionId = sessionId
                pendingLaunchAcceptedTechName = intent?.getStringExtra(EXTRA_TECH_NAME)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                pendingLaunchAcceptedLocalSupportSessionId = intent?.getStringExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }
        if (
            action == ACTION_OPEN_SESSION_CHAT ||
            action == ACTION_OPEN_SESSION_FEEDBACK ||
            action == ACTION_WAITING_SUPPORT_ACCEPTED
        ) {
            // Evita reprocessar a mesma acao em recriacoes da Activity (ex.: rotacao de tela).
            intent?.action = Intent.ACTION_MAIN
            intent?.removeExtra(EXTRA_SESSION_ID)
            intent?.removeExtra(EXTRA_TECH_NAME)
            intent?.removeExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
        }
    }

    private fun applyPendingLaunchIntentNavigation() {
        val feedbackSessionId = pendingLaunchFeedbackSessionId
        if (!feedbackSessionId.isNullOrBlank()) {
            pendingLaunchFeedbackSessionId = null
            setEndedSessionForFeedback?.invoke(feedbackSessionId)
            setScreenFromSocket?.invoke(Screen.SESSION_FEEDBACK)
            return
        }

        val acceptedSessionId = pendingLaunchAcceptedSessionId
        if (!acceptedSessionId.isNullOrBlank()) {
            pendingLaunchAcceptedSessionId = null
            val techName = pendingLaunchAcceptedTechName
            pendingLaunchAcceptedTechName = null
            val localSupportSessionId = pendingLaunchAcceptedLocalSupportSessionId
            pendingLaunchAcceptedLocalSupportSessionId = null
            if (!localSupportSessionId.isNullOrBlank()) {
                setPendingSupportSession(localSupportSessionId)
            }
            handleSupportAccepted(
                sessionId = acceptedSessionId,
                techName = techName,
                source = "waiting_service_launch_intent"
            )
            return
        }

        val chatSessionId = pendingLaunchSessionFromNotification
        if (!chatSessionId.isNullOrBlank()) {
            pendingLaunchSessionFromNotification = null
            openSessionFromChatNotification(chatSessionId)
        }
    }

    private fun openSessionFromChatNotification(sessionId: String) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return

        if (currentSessionId == normalizedSessionId) {
            clearTransientSupportNotifications(normalizedSessionId)
            setSessionIdFromSocket?.invoke(normalizedSessionId)
            setScreenFromSocket?.invoke(Screen.SESSION)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val activeRealtimeSession = runCatching {
                clientSupportRepository.findActiveRealtimeSession(pendingSupportSessionId)
            }.getOrNull()
            val canOpenSession = activeRealtimeSession?.sessionId
                ?.trim()
                ?.equals(normalizedSessionId, ignoreCase = false) == true
            runOnUiThread {
                clearTransientSupportNotifications(normalizedSessionId)
                if (!canOpenSession) {
                    if (currentSessionId.isNullOrBlank()) {
                        setScreenFromSocket?.invoke(Screen.HOME)
                    }
                    setSystemMessageFromLauncher?.invoke("Essa sessao ja foi encerrada.")
                    return@runOnUiThread
                }
                handleSupportAccepted(
                    sessionId = normalizedSessionId,
                    techName = activeRealtimeSession?.techName,
                    source = "chat_notification_validation"
                )
            }
        }
    }

    private fun buildChatNotificationBody(message: Message): String {
        return when {
            !message.text.isNullOrBlank() -> message.text
            !message.audioUrl.isNullOrBlank() -> "Audio recebido."
            !message.imageUrl.isNullOrBlank() || !message.fileUrl.isNullOrBlank() -> "Imagem recebida."
            else -> "Nova mensagem recebida."
        }
    }

    private fun notifyIncomingChatMessage(sessionId: String, message: Message) {
        if (appInForeground) return
        if (!isNotificationPermissionGranted()) return

        ensureNotificationChannel(
            id = CHAT_NOTIFICATION_CHANNEL_ID,
            name = "Mensagens do atendimento",
            description = "Notificacoes de novas mensagens durante o suporte.",
            importance = NotificationManager.IMPORTANCE_HIGH
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION_CHAT
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            abs(sessionId.hashCode()),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = message.fromName?.takeIf { it.isNotBlank() } ?: "Suporte X"
        val body = buildChatNotificationBody(message)
        val notification = NotificationCompat.Builder(this, CHAT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(chatNotificationId(sessionId), notification)
    }

    private fun chatNotificationId(sessionId: String): Int {
        return 2_000 + abs(sessionId.hashCode() % 900)
    }

    private fun sessionEndedNotificationId(sessionId: String): Int {
        return 3_000 + abs(sessionId.hashCode() % 900)
    }

    private fun supportAcceptedNotificationId(sessionId: String): Int {
        return 3_500 + abs(sessionId.hashCode() % 900)
    }

    private fun waitingAcceptedNotificationId(sessionId: String): Int {
        return 3_600 + abs(sessionId.hashCode() % 900)
    }

    private fun callNotificationId(sessionId: String): Int {
        return 4_000 + abs(sessionId.hashCode() % 900)
    }

    private fun clearTransientSupportNotifications(sessionId: String? = null) {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        if (!sessionId.isNullOrBlank()) {
            val normalized = sessionId.trim()
            manager.cancel(chatNotificationId(normalized))
            manager.cancel(callNotificationId(normalized))
            manager.cancel(sessionEndedNotificationId(normalized))
            manager.cancel(supportAcceptedNotificationId(normalized))
            manager.cancel(waitingAcceptedNotificationId(normalized))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNotifications.forEach { active ->
                val notificationId = active.id
                val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    active.notification.channelId
                } else {
                    null
                }
                val isPersistentForeground = notificationId == 1 ||
                    notificationId == 6_100 ||
                    notificationId == 6_200 ||
                    channelId == "screen_capture" ||
                    channelId == "suportex_waiting_queue" ||
                    channelId == "suportex_session_anchor" ||
                    channelId == "suportex_session_anchor_v2"
                if (!isPersistentForeground) {
                    manager.cancel(notificationId)
                }
            }
            return
        }

        for (offset in 0 until 900) {
            manager.cancel(2_000 + offset)
            manager.cancel(3_000 + offset)
            manager.cancel(3_500 + offset)
            manager.cancel(3_600 + offset)
            manager.cancel(4_000 + offset)
        }
    }

    private fun clearAllSupportNotificationsAfterFeedback() {
        stopIncomingCallAlert()
        stopWaitingSupportForegroundService()
        stopSessionAnchorForegroundService()
        val stopCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        runCatching { ContextCompat.startForegroundService(this, stopCaptureIntent) }
            .onFailure { err ->
                Log.w("SXS/Main", "Falha ao parar captura durante limpeza final", err)
            }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancelAll()
        }.onFailure { err ->
            Log.w("SXS/Main", "Falha ao limpar notificacoes apos feedback", err)
        }
    }

    private fun ensureCallNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CALL_NOTIFICATION_CHANNEL_ID) != null) return
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CALL_NOTIFICATION_CHANNEL_ID,
            "Chamadas do atendimento",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas de chamada recebida no suporte."
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 450L, 350L, 450L, 350L)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            if (ringtoneUri != null) {
                setSound(ringtoneUri, audioAttributes)
            }
        }
        manager.createNotificationChannel(channel)
    }

    private fun resolveDefaultVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun startIncomingCallAlert() {
        if (incomingCallAlertActive) return
        incomingCallAlertActive = true

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (ringtoneUri != null) {
            runCatching {
                incomingCallRingtone = RingtoneManager.getRingtone(this, ringtoneUri)
                incomingCallRingtone?.play()
            }
        }

        val vibrator = resolveDefaultVibrator()
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0L, 450L, 350L, 450L, 350L), 0)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0L, 450L, 350L, 450L, 350L), 0)
            }
        }
    }

    private fun stopIncomingCallAlert() {
        if (!incomingCallAlertActive) return
        incomingCallAlertActive = false

        runCatching { incomingCallRingtone?.stop() }
        incomingCallRingtone = null

        resolveDefaultVibrator()?.cancel()
    }

    private fun notifyIncomingCallFromTech(sessionId: String) {
        if (!isNotificationPermissionGranted()) return
        ensureCallNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION_CHAT
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            callNotificationId(sessionId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Chamada recebida")
            .setContentText("Suporte X está chamando você. Toque para abrir.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(callNotificationId(sessionId), notification)
    }

    private fun cancelIncomingCallNotification(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(callNotificationId(sessionId))
    }

    private fun normalizeSessionEndReason(reason: String?): String {
        val raw = reason?.trim().orEmpty()
        if (raw.isBlank()) return "Atendimento encerrado."
        val normalized = raw.lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        return when (normalized) {
            "tech_ended",
            "session_end",
            "end",
            "finish_end",
            "finished_end",
            "peer_ended",
            "peer_left",
            "client_ended",
            "client_left",
            "support_ended" -> "Atendimento encerrado."
            else -> raw
        }
    }

    private fun buildSessionEndedNotificationBody(reason: String?): String {
        val normalizedReason = normalizeSessionEndReason(reason)
        return if (normalizedReason == "Atendimento encerrado.") {
            "Seu atendimento foi encerrado. Toque para avaliar."
        } else {
            normalizedReason
        }
    }

    private fun notifySessionEndedByTech(sessionId: String, reason: String?) {
        if (!isNotificationPermissionGranted()) return

        ensureNotificationChannel(
            id = SESSION_NOTIFICATION_CHANNEL_ID,
            name = "Atualizacoes do atendimento",
            description = "Notificacoes sobre encerramento da sessao de suporte.",
            importance = NotificationManager.IMPORTANCE_HIGH
        )

        val pendingIntent = buildSessionLaunchPendingIntent(
            action = ACTION_OPEN_SESSION_FEEDBACK,
            sessionId = sessionId,
            requestCode = abs(sessionId.hashCode()) + 10_000
        )

        val body = buildSessionEndedNotificationBody(reason)
        val canUseFullscreen = !appInForeground && canUseFullScreenIntent()
        val notificationBuilder = NotificationCompat.Builder(this, SESSION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Atendimento encerrado")
            .setContentText(body)
            .setPriority(if (canUseFullscreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
        if (canUseFullscreen) {
            notificationBuilder.setFullScreenIntent(pendingIntent, true)
        }
        val notification = notificationBuilder.build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(sessionEndedNotificationId(sessionId), notification)
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    private fun buildSessionLaunchPendingIntent(action: String, sessionId: String, requestCode: Int): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun forceOpenMainActivity(
        action: String,
        sessionId: String,
        requestCode: Int,
        source: String
    ) {
        val pendingIntent = buildSessionLaunchPendingIntent(action, sessionId, requestCode)
        val launchedViaPendingIntent = runCatching {
            pendingIntent.send()
            true
        }.getOrElse { error ->
            Log.w("SXS/Main", "Falha ao abrir app por PendingIntent ($source)", error)
            false
        }
        Log.i(
            "SXS/Main",
            "ForceOpen source=$source action=$action session=$sessionId foreground=$appInForeground pendingSent=$launchedViaPendingIntent"
        )
        if (launchedViaPendingIntent) return

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(launchIntent) }
            .onFailure { err -> Log.w("SXS/Main", "Falha ao abrir app via startActivity ($source)", err) }
    }

    private fun bringAppToForegroundForFeedback(sessionId: String) {
        forceOpenMainActivity(
            action = ACTION_OPEN_SESSION_FEEDBACK,
            sessionId = sessionId,
            requestCode = abs(sessionId.hashCode()) + 10_001,
            source = "feedback"
        )
    }

    private fun bringAppToForegroundForSession(sessionId: String) {
        forceOpenMainActivity(
            action = ACTION_OPEN_SESSION_CHAT,
            sessionId = sessionId,
            requestCode = abs(sessionId.hashCode()) + 8_001,
            source = "session"
        )
    }

    private fun notifySupportAcceptedByTech(sessionId: String, techName: String?) {
        if (!isNotificationPermissionGranted()) return

        ensureNotificationChannel(
            id = SESSION_NOTIFICATION_CHANNEL_ID,
            name = "Atualizacoes do atendimento",
            description = "Notificacoes sobre o inicio e encerramento da sessao de suporte.",
            importance = NotificationManager.IMPORTANCE_HIGH
        )

        val pendingIntent = buildSessionLaunchPendingIntent(
            action = ACTION_OPEN_SESSION_CHAT,
            sessionId = sessionId,
            requestCode = abs(sessionId.hashCode()) + 8_000
        )

        val techLabel = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Tecnico"
        val canUseFullscreen = !appInForeground && canUseFullScreenIntent()
        val notificationBuilder = NotificationCompat.Builder(this, SESSION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Atendimento iniciado")
            .setContentText("$techLabel iniciou seu atendimento. Abrindo o Suporte X...")
            .setPriority(if (canUseFullscreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
        if (canUseFullscreen) {
            notificationBuilder.setFullScreenIntent(pendingIntent, true)
        }
        val notification = notificationBuilder.build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(supportAcceptedNotificationId(sessionId), notification)
    }

    private fun stopWaitingSessionRecovery() {
        waitingSessionRecoveryJob?.cancel()
        waitingSessionRecoveryJob = null
    }

    private fun startWaitingSessionRecovery() {
        if (waitingSessionRecoveryJob?.isActive == true) return
        waitingSessionRecoveryJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val localSupportSessionId = pendingSupportSessionId
                if (localSupportSessionId.isNullOrBlank() || !currentSessionId.isNullOrBlank()) break

                val recovered = runCatching {
                    clientSupportRepository.findActiveRealtimeSession(localSupportSessionId)
                }.getOrNull()

                if (recovered?.sessionId?.isNotBlank() == true) {
                    runOnUiThread {
                        handleSupportAccepted(
                            sessionId = recovered.sessionId,
                            techName = recovered.techName,
                            source = "firestore_recovery"
                        )
                    }
                    break
                }

                delay(WAITING_SESSION_RECOVERY_INTERVAL_MS)
            }
            waitingSessionRecoveryJob = null
        }
    }

    private fun handleSupportAccepted(sessionId: String, techName: String?, source: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        val resolvedTechName = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Tecnico"

        if (currentSessionId == sid) {
            Conn.techName = resolvedTechName
            startSessionAnchorForegroundService(sid, resolvedTechName)
            activeSupportRequestId = null
            syncWaitingSupportForegroundService()
            runOnUiThread {
                setTechNameFromSocket?.invoke(resolvedTechName)
                setRequestIdFromSocket?.invoke(null)
                setSessionIdFromSocket?.invoke(sid)
                setScreenFromSocket?.invoke(Screen.SESSION)
            }
            if (!appInForeground) {
                bringAppToForegroundForSession(sid)
            }
            return
        }

        val localSupportSessionId = pendingSupportSessionId
        activeSupportRequestId = null
        setPendingSupportSession(null)
        pendingSupportStartContext = null
        stopWaitingSessionRecovery()

        Conn.sessionId = sid
        Conn.techName = resolvedTechName
        runOnUiThread { setTechNameFromSocket?.invoke(resolvedTechName) }
        currentSessionId = sid
        remoteConsentAcceptedForCurrentSession = false
        resetSessionState()
        startSessionAnchorForegroundService(sid, resolvedTechName)
        voiceCallManager.bindSession(sid)
        registerSessionStart(sid, resolvedTechName)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                clientSupportRepository.attachRealtimeSession(
                    localSupportSessionId = localSupportSessionId,
                    realtimeSessionId = sid,
                    techName = resolvedTechName
                )
            }.onFailure { err ->
                Log.w("SXS/Main", "Falha ao vincular sessao realtime ($source)", err)
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
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(sid)
            setScreenFromSocket?.invoke(Screen.SESSION)
        }

        if (!appInForeground) {
            notifySupportAcceptedByTech(sid, resolvedTechName)
            bringAppToForegroundForSession(sid)
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
        if (message.from.equals("tech", ignoreCase = true)) {
            notifyIncomingChatMessage(sid, message)
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
            "session_end", "end" -> handleSessionEnded(reason = normalizeSessionEndReason(obj.optString("reason", "")))
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
            val recorder = createMediaRecorder().apply {
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

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
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
        activeSupportRequestId = null
        stopWaitingSessionRecovery()
        resetSessionState()
        currentSessionId = null
        remoteConsentAcceptedForCurrentSession = false
        Conn.sessionId = null
        Conn.techName = null
        runOnUiThread { setTechNameFromSocket?.invoke("T\u00e9cnico") }
    }

    private fun submitCustomerSatisfaction(sessionId: String, score: Int) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return
        val normalizedScore = score.coerceIn(0, 5)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = authRepository.ensureAnonIdToken(forceRefresh = false)
                if (token.isBlank()) return@launch
                val payload = JSONObject().apply {
                    put("customerSatisfactionScore", normalizedScore)
                }.toString()
                val request = Request.Builder()
                    .url("${Conn.SERVER_BASE}/api/sessions/$normalizedSessionId/customer-feedback")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .header("Authorization", "Bearer $token")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("SXS/Main", "Falha ao enviar feedback do cliente (${response.code})")
                    }
                }
            } catch (error: Exception) {
                Log.w("SXS/Main", "Falha ao enviar satisfação do cliente", error)
            }
        }
    }

    private fun handleSessionEnded(fromCommand: Boolean = true, reason: String? = null) {
        val sid = currentSessionId
        if (sid.isNullOrBlank()) {
            // Evita sobrescrever a tela de feedback quando chegam eventos duplicados de encerramento.
            return
        }
        val normalizedReason = normalizeSessionEndReason(reason)
        val origin = if (fromCommand) "tech" else "client"
        if (!fromCommand) sendCommand("end")

        sid?.let {
            logSessionEvent("end", origin = origin, extras = mapOf("reason" to normalizedReason))
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { sessionRepository.markSessionClosed(it) }
                runCatching {
                    clientSupportRepository.completeSupportSession(
                        localSupportSessionId = pendingSupportSessionId,
                        realtimeSessionId = it,
                        techId = null,
                        techName = Conn.techName,
                        problemSummary = normalizedReason,
                        solutionSummary = null,
                        internalNotes = "Encerrado via app Android ($origin)",
                        reportSummary = normalizedReason
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
        if (!shareWasActive && isScreenCaptureServiceRunning()) {
            stopScreenShare(fromCommand = true, originOverride = origin)
        }

        if (!shareWasActive && !remoteWasActive && !callWasActive) {
            emitTelemetry()
        }

        shareRequestFromCommand = false
        activeSupportRequestId = null
        pendingSupportStartContext = null
        setPendingSupportSession(null)
        stopWaitingSessionRecovery()
        stopSessionAnchorForegroundService()
        stopIncomingCallAlert()
        cancelIncomingCallNotification(sid)
        finalizeSession()
        runOnUiThread {
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(null)
            setEndedSessionForFeedback?.invoke(sid)
            setScreenFromSocket?.invoke(if (sid.isNullOrBlank()) Screen.HOME else Screen.SESSION_FEEDBACK)
            setSystemMessageFromLauncher?.invoke(null)
        }
        if (fromCommand && !sid.isNullOrBlank() && !appInForeground) {
            notifySessionEndedByTech(sid, normalizedReason)
            bringAppToForegroundForFeedback(sid)
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
            activeSupportRequestId = reqId
            syncWaitingSupportForegroundService()
            startWaitingSessionRecovery()
            runOnUiThread { setRequestIdFromSocket?.invoke(reqId) }
        }

        socket.on("support:error") { args ->
            val data = args.getOrNull(0) as? JSONObject
            val errorCode = data?.optString("error", "")?.trim().orEmpty()
            val backendMessage = data?.optString("message", "")?.trim()?.takeIf { it.isNotBlank() }
            val feedbackMessage = when (errorCode) {
                "credit_required" -> "Sem credito disponivel. Compre creditos para solicitar novo atendimento."
                else -> backendMessage ?: "Nao foi possivel solicitar suporte agora."
            }

            val localSupportId = pendingSupportSessionId
            activeSupportRequestId = null
            setPendingSupportSession(null)
            pendingSupportStartContext = null
            stopWaitingSessionRecovery()
            if (!localSupportId.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { clientSupportRepository.cancelSupportRequest(localSupportId) }
                }
            }

            runOnUiThread {
                setRequestIdFromSocket?.invoke(null)
                setSessionIdFromSocket?.invoke(null)
                setScreenFromSocket?.invoke(Screen.HOME)
                setSystemMessageFromLauncher?.invoke(feedbackMessage)
            }
        }

        // tecnico aceitou -> recebemos sessionId e (opcional) techName
        socket.on("support:accepted") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sid = data.optString("sessionId", "")
            val tname = data.optString("techName", "Tecnico")
            if (sid.isBlank()) return@on
            handleSupportAccepted(sessionId = sid, techName = tname, source = "socket")
        }

        socket.on("queue:updated") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val state = data.optString("state", "").trim().lowercase()
            if (state != "accepted") return@on

            val requestId = data.optString("requestId", "").trim()
            val trackedRequestId = activeSupportRequestId?.trim().orEmpty()
            if (requestId.isBlank() || trackedRequestId.isBlank() || requestId != trackedRequestId) return@on

            val sid = data.optString("sessionId", "").trim()
            if (sid.isBlank()) return@on
            val tname = data.optString("techName", "Tecnico")
            handleSupportAccepted(sessionId = sid, techName = tname, source = "queue_updated")
        }

        socket.on("client:verification:trigger") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val serverClientUid = data.optString("clientUid", "").trim().takeIf { it.isNotBlank() }
            val serverClientId = data.optString("clientId", "").trim().takeIf { it.isNotBlank() }
            val serverPhone = data.optString("phone", "").trim().takeIf { it.isNotBlank() }
            val serverSupportSessionId = data.optString("supportSessionId", "").trim().takeIf { it.isNotBlank() }

            lifecycleScope.launch(Dispatchers.IO) {
                val resolvedUid = serverClientUid
                    ?: runCatching { authRepository.ensureAnonAuth() }.getOrNull()
                if (resolvedUid.isNullOrBlank()) return@launch

                val startContext = SupportStartContext(
                    clientId = serverClientId,
                    phone = serverPhone,
                    isNewClient = false,
                    isFreeFirstSupport = false,
                    creditsToConsume = 0
                )

                runOnUiThread {
                    setSystemMessageFromLauncher?.invoke("Confirmacao de numero solicitada para concluir o cadastro.")
                    launchPnvVerificationFlow(
                        clientUid = resolvedUid,
                        startContext = startContext,
                        localSupportSessionId = serverSupportSessionId ?: pendingSupportSessionId
                    )
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
            val reason = normalizeSessionEndReason((args.getOrNull(0) as? JSONObject)?.optString("reason"))
            handleSessionEnded(reason = reason)
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
                    rawPhone = firebasePhone,
                    deviceAnchor = deviceAnchor()
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
            val verifiedPhone = snapshot.verification?.verifiedPhone
                ?.trim()
                ?.takeIf { it.isNotBlank() && snapshot.verification.status == "verified" }
            if (!verifiedPhone.isNullOrBlank()) {
                runCatching { phoneIdentityProvider.saveVerifiedPhoneNumber(verifiedPhone) }
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
                    fallbackVerifiedPhone = firebasePhone,
                    deviceAnchor = deviceAnchor()
                )
            }.getOrElse {
                Log.e("SXS/Main", "Falha ao verificar acesso de suporte", it)
                SupportAccessDecision.BlockedUnavailable(
                    message = "Nao foi possivel validar o acesso agora. Tente novamente em instantes."
                )
            }
            pendingSupportStartContext = (decision as? SupportAccessDecision.Allowed)?.startContext
            runOnUiThread { onDecision(decision) }
        }
    }

    private fun requestSupport(
        startContext: SupportStartContext,
        clientName: String?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            activeSupportRequestId = null
            setPendingSupportSession(null)
            stopWaitingSessionRecovery()
            val uid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
            val idToken = runCatching { authRepository.ensureAnonIdToken(forceRefresh = false) }.getOrDefault("")
            val verifiedPhone = runCatching { phoneIdentityProvider.getVerifiedPhoneNumber() }.getOrNull()
            val effectivePhone = verifiedPhone ?: startContext.phone
            val currentDeviceAnchor = deviceAnchor()
            if (uid.isNullOrBlank()) {
                Log.e("SXS/Main", "Falha ao autenticar cliente antes de support:request")
                runOnUiThread {
                    setScreenFromSocket?.invoke(Screen.HOME)
                    setSystemMessageFromLauncher?.invoke("Falha de autenticacao. Tente novamente em alguns segundos.")
                }
                return@launch
            }

            val localSupportSessionId = runCatching {
                clientSupportRepository.registerSupportRequest(
                    startContext = startContext.copy(phone = effectivePhone),
                    clientName = clientName,
                    clientUid = uid,
                    deviceAnchor = currentDeviceAnchor,
                    deviceBrand = Build.BRAND,
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
                )
            }.getOrNull()
            setPendingSupportSession(localSupportSessionId)
            startWaitingSessionRecovery()

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
                put("deviceAnchor", currentDeviceAnchor)
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
                            localSupportSessionId = pendingSupportSessionId,
                            deviceAnchor = currentDeviceAnchor
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
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
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
        activeSupportRequestId = null
        setPendingSupportSession(null)
        pendingSupportStartContext = null
        stopWaitingSessionRecovery()

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

    private val initialRuntimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

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
        handleLaunchIntent(intent)
        val appBackgroundArgb = Color(0xFFF4F6F8).toArgb()
        window.statusBarColor = appBackgroundArgb
        window.navigationBarColor = appBackgroundArgb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

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
        readPendingSupportSessionFromPrefs()?.let { localSupportSessionId ->
            setPendingSupportSession(localSupportSessionId)
            startWaitingSessionRecovery()
        }

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
                var current by remember {
                    mutableStateOf(
                        if (pendingSupportSessionId.isNullOrBlank()) Screen.HOME else Screen.WAITING
                    )
                }

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
                var endedSessionId by remember { mutableStateOf<String?>(null) }
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
                var supportQueueWaitStats by remember { mutableStateOf<SupportQueueWaitStats?>(null) }
                var selectedPackage by remember { mutableStateOf<CreditPackageRecord?>(null) }
                var autoOpenedPurchaseClientId by rememberSaveable { mutableStateOf<String?>(null) }
                var bootstrapHomeLoaded by remember { mutableStateOf(false) }
                var bootstrapQueueLoaded by remember { mutableStateOf(false) }
                var bootstrapAccessLoaded by remember { mutableStateOf(false) }
                var showStartupLoading by remember { mutableStateOf(true) }
                var showMainContent by remember { mutableStateOf(false) }
                var preloadedSupportDecision by remember { mutableStateOf<SupportAccessDecision?>(null) }
                var bootstrapCycle by remember { mutableStateOf(0) }
                var hasHandledFirstOnStart by remember { mutableStateOf(false) }
                var skipInitialHomeRefresh by remember { mutableStateOf(true) }
                val lifecycleOwner = LocalLifecycleOwner.current
                val currentScreen by rememberUpdatedState(current)
                val homeAverageWaitLabel = formatHomeAverageWaitLabel(supportQueueWaitStats)
                val waitingAverageWaitLabel = formatWaitingAverageWaitLabel(supportQueueWaitStats)

                fun finishStartupLoadingIfReady() {
                    if (showStartupLoading &&
                        bootstrapHomeLoaded &&
                        bootstrapQueueLoaded &&
                        bootstrapAccessLoaded
                    ) {
                        showMainContent = true
                        showStartupLoading = false
                    }
                }

                fun mergeSnapshotWithSupportDecision(
                    snapshot: ClientHomeSnapshot,
                    decision: SupportAccessDecision?
                ): ClientHomeSnapshot {
                    return when (decision) {
                        is SupportAccessDecision.Allowed -> {
                            val decidedClient = decision.client ?: return snapshot
                            snapshot.copy(client = decidedClient)
                        }

                        is SupportAccessDecision.BlockedNeedsCredit -> {
                            snapshot.copy(
                                client = decision.client,
                                packages = decision.packages
                            )
                        }

                        else -> snapshot
                    }
                }

                fun syncHomeWithSupportDecision(decision: SupportAccessDecision?) {
                    homeSnapshot = mergeSnapshotWithSupportDecision(homeSnapshot, decision)
                    if (selectedPackage == null || homeSnapshot.packages.none { it.id == selectedPackage?.id }) {
                        selectedPackage = homeSnapshot.packages.firstOrNull()
                    }
                }

                fun startBootstrap(showAnimation: Boolean) {
                    val cycle = bootstrapCycle + 1
                    bootstrapCycle = cycle
                    bootstrapHomeLoaded = false
                    bootstrapQueueLoaded = false
                    bootstrapAccessLoaded = false
                    if (showAnimation) {
                        showMainContent = false
                        showStartupLoading = true
                    }

                    loadHomeSnapshot { snapshot ->
                        if (cycle != bootstrapCycle) return@loadHomeSnapshot
                        homeSnapshot = mergeSnapshotWithSupportDecision(
                            snapshot = snapshot,
                            decision = preloadedSupportDecision
                        )
                        if (selectedPackage == null) {
                            selectedPackage = homeSnapshot.packages.firstOrNull()
                        }
                        bootstrapHomeLoaded = true
                        finishStartupLoadingIfReady()
                    }

                    loadSupportQueueWaitStats { stats ->
                        if (cycle != bootstrapCycle) return@loadSupportQueueWaitStats
                        supportQueueWaitStats = stats
                        bootstrapQueueLoaded = true
                        finishStartupLoadingIfReady()
                    }

                    evaluateSupportEntry { decision ->
                        if (cycle != bootstrapCycle) return@evaluateSupportEntry
                        preloadedSupportDecision = decision
                        syncHomeWithSupportDecision(decision)
                        bootstrapAccessLoaded = true
                        finishStartupLoadingIfReady()
                    }
                }

                fun handleSupportAccessDecision(decision: SupportAccessDecision) {
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

                        is SupportAccessDecision.BlockedUnavailable -> {
                            Toast.makeText(
                                this@MainActivity,
                                decision.message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

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
                                activeSupportRequestId = null
                                setPendingSupportSession(null)
                                pendingSupportStartContext = null
                                stopWaitingSessionRecovery()
                            }
                            requestId = null
                            sessionId = null
                            currentSessionId = null
                            current = Screen.HOME
                        }
                        Screen.SESSION_FEEDBACK -> Unit
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
                    setEndedSessionForFeedback = { endedSessionId = it }
                    applyPendingLaunchIntentNavigation()
                    startBootstrap(showAnimation = true)
                }

                LaunchedEffect(current) {
                    if (current == Screen.HOME) {
                        if (skipInitialHomeRefresh) {
                            skipInitialHomeRefresh = false
                            return@LaunchedEffect
                        }
                        loadHomeSnapshot { snapshot ->
                            homeSnapshot = mergeSnapshotWithSupportDecision(
                                snapshot = snapshot,
                                decision = preloadedSupportDecision
                            )
                            if (selectedPackage == null) {
                                selectedPackage = homeSnapshot.packages.firstOrNull()
                            }
                        }
                        evaluateSupportEntry { decision ->
                            preloadedSupportDecision = decision
                            syncHomeWithSupportDecision(decision)
                        }
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
                        if (!hasHandledFirstOnStart) {
                            hasHandledFirstOnStart = true
                            return@LifecycleEventObserver
                        }
                        if (currentScreen == Screen.HOME) {
                            startBootstrap(showAnimation = true)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(current) {
                    if (current == Screen.HOME || current == Screen.WAITING) {
                        while (isActive) {
                            loadSupportQueueWaitStats { stats ->
                                supportQueueWaitStats = stats
                            }
                            delay(20_000)
                        }
                    }
                }

                LaunchedEffect(current, homeSnapshot.client?.id, homeSnapshot.shouldAutoOpenPurchase) {
                    val currentClientId = homeSnapshot.client?.id
                    if (
                        current == Screen.HOME &&
                        homeSnapshot.shouldAutoOpenPurchase &&
                        !currentClientId.isNullOrBlank() &&
                        autoOpenedPurchaseClientId != currentClientId
                    ) {
                        if (selectedPackage == null) {
                            selectedPackage = homeSnapshot.packages.firstOrNull()
                        }
                        autoOpenedPurchaseClientId = currentClientId
                        current = Screen.PURCHASE_CREDITS
                    }
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        if (showMainContent) {
                            when (current) {
                        Screen.HOME -> SupportHomeScreen(
                            homeSnapshot = homeSnapshot,
                            onRequestSupport = {
                                val cachedDecision = preloadedSupportDecision
                                if (cachedDecision != null) {
                                    handleSupportAccessDecision(cachedDecision)
                                } else {
                                    evaluateSupportEntry { decision ->
                                        preloadedSupportDecision = decision
                                        handleSupportAccessDecision(decision)
                                    }
                                }
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
                            textMuted = Color(0xFF8A8A8E),
                            averageWaitLabel = homeAverageWaitLabel
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
                                    activeSupportRequestId = null
                                    setPendingSupportSession(null)
                                    pendingSupportStartContext = null
                                    stopWaitingSessionRecovery()
                                }
                                requestId = null
                                sessionId = null
                                currentSessionId = null
                                current = Screen.HOME
                            },
                            onAccepted = { /* nao usamos; aceitacao vem do socket */ },
                            textMuted = Color(0xFF8A8A8E),
                            averageWaitLabel = waitingAverageWaitLabel
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
                            }
                        )

                        Screen.SESSION_FEEDBACK -> SessionFeedbackScreen(
                            onRate = { score ->
                                val targetSessionId = endedSessionId
                                endedSessionId = null
                                if (!targetSessionId.isNullOrBlank()) {
                                    submitCustomerSatisfaction(targetSessionId, score)
                                }
                                clearAllSupportNotificationsAfterFeedback()
                                Toast.makeText(
                                    this@MainActivity,
                                    "Obrigado pelo seu feedback.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                current = Screen.HOME
                            },
                            onTimeout = {
                                endedSessionId = null
                                current = Screen.HOME
                            }
                        )
                            }
                        }

                        AnimatedVisibility(
                            visible = showStartupLoading,
                            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
                            exit = fadeOut(animationSpec = tween(durationMillis = 300))
                        ) {
                            StartupLoadingScreen()
                        }
                    }
                }

            }
        }
    }

    override fun onStart() {
        super.onStart()
        appInForeground = true
        syncWaitingSupportForegroundService()
        syncSessionAnchorForegroundService()
        if (!currentSessionId.isNullOrBlank()) {
            clearTransientSupportNotifications(currentSessionId)
        }
        maybePromptInitialCriticalPermissions()
        val incomingFromTech = currentCallUiState == CallState.INCOMING_RINGING &&
            currentCallDirection == CallDirection.TECH_TO_CLIENT
        if (incomingFromTech) {
            cancelIncomingCallNotification(currentSessionId)
            startIncomingCallAlert()
        }
    }

    override fun onStop() {
        val incomingFromTech = currentCallUiState == CallState.INCOMING_RINGING &&
            currentCallDirection == CallDirection.TECH_TO_CLIENT
        if (incomingFromTech && !currentSessionId.isNullOrBlank()) {
            notifyIncomingCallFromTech(currentSessionId!!)
        } else {
            stopIncomingCallAlert()
        }
        appInForeground = false
        syncWaitingSupportForegroundService()
        syncSessionAnchorForegroundService()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
        applyPendingLaunchIntentNavigation()
    }

    override fun onDestroy() {
        stopIncomingCallAlert()
        super.onDestroy()
        stopWaitingSessionRecovery()
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
        Text("Tempo médio de atendimento: 2-5 min", color = textMuted, fontSize = 16.sp)
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
        Text("Guia rápido de atendimento", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Como iniciar um atendimento",
                body = "Toque em SOLICITAR SUPORTE e aguarde a conexão com um técnico. Quando o atendimento for aceito, a sessão será aberta automaticamente no aplicativo."
            )
            PolicySection(
                title = "2. Crédito e liberação de atendimento",
                body = "O primeiro atendimento pode ser disponibilizado gratuitamente, conforme o status da sua conta. Depois disso, para novos atendimentos, é necessário ter créditos disponíveis no aplicativo."
            )
            PolicySection(
                title = "3. Compra de créditos",
                body = "Na tela inicial, você pode abrir a área de créditos, escolher um pacote e solicitar a compra pelos canais oficiais. As opções de Cartão e PIX podem aparecer no app conforme a etapa de implantação."
            )
            PolicySection(
                title = "4. Permissões durante o suporte",
                body = "Alguns atendimentos podem exigir permissões temporárias, como compartilhamento de tela, envio de arquivos, uso do microfone e acesso assistido. Essas permissões são opcionais e você controla todas elas no app."
            )
            PolicySection(
                title = "5. Segurança da sessão",
                body = "Nenhuma ação remota é iniciada sem sua autorização explícita. Você pode interromper o compartilhamento, desligar o acesso remoto e revogar permissões a qualquer momento."
            )
            PolicySection(
                title = "6. Encerrar atendimento",
                body = "Quando desejar, finalize o suporte pelo botão de encerramento na tela de sessão. Você também pode interromper permissões diretamente nas configurações do seu dispositivo."
            )
            PolicySection(
                title = "7. Problemas comuns",
                body = "Se a conexão estiver instável, verifique internet, bateria, armazenamento e permissões do app. Em caso de falha de acesso remoto, confira se o serviço de acessibilidade do Suporte X está ativado."
            )
            PolicySection(
                title = "8. Canais oficiais",
                body = "Para suporte administrativo, dúvidas sobre privacidade ou uso da plataforma, utilize os canais oficiais da Suporte X informados no aplicativo."
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
        Text("Política de Privacidade", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Suporte X", color = textMuted, fontSize = 13.sp)
        Text("Última atualização: 31 de março de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "A Suporte X valoriza a privacidade e a segurança dos dados dos usuários. Esta Política de Privacidade descreve como coletamos, utilizamos, armazenamos e protegemos as informações durante a utilização do aplicativo e dos serviços de suporte técnico remoto.",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            PolicySection(
                title = "1. Dados que coletamos",
                body = "Para a prestação adequada do serviço, podemos coletar as seguintes informações:\n\n- Identificadores de conta e atendimento (como `clientUid`, `clientId` e `sessionId`)\n- Telefone verificado (quando disponível), nome informado e dados de vínculo da conta\n- Informações técnicas do dispositivo (marca, modelo e versão do Android)\n- Telemetria de sessão (rede, bateria, armazenamento e status de permissões)\n- Mensagens trocadas no chat (texto, áudio e anexos)\n- Registros operacionais de suporte, créditos e tentativas de verificação de número\n\nEssas informações são coletadas para viabilizar e proteger o funcionamento do serviço."
            )
            PolicySection(
                title = "2. Finalidade do uso dos dados",
                body = "Os dados coletados são utilizados para:\n\n- Identificar e autenticar o usuário durante o atendimento\n- Realizar suporte técnico remoto com chat, áudio, compartilhamento e controle assistido\n- Registrar histórico de sessões e eventos para segurança e auditoria\n- Diagnosticar e solucionar problemas técnicos\n- Viabilizar controle de créditos, pacotes e solicitações de compra\n- Melhorar a qualidade, estabilidade e segurança do serviço"
            )
            PolicySection(
                title = "3. Compartilhamento de dados",
                body = "A Suporte X não vende, aluga ou comercializa dados pessoais dos usuários.\n\nO compartilhamento de informações ocorre apenas quando necessário para operação do serviço, como com:\n\n- Provedores de infraestrutura, autenticação e banco de dados\n- Serviços de armazenamento de mídias e arquivos de suporte\n- Plataformas de comunicação e integração operacional\n\nTodos os parceiros devem seguir padrões de segurança compatíveis com a legislação aplicável."
            )
            PolicySection(
                title = "4. Permissões e controle do usuário",
                body = "Alguns recursos do aplicativo podem solicitar permissões específicas do dispositivo, como:\n\n- Compartilhamento de tela\n- Acesso remoto assistido via acessibilidade\n- Uso do microfone para áudio de atendimento\n- Envio de arquivos, imagens e áudios\n\nEssas permissões são sempre solicitadas com autorização explícita do usuário e podem ser interrompidas ou revogadas a qualquer momento."
            )
            PolicySection(
                title = "5. Armazenamento e retenção de dados",
                body = "Os dados são armazenados pelo período necessário para:\n\n- Prestação do suporte técnico\n- Cumprimento de obrigações legais e regulatórias\n- Segurança operacional e rastreabilidade de eventos\n\nDeterminados registros operacionais podem seguir janelas técnicas de retenção (como 15 a 30 dias), conforme a natureza do dado e a política interna de segurança."
            )
            PolicySection(
                title = "6. Direitos do usuário",
                body = "Em conformidade com a Lei Geral de Proteção de Dados (LGPD - Lei nº 13.709/2018), o usuário possui direito de:\n\n- Solicitar acesso aos seus dados\n- Corrigir dados incompletos ou desatualizados\n- Solicitar exclusão de dados, quando aplicável\n- Solicitar informações sobre tratamento e compartilhamento de dados\n\nAs solicitações podem ser feitas pelos canais oficiais da Suporte X."
            )
            PolicySection(
                title = "7. Segurança das informações",
                body = "A Suporte X adota medidas técnicas e organizacionais para proteger os dados contra acesso não autorizado, alteração, divulgação ou destruição indevida.\n\nEntre as medidas aplicadas estão:\n\n- Controle de acesso por autenticação\n- Monitoramento e registros de auditoria\n- Proteção de infraestrutura e serviços de backend\n- Revisões técnicas periódicas de segurança"
            )
            PolicySection(
                title = "8. Contato",
                body = "Para dúvidas, solicitações ou questões relacionadas à privacidade e proteção de dados, entre em contato com o suporte oficial da Suporte X pelos canais disponibilizados no aplicativo."
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
        Text("Última atualização: 31 de março de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PolicySection(
                title = "1. Sobre o serviço",
                body = "O Suporte X é uma plataforma desenvolvida pela Xavier Assessoria Digital para atendimento técnico remoto. O serviço permite comunicação em tempo real entre técnico e usuário, com recursos de chat, áudio, compartilhamento de tela e assistência remota.\n\nO objetivo do serviço é facilitar diagnóstico e resolução de problemas técnicos no dispositivo do usuário, mediante autorização expressa."
            )
            PolicySection(
                title = "2. Aceitação dos termos",
                body = "Ao utilizar o aplicativo Suporte X, o usuário declara que:\n\n- leu e compreendeu estes Termos de Uso\n- concorda com as condições aqui estabelecidas\n- autoriza o funcionamento das funcionalidades necessárias ao suporte técnico remoto\n\nCaso não concorde com estes termos, o usuário não deverá utilizar o aplicativo."
            )
            PolicySection(
                title = "3. Elegibilidade e acesso ao atendimento",
                body = "O acesso ao atendimento segue as regras operacionais da plataforma:\n\n- o primeiro atendimento pode ser disponibilizado gratuitamente\n- após o primeiro atendimento, novos atendimentos dependem de créditos disponíveis\n- sem crédito disponível, o usuário deve solicitar compra de pacote para liberar novos atendimentos"
            )
            PolicySection(
                title = "4. Funcionamento do suporte remoto",
                body = "Durante uma sessão de suporte, o usuário poderá autorizar funcionalidades como:\n\n- compartilhamento de tela\n- envio de arquivos e imagens\n- comunicação por chat e áudio\n- controle remoto assistido do dispositivo\n\nEssas funcionalidades só são ativadas com autorização explícita e podem ser interrompidas a qualquer momento."
            )
            PolicySection(
                title = "5. Créditos, pacotes e pagamentos",
                body = "A compra de créditos é realizada por pacotes disponibilizados na plataforma. A solicitação pode ocorrer pelos canais oficiais, incluindo WhatsApp. Algumas modalidades de pagamento podem ser exibidas no aplicativo conforme etapa de implantação operacional."
            )
            PolicySection(
                title = "6. Responsabilidades do usuário",
                body = "O usuário é responsável por:\n\n- conceder permissões apenas quando desejar iniciar um atendimento\n- manter controle sobre acessos concedidos durante a sessão\n- encerrar o atendimento quando considerar necessário\n- não utilizar o aplicativo para atividades ilegais, abusivas ou fraudulentas"
            )
            PolicySection(
                title = "7. Limitação de responsabilidade",
                body = "A Xavier Assessoria Digital não se responsabiliza por:\n\n- falhas causadas por terceiros, operadoras ou provedores externos\n- indisponibilidade temporária de serviços de infraestrutura\n- problemas decorrentes de uso indevido do dispositivo ou do aplicativo pelo usuário"
            )
            PolicySection(
                title = "8. Privacidade e proteção de dados",
                body = "O tratamento de dados pessoais e operacionais segue a Política de Privacidade do Suporte X e a legislação aplicável, em especial a LGPD (Lei nº 13.709/2018)."
            )
            PolicySection(
                title = "9. Alterações dos termos",
                body = "Estes Termos de Uso podem ser atualizados para refletir ajustes técnicos, operacionais, legais ou de segurança. A versão vigente estará disponível no aplicativo com a respectiva data de atualização."
            )
            PolicySection(
                title = "10. Legislação aplicável e foro",
                body = "Este serviço é regido pelas leis da República Federativa do Brasil. Fica eleito o foro da comarca competente para dirimir eventuais controvérsias, ressalvadas as hipóteses legais de competência específica."
            )
            PolicySection(
                title = "11. Contato",
                body = "Empresa responsável:\nXavier Assessoria Digital\n\nCNPJ:\n45.765.097/0001-61\n\nEndereço:\nRua dos Jequitibás, 1895W\nResidencial Paraíso\nNova Mutum - MT\nCEP: 78.454-528\n\nE-mail:\nsuportex@xavierassessoriadigital.com.br\n\nTelefone / WhatsApp:\n+55 65 99649-7550"
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
    textMuted: Color,
    averageWaitLabel: String
) {
    var showWaitingHelp by rememberSaveable { mutableStateOf(false) }
    val dismissHelp: () -> Unit = { if (showWaitingHelp) showWaitingHelp = false }
    val outsideClickInteraction = remember { MutableInteractionSource() }
    val helpBubbleInteraction = remember { MutableInteractionSource() }

    BackHandler(enabled = showWaitingHelp) {
        showWaitingHelp = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = outsideClickInteraction,
                indication = null
            ) { dismissHelp() }
            .padding(24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(80.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("Acionando técnico, aguarde...", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { showWaitingHelp = !showWaitingHelp }
                        .wrapContentSize(Alignment.Center)
                )
            }
            Text(averageWaitLabel, color = textMuted)

            AnimatedVisibility(visible = showWaitingHelp) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable(
                            interactionSource = helpBubbleInteraction,
                            indication = null
                        ) {},
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.92f),
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = "Você pode usar o celular normalmente enquanto aguarda. A solicitação só para quando você tocar em CANCELAR SOLICITAÇÃO.\nLembre-se de não usar o botão voltar do seu dispositivo para isso. Use as opções Home ou Recentes.",
                        color = textMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("CANCELAR SOLICITAÇÃO", color = Color.White) }
        }
    }
}
