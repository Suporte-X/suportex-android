package com.suportex.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class SessionAnchorService : Service() {

    private var activeSessionId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SESSION_ANCHOR) {
            writeSessionStateToPrefs(null, null, null)
            stopSelfSafe()
            return START_NOT_STICKY
        }

        val prefState = readSessionStateFromPrefs()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prefState.sessionId

        if (sessionId.isNullOrBlank()) {
            writeSessionStateToPrefs(null, null, null)
            stopSelfSafe()
            return START_NOT_STICKY
        }

        val techName = intent?.getStringExtra(EXTRA_TECH_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prefState.techName

        val startedAtMillis = when {
            sessionId == prefState.sessionId && prefState.startedAtMillis > 0L -> prefState.startedAtMillis
            else -> System.currentTimeMillis()
        }

        activeSessionId = sessionId
        writeSessionStateToPrefs(sessionId, techName, startedAtMillis)
        startSessionForeground(sessionId, techName, startedAtMillis)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSessionForeground(sessionId: String, techName: String?, startedAtMillis: Long) {
        ensureNotificationChannel(
            id = SESSION_ANCHOR_CHANNEL_ID,
            name = "Sessao ativa",
            description = "Indicador continuo de sessao ativa durante o atendimento.",
            importance = NotificationManager.IMPORTANCE_DEFAULT
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION_CHAT
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            abs(sessionId.hashCode()) + 14_000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val techLabel = techName?.trim()?.takeIf { it.isNotBlank() } ?: "Tecnico"
        val content = "Atendimento em andamento com $techLabel."
        val notification = NotificationCompat.Builder(this, SESSION_ANCHOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle("Sessao ativa")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(startedAtMillis)
            .setUsesChronometer(true)
            .build()

        startForeground(SESSION_ANCHOR_NOTIFICATION_ID, notification)
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
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopSelfSafe() {
        activeSessionId = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun readSessionStateFromPrefs(): SessionAnchorState {
        val prefs = getSharedPreferences(PREFS_SESSION_ANCHOR, MODE_PRIVATE)
        val sessionId = prefs.getString(KEY_SESSION_ID, null)?.trim()?.takeIf { it.isNotBlank() }
        val techName = prefs.getString(KEY_TECH_NAME, null)?.trim()?.takeIf { it.isNotBlank() }
        val startedAtMillis = prefs.getLong(KEY_STARTED_AT_MILLIS, 0L).coerceAtLeast(0L)
        return SessionAnchorState(
            sessionId = sessionId,
            techName = techName,
            startedAtMillis = startedAtMillis
        )
    }

    private fun writeSessionStateToPrefs(sessionId: String?, techName: String?, startedAtMillis: Long?) {
        val prefs = getSharedPreferences(PREFS_SESSION_ANCHOR, MODE_PRIVATE)
        val edit = prefs.edit()
        if (sessionId.isNullOrBlank()) {
            edit.remove(KEY_SESSION_ID)
            edit.remove(KEY_TECH_NAME)
            edit.remove(KEY_STARTED_AT_MILLIS)
        } else {
            edit.putString(KEY_SESSION_ID, sessionId)
            if (techName.isNullOrBlank()) {
                edit.remove(KEY_TECH_NAME)
            } else {
                edit.putString(KEY_TECH_NAME, techName)
            }
            edit.putLong(KEY_STARTED_AT_MILLIS, (startedAtMillis ?: 0L).coerceAtLeast(0L))
        }
        edit.apply()
    }

    private data class SessionAnchorState(
        val sessionId: String?,
        val techName: String?,
        val startedAtMillis: Long
    )

    companion object {
        const val ACTION_START_SESSION_ANCHOR = "com.suportex.app.action.START_SESSION_ANCHOR"
        const val ACTION_STOP_SESSION_ANCHOR = "com.suportex.app.action.STOP_SESSION_ANCHOR"

        private const val ACTION_OPEN_SESSION_CHAT = "com.suportex.app.action.OPEN_SESSION_CHAT"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_TECH_NAME = "extra_tech_name"
        private const val PREFS_SESSION_ANCHOR = "session_anchor_runtime"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_TECH_NAME = "tech_name"
        private const val KEY_STARTED_AT_MILLIS = "started_at_millis"

        private const val SESSION_ANCHOR_CHANNEL_ID = "suportex_session_anchor_v2"
        private const val SESSION_ANCHOR_NOTIFICATION_ID = 6_200
    }
}
