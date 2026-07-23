package com.suportex.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.suportex.app.data.AuthRepository
import com.suportex.app.data.ClientSupportRepository
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.abs

class WaitingSupportMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientSupportRepository = ClientSupportRepository()
    private val authRepository = AuthRepository()
    private var monitorJob: Job? = null
    private var cancellationJob: Job? = null
    private var monitoredAnchorId: String? = null
    private var monitoredLocalSupportSessionId: String? = null
    private var cancellingLocalSupportSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        startWaitingForeground(
            localSupportSessionId = null,
            startedAtMillis = System.currentTimeMillis()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_WAITING) {
            handleCancelWaiting(intent)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_MONITOR) {
            writeWaitingStateToPrefs(anchorId = null, localSupportSessionId = null, startedAtMillis = null)
            stopSelfSafe()
            return START_NOT_STICKY
        }

        val prefState = readWaitingStateFromPrefs()
        val localSupportSessionId = intent?.getStringExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prefState.localSupportSessionId
        val anchorId = intent?.getStringExtra(EXTRA_WAITING_ANCHOR_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prefState.anchorId
            ?: localSupportSessionId

        if (anchorId.isNullOrBlank()) {
            stopSelfSafe()
            return START_NOT_STICKY
        }

        val waitingStartedAtMillis = when {
            anchorId == prefState.anchorId && prefState.startedAtMillis > 0L ->
                prefState.startedAtMillis
            else -> System.currentTimeMillis()
        }
        writeWaitingStateToPrefs(
            anchorId = anchorId,
            localSupportSessionId = localSupportSessionId,
            startedAtMillis = waitingStartedAtMillis
        )
        startWaitingForeground(localSupportSessionId, waitingStartedAtMillis)

        if (
            monitorJob?.isActive == true &&
            monitoredAnchorId == anchorId &&
            monitoredLocalSupportSessionId == localSupportSessionId
        ) {
            return START_STICKY
        }

        startMonitoring(anchorId, localSupportSessionId)

        return START_STICKY
    }

    private fun startMonitoring(anchorId: String, localSupportSessionId: String?) {
        monitorJob?.cancel()
        monitoredAnchorId = anchorId
        monitoredLocalSupportSessionId = localSupportSessionId
        monitorJob = serviceScope.launch {
            while (isActive) {
                val trackedAnchorId = monitoredAnchorId
                if (trackedAnchorId.isNullOrBlank()) break
                val trackedSessionId = monitoredLocalSupportSessionId

                if (trackedSessionId.isNullOrBlank()) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                val recovered = runCatching {
                    clientSupportRepository.findActiveRealtimeSession(trackedSessionId)
                }.getOrNull()

                if (recovered?.sessionId?.isNotBlank() == true) {
                    runCatching {
                        clientSupportRepository.attachRealtimeSession(
                            localSupportSessionId = trackedSessionId,
                            realtimeSessionId = recovered.sessionId,
                            techName = recovered.techName
                        )
                    }
                    notifySupportAccepted(
                        sessionId = recovered.sessionId,
                        techName = recovered.techName,
                        localSupportSessionId = trackedSessionId
                    )
                    clearWaitingStateIfMatches(trackedSessionId)
                    stopSelfSafe()
                    break
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleCancelWaiting(intent: Intent) {
        val requestedLocalId = intent.getStringExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val waitingState = readWaitingStateFromPrefs()
        if (
            requestedLocalId == null ||
            waitingState.localSupportSessionId != requestedLocalId
        ) {
            Log.w(TAG, "Ação de cancelamento obsoleta ou sem identificador ignorada")
            return
        }
        if (
            cancellationJob?.isActive == true &&
            cancellingLocalSupportSessionId == requestedLocalId
        ) {
            return
        }

        monitorJob?.cancel()
        monitorJob = null
        cancellingLocalSupportSessionId = requestedLocalId
        startWaitingForeground(
            localSupportSessionId = requestedLocalId,
            startedAtMillis = waitingState.startedAtMillis,
            contentOverride = "Cancelando sua espera com segurança...",
            includeCancelAction = false
        )

        cancellationJob = serviceScope.launch {
            val backendCancelled = cancelBackendQueue(requestedLocalId)
            val localCancelled = backendCancelled && runCatching {
                clientSupportRepository.cancelSupportRequest(requestedLocalId)
                true
            }.getOrElse { error ->
                Log.w(TAG, "Falha ao marcar solicitação local como cancelada", error)
                false
            }

            val latestState = readWaitingStateFromPrefs()
            if (latestState.localSupportSessionId != requestedLocalId) {
                cancellingLocalSupportSessionId = null
                cancellationJob = null
                latestState.anchorId?.let { currentAnchor ->
                    startWaitingForeground(
                        localSupportSessionId = latestState.localSupportSessionId,
                        startedAtMillis = latestState.startedAtMillis
                    )
                    startMonitoring(currentAnchor, latestState.localSupportSessionId)
                }
                return@launch
            }

            if (backendCancelled && localCancelled) {
                clearWaitingStateIfMatches(requestedLocalId)
                sendBroadcast(
                    Intent(ACTION_WAITING_SUPPORT_CANCELLED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID, requestedLocalId)
                )
                cancellingLocalSupportSessionId = null
                cancellationJob = null
                stopSelfSafe()
                return@launch
            }

            Log.w(TAG, "Cancelamento da espera não foi confirmado; mantendo estado para nova tentativa")
            cancellingLocalSupportSessionId = null
            cancellationJob = null
            startWaitingForeground(
                localSupportSessionId = requestedLocalId,
                startedAtMillis = latestState.startedAtMillis,
                contentOverride = "Não foi possível cancelar agora. Tente novamente."
            )
            latestState.anchorId?.let { currentAnchor ->
                startMonitoring(currentAnchor, requestedLocalId)
            }
        }
    }

    private suspend fun cancelBackendQueue(localSupportSessionId: String): Boolean {
        val existingSocket = Conn.socket?.takeIf { it.connected() }
        if (
            existingSocket != null &&
            emitBackendCancellation(existingSocket, localSupportSessionId)
        ) {
            return true
        }

        val idToken = runCatching {
            authRepository.ensureAnonIdToken(forceRefresh = false)
        }.getOrElse { error ->
            Log.w(TAG, "Falha ao autenticar cancelamento da espera", error)
            ""
        }
        if (idToken.isBlank()) return false

        val options = IO.Options().apply {
            forceNew = true
            reconnection = false
            timeout = SOCKET_CONNECT_TIMEOUT_MS
            extraHeaders = mapOf(
                "Authorization" to listOf("Bearer $idToken"),
                "x-id-token" to listOf(idToken)
            )
        }
        val temporarySocket = runCatching {
            IO.socket(Conn.SERVER_BASE, options)
        }.getOrElse { error ->
            Log.w(TAG, "Falha ao preparar conexão para cancelar espera", error)
            return false
        }
        val connected = CompletableDeferred<Boolean>()
        temporarySocket.once(Socket.EVENT_CONNECT) {
            connected.complete(true)
        }
        temporarySocket.once(Socket.EVENT_CONNECT_ERROR) {
            connected.complete(false)
        }

        return try {
            temporarySocket.connect()
            val connectionReady = withTimeoutOrNull(SOCKET_CONNECT_TIMEOUT_MS) {
                connected.await()
            } == true
            connectionReady &&
                emitBackendCancellation(temporarySocket, localSupportSessionId)
        } finally {
            temporarySocket.off()
            temporarySocket.disconnect()
        }
    }

    private suspend fun emitBackendCancellation(
        targetSocket: Socket,
        localSupportSessionId: String
    ): Boolean {
        val ackDeferred = CompletableDeferred<Boolean>()
        val payload = JSONObject().apply {
            put("localSupportSessionId", localSupportSessionId)
        }
        val emitted = runCatching {
            targetSocket.emit(
                "support:cancel",
                arrayOf<Any>(payload),
                Ack { args ->
                    val response = args.getOrNull(0) as? JSONObject
                    ackDeferred.complete(response?.optBoolean("ok", false) == true)
                }
            )
        }.onFailure { error ->
            Log.w(TAG, "Falha ao enviar cancelamento da espera", error)
        }.isSuccess
        if (!emitted) return false

        return withTimeoutOrNull(CANCEL_ACK_TIMEOUT_MS) {
            ackDeferred.await()
        } == true
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        monitorJob = null
        cancellationJob?.cancel()
        cancellationJob = null
        serviceScope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout (startId=$startId, type=$fgsType); stopping safely")
        stopSelfSafe()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWaitingForeground(
        localSupportSessionId: String?,
        startedAtMillis: Long,
        contentOverride: String? = null,
        includeCancelAction: Boolean = true
    ) {
        ensureNotificationChannel(
            id = WAITING_CHANNEL_ID,
            name = "Fila de atendimento",
            description = "Acompanhamento da fila enquanto voce aguarda o tecnico.",
            importance = NotificationManager.IMPORTANCE_LOW
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            WAITING_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelPendingIntent = localSupportSessionId
            ?.takeIf { includeCancelAction && it.isNotBlank() }
            ?.let { localId ->
                val cancelIntent = Intent(this, WaitingSupportMonitorService::class.java).apply {
                    action = ACTION_CANCEL_WAITING
                    putExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID, localId)
                }
                PendingIntent.getService(
                    this,
                    CANCEL_REQUEST_CODE_BASE + abs(localId.hashCode() % 10_000),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

        val contentText = contentOverride ?: if (localSupportSessionId.isNullOrBlank()) {
            "Aguardando técnico. Você pode usar outros apps enquanto espera."
        } else {
            "Aguardando técnico para o protocolo $localSupportSessionId."
        }

        val notificationBuilder = NotificationCompat.Builder(this, WAITING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Suporte X em espera ativa")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(startedAtMillis.coerceAtLeast(0L))
            .setUsesChronometer(true)
            .setContentIntent(openPendingIntent)
        if (cancelPendingIntent != null) {
            notificationBuilder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar espera",
                cancelPendingIntent
            )
        }
        val notification = notificationBuilder.build()

        ServiceCompat.startForeground(
            this,
            WAITING_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun notifySupportAccepted(
        sessionId: String,
        techName: String?,
        localSupportSessionId: String?
    ) {
        ensureNotificationChannel(
            id = SESSION_CHANNEL_ID,
            name = "Atualizacoes do atendimento",
            description = "Notificacoes sobre o inicio e encerramento da sessao de suporte.",
            importance = NotificationManager.IMPORTANCE_HIGH
        )

        val openIntent = InternalLaunchGuard.attach(
            this,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_WAITING_SUPPORT_ACCEPTED
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TECH_NAME, techName)
                localSupportSessionId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { putExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID, it) }
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            abs(sessionId.hashCode()) + 12_000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val techLabel = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Técnico"
        val body = "$techLabel iniciou seu atendimento. Toque para abrir."
        val notification = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Atendimento iniciado")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        if (manager == null) {
            Log.w(TAG, "NotificationManager indisponivel para notificar aceite")
            return
        }
        manager.notify(3_600 + abs(sessionId.hashCode() % 900), notification)
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

    private fun stopSelfSafe() {
        monitorJob?.cancel()
        monitorJob = null
        cancellationJob?.cancel()
        cancellationJob = null
        monitoredAnchorId = null
        monitoredLocalSupportSessionId = null
        cancellingLocalSupportSessionId = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun readWaitingStateFromPrefs(): WaitingState {
        val prefs = getSharedPreferences(PREFS_WAITING_SUPPORT, MODE_PRIVATE)
        val anchorId = prefs.getString(KEY_WAITING_ANCHOR_ID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val localSupportSessionId = prefs.getString(KEY_PENDING_SUPPORT_SESSION_ID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val startedAtMillis = prefs.getLong(KEY_WAITING_STARTED_AT_MILLIS, 0L).coerceAtLeast(0L)
        return WaitingState(
            anchorId = anchorId,
            localSupportSessionId = localSupportSessionId,
            startedAtMillis = startedAtMillis
        )
    }

    private fun writeWaitingStateToPrefs(
        anchorId: String?,
        localSupportSessionId: String?,
        startedAtMillis: Long?
    ) {
        val prefs = getSharedPreferences(PREFS_WAITING_SUPPORT, MODE_PRIVATE)
        prefs.edit {
            if (anchorId.isNullOrBlank()) {
                remove(KEY_WAITING_ANCHOR_ID)
                remove(KEY_PENDING_SUPPORT_SESSION_ID)
                remove(KEY_WAITING_STARTED_AT_MILLIS)
            } else {
                putString(KEY_WAITING_ANCHOR_ID, anchorId)
                putString(KEY_PENDING_SUPPORT_SESSION_ID, localSupportSessionId)
                putLong(KEY_WAITING_STARTED_AT_MILLIS, (startedAtMillis ?: 0L).coerceAtLeast(0L))
            }
        }
    }

    private fun clearWaitingStateIfMatches(localSupportSessionId: String): Boolean {
        val currentState = readWaitingStateFromPrefs()
        if (currentState.localSupportSessionId != localSupportSessionId) return false
        writeWaitingStateToPrefs(
            anchorId = null,
            localSupportSessionId = null,
            startedAtMillis = null
        )
        return true
    }

    private data class WaitingState(
        val anchorId: String?,
        val localSupportSessionId: String?,
        val startedAtMillis: Long
    )

    companion object {
        const val ACTION_START_MONITOR = "com.suportex.app.action.START_WAITING_SUPPORT_MONITOR"
        const val ACTION_STOP_MONITOR = "com.suportex.app.action.STOP_WAITING_SUPPORT_MONITOR"
        const val ACTION_WAITING_SUPPORT_CANCELLED =
            "com.suportex.app.action.WAITING_SUPPORT_CANCELLED"

        private const val ACTION_CANCEL_WAITING = "com.suportex.app.action.CANCEL_WAITING_SUPPORT"
        private const val ACTION_WAITING_SUPPORT_ACCEPTED = "com.suportex.app.action.WAITING_SUPPORT_ACCEPTED"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_TECH_NAME = "extra_tech_name"
        private const val EXTRA_LOCAL_SUPPORT_SESSION_ID = "extra_local_support_session_id"
        private const val EXTRA_WAITING_ANCHOR_ID = "extra_waiting_anchor_id"
        private const val PREFS_WAITING_SUPPORT = "waiting_support_runtime"
        private const val KEY_WAITING_ANCHOR_ID = "waiting_anchor_id"
        private const val KEY_PENDING_SUPPORT_SESSION_ID = "pending_support_session_id"
        private const val KEY_WAITING_STARTED_AT_MILLIS = "waiting_started_at_millis"

        private const val WAITING_CHANNEL_ID = "suportex_waiting_queue"
        private const val SESSION_CHANNEL_ID = "suportex_session_events"
        private const val WAITING_NOTIFICATION_ID = 6_100
        private const val CANCEL_REQUEST_CODE_BASE = 16_100
        private const val POLL_INTERVAL_MS = 4_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 8_000L
        private const val CANCEL_ACK_TIMEOUT_MS = 8_000L
        private const val TAG = "SXS/WaitingService"
    }
}
