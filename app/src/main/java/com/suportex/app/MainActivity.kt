package com.suportex.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionConfig
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
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.suportex.app.data.AccountDeletionFailureReason
import com.suportex.app.data.AccountDeletionRepository
import com.suportex.app.data.AccountDeletionResult
import com.suportex.app.data.ActiveSupportSessionState
import com.suportex.app.data.ActiveSupportSessionStore
import com.suportex.app.data.ClientIdentityStore
import com.suportex.app.data.ClientSupportRepository
import com.suportex.app.data.ClientSupportRepository.SupportQueueWaitStats
import com.suportex.app.data.ClientNotificationRecord
import com.suportex.app.data.ClientNotificationRepository
import com.suportex.app.data.DeviceIdentity
import com.suportex.app.data.FirebasePhoneIdentityProvider
import com.suportex.app.data.PhonePnvVerificationResult
import com.suportex.app.data.SupportCorrelationResult
import com.suportex.app.data.SupportCorrelationStatus
import com.suportex.app.data.SupportBillingConfig
import com.suportex.app.data.SupportQueueConfirmation
import com.suportex.app.data.SupportQueueConfirmationSource
import com.suportex.app.data.SupportRequestCorrelation
import com.suportex.app.data.SupportRequestToken
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
import com.suportex.app.call.VoiceCallForegroundService
import com.suportex.app.call.VoiceCallManager
import com.suportex.app.engagement.GooglePlayReviewPrompt
import com.suportex.app.remote.AccessibilityUtils
import com.suportex.app.remote.RemoteCommandBus
import com.suportex.app.remote.RemoteControlService
import com.suportex.app.remote.RemoteExecutor
import com.suportex.app.ui.screens.CardPlaceholderScreen
import com.suportex.app.ui.screens.ClientNotificationUi
import com.suportex.app.ui.screens.ClientNotificationType
import com.suportex.app.ui.screens.NotificationCenterUiState
import com.suportex.app.ui.screens.PixPlaceholderScreen
import com.suportex.app.ui.screens.PurchaseCreditsScreen
import com.suportex.app.ui.screens.SessionFeedbackScreen
import com.suportex.app.ui.screens.SessionScreen
import com.suportex.app.ui.screens.StartupLoadingScreen
import com.suportex.app.ui.screens.SupportHomeScreen
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import io.socket.client.IO
import io.socket.client.Ack
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
private const val SUPPORT_REQUEST_ACK_TIMEOUT_MS = 8_000L
private const val SUPPORT_REQUEST_LEGACY_GRACE_MS = 2_000L
private const val SUPPORT_REQUEST_MAX_ATTEMPTS = 2
private const val ACCOUNT_DELETION_CONFIRMATION = "EXCLUIR CONTA"
private const val KEY_ACCOUNT_DELETION_RESTART_PENDING = "account_deletion_restart_pending"

private data class SupportFlowFlags(
    val showCreditPanelOnlyForRegisteredClients: Boolean = true,
    val disableQuickIdentificationModal: Boolean = true,
    val technicianDrivenRegistrationEnabled: Boolean = true,
    val pnvPostRegistrationFlow: Boolean = true
)

private data class SupportRequestAck(
    val ok: Boolean,
    val requestId: String?,
    val reused: Boolean,
    val error: String?,
    val message: String?,
    val localSupportSessionId: String?
)

private sealed interface SupportRequestAttemptResult {
    data class AckReceived(val ack: SupportRequestAck) : SupportRequestAttemptResult
    data class QueueConfirmed(
        val confirmation: SupportQueueConfirmation
    ) : SupportRequestAttemptResult
}

private data class SupportQueueConfirmationWaiter(
    val token: SupportRequestToken,
    val deferred: CompletableDeferred<SupportQueueConfirmation>
)

private class SupportRequestRejectedException(
    val errorCode: String?,
    override val message: String
) : Exception(message)

private inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // (mantido so para cancelar request por HTTP, se desejar)
    private val http = OkHttpClient()

    private val sessionRepository = SessionRepository()
    private val authRepository = AuthRepository()
    private val accountDeletionRepository = AccountDeletionRepository(authRepository, http)
    private val clientSupportRepository = ClientSupportRepository()
    private val clientNotificationRepository = ClientNotificationRepository()
    private val clientIdentityStore by lazy { ClientIdentityStore(applicationContext) }
    private val phoneIdentityProvider by lazy { FirebasePhoneIdentityProvider(applicationContext) }
    private val googlePlayReviewPrompt by lazy { GooglePlayReviewPrompt(applicationContext) }
    private val supportFlowFlags = SupportFlowFlags()
    private lateinit var appUpdateManager: AppUpdateManager

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
    private var screenSharingConsentAcceptedForCurrentSession = false
    private var remoteConsentAcceptedForCurrentSession = false
    private var pendingRemoteEnableAfterAccessibility = false
    private var pendingAudioAction: (() -> Unit)? = null
    private val audioRecordingLock = Any()
    private var audioRecordingGeneration = 0L
    private var audioRecordingStartInProgress = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioTempFile: File? = null
    private var audioRecordingSessionId: String? = null
    private val supportRequestCorrelation = SupportRequestCorrelation()
    @Volatile
    private var supportQueueConfirmationWaiter: SupportQueueConfirmationWaiter? = null
    private var supportRequestJob: Job? = null
    private var activeSupportRequestId: String? = null
    private var pendingSupportStartContext: SupportStartContext? = null
    private var pendingSupportSessionId: String? = null
    private var waitingSessionRecoveryJob: Job? = null
    private val activeSupportSessionStore by lazy {
        ActiveSupportSessionStore(applicationContext)
    }
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
    private var appUpdateCheckInProgress = false
    private var appUpdatePromptVisible = false
    private var appUpdatePromptShownThisLaunch = false
    private var appUpdateDialog: Dialog? = null
    @Volatile
    private var accountDeletionOperationInProgress = false
    @Volatile
    private var accountDeletionRestartPending = false
    private var clientNotificationRegistration: ListenerRegistration? = null
    private val accountDeletionAuthStateListener = FirebaseAuth.AuthStateListener { auth ->
        if (accountDeletionRestartPending && auth.currentUser != null) {
            authRepository.signOut()
        }
    }
    private var waitingCancellationReceiverRegistered = false
    private val waitingCancellationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WaitingSupportMonitorService.ACTION_WAITING_SUPPORT_CANCELLED) {
                return
            }
            val cancelledLocalId = intent.getStringExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return
            if (pendingSupportSessionId != cancelledLocalId) return

            invalidateSupportRequestTracking(cancelJob = true)
            activeSupportRequestId = null
            setPendingSupportSession(null)
            pendingSupportStartContext = null
            stopWaitingSessionRecovery()
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(null)
            setScreenFromSocket?.invoke(Screen.HOME)
            setSystemMessageFromLauncher?.invoke("Espera cancelada.")
        }
    }

    // -------- Helpers --------
    @Suppress("unused")
    private fun deviceId(): String {
        return DeviceIdentity.deviceId(this)
    }

    @SuppressLint("HardwareIds")
    private fun deviceAnchor(): String {
        return DeviceIdentity.deviceAnchor(this)
    }
    @Suppress("unused")
    private fun copyToClipboard(label: String, text: String) {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun registerWaitingCancellationReceiver() {
        if (waitingCancellationReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            waitingCancellationReceiver,
            IntentFilter(WaitingSupportMonitorService.ACTION_WAITING_SUPPORT_CANCELLED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        waitingCancellationReceiverRegistered = true
    }

    private fun unregisterWaitingCancellationReceiver() {
        if (!waitingCancellationReceiverRegistered) return
        runCatching { unregisterReceiver(waitingCancellationReceiver) }
            .onFailure { error ->
                Log.w("SXS/Main", "Falha ao remover receptor de cancelamento da espera", error)
            }
        waitingCancellationReceiverRegistered = false
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
            prefs.edit { remove(KEY_PENDING_SUPPORT_SESSION_ID) }
        } else {
            prefs.edit { putString(KEY_PENDING_SUPPORT_SESSION_ID, localSupportSessionId) }
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

    private fun isAccountIdentityWorkBlocked(): Boolean =
        accountDeletionOperationInProgress || accountDeletionRestartPending

    private fun disconnectAuthenticatedSocketForAccountDeletion() {
        val authenticatedSocket = if (this::socket.isInitialized) socket else Conn.socket
        runCatching { authenticatedSocket?.off() }
        runCatching { authenticatedSocket?.disconnect() }
        Conn.socket = null
    }

    private suspend fun clearLocalStateAfterAccountDeletion() {
        accountDeletionRestartPending = true
        runCatching { clientNotificationRegistration?.remove() }
        clientNotificationRegistration = null
        disconnectAuthenticatedSocketForAccountDeletion()

        invalidateSupportRequestTracking(cancelJob = true)
        activeSupportRequestId = null
        pendingSupportStartContext = null
        setPendingSupportSession(null)
        stopWaitingSessionRecovery()
        stopWaitingSupportForegroundService()
        stopTelemetryLoop()
        discardAudioRecording()
        stopIncomingCallAlert()

        currentSessionId = null
        activeSupportSessionStore.clear()
        Conn.sessionId = null
        Conn.techName = null
        Conn.chatRepository = null
        pendingLaunchSessionFromNotification = null
        pendingLaunchFeedbackSessionId = null
        pendingLaunchAcceptedSessionId = null
        pendingLaunchAcceptedTechName = null
        pendingLaunchAcceptedLocalSupportSessionId = null
        runCatching { voiceCallManager.bindSession(null) }
        runCatching { resetSessionState() }

        runCatching { phoneIdentityProvider.saveVerifiedPhoneNumber(null) }
        runCatching { clientIdentityStore.clear() }
        runCatching { authRepository.signOut() }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancelAll()
        }
    }

    private fun persistActiveSupportSession(
        realtimeSessionId: String,
        localSupportSessionId: String?,
        techName: String?
    ) {
        val normalizedRealtimeId = realtimeSessionId.trim().takeIf { it.isNotBlank() } ?: return
        val previous = activeSupportSessionStore.read()
            ?.takeIf { it.realtimeSessionId == normalizedRealtimeId }
        activeSupportSessionStore.write(
            ActiveSupportSessionState(
                realtimeSessionId = normalizedRealtimeId,
                localSupportSessionId = localSupportSessionId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: previous?.localSupportSessionId,
                techName = techName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: previous?.techName,
                acceptedAtMillis = previous?.acceptedAtMillis
                    ?.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            )
        )
    }

    private fun recoveryLocalSupportSessionId(realtimeSessionId: String): String? {
        val normalizedRealtimeId = realtimeSessionId.trim()
        return pendingSupportSessionId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: activeSupportSessionStore.read()
                ?.takeIf { it.realtimeSessionId == normalizedRealtimeId }
                ?.localSupportSessionId
    }

    private fun recoverPersistedActiveSupportSession() {
        if (!currentSessionId.isNullOrBlank() || isAccountIdentityWorkBlocked()) return
        val persisted = activeSupportSessionStore.read() ?: return
        val localSupportSessionId = persisted.localSupportSessionId ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val recovered = runCatching {
                clientSupportRepository.findActiveRealtimeSession(localSupportSessionId)
            }.getOrNull() ?: return@launch

            runOnUiThread {
                if (currentSessionId.isNullOrBlank() && !isAccountIdentityWorkBlocked()) {
                    handleSupportAccepted(
                        sessionId = recovered.sessionId,
                        techName = recovered.techName ?: persisted.techName,
                        source = "persisted_session_recovery"
                    )
                }
            }
        }
    }

    private fun loadSupportQueueWaitStats(onResult: (SupportQueueWaitStats) -> Unit) {
        if (isAccountIdentityWorkBlocked()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (isAccountIdentityWorkBlocked()) return@launch
            val stats = runCatching { clientSupportRepository.loadSupportQueueWaitStats() }
                .getOrElse {
                    SupportQueueWaitStats(
                        queueDepth = 0,
                        targetSampleSize = 1,
                        usedSampleSize = 0,
                        averageWaitMillis = null
                    )
                }
            runOnUiThread {
                if (!isAccountIdentityWorkBlocked()) {
                    onResult(stats)
                }
            }
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
        localSupportSessionId: String?,
        realtimeSessionId: String? = null
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
                            deviceAnchor = deviceAnchor(),
                            clientId = startContext.clientId,
                            realtimeSessionId = realtimeSessionId
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
                                    deviceAnchor = deviceAnchor(),
                                    clientId = startContext.clientId,
                                    realtimeSessionId = realtimeSessionId
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
                check(sessionRepository.ensureClientMembership(sessionId)) { "session_membership_not_ready" }
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

    private fun ensureScreenSharingConsent(onApproved: () -> Unit) {
        if (screenSharingConsentAcceptedForCurrentSession) {
            onApproved()
            return
        }

        val message = """
            Ao continuar, o Android solicitará autorização para compartilhar a tela.

            Durante esta sessão de suporte iniciada por você, o técnico designado poderá visualizar tudo o que aparecer na tela compartilhada, inclusive notificações ou informações sensíveis que você abrir.

            O compartilhamento não ativa o Serviço de Acessibilidade e pode ser interrompido a qualquer momento pelo botão de compartilhamento ou pelo encerramento da sessão.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Compartilhar tela com o técnico")
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton("Agora não") { dialog, _ ->
                shareRequestFromCommand = false
                dialog.dismiss()
            }
            .setPositiveButton("Concordo e continuar") { dialog, _ ->
                screenSharingConsentAcceptedForCurrentSession = true
                dialog.dismiss()
                onApproved()
            }
            .show()
    }

    private fun showAccessibilityDisclosure(
        grantForCurrentSession: Boolean,
        onAccepted: () -> Unit
    ) {
        val message = """
            O Serviço de Acessibilidade do Suporte X permite que o técnico designado:

            • leia o conteúdo exibido e as janelas do dispositivo para localizar campos e controles;
            • execute toques e gestos;
            • use Voltar, Início e Recentes;
            • digite e edite texto nos campos em foco.

            Esse acesso é usado somente durante uma sessão de suporte iniciada por você. Ele não é usado para anúncios, monitoramento oculto nem ações fora da sessão.

            O serviço é uma configuração do Android e pode permanecer ativado até você desativá-lo nas Configurações. O Suporte X bloqueia comandos fora da sessão ativa e tenta desativar o serviço quando o atendimento é encerrado normalmente.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Uso do Serviço de Acessibilidade")
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton("Agora não") { dialog, _ ->
                if (grantForCurrentSession) {
                    pendingRemoteEnableAfterAccessibility = false
                    remoteConsentAcceptedForCurrentSession = false
                }
                dialog.dismiss()
            }
            .setPositiveButton("Concordo e abrir Acessibilidade") { dialog, _ ->
                if (grantForCurrentSession) {
                    remoteConsentAcceptedForCurrentSession = true
                }
                dialog.dismiss()
                onAccepted()
            }
            .show()
    }

    private fun requestRemoteStateChange(enabled: Boolean) {
        if (enabled) {
            if (
                remoteConsentAcceptedForCurrentSession &&
                isAccessibilityServiceEnabled()
            ) {
                pendingRemoteEnableAfterAccessibility = false
                updateRemoteState(enabled = true, origin = "client")
                sendCommand("remote_enable")
                return
            }

            showAccessibilityDisclosure(grantForCurrentSession = true) {
                pendingRemoteEnableAfterAccessibility = true
                updateRemoteState(enabled = false, origin = "client")
                sendCommand("remote_revoke")
                setSystemMessageFromLauncher?.invoke(
                    "Ative Suporte X na tela de Acessibilidade e volte ao app para permitir o controle remoto."
                )
                openAccessibilitySettings()
            }
            return
        }

        pendingRemoteEnableAfterAccessibility = false
        updateRemoteState(enabled = false, origin = "client")
        sendCommand("remote_revoke")
    }

    private fun reconcileRemoteAccessAfterResume() {
        val sid = currentSessionId?.trim()?.takeIf { it.isNotBlank() }
        if (sid == null) {
            pendingRemoteEnableAfterAccessibility = false
            return
        }

        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (
            remoteConsentAcceptedForCurrentSession &&
            accessibilityEnabled &&
            (pendingRemoteEnableAfterAccessibility || remoteEnabledActive)
        ) {
            pendingRemoteEnableAfterAccessibility = false
            if (!remoteEnabledActive) {
                updateRemoteState(enabled = true, origin = "client")
            } else {
                RemoteCommandBus.setRemoteEnabled(true)
                pushSessionState()
                emitTelemetry()
            }
            sendCommand("remote_enable")
            setSystemMessageFromLauncher?.invoke("Acesso remoto autorizado para esta sessão.")
            return
        }

        if (!accessibilityEnabled && remoteEnabledActive) {
            updateRemoteState(enabled = false, origin = "system")
            sendCommand("remote_revoke")
            setSystemMessageFromLauncher?.invoke("Acessibilidade desativada. Acesso remoto interrompido.")
        }
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
                delay(5.seconds)
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
        description: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
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
        prefs.edit { putBoolean(KEY_INITIAL_PERMISSIONS_REQUESTED, true) }

        val runtimePermissions = mutableListOf<String>()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
        val isInternalNotificationAction =
            action == ACTION_OPEN_SESSION_CHAT ||
                action == ACTION_OPEN_SESSION_FEEDBACK ||
                action == ACTION_WAITING_SUPPORT_ACCEPTED
        if (isInternalNotificationAction && !InternalLaunchGuard.isTrusted(this, intent)) {
            Log.w("SXS/Main", "Ação interna de notificação sem autenticação foi ignorada")
            intent?.action = Intent.ACTION_MAIN
            intent?.removeExtra(EXTRA_SESSION_ID)
            intent?.removeExtra(EXTRA_TECH_NAME)
            intent?.removeExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
            InternalLaunchGuard.sanitize(intent)
            return
        }

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
        if (isInternalNotificationAction) {
            // Evita reprocessar a mesma acao em recriacoes da Activity (ex.: rotacao de tela).
            intent?.action = Intent.ACTION_MAIN
            intent?.removeExtra(EXTRA_SESSION_ID)
            intent?.removeExtra(EXTRA_TECH_NAME)
            intent?.removeExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
            InternalLaunchGuard.sanitize(intent)
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
            val localSupportSessionId = recoveryLocalSupportSessionId(normalizedSessionId)
            val activeRealtimeSession = runCatching {
                clientSupportRepository.findActiveRealtimeSession(localSupportSessionId)
            }.getOrNull()
            val sessionToOpen = activeRealtimeSession?.takeIf {
                it.sessionId.trim() == normalizedSessionId
            }
            runOnUiThread {
                clearTransientSupportNotifications(normalizedSessionId)
                if (sessionToOpen == null) {
                    if (currentSessionId.isNullOrBlank()) {
                        setScreenFromSocket?.invoke(Screen.HOME)
                    }
                    setSystemMessageFromLauncher?.invoke("Essa sessao ja foi encerrada.")
                    return@runOnUiThread
                }
                handleSupportAccepted(
                    sessionId = normalizedSessionId,
                    techName = sessionToOpen.techName,
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
            description = "Notificacoes de novas mensagens durante o suporte."
        )

        val openIntent = InternalLaunchGuard.attach(
            this,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_SESSION_CHAT
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
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

        manager.activeNotifications.forEach { active ->
            val notificationId = active.id
            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                active.notification.channelId
            } else {
                null
            }
            val isPersistentForeground = notificationId == 1 ||
                notificationId == 6_100 ||
                notificationId == VoiceCallForegroundService.NOTIFICATION_ID ||
                channelId == "screen_capture" ||
                channelId == "suportex_waiting_queue" ||
                channelId == VoiceCallForegroundService.NOTIFICATION_CHANNEL_ID
            if (!isPersistentForeground) {
                manager.cancel(notificationId)
            }
        }
    }

    private fun clearAllSupportNotificationsAfterFeedback() {
        stopIncomingCallAlert()
        stopWaitingSupportForegroundService()
        requestScreenCaptureServiceStop("limpeza final")
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

        val openIntent = InternalLaunchGuard.attach(
            this,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_SESSION_CHAT
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
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
            "Seu atendimento foi encerrado. Toque para abrir."
        } else {
            "${normalizedReason.trimEnd('.', ' ')}. Toque para abrir."
        }
    }

    private fun notifySessionEndedByTech(sessionId: String, reason: String?) {
        if (!isNotificationPermissionGranted()) return

        ensureNotificationChannel(
            id = SESSION_NOTIFICATION_CHANNEL_ID,
            name = "Atualizacoes do atendimento",
            description = "Notificacoes sobre encerramento da sessao de suporte."
        )

        val pendingIntent = buildSessionLaunchPendingIntent(
            action = ACTION_OPEN_SESSION_FEEDBACK,
            sessionId = sessionId,
            requestCode = abs(sessionId.hashCode()) + 10_000
        )

        val body = buildSessionEndedNotificationBody(reason)
        val notification = NotificationCompat.Builder(this, SESSION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Atendimento encerrado")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(sessionEndedNotificationId(sessionId), notification)
    }

    private fun buildSessionLaunchPendingIntent(action: String, sessionId: String, requestCode: Int): PendingIntent {
        val openIntent = InternalLaunchGuard.attach(
            this,
            Intent(this, MainActivity::class.java).apply {
                this.action = action
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        return PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifySupportAcceptedByTech(sessionId: String, techName: String?) {
        if (!isNotificationPermissionGranted()) return

        ensureNotificationChannel(
            id = SESSION_NOTIFICATION_CHANNEL_ID,
            name = "Atualizacoes do atendimento",
            description = "Notificacoes sobre o inicio e encerramento da sessao de suporte."
        )

        val pendingIntent = buildSessionLaunchPendingIntent(
            action = ACTION_OPEN_SESSION_CHAT,
            sessionId = sessionId,
            requestCode = abs(sessionId.hashCode()) + 8_000
        )

        val techLabel = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Técnico"
        val notification = NotificationCompat.Builder(this, SESSION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Atendimento iniciado")
            .setContentText("$techLabel iniciou seu atendimento. Toque para abrir.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .build()

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

                delay(WAITING_SESSION_RECOVERY_INTERVAL_MS.milliseconds)
            }
            waitingSessionRecoveryJob = null
        }
    }

    private fun handleSupportAccepted(sessionId: String, techName: String?, source: String) {
        if (isAccountIdentityWorkBlocked()) return
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        val resolvedTechName = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Tecnico"

        if (currentSessionId == sid) {
            Conn.techName = resolvedTechName
            activeSupportRequestId = null
            persistActiveSupportSession(
                realtimeSessionId = sid,
                localSupportSessionId = recoveryLocalSupportSessionId(sid),
                techName = resolvedTechName
            )
            syncWaitingSupportForegroundService()
            runOnUiThread {
                setTechNameFromSocket?.invoke(resolvedTechName)
                setRequestIdFromSocket?.invoke(null)
                setSessionIdFromSocket?.invoke(sid)
                setScreenFromSocket?.invoke(Screen.SESSION)
            }
            if (!appInForeground) notifySupportAcceptedByTech(sid, resolvedTechName)
            return
        }

        val localSupportSessionId = recoveryLocalSupportSessionId(sid)
        persistActiveSupportSession(
            realtimeSessionId = sid,
            localSupportSessionId = localSupportSessionId,
            techName = resolvedTechName
        )
        finishSupportRequestTracking(localSupportSessionId)
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
            emitSessionJoin(sid, joinToken)
        }

        startTelemetryLoop()
        runOnUiThread {
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(sid)
            setScreenFromSocket?.invoke(Screen.SESSION)
        }

        if (!appInForeground) {
            notifySupportAcceptedByTech(sid, resolvedTechName)
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
        screenSharingConsentAcceptedForCurrentSession = false
        remoteConsentAcceptedForCurrentSession = false
        pendingRemoteEnableAfterAccessibility = false
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
        val sid = currentSessionId?.trim()?.takeIf { it.isNotBlank() }
        if (sid == null) {
            setSystemMessageFromLauncher?.invoke("Sessao ainda nao aceita pelo tecnico.")
            return
        }
        voiceCallManager.bindSession(sid)
        warmUpBackendForSupport()
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
        val sid = currentSessionId?.trim()?.takeIf { it.isNotBlank() }
        if (sid == null) {
            setSystemMessageFromLauncher?.invoke("Sessao ainda nao aceita pelo tecnico.")
            return
        }
        if (!appInForeground) return

        val generation = synchronized(audioRecordingLock) {
            if (audioRecordingStartInProgress || mediaRecorder != null) return
            audioRecordingStartInProgress = true
            audioRecordingGeneration += 1
            audioRecordingGeneration
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var recordingInstalled = false
            var initializationFailed = false

            synchronized(audioRecordingLock) {
                if (
                    generation != audioRecordingGeneration ||
                    !audioRecordingStartInProgress ||
                    currentSessionId != sid ||
                    !appInForeground
                ) {
                    return@synchronized
                }

                var recorder: MediaRecorder? = null
                var outFile: File? = null
                var recorderStarted = false
                try {
                    val createdFile = File.createTempFile("sx_audio_", ".m4a", cacheDir)
                    outFile = createdFile
                    recorder = createMediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(44100)
                        setAudioEncodingBitRate(96000)
                        setOutputFile(createdFile.absolutePath)
                        prepare()
                        start()
                    }
                    recorderStarted = true

                    if (
                        generation == audioRecordingGeneration &&
                        audioRecordingStartInProgress &&
                        currentSessionId == sid &&
                        appInForeground
                    ) {
                        mediaRecorder = recorder
                        audioTempFile = outFile
                        audioRecordingSessionId = sid
                        audioRecordingStartInProgress = false
                        recorder = null
                        outFile = null
                        recordingInstalled = true
                    }
                } catch (error: Exception) {
                    initializationFailed = true
                    Log.w("SXS/Main", "Falha ao iniciar gravacao de audio", error)
                } finally {
                    if (recorder != null) {
                        releaseMediaRecorderSafely(recorder, stopFirst = recorderStarted)
                    }
                    runCatching { outFile?.delete() }
                    if (
                        generation == audioRecordingGeneration &&
                        !recordingInstalled
                    ) {
                        audioRecordingStartInProgress = false
                    }
                }
            }

            runOnUiThread {
                val (sameGeneration, stillRecording) = synchronized(audioRecordingLock) {
                    val currentGeneration = generation == audioRecordingGeneration
                    val activeRecording =
                        currentGeneration &&
                            mediaRecorder != null &&
                            audioRecordingSessionId == sid
                    currentGeneration to activeRecording
                }
                when {
                    recordingInstalled && stillRecording -> {
                        setRecordingAudioFromActivity?.invoke(true)
                        setSystemMessageFromLauncher?.invoke(
                            "Gravando audio... Toque novamente para enviar."
                        )
                    }
                    initializationFailed && sameGeneration -> {
                        setRecordingAudioFromActivity?.invoke(false)
                        setSystemMessageFromLauncher?.invoke("Falha ao iniciar gravacao de audio.")
                    }
                }
            }
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
        val recording = synchronized(audioRecordingLock) {
            if (audioRecordingStartInProgress && mediaRecorder == null) {
                audioRecordingGeneration += 1
                audioRecordingStartInProgress = false
                null
            } else {
                val recorder = mediaRecorder
                val file = audioTempFile
                val sid = audioRecordingSessionId
                audioRecordingGeneration += 1
                mediaRecorder = null
                audioTempFile = null
                audioRecordingSessionId = null
                if (recorder != null && file != null && !sid.isNullOrBlank()) {
                    Triple(recorder, file, sid)
                } else {
                    null
                }
            }
        }
        setRecordingAudioFromActivity?.invoke(false)
        if (recording == null) return

        val (recorder, outFile, sid) = recording
        val stopSucceeded = runCatching {
            recorder.stop()
        }.onFailure { error ->
            Log.w("SXS/Main", "Falha ao finalizar gravacao de audio", error)
        }.isSuccess
        releaseMediaRecorderSafely(recorder, stopFirst = false)

        if (!stopSucceeded) {
            runCatching { outFile.delete() }
            setSystemMessageFromLauncher?.invoke("Falha ao finalizar gravacao de audio.")
            return
        }
        if (!outFile.exists()) {
            setSystemMessageFromLauncher?.invoke("Arquivo de audio nao encontrado.")
            return
        }
        if (currentSessionId != sid) {
            runCatching { outFile.delete() }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = runCatching {
                    requireNotNull(Conn.chatRepository) {
                        "Repositorio de chat indisponivel."
                    }.sendAudio(
                        sid,
                        from = "client",
                        localUri = Uri.fromFile(outFile)
                    )
                }
                runOnUiThread {
                    if (currentSessionId == sid) {
                        if (result.isSuccess) {
                            setSystemMessageFromLauncher?.invoke("Audio enviado.")
                        } else {
                            setSystemMessageFromLauncher?.invoke("Falha ao enviar audio.")
                        }
                    }
                }
            } finally {
                runCatching { outFile.delete() }
            }
        }
    }

    private fun releaseMediaRecorderSafely(recorder: MediaRecorder, stopFirst: Boolean) {
        if (stopFirst) {
            runCatching { recorder.stop() }
        }
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }

    private fun discardAudioRecording() {
        val recording = synchronized(audioRecordingLock) {
            audioRecordingGeneration += 1
            audioRecordingStartInProgress = false
            val recorder = mediaRecorder
            val file = audioTempFile
            mediaRecorder = null
            audioTempFile = null
            audioRecordingSessionId = null
            recorder to file
        }
        recording.first?.let { recorder ->
            releaseMediaRecorderSafely(recorder, stopFirst = true)
        }
        runCatching { recording.second?.delete() }
        setRecordingAudioFromActivity?.invoke(false)
    }

    private fun toggleAudioRecording() {
        val shouldStop = synchronized(audioRecordingLock) {
            mediaRecorder != null || audioRecordingStartInProgress
        }
        if (shouldStop) {
            stopAudioRecordingAndSend()
        } else {
            startAudioRecording()
        }
    }

    private fun finalizeSession() {
        discardAudioRecording()
        stopTelemetryLoop()
        voiceCallManager.release()
        val accessibilityDisableRequested = RemoteExecutor.disableAccessibilityService()
        if (!accessibilityDisableRequested && isAccessibilityServiceEnabled()) {
            Log.w(
                "SXS/Main",
                "Serviço de Acessibilidade ainda habilitado, mas não conectado para desativação automática"
            )
        }
        activeSupportRequestId = null
        stopWaitingSessionRecovery()
        resetSessionState()
        currentSessionId = null
        activeSupportSessionStore.clear()
        Conn.sessionId = null
        Conn.techName = null
        runOnUiThread { setTechNameFromSocket?.invoke("T\u00e9cnico") }
    }

    private fun submitCustomerSatisfaction(
        sessionId: String,
        score: Int,
        onSaved: () -> Unit = {}
    ) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return
        val normalizedScore = score.coerceIn(0, 5)
        lifecycleScope.launch(Dispatchers.IO) {
            var saved = false
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
                    } else {
                        saved = true
                    }
                }
            } catch (error: Exception) {
                Log.w("SXS/Main", "Falha ao enviar satisfação do cliente", error)
            }
            if (saved) {
                runOnUiThread { onSaved() }
            }
        }
    }

    private fun requestClientSessionClosure(sessionId: String, reason: String) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            repeat(2) { attempt ->
                val token = runCatching {
                    authRepository.ensureAnonIdToken(forceRefresh = attempt > 0)
                }.getOrDefault("")
                if (token.isBlank()) return@launch

                val payload = JSONObject().apply {
                    put("reason", reason.take(160))
                }.toString()
                val request = Request.Builder()
                    .url("${Conn.SERVER_BASE}/api/sessions/$normalizedSessionId/client-close")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .header("Authorization", "Bearer $token")
                    .build()

                val status = runCatching {
                    http.newCall(request).execute().use { response -> response.code }
                }.getOrNull()
                if (status != null && status in 200..299) return@launch
                if (status != 401) {
                    Log.w("SXS/Main", "Servidor não confirmou encerramento da sessão (status=$status)")
                    return@launch
                }
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
        if (!fromCommand) {
            sendCommand("end")
            requestClientSessionClosure(sid, normalizedReason)
        }

        sid?.let {
            logSessionEvent("end", origin = origin, extras = mapOf("reason" to normalizedReason))
        }

        val shareWasActive = isSharingActive
        val remoteWasActive = remoteEnabledActive
        val callWasActive = callingActive || callConnectedActive

        if (callWasActive) {
            voiceCallManager.endCall()
            if (callingActive || callConnectedActive) {
                updateCallState(calling = false, connected = false, origin = origin)
            }
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
        stopIncomingCallAlert()
        cancelIncomingCallNotification(sid)
        finalizeSession()
        runOnUiThread {
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(null)
            setEndedSessionForFeedback?.invoke(sid)
            setScreenFromSocket?.invoke(if (sid.isBlank()) Screen.HOME else Screen.SESSION_FEEDBACK)
            setSystemMessageFromLauncher?.invoke(null)
        }
        if (fromCommand && sid.isNotBlank() && !appInForeground) {
            notifySessionEndedByTech(sid, normalizedReason)
        }
    }

    // -------- Socket.IO --------
    private fun emitSessionJoin(sessionId: String, idToken: String = "") {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank() || !::socket.isInitialized) return
        val joinPayload = JSONObject().apply {
            put("sessionId", normalizedSessionId)
            put("role", "client")
            if (idToken.isNotBlank()) put("idToken", idToken)
        }
        socket.emit("session:join", joinPayload)
        socket.emit("join", joinPayload)
    }

    private fun connectSocket() {
        if (isAccountIdentityWorkBlocked()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (isAccountIdentityWorkBlocked()) return@launch
            val idToken = runCatching { authRepository.ensureAnonIdToken(forceRefresh = true) }.getOrDefault("")
            if (isAccountIdentityWorkBlocked()) return@launch
            runOnUiThread {
                if (!isAccountIdentityWorkBlocked()) {
                    connectSocketInternal(idToken)
                }
            }
        }
    }

    private fun connectSocketInternal(idToken: String) {
        if (isAccountIdentityWorkBlocked()) return
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
            currentSessionId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { emitSessionJoin(it, idToken) }
            runOnUiThread {
                // opcional: Toast.makeText(this, "Conectado", Toast.LENGTH_SHORT).show()
            }
        }

        // cliente entrou na fila
        socket.on("support:enqueued") { args ->
            val any = args.getOrNull(0) ?: return@on
            val data = (any as? JSONObject) ?: return@on
            val reqId = data.optString("requestId", "").trim().takeIf { it.isNotBlank() }
                ?: return@on
            val eventLocalSupportSessionId = data.optString("localSupportSessionId", "")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: data.optString("supportSessionId", "").trim().takeIf { it.isNotBlank() }
            val result = supportRequestCorrelation.confirmFromLegacyEvent(
                eventLocalSupportSessionId = eventLocalSupportSessionId,
                requestId = reqId
            )
            if (result.status == SupportCorrelationStatus.IGNORED) {
                Log.w("SXS/Main", "support:enqueued atrasado ou sem correlacao ignorado")
                return@on
            }
            publishSupportQueueConfirmation(result)
        }

        socket.on("support:error") { args ->
            val data = args.getOrNull(0) as? JSONObject
            val errorCode = data?.optString("error", "")?.trim().orEmpty()
            val backendMessage = data?.optString("message", "")?.trim()?.takeIf { it.isNotBlank() }
            val eventLocalSupportSessionId = data
                ?.optString("localSupportSessionId", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val eventRequestId = data
                ?.optString("requestId", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (
                !supportRequestCorrelation.canApplyServerError(
                    eventLocalSupportSessionId = eventLocalSupportSessionId,
                    eventRequestId = eventRequestId
                )
            ) {
                Log.w("SXS/Main", "support:error atrasado ou sem correlacao ignorado")
                return@on
            }
            val token = supportRequestCorrelation.currentToken() ?: return@on
            val feedbackMessage = supportRequestFailureMessage(errorCode, backendMessage)
            supportQueueConfirmationWaiter
                ?.takeIf { it.token == token }
                ?.deferred
                ?.completeExceptionally(
                    SupportRequestRejectedException(errorCode, feedbackMessage)
                )
            failSupportRequest(token, feedbackMessage)
        }

        // tecnico aceitou -> recebemos sessionId e (opcional) techName
        socket.on("support:accepted") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sid = data.optString("sessionId", "").trim()
            val tname = data.optString("techName", "Tecnico")
            if (sid.isBlank()) return@on
            val currentSid = currentSessionId?.trim()
            if (!currentSid.isNullOrBlank() && currentSid != sid) {
                Log.w("SXS/Main", "support:accepted atrasado para outra sessao ignorado")
                return@on
            }
            val activeToken = supportRequestCorrelation.currentToken()
            val expectedLocalSupportSessionId =
                activeToken?.localSupportSessionId ?: pendingSupportSessionId
            if (currentSid.isNullOrBlank() && expectedLocalSupportSessionId.isNullOrBlank()) {
                Log.w("SXS/Main", "support:accepted sem solicitacao ativa ignorado")
                return@on
            }
            val eventLocalSupportSessionId = data
                .optString("localSupportSessionId", "")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: data.optString("supportSessionId", "").trim().takeIf { it.isNotBlank() }
            val eventRequestId = data
                .optString("requestId", "")
                .trim()
                .takeIf { it.isNotBlank() }
            if (
                eventLocalSupportSessionId != null &&
                expectedLocalSupportSessionId != null &&
                eventLocalSupportSessionId != expectedLocalSupportSessionId
            ) {
                Log.w("SXS/Main", "support:accepted com sessao local divergente ignorado")
                return@on
            }
            val trackedRequestId = activeSupportRequestId?.trim()?.takeIf { it.isNotBlank() }
            if (
                eventRequestId != null &&
                trackedRequestId != null &&
                eventRequestId != trackedRequestId
            ) {
                Log.w("SXS/Main", "support:accepted com requestId divergente ignorado")
                return@on
            }
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
            val serverRealtimeSessionId = data.optString("sessionId", "").trim().takeIf { it.isNotBlank() }
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
                        localSupportSessionId = serverSupportSessionId ?: pendingSupportSessionId,
                        realtimeSessionId = serverRealtimeSessionId
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

    private fun warmUpBackendForSupport() {
        lifecycleScope.launch(Dispatchers.IO) {
            warmUpBackendForSupportBlocking()
        }
    }

    private suspend fun warmUpBackendForSupportBlocking(): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${Conn.SERVER_BASE}/healthz")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                Log.d("SXS/Main", "Backend warmup status=${response.code}")
            }
        }.onFailure { err ->
            Log.w("SXS/Main", "Backend warmup falhou", err)
        }
    }

    private suspend fun ensureSupportSocketReady(): Boolean {
        if (!::socket.isInitialized) return false
        if (socket.connected()) return true
        runCatching { socket.connect() }
        repeat(16) {
            if (socket.connected()) return true
            delay(500.milliseconds)
        }
        return socket.connected()
    }

    private fun parseSupportRequestAck(args: Array<out Any?>): SupportRequestAck {
        val data = args.getOrNull(0) as? JSONObject
        if (data == null) {
            return SupportRequestAck(
                ok = false,
                requestId = null,
                reused = false,
                error = "invalid_ack",
                message = null,
                localSupportSessionId = null
            )
        }
        return SupportRequestAck(
            ok = data.optBoolean("ok", false),
            requestId = data.optString("requestId", "").trim().takeIf { it.isNotBlank() },
            reused = data.optBoolean("reused", false),
            error = data.optString("error", "").trim().takeIf { it.isNotBlank() }
                ?: data.optString("err", "").trim().takeIf { it.isNotBlank() },
            message = data.optString("message", "").trim().takeIf { it.isNotBlank() },
            localSupportSessionId = data.optString("localSupportSessionId", "")
                .trim()
                .takeIf { it.isNotBlank() }
        )
    }

    private fun publishSupportQueueConfirmation(
        result: SupportCorrelationResult
    ): SupportQueueConfirmation? {
        if (result.status == SupportCorrelationStatus.IGNORED) return null
        val confirmation = result.confirmation ?: return null
        val waiter = supportQueueConfirmationWaiter
        if (waiter?.token == confirmation.token) {
            waiter.deferred.complete(confirmation)
        }
        runOnUiThread {
            if (!supportRequestCorrelation.isActive(confirmation.token)) return@runOnUiThread
            activeSupportRequestId = confirmation.requestId
            syncWaitingSupportForegroundService()
            startWaitingSessionRecovery()
            setRequestIdFromSocket?.invoke(confirmation.requestId)
        }
        return confirmation
    }

    private fun supportRequestFailureMessage(errorCode: String?, backendMessage: String?): String {
        return when (errorCode) {
            "credit_required" ->
                "Sem crédito disponível. Compre créditos para solicitar novo atendimento."
            "unauthorized", "auth_required", "invalid_token" ->
                "Não foi possível confirmar sua autenticação. Tente novamente."
            "invalid_ack" ->
                "O servidor respondeu de forma inesperada. Tente solicitar novamente."
            else -> backendMessage?.takeIf { it.isNotBlank() }
                ?: "Não foi possível entrar na fila agora. Tente novamente em alguns segundos."
        }
    }

    private fun failSupportRequest(
        token: SupportRequestToken,
        message: String,
        cancelLocalSession: Boolean = true
    ) {
        if (!supportRequestCorrelation.finish(token)) return
        val waiter = supportQueueConfirmationWaiter
        if (waiter?.token == token) {
            supportQueueConfirmationWaiter = null
            waiter.deferred.completeExceptionally(
                SupportRequestRejectedException(errorCode = null, message = message)
            )
        }
        if (cancelLocalSession) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    if (::socket.isInitialized && socket.connected()) {
                        socket.emit(
                            "support:cancel",
                            JSONObject().apply {
                                put(
                                    "localSupportSessionId",
                                    token.localSupportSessionId
                                )
                            }
                        )
                    }
                }.onFailure { error ->
                    Log.w(
                        "SXS/Main",
                        "Falha ao cancelar fila remota após solicitação não confirmada",
                        error
                    )
                }
                runCatching {
                    clientSupportRepository.cancelSupportRequest(token.localSupportSessionId)
                }
            }
        }
        runOnUiThread {
            if (pendingSupportSessionId != token.localSupportSessionId) return@runOnUiThread
            activeSupportRequestId = null
            setPendingSupportSession(null)
            pendingSupportStartContext = null
            stopWaitingSessionRecovery()
            setRequestIdFromSocket?.invoke(null)
            setSessionIdFromSocket?.invoke(null)
            setScreenFromSocket?.invoke(Screen.HOME)
            setSystemMessageFromLauncher?.invoke(message)
        }
    }

    private fun invalidateSupportRequestTracking(cancelJob: Boolean) {
        supportRequestCorrelation.invalidate()
        supportQueueConfirmationWaiter?.deferred?.cancel()
        supportQueueConfirmationWaiter = null
        if (cancelJob) {
            supportRequestJob?.cancel()
            supportRequestJob = null
        }
    }

    private fun finishSupportRequestTracking(localSupportSessionId: String?) {
        val token = supportRequestCorrelation.currentToken() ?: return
        if (
            !localSupportSessionId.isNullOrBlank() &&
            token.localSupportSessionId != localSupportSessionId
        ) {
            return
        }
        supportQueueConfirmationWaiter
            ?.takeIf { it.token == token }
            ?.deferred
            ?.complete(
                SupportQueueConfirmation(
                    token = token,
                    requestId = activeSupportRequestId ?: token.localSupportSessionId,
                    source = SupportQueueConfirmationSource.LEGACY_EVENT,
                    reused = true
                )
            )
        supportRequestCorrelation.finish(token)
        if (supportQueueConfirmationWaiter?.token == token) {
            supportQueueConfirmationWaiter = null
        }
    }

    private suspend fun emitSupportRequestWithConfirmation(
        token: SupportRequestToken,
        payload: JSONObject,
        confirmationDeferred: CompletableDeferred<SupportQueueConfirmation>
    ): SupportQueueConfirmation {
        repeat(SUPPORT_REQUEST_MAX_ATTEMPTS) { attemptIndex ->
            if (confirmationDeferred.isCompleted) {
                return confirmationDeferred.await()
            }
            if (!supportRequestCorrelation.isActive(token)) {
                throw SupportRequestRejectedException(
                    errorCode = "request_superseded",
                    message = "Solicitação substituída por uma tentativa mais recente."
                )
            }

            val ackDeferred = CompletableDeferred<SupportRequestAck>()
            val emitted = runCatching {
                socket.emit(
                    "support:request",
                    arrayOf<Any>(payload),
                    Ack { args -> ackDeferred.complete(parseSupportRequestAck(args)) }
                )
            }.onFailure { error ->
                Log.w(
                    "SXS/Main",
                    "Falha ao emitir support:request tentativa=${attemptIndex + 1}",
                    error
                )
            }.isSuccess

            val attemptResult = if (emitted) {
                withTimeoutOrNull<SupportRequestAttemptResult>(SUPPORT_REQUEST_ACK_TIMEOUT_MS) {
                    select {
                        ackDeferred.onAwait { SupportRequestAttemptResult.AckReceived(it) }
                        confirmationDeferred.onAwait {
                            SupportRequestAttemptResult.QueueConfirmed(it)
                        }
                    }
                }
            } else {
                null
            }

            when (attemptResult) {
                is SupportRequestAttemptResult.QueueConfirmed -> {
                    return attemptResult.confirmation
                }
                is SupportRequestAttemptResult.AckReceived -> {
                    val ack = attemptResult.ack
                    if (!ack.ok) {
                        val retryMalformedAck =
                            ack.error == "invalid_ack" &&
                                attemptIndex + 1 < SUPPORT_REQUEST_MAX_ATTEMPTS
                        if (!retryMalformedAck) {
                            throw SupportRequestRejectedException(
                                errorCode = ack.error,
                                message = supportRequestFailureMessage(ack.error, ack.message)
                            )
                        }
                    } else {
                        val requestId = ack.requestId
                        if (requestId.isNullOrBlank()) {
                            if (attemptIndex + 1 >= SUPPORT_REQUEST_MAX_ATTEMPTS) {
                                throw SupportRequestRejectedException(
                                    errorCode = "invalid_ack",
                                    message = supportRequestFailureMessage("invalid_ack", null)
                                )
                            }
                        } else {
                            val confirmation = publishSupportQueueConfirmation(
                                supportRequestCorrelation.confirmFromAck(
                                    token = token,
                                    ackLocalSupportSessionId = ack.localSupportSessionId,
                                    requestId = requestId,
                                    reused = ack.reused
                                )
                            )
                            if (confirmation != null) return confirmation
                            if (!supportRequestCorrelation.isActive(token)) {
                                throw SupportRequestRejectedException(
                                    errorCode = "request_superseded",
                                    message = "Solicitação substituída por uma tentativa mais recente."
                                )
                            }
                        }
                    }
                }
                null -> {
                    if (confirmationDeferred.isCompleted) {
                        return confirmationDeferred.await()
                    }
                }
            }

            if (attemptIndex + 1 < SUPPORT_REQUEST_MAX_ATTEMPTS) {
                ensureSupportSocketReady()
            }
        }

        val legacyConfirmation = withTimeoutOrNull(SUPPORT_REQUEST_LEGACY_GRACE_MS) {
            confirmationDeferred.await()
        }
        if (legacyConfirmation != null) return legacyConfirmation
        throw SupportRequestRejectedException(
            errorCode = "ack_timeout",
            message = "O servidor não confirmou a entrada na fila. Tente solicitar novamente."
        )
    }

    private fun loadHomeSnapshot(onResult: (ClientHomeSnapshot) -> Unit) {
        if (isAccountIdentityWorkBlocked()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (isAccountIdentityWorkBlocked()) return@launch
            val clientUid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
            if (isAccountIdentityWorkBlocked()) return@launch
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
            runOnUiThread {
                if (!isAccountIdentityWorkBlocked()) {
                    onResult(snapshot)
                }
            }
        }
    }

    private fun evaluateSupportEntry(onDecision: (SupportAccessDecision) -> Unit) {
        if (isAccountIdentityWorkBlocked()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (isAccountIdentityWorkBlocked()) return@launch
            val clientUid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
            if (isAccountIdentityWorkBlocked()) return@launch
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
            runOnUiThread {
                if (!isAccountIdentityWorkBlocked()) {
                    onDecision(decision)
                }
            }
        }
    }

    private fun requestSupport(
        startContext: SupportStartContext,
        clientName: String?
    ) {
        invalidateSupportRequestTracking(cancelJob = true)
        activeSupportRequestId = null
        setPendingSupportSession(null)
        stopWaitingSessionRecovery()

        val requestJob = lifecycleScope.launch(Dispatchers.IO) {
            val uid = runCatchingUnlessCancelled { authRepository.ensureAnonAuth() }.getOrNull()
            val idToken = runCatchingUnlessCancelled {
                authRepository.ensureAnonIdToken(forceRefresh = false)
            }.getOrDefault("")
            val verifiedPhone = runCatchingUnlessCancelled {
                phoneIdentityProvider.getVerifiedPhoneNumber()
            }.getOrNull()
            val effectivePhone = verifiedPhone ?: startContext.phone
            val currentDeviceAnchor = deviceAnchor()
            currentCoroutineContext().ensureActive()
            if (uid.isNullOrBlank()) {
                Log.e("SXS/Main", "Falha ao autenticar cliente antes de support:request")
                runOnUiThread {
                    pendingSupportStartContext = null
                    setScreenFromSocket?.invoke(Screen.HOME)
                    setSystemMessageFromLauncher?.invoke(
                        "Falha de autenticação. Tente novamente em alguns segundos."
                    )
                }
                return@launch
            }

            val localSupportSessionResult = runCatchingUnlessCancelled {
                clientSupportRepository.registerSupportRequest(
                    startContext = startContext.copy(phone = effectivePhone),
                    clientName = clientName,
                    clientUid = uid,
                    deviceAnchor = currentDeviceAnchor,
                    deviceBrand = Build.BRAND,
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
                )
            }
            val localSupportSessionId = localSupportSessionResult
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (localSupportSessionId == null) {
                localSupportSessionResult.exceptionOrNull()?.let { error ->
                    Log.e("SXS/Main", "Falha ao registrar solicitacao local de suporte", error)
                }
                runOnUiThread {
                    activeSupportRequestId = null
                    setPendingSupportSession(null)
                    pendingSupportStartContext = null
                    stopWaitingSessionRecovery()
                    setRequestIdFromSocket?.invoke(null)
                    setScreenFromSocket?.invoke(Screen.HOME)
                    setSystemMessageFromLauncher?.invoke(
                        "Não foi possível preparar sua solicitação. Verifique a conexão e tente novamente."
                    )
                }
                return@launch
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        clientSupportRepository.cancelSupportRequest(localSupportSessionId)
                    }
                }
                throw error
            }

            val token = supportRequestCorrelation.begin(localSupportSessionId)
            val confirmationDeferred = CompletableDeferred<SupportQueueConfirmation>()
            supportQueueConfirmationWaiter = SupportQueueConfirmationWaiter(
                token = token,
                deferred = confirmationDeferred
            )
            try {
                withContext(Dispatchers.Main) {
                    if (!supportRequestCorrelation.isActive(token)) return@withContext
                    setPendingSupportSession(localSupportSessionId)
                    startWaitingSessionRecovery()
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        clientSupportRepository.cancelSupportRequest(localSupportSessionId)
                    }
                }
                throw error
            }
            if (!supportRequestCorrelation.isActive(token)) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        clientSupportRepository.cancelSupportRequest(localSupportSessionId)
                    }
                }
                return@launch
            }

            warmUpBackendForSupportBlocking()
            currentCoroutineContext().ensureActive()
            val socketReady = ensureSupportSocketReady()
            if (!socketReady) {
                failSupportRequest(
                    token = token,
                    message = "Não foi possível conectar ao servidor. Tente solicitar novamente."
                )
                return@launch
            }

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
                put("localSupportSessionId", localSupportSessionId)
                put("supportProfile", JSONObject().apply {
                    put("isNewClient", startContext.isNewClient)
                    put("isFreeFirstSupport", startContext.isFreeFirstSupport)
                    put("creditsToConsume", startContext.creditsToConsume)
                    put("localSupportSessionId", localSupportSessionId)
                    put("disableQuickIdentificationModal", supportFlowFlags.disableQuickIdentificationModal)
                    put("technicianDrivenRegistrationEnabled", supportFlowFlags.technicianDrivenRegistrationEnabled)
                    put("pnvPostRegistrationFlow", supportFlowFlags.pnvPostRegistrationFlow)
                })
                if (idToken.isNotBlank()) put("idToken", idToken)
            }

            try {
                emitSupportRequestWithConfirmation(
                    token = token,
                    payload = payload,
                    confirmationDeferred = confirmationDeferred
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: SupportRequestRejectedException) {
                if (error.errorCode != "request_superseded") {
                    failSupportRequest(token, error.message)
                }
                return@launch
            } catch (error: Exception) {
                Log.e("SXS/Main", "Falha ao confirmar entrada na fila", error)
                failSupportRequest(
                    token = token,
                    message = "Não foi possível confirmar sua entrada na fila. Tente novamente."
                )
                return@launch
            } finally {
                if (supportQueueConfirmationWaiter?.token == token) {
                    supportQueueConfirmationWaiter = null
                }
            }

            if (!verifiedPhone.isNullOrBlank()) {
                runCatching {
                    clientSupportRepository.registerPnvSuccess(
                        clientUid = uid,
                        verifiedPhone = verifiedPhone,
                        token = null,
                        localSupportSessionId = localSupportSessionId,
                        deviceAnchor = currentDeviceAnchor,
                        clientId = startContext.clientId
                    )
                }
            } else {
                runOnUiThread {
                    launchPnvVerificationFlow(
                        clientUid = uid,
                        startContext = startContext,
                        localSupportSessionId = localSupportSessionId
                    )
                }
            }
        }
        supportRequestJob = requestJob
        requestJob.invokeOnCompletion {
            runOnUiThread {
                if (supportRequestJob === requestJob) {
                    supportRequestJob = null
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
        invalidateSupportRequestTracking(cancelJob = true)
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
                        localSupportId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("localSupportSessionId", it) }
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
        ensureScreenSharingConsent {
            setSystemMessageFromLauncher?.invoke("Preparando compartilhamento...")
            lifecycleScope.launch(Dispatchers.IO) {
                val ready = ensureRealtimeSessionReady(sid, reason = "screen_share_start")
                runOnUiThread {
                    if (!ready || currentSessionId != sid) {
                        shareRequestFromCommand = false
                        setSystemMessageFromLauncher?.invoke(
                            "Sessao ainda sincronizando. Tente compartilhar novamente em alguns segundos."
                        )
                        return@runOnUiThread
                    }
                    setSystemMessageFromLauncher?.invoke("Ao compartilhar, selecione a tela inteira do dispositivo.")
                    val intent = createScreenCaptureIntentForSupport()
                    screenCaptureLauncher.launch(intent)
                }
            }
        }
    }

    private suspend fun ensureRealtimeSessionReady(sessionId: String, reason: String): Boolean {
        val sid = sessionId.trim()
        if (sid.isBlank()) return false
        return runCatching {
            sessionRepository.ensureClientMembership(sid)
        }.getOrElse { error ->
            Log.w("SXS/Main", "Sessao realtime indisponivel para $reason", error)
            false
        }
    }

    private fun createScreenCaptureIntentForSupport(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            }.getOrElse {
                Log.w("SXS/Main", "Falha ao criar intent de captura de tela inteira", it)
                mediaProjectionManager.createScreenCaptureIntent()
            }
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
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
                val resultCode = result.resultCode
                val resultData = result.data
                lifecycleScope.launch(Dispatchers.IO) {
                    val ready = ensureRealtimeSessionReady(sid, reason = "screen_capture_result")
                    runOnUiThread {
                        if (!ready || currentSessionId != sid) {
                            shareRequestFromCommand = false
                            setSystemMessageFromLauncher?.invoke(
                                "Sessao ainda sincronizando. Inicie o compartilhamento novamente."
                            )
                            return@runOnUiThread
                        }
                        val serviceIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                            action = ScreenCaptureService.ACTION_START
                            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
                            putExtra(ScreenCaptureService.EXTRA_ROOM_CODE, sid)
                        }
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                        val origin = if (shareRequestFromCommand) "tech" else "client"
                        updateSharingState(active = true, origin = origin)
                        shareRequestFromCommand = false
                    }
                }
            } else {
                setSystemMessageFromLauncher?.invoke("Permissao de captura negada.")
                shareRequestFromCommand = false
            }
        }

    private fun stopScreenShare(fromCommand: Boolean = false, originOverride: String? = null) {
        requestScreenCaptureServiceStop("parar compartilhamento")
        val origin = originOverride ?: if (fromCommand) "tech" else "client"
        updateSharingState(active = false, origin = origin)
    }

    private fun requestScreenCaptureServiceStop(reason: String) {
        val stop = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        if (isScreenCaptureServiceRunning()) {
            runCatching { startService(stop) }
                .onFailure { err ->
                    Log.w("SXS/Main", "Falha ao enviar stop da captura ($reason)", err)
                    runCatching { stopService(stop) }
                        .onFailure { stopErr ->
                            Log.w("SXS/Main", "Falha ao parar servico de captura ($reason)", stopErr)
                        }
                }
        } else {
            runCatching { stopService(stop) }
                .onFailure { err ->
                    Log.w("SXS/Main", "Falha ao parar captura inativa ($reason)", err)
                }
        }
    }

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingAudioAction = onGranted
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val appUpdateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            appUpdatePromptVisible = false
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(
                    this,
                    "Atualizacao nao concluida. Abra a Play Store para atualizar o Suporte X.",
                    Toast.LENGTH_LONG
                ).show()
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

    private fun checkForPlayStoreUpdate() {
        if (!::appUpdateManager.isInitialized || appUpdateCheckInProgress) return
        appUpdateCheckInProgress = true
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                appUpdateCheckInProgress = false

                val updateInProgress =
                    appUpdateInfo.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if (updateInProgress && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    startPlayStoreUpdate(appUpdateInfo, AppUpdateType.IMMEDIATE)
                    return@addOnSuccessListener
                }

                if (appUpdatePromptShownThisLaunch) return@addOnSuccessListener
                if (appUpdateInfo.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                    return@addOnSuccessListener
                }

                val updateType = when {
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                    else -> null
                } ?: return@addOnSuccessListener

                appUpdatePromptShownThisLaunch = true
                showPlayStoreUpdatePrompt(appUpdateInfo, updateType)
            }
            .addOnFailureListener { err ->
                appUpdateCheckInProgress = false
                Log.w("SXS/Main", "Falha ao verificar atualizacao pela Play Store", err)
            }
    }

    private fun showPlayStoreUpdatePrompt(appUpdateInfo: AppUpdateInfo, updateType: Int) {
        if (isFinishing || isDestroyed || appUpdatePromptVisible) return
        appUpdatePromptVisible = true
        val mandatoryUpdate = updateType == AppUpdateType.IMMEDIATE
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(!mandatoryUpdate)
            setCanceledOnTouchOutside(!mandatoryUpdate)
        }

        val dialogContent = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(0xFFFFCB19),
                        onPrimary = Color(0xFF111111),
                        surface = Color.White,
                        background = Color(0xFFF4F6F8),
                        outline = Color(0xFFE1E3E7)
                    ),
                    typography = Typography()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayStoreUpdatePromptCard(
                            mandatoryUpdate = mandatoryUpdate,
                            onLater = { dialog.dismiss() },
                            onUpdate = {
                                dialog.dismiss()
                                startPlayStoreUpdate(appUpdateInfo, updateType)
                            }
                        )
                    }
                }
            }
        }

        dialog.setContentView(
            dialogContent,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.setOnDismissListener {
            if (appUpdateDialog === dialog) appUpdateDialog = null
            appUpdatePromptVisible = false
        }
        appUpdateDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(0.62f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun startPlayStoreUpdate(appUpdateInfo: AppUpdateInfo, updateType: Int) {
        appUpdatePromptVisible = false
        runCatching {
            appUpdateManager.startUpdateFlowForResult(
                appUpdateInfo,
                appUpdateLauncher,
                AppUpdateOptions.newBuilder(updateType).build()
            )
        }.onFailure { err ->
            Log.w("SXS/Main", "Falha ao iniciar atualizacao pela Play Store", err)
            openPlayStoreListing()
        }
    }

    private fun openPlayStoreListing() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            "market://details?id=$packageName".toUri()
        ).apply {
            setPackage("com.android.vending")
        }
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$packageName".toUri()
        )
        runCatching { startActivity(marketIntent) }
            .recoverCatching { startActivity(webIntent) }
            .onFailure { err ->
                Log.w("SXS/Main", "Falha ao abrir pagina do app na Play Store", err)
                Toast.makeText(this, "Abra a Play Store para avaliar ou atualizar o Suporte X.", Toast.LENGTH_LONG).show()
            }
    }

    private fun shareAppListing() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Baixe o Suporte X: https://play.google.com/store/apps/details?id=$packageName"
            )
        }
        startActivity(Intent.createChooser(shareIntent, "Compartilhar Suporte X"))
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun registerNotificationTokenForClient(clientId: String?) {
        if (isAccountIdentityWorkBlocked()) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (isAccountIdentityWorkBlocked()) return@addOnSuccessListener
                lifecycleScope.launch(Dispatchers.IO) {
                    if (isAccountIdentityWorkBlocked()) return@launch
                    runCatching {
                        clientNotificationRepository.registerDevice(
                            context = applicationContext,
                            fcmToken = token,
                            clientId = clientId,
                            deviceAnchor = deviceAnchor()
                        )
                    }.onFailure { err ->
                        Log.w("SXS/Notifications", "Falha ao registrar token FCM", err)
                    }
                }
            }
            .addOnFailureListener { err ->
                Log.w("SXS/Notifications", "Falha ao obter token FCM", err)
            }
    }

    private fun recordsToNotificationUiState(records: List<ClientNotificationRecord>): NotificationCenterUiState {
        val visible = records.filter { !it.dismissed && it.status != "dismissed" }
        return NotificationCenterUiState(
            hasNotifications = visible.isNotEmpty(),
            unreadCount = visible.count { !it.read }.coerceAtLeast(0),
            notifications = visible.map { record ->
                ClientNotificationUi(
                    id = record.id,
                    title = record.title,
                    description = record.body,
                    badgeLabel = notificationBadgeLabel(record),
                    actionLabel = record.actionLabel,
                    type = notificationTypeFor(record),
                    isRead = record.read,
                    actionType = record.actionType,
                    canDismiss = record.priority != "critical"
                )
            }
        )
    }

    private fun notificationBadgeLabel(record: ClientNotificationRecord): String? {
        if (!record.read) return "Novo"
        return when (record.type) {
            "CREDIT_ADDED",
            "CREDIT_AVAILABLE",
            "FIRST_FREE_AVAILABLE" -> "Crédito"
            "LOW_CREDITS",
            "NO_CREDITS" -> "Aviso"
            "APP_UPDATE",
            "APP_UPDATE_REQUIRED" -> "Atualização"
            "SECURITY_NOTICE" -> "Segurança"
            else -> null
        }
    }

    private fun notificationTypeFor(record: ClientNotificationRecord): ClientNotificationType {
        return when (record.type) {
            "REVIEW_APP" -> ClientNotificationType.REVIEW
            "SHARE_APP" -> ClientNotificationType.SHARE
            "CREDIT_ADDED",
            "CREDIT_AVAILABLE",
            "FIRST_FREE_AVAILABLE" -> ClientNotificationType.CREDIT_REWARD
            "LOW_CREDITS",
            "NO_CREDITS" -> ClientNotificationType.LOW_CREDITS
            "SECURITY_NOTICE",
            "VERIFY_PHONE",
            "COMPLETE_PROFILE" -> ClientNotificationType.SECURITY_NOTICE
            else -> if (record.priority == "critical" || record.priority == "high") {
                ClientNotificationType.WARNING
            } else {
                ClientNotificationType.INFO
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return AccessibilityUtils.isServiceEnabled(this, RemoteControlService::class.java)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars(backgroundArgb: Int) {
        window.statusBarColor = backgroundArgb
        window.navigationBarColor = backgroundArgb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountDeletionRestartPending =
            savedInstanceState?.getBoolean(KEY_ACCOUNT_DELETION_RESTART_PENDING) == true
        FirebaseAuth.getInstance().addAuthStateListener(accountDeletionAuthStateListener)
        registerWaitingCancellationReceiver()
        handleLaunchIntent(intent)
        val appBackgroundArgb = Color(0xFFF4F6F8).toArgb()
        configureSystemBars(appBackgroundArgb)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        if (!isAccountIdentityWorkBlocked()) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!isAccountIdentityWorkBlocked()) {
                    runCatching { clientSupportRepository.seedDefaultPackagesIfNeeded() }
                }
            }
        }
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        appUpdateManager = AppUpdateManagerFactory.create(this)

        voiceCallManager = VoiceCallManager(
            context = this,
            scope = lifecycleScope,
            onUpdate = ::handleCallUpdate
        )

        connectSocket() // conecta o Socket.IO assim que abrir o app
        readPendingSupportSessionFromPrefs()?.let { localSupportSessionId ->
            supportRequestCorrelation.begin(localSupportSessionId)
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
                var bootstrapCycle by remember { mutableIntStateOf(0) }
                var hasHandledFirstOnStart by remember { mutableStateOf(false) }
                var skipInitialHomeRefresh by remember { mutableStateOf(true) }
                var showAccountDeletionDialog by remember { mutableStateOf(false) }
                var accountDeletionConfirmation by remember { mutableStateOf("") }
                var accountDeletionInProgress by remember { mutableStateOf(false) }
                var accountDeletionFeedback by remember { mutableStateOf<String?>(null) }
                var accountDeletedAwaitingRestart by rememberSaveable {
                    mutableStateOf(accountDeletionRestartPending)
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                val currentScreen by rememberUpdatedState(current)
                val accountDeletedAwaitingRestartState by rememberUpdatedState(
                    accountDeletedAwaitingRestart
                )
                val homeAverageWaitLabel = formatHomeAverageWaitLabel(supportQueueWaitStats)
                val waitingAverageWaitLabel = formatWaitingAverageWaitLabel(supportQueueWaitStats)
                var notificationCenterUiState by remember { mutableStateOf(NotificationCenterUiState.Empty) }

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
                    if (isAccountIdentityWorkBlocked() || accountDeletedAwaitingRestart) return
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
                        if (
                            isAccountIdentityWorkBlocked() ||
                            accountDeletedAwaitingRestart ||
                            cycle != bootstrapCycle
                        ) {
                            return@loadHomeSnapshot
                        }
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
                        if (
                            isAccountIdentityWorkBlocked() ||
                            accountDeletedAwaitingRestart ||
                            cycle != bootstrapCycle
                        ) {
                            return@loadSupportQueueWaitStats
                        }
                        supportQueueWaitStats = stats
                        bootstrapQueueLoaded = true
                        finishStartupLoadingIfReady()
                    }

                    evaluateSupportEntry { decision ->
                        if (
                            isAccountIdentityWorkBlocked() ||
                            accountDeletedAwaitingRestart ||
                            cycle != bootstrapCycle
                        ) {
                            return@evaluateSupportEntry
                        }
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

                fun markNotificationAsRead(notification: ClientNotificationUi) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { clientNotificationRepository.markAsRead(notification.id) }
                            .onFailure { err -> Log.w("SXS/Notifications", "Falha ao marcar notificação como lida", err) }
                    }
                }

                fun dismissClientNotification(notification: ClientNotificationUi) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { clientNotificationRepository.dismiss(notification.id) }
                            .onFailure { err -> Log.w("SXS/Notifications", "Falha ao dispensar notificação", err) }
                    }
                }

                fun handleClientNotificationAction(notification: ClientNotificationUi) {
                    val actionType = notification.actionType.trim().uppercase()
                    if (actionType != "DISMISS") {
                        markNotificationAsRead(notification)
                    }
                    when (actionType) {
                        "REQUEST_SUPPORT" -> {
                            val cachedDecision = preloadedSupportDecision
                            if (cachedDecision != null) {
                                handleSupportAccessDecision(cachedDecision)
                            } else {
                                evaluateSupportEntry { decision ->
                                    preloadedSupportDecision = decision
                                    handleSupportAccessDecision(decision)
                                }
                            }
                        }
                        "OPEN_CREDITS" -> {
                            if (selectedPackage == null) {
                                selectedPackage = homeSnapshot.packages.firstOrNull()
                            }
                            current = Screen.PURCHASE_CREDITS
                        }
                        "OPEN_PLAY_STORE",
                        "REVIEW",
                        "REVIEW_APP" -> {
                            openPlayStoreListing()
                        }
                        "OPEN_SHARE_SHEET" -> shareAppListing()
                        "OPEN_SECURITY_SETTINGS" -> {
                            showAccessibilityDisclosure(grantForCurrentSession = false) {
                                openAccessibilitySettings()
                            }
                        }
                        "OPEN_PERMISSIONS" -> openAppPermissionSettings()
                        "DISMISS" -> dismissClientNotification(notification)
                        "MARK_AS_READ",
                        "OPEN_NOTIFICATIONS",
                        "NONE",
                        "" -> Unit
                        else -> Unit
                    }
                }

                fun accountDeletionLocalBlockMessage(): String? {
                    val hasActiveSession =
                        !currentSessionId.isNullOrBlank() ||
                            !sessionId.isNullOrBlank() ||
                            current == Screen.SESSION
                    if (hasActiveSession) {
                        return "Encerre o atendimento atual antes de excluir sua conta."
                    }

                    val hasWaitingRequest =
                        !pendingSupportSessionId.isNullOrBlank() ||
                            !activeSupportRequestId.isNullOrBlank() ||
                            !requestId.isNullOrBlank() ||
                            supportRequestCorrelation.currentToken() != null ||
                            current == Screen.WAITING
                    if (hasWaitingRequest) {
                        return "Cancele a solicitação de suporte em espera antes de excluir sua conta."
                    }
                    return null
                }

                fun openAccountDeletionConfirmation() {
                    accountDeletionConfirmation = ""
                    accountDeletionFeedback = accountDeletionLocalBlockMessage()
                    showAccountDeletionDialog = true
                }

                fun confirmAccountDeletion() {
                    if (accountDeletionInProgress) return

                    val localBlockMessage = accountDeletionLocalBlockMessage()
                    if (localBlockMessage != null) {
                        accountDeletionFeedback = localBlockMessage
                        return
                    }
                    if (accountDeletionConfirmation != ACCOUNT_DELETION_CONFIRMATION) {
                        accountDeletionFeedback =
                            "Digite exatamente $ACCOUNT_DELETION_CONFIRMATION para confirmar."
                        return
                    }

                    accountDeletionInProgress = true
                    accountDeletionFeedback = null
                    accountDeletionOperationInProgress = true
                    val idempotencyKey = UUID.randomUUID().toString()

                    lifecycleScope.launch {
                        try {
                            var result = accountDeletionRepository.deleteAccount(
                                idempotencyKey = idempotencyKey
                            )

                            if (result == AccountDeletionResult.PnvRequired) {
                                val supportInfo = runCatching {
                                    phoneIdentityProvider.checkPnvSupport()
                                }.getOrNull()
                                if (supportInfo?.hasSupportedSim != true) {
                                    accountDeletionFeedback =
                                        "A verificação automática exigida para excluir a conta não está disponível neste aparelho ou operadora. Use o canal oficial informado nesta tela."
                                    return@launch
                                }

                                when (
                                    val verification =
                                        phoneIdentityProvider.verifyWithPnv(this@MainActivity)
                                ) {
                                    is PhonePnvVerificationResult.Success -> {
                                        result = accountDeletionRepository.deleteAccount(
                                            idempotencyKey = idempotencyKey,
                                            pnvPhone = verification.phoneNumber,
                                            pnvToken = verification.token
                                        )
                                    }

                                    is PhonePnvVerificationResult.Failure -> {
                                        accountDeletionFeedback = if (verification.userCancelled) {
                                            "Verificação cancelada. Nenhum dado foi excluído."
                                        } else {
                                            "Não foi possível confirmar o número agora. Nenhum dado foi excluído; tente novamente."
                                        }
                                        return@launch
                                    }
                                }
                            }

                            when (val finalResult = result) {
                                is AccountDeletionResult.Success -> {
                                    accountDeletionRestartPending = true
                                    accountDeletedAwaitingRestart = true
                                    bootstrapCycle += 1
                                    runCatching { clearLocalStateAfterAccountDeletion() }

                                    requestId = null
                                    sessionId = null
                                    isSharing = false
                                    remoteEnabled = false
                                    calling = false
                                    callConnected = false
                                    callState = CallState.IDLE
                                    callDirection = null
                                    techName = "T\u00e9cnico"
                                    systemMessage = null
                                    isRecordingAudio = false
                                    endedSessionId = null
                                    homeSnapshot = ClientHomeSnapshot(
                                        clientUid = null,
                                        phone = null,
                                        client = null,
                                        clientMeta = null,
                                        verification = null,
                                        packages = SupportBillingConfig.defaultCreditPackages
                                    )
                                    supportQueueWaitStats = null
                                    selectedPackage = null
                                    autoOpenedPurchaseClientId = null
                                    preloadedSupportDecision = null
                                    notificationCenterUiState = NotificationCenterUiState.Empty
                                    accountDeletionConfirmation = ""
                                    accountDeletionFeedback = null
                                    showAccountDeletionDialog = false
                                }

                                AccountDeletionResult.InvalidPnv -> {
                                    accountDeletionFeedback =
                                        "A verificação do número expirou ou não foi aceita. Nenhum dado foi excluído; tente novamente."
                                }

                                AccountDeletionResult.ActiveSupport -> {
                                    accountDeletionFeedback =
                                        "Encerre o atendimento atual antes de excluir sua conta."
                                }

                                AccountDeletionResult.InProgress -> {
                                    accountDeletionFeedback =
                                        "A exclusão desta conta já está sendo processada. Aguarde e tente novamente."
                                }

                                AccountDeletionResult.PnvRequired -> {
                                    accountDeletionFeedback =
                                        "Não foi possível validar o número para excluir a conta. Tente novamente."
                                }

                                is AccountDeletionResult.RecoverableFailure -> {
                                    accountDeletionFeedback = when (finalResult.reason) {
                                        AccountDeletionFailureReason.AUTHENTICATION ->
                                            "Não foi possível confirmar sua sessão. Feche e abra o app antes de tentar novamente."

                                        AccountDeletionFailureReason.RATE_LIMITED ->
                                            "Foram feitas muitas tentativas. Aguarde alguns minutos e tente novamente."

                                        AccountDeletionFailureReason.TRANSPORT,
                                        AccountDeletionFailureReason.TRANSIENT_HTTP ->
                                            "Não foi possível concluir agora. Verifique sua conexão e tente novamente."

                                        AccountDeletionFailureReason.INVALID_REQUEST,
                                        AccountDeletionFailureReason.INVALID_RESPONSE,
                                        AccountDeletionFailureReason.SERVER_REJECTED ->
                                            "Não foi possível excluir a conta agora. Nenhum dado foi alterado; tente novamente mais tarde."
                                    }
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            accountDeletionFeedback =
                                "Não foi possível excluir a conta agora. Nenhum dado foi alterado; tente novamente mais tarde."
                        } finally {
                            accountDeletionInProgress = false
                            accountDeletionOperationInProgress = false
                        }
                    }
                }

                fun restartAfterAccountDeletion() {
                    if (!accountDeletedAwaitingRestart) return

                    pendingLaunchSessionFromNotification = null
                    pendingLaunchFeedbackSessionId = null
                    pendingLaunchAcceptedSessionId = null
                    pendingLaunchAcceptedTechName = null
                    pendingLaunchAcceptedLocalSupportSessionId = null
                    accountDeletionRestartPending = false
                    accountDeletedAwaitingRestart = false
                    showAccountDeletionDialog = false
                    accountDeletionConfirmation = ""
                    accountDeletionFeedback = null
                    preloadedSupportDecision = null
                    skipInitialHomeRefresh = true
                    current = Screen.HOME
                    startBootstrap(showAnimation = true)
                    connectSocket()
                }

                BackHandler {
                    if (accountDeletionInProgress || accountDeletedAwaitingRestart) {
                        return@BackHandler
                    }
                    when (current) {
                        Screen.HELP -> current = Screen.HOME
                        Screen.PRIVACY -> current = Screen.HOME
                        Screen.TERMS -> current = Screen.HOME
                        Screen.PURCHASE_CREDITS -> current = Screen.HOME
                        Screen.PAYMENT_CARD -> current = Screen.PURCHASE_CREDITS
                        Screen.PAYMENT_PIX -> current = Screen.PURCHASE_CREDITS
                        Screen.WAITING -> {
                            val activeRequestId = requestId
                            if (activeRequestId != null) {
                                cancelRequest(activeRequestId)
                            } else {
                                val localSupportId = pendingSupportSessionId
                                invalidateSupportRequestTracking(cancelJob = true)
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
                    setIsSharingFromLauncher = {
                        if (!isAccountIdentityWorkBlocked()) isSharing = it
                    }
                    setSystemMessageFromLauncher = { msg ->
                        if (!isAccountIdentityWorkBlocked()) systemMessage = msg
                    }
                    // Bridges Socket -> Compose
                    setRequestIdFromSocket = { req ->
                        if (!isAccountIdentityWorkBlocked()) requestId = req
                    }
                    setSessionIdFromSocket = { sid ->
                        if (!isAccountIdentityWorkBlocked()) {
                            sessionId = sid
                            currentSessionId = sid
                            voiceCallManager.bindSession(sid)
                        }
                    }
                    setScreenFromSocket = { scr ->
                        if (!isAccountIdentityWorkBlocked()) current = scr
                    }
                    setRemoteEnabledFromSocket = {
                        if (!isAccountIdentityWorkBlocked()) remoteEnabled = it
                    }
                    setCallingFromSocket = {
                        if (!isAccountIdentityWorkBlocked()) calling = it
                    }
                    setCallConnectedFromSocket = {
                        if (!isAccountIdentityWorkBlocked()) callConnected = it
                    }
                    setCallStateFromManager = {
                        if (!isAccountIdentityWorkBlocked()) callState = it
                    }
                    setCallDirectionFromManager = {
                        if (!isAccountIdentityWorkBlocked()) callDirection = it
                    }
                    setTechNameFromSocket = { name ->
                        if (!isAccountIdentityWorkBlocked()) {
                            techName = name.ifBlank { "T\u00e9cnico" }
                        }
                    }
                    setRecordingAudioFromActivity = {
                        if (!isAccountIdentityWorkBlocked()) isRecordingAudio = it
                    }
                    setEndedSessionForFeedback = {
                        if (!isAccountIdentityWorkBlocked()) endedSessionId = it
                    }
                    applyPendingLaunchIntentNavigation()
                    recoverPersistedActiveSupportSession()
                    startBootstrap(showAnimation = true)
                }

                LaunchedEffect(
                    homeSnapshot.clientUid,
                    homeSnapshot.client?.id,
                    accountDeletedAwaitingRestart
                ) {
                    if (!accountDeletedAwaitingRestart) {
                        registerNotificationTokenForClient(homeSnapshot.client?.id)
                    }
                }

                DisposableEffect(
                    homeSnapshot.clientUid,
                    homeSnapshot.client?.id,
                    accountDeletedAwaitingRestart
                ) {
                    if (accountDeletedAwaitingRestart) {
                        clientNotificationRegistration?.remove()
                        clientNotificationRegistration = null
                        onDispose { }
                    } else {
                        val registration = clientNotificationRepository.listenClientNotifications(
                            clientUid = homeSnapshot.clientUid,
                            clientId = homeSnapshot.client?.id,
                            onChanged = { records ->
                                if (!isAccountIdentityWorkBlocked()) {
                                    notificationCenterUiState =
                                        recordsToNotificationUiState(records)
                                }
                            },
                            onError = { err ->
                                Log.w("SXS/Notifications", "Listener de notificações falhou", err)
                            }
                        )
                        clientNotificationRegistration = registration
                        onDispose {
                            registration.remove()
                            if (clientNotificationRegistration === registration) {
                                clientNotificationRegistration = null
                            }
                        }
                    }
                }

                LaunchedEffect(current) {
                    if (current == Screen.HOME && !accountDeletedAwaitingRestart) {
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
                        if (
                            currentScreen == Screen.HOME &&
                            !accountDeletedAwaitingRestartState
                        ) {
                            startBootstrap(showAnimation = true)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(current) {
                    if (
                        !accountDeletedAwaitingRestart &&
                        (current == Screen.HOME || current == Screen.WAITING)
                    ) {
                        while (isActive) {
                            loadSupportQueueWaitStats { stats ->
                                supportQueueWaitStats = stats
                            }
                            delay(20.seconds)
                        }
                    }
                }

                LaunchedEffect(current, homeSnapshot.client?.id, homeSnapshot.shouldAutoOpenPurchase) {
                    val currentClientId = homeSnapshot.client?.id
                    if (
                        current == Screen.HOME &&
                        !accountDeletedAwaitingRestart &&
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

                Surface(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
                            averageWaitLabel = homeAverageWaitLabel,
                            notificationState = notificationCenterUiState,
                            onNotificationAction = { handleClientNotificationAction(it) },
                            onNotificationDismiss = { dismissClientNotification(it) }
                        )

                        Screen.HELP -> HelpScreen(
                            onClose = { current = Screen.HOME },
                            onOpenPlayStore = { openPlayStoreListing() },
                            textMuted = Color(0xFF8A8A8E)
                        )

                        Screen.PRIVACY -> PrivacyPolicyScreen(
                            onClose = {
                                if (!accountDeletionInProgress && !accountDeletedAwaitingRestart) {
                                    current = Screen.HOME
                                }
                            },
                            onRequestAccountDeletion = { openAccountDeletionConfirmation() },
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
                                val activeRequestId = requestId
                                if (activeRequestId != null) {
                                    cancelRequest(activeRequestId)
                                } else {
                                    val localSupportId = pendingSupportSessionId
                                    invalidateSupportRequestTracking(cancelJob = true)
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
                                    toggleAudioRecording()
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
                                    submitCustomerSatisfaction(targetSessionId, score) {
                                        googlePlayReviewPrompt.onInternalRatingSubmitted(this@MainActivity)
                                    }
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

                        if (showAccountDeletionDialog) {
                            AccountDeletionConfirmationDialog(
                                confirmation = accountDeletionConfirmation,
                                blockingMessage = accountDeletionLocalBlockMessage(),
                                feedbackMessage = accountDeletionFeedback,
                                inProgress = accountDeletionInProgress,
                                onConfirmationChange = { value ->
                                    accountDeletionConfirmation = value
                                    accountDeletionFeedback = null
                                },
                                onDismiss = {
                                    if (!accountDeletionInProgress) {
                                        showAccountDeletionDialog = false
                                        accountDeletionConfirmation = ""
                                        accountDeletionFeedback = null
                                    }
                                },
                                onConfirm = { confirmAccountDeletion() }
                            )
                        }

                        if (accountDeletedAwaitingRestart) {
                            AccountDeletionSuccessDialog(
                                onReturnToStart = { restartAfterAccountDeletion() }
                            )
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
        reconcileRemoteAccessAfterResume()
        if (!currentSessionId.isNullOrBlank()) {
            clearTransientSupportNotifications(currentSessionId)
        }
        maybePromptInitialCriticalPermissions()
        checkForPlayStoreUpdate()
        val incomingFromTech = currentCallUiState == CallState.INCOMING_RINGING &&
            currentCallDirection == CallDirection.TECH_TO_CLIENT
        if (incomingFromTech) {
            cancelIncomingCallNotification(currentSessionId)
            startIncomingCallAlert()
        }
    }

    override fun onStop() {
        discardAudioRecording()
        val incomingFromTech = currentCallUiState == CallState.INCOMING_RINGING &&
            currentCallDirection == CallDirection.TECH_TO_CLIENT
        val sessionId = currentSessionId
        if (incomingFromTech && !sessionId.isNullOrBlank()) {
            notifyIncomingCallFromTech(sessionId)
        } else {
            stopIncomingCallAlert()
        }
        appInForeground = false
        syncWaitingSupportForegroundService()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAccountIdentityWorkBlocked()) return
        handleLaunchIntent(intent)
        applyPendingLaunchIntentNavigation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            KEY_ACCOUNT_DELETION_RESTART_PENDING,
            accountDeletionRestartPending
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        FirebaseAuth.getInstance().removeAuthStateListener(accountDeletionAuthStateListener)
        clientNotificationRegistration?.remove()
        clientNotificationRegistration = null
        unregisterWaitingCancellationReceiver()
        invalidateSupportRequestTracking(cancelJob = true)
        discardAudioRecording()
        stopIncomingCallAlert()
        appUpdateDialog?.dismiss()
        appUpdateDialog = null
        stopWaitingSessionRecovery()
        stopTelemetryLoop()
        voiceCallManager.release()
        super.onDestroy()
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
    onOpenPlayStore: () -> Unit,
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
                body = "Toque em SOLICITAR SUPORTE e aguarde a conexão com um técnico. Quando o atendimento for aceito, a sessão será aberta automaticamente. Se o aplicativo for fechado ou reiniciado durante a espera ou o atendimento, o Suporte X tentará retomar o estado confirmado pelo servidor."
            )
            PolicySection(
                title = "2. Crédito e liberação de atendimento",
                body = "O primeiro atendimento pode ser disponibilizado gratuitamente, conforme o status da conta. Depois disso, novos atendimentos dependem de créditos disponíveis. O saldo e a regra aplicável aparecem na área de créditos da tela inicial."
            )
            PolicySection(
                title = "3. Compra de créditos",
                body = "Na tela inicial, abra a área de créditos, escolha um pacote disponível e siga o canal oficial apresentado. Confira pacote, quantidade, valor e forma de pagamento antes de confirmar a solicitação."
            )
            PolicySection(
                title = "4. Chat, arquivos e chamada",
                body = "Na sessão, você pode conversar por texto, enviar imagens ou arquivos e trocar mensagens de áudio. A chamada de voz usa o microfone somente depois da sua ação para iniciar ou aceitar a chamada e pode ser encerrada no próprio aplicativo."
            )
            PolicySection(
                title = "5. Compartilhamento e acesso remoto",
                body = "O compartilhamento de tela exige a confirmação do Android. O acesso remoto assistido é separado, opcional e exige sua concordância antes de abrir as configurações de Acessibilidade. Você pode interromper o compartilhamento, desligar o acesso remoto ou revogar a permissão a qualquer momento."
            )
            PolicySection(
                title = "6. Segurança e privacidade",
                body = "Autorize recursos sensíveis somente durante um atendimento solicitado por você e confira o técnico identificado na sessão. Evite abrir senhas, códigos bancários ou outras informações desnecessárias enquanto compartilha a tela. O Suporte X nunca deve pedir que você revele senhas ou códigos de verificação."
            )
            PolicySection(
                title = "7. Notificações e retomada",
                body = "O sino da tela inicial reúne avisos do Suporte X. As notificações do Android ajudam a acompanhar a fila, o aceite do técnico, mensagens e chamadas quando o app não está visível. Se uma sessão não reaparecer após a reconexão, feche e abra o aplicativo com internet ativa."
            )
            PolicySection(
                title = "8. Encerrar e avaliar o atendimento",
                body = "Use ENCERRAR SUPORTE na tela da sessão quando desejar finalizar. Ao encerrar, o aplicativo interrompe os recursos ativos e pode solicitar sua avaliação. Se o Serviço de Acessibilidade continuar ativado no Android, desative-o nas Configurações do dispositivo."
            )
            PolicySection(
                title = "9. Problemas comuns",
                body = "Se a conexão estiver instável, verifique internet, bateria, armazenamento e permissões do app. Para chamada, confira o microfone. Para compartilhamento, confirme a tela inteira quando o Android solicitar. Para acesso remoto, confira se o Serviço de Acessibilidade do Suporte X está ativado e volte ao aplicativo."
            )
            PolicySection(
                title = "10. Conta, dados e canais oficiais",
                body = "Para consultar a política ou excluir sua conta e seus dados, abra Privacidade na tela inicial. Para suporte administrativo ou dúvidas, use suportex@xavierassessoriadigital.com.br ou o WhatsApp +55 65 99649-7550."
            )
            Spacer(Modifier.height(18.dp))
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onOpenPlayStore,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFFFCB19)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF2F3033)
            )
        ) {
            Text(
                text = "Avaliar Suporte X na Google Play",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PrivacyPolicyScreen(
    onClose: () -> Unit,
    onRequestAccountDeletion: () -> Unit,
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
        Text("Última atualização: 23 de julho de 2026", color = textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "O Suporte X é operado por Xavier Assessoria Digital, CNPJ 45.765.097/0001-61, responsável pelo tratamento descrito nesta Política de Privacidade. Este documento explica como os dados são acessados, coletados, usados, compartilhados, armazenados e protegidos durante o uso do aplicativo e do suporte técnico remoto.",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            PolicySection(
                title = "1. Dados que coletamos",
                body = "Conforme os recursos usados, podemos tratar:\n\n- Identificadores de conta, dispositivo, solicitação e sessão\n- Nome, telefone verificado e dados informados durante o atendimento\n- Marca, modelo, versão do Android, rede, bateria, armazenamento e status de permissões\n- Token de notificações e eventos necessários para avisos de fila e atendimento\n- Saldo de créditos, histórico de uso e solicitações de compra\n- Mensagens, imagens, anexos e mensagens de áudio enviados no chat\n- Registros de segurança, diagnóstico, fila, chamadas e ações da sessão\n\nO compartilhamento de tela e a chamada de voz são transmitidos em tempo real e não são gravados por padrão. Mensagens de áudio enviadas no chat são arquivos do atendimento."
            )
            PolicySection(
                title = "2. Finalidades e bases legais",
                body = "Os dados são tratados para autenticar o usuário; prestar o suporte solicitado; manter fila, chat, chamada, compartilhamento e controle assistido; administrar créditos e solicitações de compra; enviar avisos operacionais; prevenir fraude e abuso; diagnosticar falhas; cumprir obrigações legais; e responder ao titular.\n\nConforme o caso, o tratamento se apoia na execução do serviço ou de procedimentos solicitados pelo usuário, no consentimento quando exigido, no legítimo interesse para segurança e melhoria com proteção dos direitos do titular, no cumprimento de obrigação legal e no exercício regular de direitos."
            )
            PolicySection(
                title = "3. Compartilhamento e operadores",
                body = "Não vendemos, alugamos nem comercializamos dados pessoais. Usamos prestadores necessários à operação, incluindo Google Firebase para autenticação, banco de dados, armazenamento e notificações; Render para hospedagem do backend; Cloudflare para segurança e conectividade; e, quando o usuário escolhe esses canais, Meta/WhatsApp e o provedor de e-mail.\n\nTécnicos acessam somente os dados necessários ao atendimento sob sua responsabilidade. Autoridades podem receber informações diante de obrigação legal válida. Alguns prestadores podem processar dados fora do Brasil, com medidas contratuais e de segurança compatíveis com a legislação aplicável."
            )
            PolicySection(
                title = "4. Permissões, tela e Acessibilidade",
                body = "O compartilhamento de tela só começa após a confirmação do Android. O microfone é usado em chamadas ou mensagens de áudio iniciadas pelo usuário. O envio de arquivos ocorre somente após a escolha do conteúdo.\n\nO Serviço de Acessibilidade é opcional e serve exclusivamente para o controle remoto assistido em uma sessão iniciada pelo usuário. Quando autorizado, ele pode permitir ao técnico designado ler o conteúdo exibido e as janelas do dispositivo para localizar campos e controles, digitar ou editar texto, executar toques e gestos e acionar Voltar, Início e Recentes.\n\nComandos remotos são bloqueados fora de sessão ativa e sem autorização no app. O recurso não é usado para anúncios ou monitoramento oculto. O usuário pode interromper o compartilhamento, revogar o controle ou desativar o serviço nas configurações do Android a qualquer momento."
            )
            PolicySection(
                title = "5. Armazenamento e retenção de dados",
                body = "Tentativas técnicas de verificação têm prazo previsto de até 15 dias. Registros finais de fila, sessões encerradas, mensagens, mídias e relatórios operacionais têm prazo previsto de até 30 dias após o encerramento, aplicado por rotinas periódicas de limpeza. Perfil e saldo permanecem enquanto a conta estiver ativa.\n\nAlguns registros podem ser conservados pelo prazo necessário para obrigação legal, prevenção de fraude, segurança ou exercício regular de direitos. Cópias de segurança podem levar um ciclo técnico adicional para expirar e não são reutilizadas na operação normal."
            )
            PolicySection(
                title = "6. Direitos e exclusão",
                body = "Nos limites da Lei Geral de Proteção de Dados (Lei nº 13.709/2018), o usuário pode solicitar confirmação e acesso; correção; anonimização, bloqueio ou eliminação de dados desnecessários, excessivos ou irregulares; portabilidade, quando aplicável; informação sobre compartilhamento; revogação de consentimento; e revisão de decisões automatizadas, quando houver.\n\nA exclusão pode ser iniciada pelo botão abaixo ou em https://suportex.app/excluir-conta. Ao confirmar, apagamos ou desvinculamos perfil, vínculos, notificações, filas, mensagens, mídias e demais dados pessoais associados, ressalvadas as retenções exigidas por lei ou necessárias ao exercício regular de direitos."
            )
            PolicySection(
                title = "7. Segurança das informações",
                body = "Adotamos autenticação, controle de acesso por usuário e sessão, conexões criptografadas, validação no servidor, limitação de arquivos, registros de segurança e revisões técnicas. Nenhum sistema elimina todo risco; por isso, os controles são monitorados e atualizados continuamente."
            )
            PolicySection(
                title = "8. Contato e alterações",
                body = "Responsável: Xavier Assessoria Digital, CNPJ 45.765.097/0001-61.\n\nE-mail: suportex@xavierassessoriadigital.com.br\nWhatsApp: +55 65 99649-7550\n\nEsta política pode ser atualizada quando o serviço, os operadores ou a legislação mudarem. A data exibida no início identifica a versão vigente."
            )
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFFE1E3E7))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRequestAccountDeletion,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Excluir minha conta",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AccountDeletionConfirmationDialog(
    confirmation: String,
    blockingMessage: String?,
    feedbackMessage: String?,
    inProgress: Boolean,
    onConfirmationChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val visibleMessage = blockingMessage ?: feedbackMessage
    val canConfirm =
        blockingMessage == null &&
            confirmation == ACCOUNT_DELETION_CONFIRMATION &&
            !inProgress

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (!inProgress) onDismiss()
        },
        title = {
            Text(
                text = "Excluir conta e dados",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Esta ação é permanente. Sua conta, histórico e dados vinculados serão excluídos conforme as regras de retenção aplicáveis."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Para continuar, digite exatamente:",
                    fontSize = 13.sp
                )
                Text(
                    text = ACCOUNT_DELETION_CONFIRMATION,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !inProgress && blockingMessage == null,
                    singleLine = true,
                    label = { Text("Confirmação") }
                )

                if (inProgress) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Excluindo sua conta com segurança...")
                    }
                } else if (!visibleMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = visibleMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Excluir definitivamente", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !inProgress
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun AccountDeletionSuccessDialog(
    onReturnToStart: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = "Conta excluída",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Sua conta e os dados vinculados foram excluídos. Ao voltar ao início, o Suporte X criará uma nova identidade somente para um novo uso do aplicativo."
            )
        },
        confirmButton = {
            Button(onClick = onReturnToStart) {
                Text("Voltar ao início", fontWeight = FontWeight.Bold)
            }
        }
    )
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
        Text("Última atualização: 23 de julho de 2026", color = textMuted, fontSize = 13.sp)
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
                body = "Ao utilizar o Suporte X, o usuário declara que leu e compreendeu estes Termos de Uso e concorda com as condições aplicáveis ao serviço. Permissões sensíveis, compartilhamento de tela, microfone e acesso remoto assistido dependem de avisos e autorizações próprios no contexto de cada recurso; a aceitação destes termos não substitui esses consentimentos.\n\nCaso não concorde com estes termos, o usuário não deverá solicitar atendimento."
            )
            PolicySection(
                title = "3. Elegibilidade e acesso ao atendimento",
                body = "O usuário deve ter capacidade legal para contratar o serviço ou estar devidamente representado e fornecer informações verdadeiras quando necessárias à identificação e ao atendimento.\n\nO primeiro atendimento pode ser disponibilizado gratuitamente, conforme o status da conta. Depois disso, novos atendimentos dependem de créditos disponíveis. Sem saldo, o usuário poderá solicitar um pacote pelos canais oferecidos na plataforma."
            )
            PolicySection(
                title = "4. Funcionamento do suporte remoto",
                body = "Durante uma sessão, o usuário pode usar chat, enviar arquivos ou mensagens de áudio, iniciar ou aceitar chamada de voz e autorizar compartilhamento de tela e controle remoto assistido.\n\nCompartilhamento e controle remoto são recursos distintos. Cada um exige ação do usuário e pode ser interrompido a qualquer momento. O Serviço de Acessibilidade é usado somente para a assistência remota autorizada, conforme o aviso específico apresentado antes da ativação."
            )
            PolicySection(
                title = "5. Créditos, pacotes e pagamentos",
                body = "Pacotes, quantidade de atendimentos, preço e formas de pagamento são os exibidos ou confirmados no canal oficial antes da contratação. Créditos liberam a solicitação do atendimento, mas não garantem um resultado técnico específico.\n\nCancelamentos, estornos e reembolsos observarão as condições informadas na contratação e os direitos assegurados pela legislação de consumo. Em caso de divergência de saldo ou cobrança, o usuário deve contatar os canais oficiais."
            )
            PolicySection(
                title = "6. Responsabilidades do usuário",
                body = "O usuário é responsável por:\n\n- proteger o acesso ao dispositivo, telefone e códigos de verificação\n- conceder permissões somente quando desejar usar o respectivo recurso\n- acompanhar a sessão e interromper acessos que não queira manter\n- fazer cópia de segurança de informações importantes antes de intervenções relevantes, quando possível\n- não usar o serviço para fraude, abuso, violação de direitos ou atividade ilegal"
            )
            PolicySection(
                title = "7. Responsabilidades da operadora",
                body = "A Xavier Assessoria Digital presta o serviço com medidas razoáveis de qualidade, segurança e controle de acesso. O técnico deve atuar dentro do atendimento atribuído e das autorizações concedidas pelo usuário.\n\nO usuário deve comunicar imediatamente qualquer acesso, cobrança ou comportamento que considere indevido pelos canais oficiais."
            )
            PolicySection(
                title = "8. Disponibilidade e limites do serviço",
                body = "O diagnóstico e a solução dependem do aparelho, sistema, conexão, disponibilidade de terceiros e natureza do problema. Por isso, não é garantida a resolução de toda falha ou a disponibilidade ininterrupta da plataforma.\n\nNa medida permitida por lei, a operadora não responde por fatos fora de seu controle razoável nem por uso indevido do aplicativo pelo usuário. Esta cláusula não exclui responsabilidades legais nem direitos previstos no Código de Defesa do Consumidor."
            )
            PolicySection(
                title = "9. Privacidade e proteção de dados",
                body = "O tratamento de dados pessoais e operacionais segue a Política de Privacidade do Suporte X e a legislação aplicável, em especial a LGPD (Lei nº 13.709/2018). A política informa dados tratados, finalidades, operadores, retenção, segurança, direitos e formas de exclusão da conta."
            )
            PolicySection(
                title = "10. Suspensão e encerramento da conta",
                body = "O acesso pode ser limitado ou suspenso quando necessário para investigar fraude, abuso, risco de segurança, violação destes termos ou obrigação legal, com preservação dos direitos aplicáveis. O usuário pode solicitar a exclusão da conta pela área Privacidade do aplicativo ou em https://suportex.app/excluir-conta."
            )
            PolicySection(
                title = "11. Alterações dos termos",
                body = "Estes termos podem ser atualizados para refletir mudanças técnicas, operacionais, legais ou de segurança. A versão vigente ficará disponível no aplicativo com a respectiva data. Mudanças que exijam nova autorização não substituem os consentimentos específicos do usuário."
            )
            PolicySection(
                title = "12. Legislação, foro e contato",
                body = "O serviço é regido pelas leis da República Federativa do Brasil. Eventuais controvérsias serão tratadas no foro competente definido pela legislação, inclusive o do domicílio do consumidor quando aplicável.\n\nEmpresa responsável:\nXavier Assessoria Digital\n\nCNPJ:\n45.765.097/0001-61\n\nEndereço:\nRua dos Jequitibás, 1895W\nResidencial Paraíso\nNova Mutum - MT\nCEP: 78.454-528\n\nE-mail:\nsuportex@xavierassessoriadigital.com.br\n\nTelefone / WhatsApp:\n+55 65 99649-7550"
            )
        }
    }
}

@Composable
private fun PlayStoreUpdatePromptCard(
    mandatoryUpdate: Boolean,
    onLater: () -> Unit,
    onUpdate: () -> Unit
) {
    val brandPrimary = Color(0xFFFFCB19)
    val onPrimary = Color(0xFF111111)
    val textSecondary = Color(0xFF3F3F46)
    val outline = Color(0xFFD1D5DB)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 380.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Atualização disponível",
                color = onPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                textAlign = TextAlign.Center
            )
            Surface(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .width(56.dp)
                    .height(3.dp),
                shape = RoundedCornerShape(100.dp),
                color = brandPrimary
            ) {}
            Text(
                text = "Uma nova versão do Suporte X está pronta. Atualize agora para receber melhorias, correções e manter seu atendimento funcionando com mais segurança.",
                modifier = Modifier.padding(top = 28.dp),
                color = textSecondary,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(30.dp))
            if (mandatoryUpdate) {
                UpdatePromptPrimaryButton(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    brandPrimary = brandPrimary,
                    onPrimary = onPrimary
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 300.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            UpdatePromptSecondaryButton(
                                onClick = onLater,
                                modifier = Modifier.fillMaxWidth(),
                                onPrimary = onPrimary,
                                outline = outline
                            )
                            UpdatePromptPrimaryButton(
                                onClick = onUpdate,
                                modifier = Modifier.fillMaxWidth(),
                                brandPrimary = brandPrimary,
                                onPrimary = onPrimary
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            UpdatePromptSecondaryButton(
                                onClick = onLater,
                                modifier = Modifier.weight(1f),
                                onPrimary = onPrimary,
                                outline = outline
                            )
                            UpdatePromptPrimaryButton(
                                onClick = onUpdate,
                                modifier = Modifier.weight(1f),
                                brandPrimary = brandPrimary,
                                onPrimary = onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatePromptSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier,
    onPrimary: Color,
    outline: Color
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = onPrimary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.AccessTime,
            contentDescription = null,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Lembrar depois",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun UpdatePromptPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier,
    brandPrimary: Color,
    onPrimary: Color
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = brandPrimary,
            contentColor = onPrimary
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 5.dp,
            pressedElevation = 1.dp
        )
    ) {
        Icon(
            imageVector = Icons.Filled.FileDownload,
            contentDescription = null,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Atualizar agora",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
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
