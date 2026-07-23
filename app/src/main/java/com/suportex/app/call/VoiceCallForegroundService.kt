package com.suportex.app.call

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.suportex.app.MainActivity
import com.suportex.app.R

internal object VoiceCallForegroundPolicy {
    const val ACTION_START_FROM_USER =
        "com.suportex.app.action.START_USER_VOICE_CALL"

    fun canStart(action: String?, recordAudioGranted: Boolean): Boolean {
        return action == ACTION_START_FROM_USER && recordAudioGranted
    }

    fun canCapture(
        locallyAuthorizedForCall: Boolean,
        recordAudioGranted: Boolean
    ): Boolean {
        return locallyAuthorizedForCall && recordAudioGranted
    }

    fun shouldStopFor(state: CallState): Boolean {
        return state == CallState.IDLE ||
            state == CallState.DECLINED ||
            state == CallState.TIMEOUT ||
            state == CallState.FAILED ||
            state == CallState.ENDED
    }
}

class VoiceCallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            !VoiceCallForegroundPolicy.canStart(
                action = intent?.action,
                recordAudioGranted = hasRecordAudioPermission(this)
            )
        ) {
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }

        return try {
            ensureNotificationChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
            START_NOT_STICKY
        } catch (error: SecurityException) {
            Log.e(TAG, "Microphone foreground service permission denied", error)
            stopForegroundAndSelf(startId)
            START_NOT_STICKY
        } catch (error: RuntimeException) {
            Log.e(TAG, "Microphone foreground service failed to start", error)
            stopForegroundAndSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(
        this,
        NOTIFICATION_CHANNEL_ID
    )
        .setSmallIcon(R.drawable.ic_stat_suporte_x)
        .setContentTitle(getString(R.string.voice_call_notification_title))
        .setContentText(getString(R.string.voice_call_notification_text))
        .setContentIntent(openCallPendingIntent())
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setOngoing(true)
        .setAutoCancel(false)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

    private fun openCallPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.voice_call_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.voice_call_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundAndSelf(startId: Int) {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelfResult(startId)
    }

    companion object {
        internal const val NOTIFICATION_ID = 6_201
        internal const val NOTIFICATION_CHANNEL_ID = "suportex_voice_call"
        private const val TAG = "SXS/VoiceFgs"

        fun startForUserCall(context: Context): Boolean {
            val appContext = context.applicationContext
            if (!hasRecordAudioPermission(appContext)) return false

            val intent = Intent(
                appContext,
                VoiceCallForegroundService::class.java
            ).apply {
                action = VoiceCallForegroundPolicy.ACTION_START_FROM_USER
            }
            return runCatching {
                ContextCompat.startForegroundService(appContext, intent)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Unable to request microphone foreground service", error)
                false
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(
                    Intent(appContext, VoiceCallForegroundService::class.java)
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to stop microphone foreground service", error)
            }
        }

        fun hasRecordAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
