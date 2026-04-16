package com.suportex.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.suportex.app.data.ClientSupportRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class WaitingSupportMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientSupportRepository = ClientSupportRepository()
    private var monitorJob: Job? = null
    private var monitoredAnchorId: String? = null
    private var monitoredLocalSupportSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        startWaitingForeground(
            localSupportSessionId = null,
            startedAtMillis = System.currentTimeMillis()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                    writeWaitingStateToPrefs(anchorId = null, localSupportSessionId = null, startedAtMillis = null)
                    stopSelfSafe()
                    break
                }

                delay(POLL_INTERVAL_MS)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        monitorJob = null
        serviceScope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWaitingForeground(localSupportSessionId: String?, startedAtMillis: Long) {
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

        val contentText = if (localSupportSessionId.isNullOrBlank()) {
            "Aguardando tecnico. Voce pode usar outros apps enquanto espera."
        } else {
            "Aguardando tecnico para o protocolo $localSupportSessionId."
        }

        val notification = NotificationCompat.Builder(this, WAITING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Suporte X em espera ativa")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(startedAtMillis.coerceAtLeast(0L))
            .setUsesChronometer(true)
            .setContentIntent(openPendingIntent)
            .build()

        startForeground(WAITING_NOTIFICATION_ID, notification)
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

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_WAITING_SUPPORT_ACCEPTED
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_TECH_NAME, techName)
            localSupportSessionId
                ?.takeIf { it.isNotBlank() }
                ?.let { putExtra(EXTRA_LOCAL_SUPPORT_SESSION_ID, it) }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            abs(sessionId.hashCode()) + 12_000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val techLabel = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Tecnico"
        val body = "$techLabel iniciou seu atendimento. Toque para abrir o Suporte X."
        val notification = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Atendimento iniciado")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
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
        monitoredAnchorId = null
        monitoredLocalSupportSessionId = null
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
        val edit = prefs.edit()
        if (anchorId.isNullOrBlank()) {
            edit.remove(KEY_WAITING_ANCHOR_ID)
            edit.remove(KEY_PENDING_SUPPORT_SESSION_ID)
            edit.remove(KEY_WAITING_STARTED_AT_MILLIS)
        } else {
            edit.putString(KEY_WAITING_ANCHOR_ID, anchorId)
            edit.putString(KEY_PENDING_SUPPORT_SESSION_ID, localSupportSessionId)
            edit.putLong(KEY_WAITING_STARTED_AT_MILLIS, (startedAtMillis ?: 0L).coerceAtLeast(0L))
        }
        edit.apply()
    }

    private data class WaitingState(
        val anchorId: String?,
        val localSupportSessionId: String?,
        val startedAtMillis: Long
    )

    companion object {
        const val ACTION_START_MONITOR = "com.suportex.app.action.START_WAITING_SUPPORT_MONITOR"
        const val ACTION_STOP_MONITOR = "com.suportex.app.action.STOP_WAITING_SUPPORT_MONITOR"

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
        private const val POLL_INTERVAL_MS = 4_000L
        private const val TAG = "SXS/WaitingService"
    }
}
