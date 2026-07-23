package com.suportex.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.suportex.app.MainActivity
import com.suportex.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.suportex.app.BuildConfig
import com.suportex.app.data.AuthRepository
import com.suportex.app.data.ClientNotificationRepository
import com.suportex.app.data.ClientSupportRepository
import com.suportex.app.data.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppFirebaseMessagingService : FirebaseMessagingService() {

    private val authRepository = AuthRepository()
    private val clientSupportRepository = ClientSupportRepository()
    private val clientNotificationRepository = ClientNotificationRepository()

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                clientNotificationRepository.registerDevice(
                    context = applicationContext,
                    fcmToken = token,
                    clientId = null,
                    deviceAnchor = DeviceIdentity.deviceAnchor(applicationContext)
                )
            }.onFailure { err ->
                Log.e("SXS/FCM", "Falha ao registrar token FCM", err)
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d("SXS/FCM", "Novo token recebido")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val messageType = message.data["type"]?.trim()?.lowercase().orEmpty()
        if (messageType == "client_notification") {
            showClientNotification(message.data)
        }
        if (messageType == "pnv_request" || messageType == "pnv_manual_request") {
            CoroutineScope(Dispatchers.IO).launch {
                val uid = runCatching { authRepository.ensureAnonAuth() }.getOrNull()
                runCatching {
                    clientSupportRepository.loadHomeSnapshot(
                        clientUid = uid,
                        rawPhone = null,
                        deviceAnchor = null
                    )
                }.onFailure { err ->
                    Log.e("SXS/FCM", "Falha ao processar atualização de verificação", err)
                }
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d("SXS/FCM", "FCM recebido: ${message.data.keys}")
        }
    }

    private fun showClientNotification(data: Map<String, String>) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("SXS/FCM", "Push recebido sem permissao POST_NOTIFICATIONS")
            return
        }
        ensureClientNotificationChannel()
        val notificationId = data["notificationId"]?.trim().orEmpty()
        val title = data["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "Suporte X"
        val body = data["body"]?.trim().orEmpty()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_CLIENT_NOTIFICATIONS)
            .setSmallIcon(R.drawable.ic_stat_suporte_x)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notificationId.hashCode(), notification)
    }

    private fun ensureClientNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_CLIENT_NOTIFICATIONS) != null) return
        val channel = NotificationChannel(
            CHANNEL_CLIENT_NOTIFICATIONS,
            "Notificações do cliente",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisos e ações da Central de Notificações do Suporte X"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_CLIENT_NOTIFICATIONS = "client_notifications"
    }
}
