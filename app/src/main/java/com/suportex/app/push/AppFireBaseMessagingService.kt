package com.suportex.app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.suportex.app.BuildConfig
import com.suportex.app.data.AuthRepository
import com.suportex.app.data.ClientSupportRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppFirebaseMessagingService : FirebaseMessagingService() {

    private val authRepository = AuthRepository()
    private val clientSupportRepository = ClientSupportRepository()

    override fun onNewToken(token: String) {
        // Aqui você pode salvar o token em /devices/{deviceId}
        if (BuildConfig.DEBUG) {
            Log.d("SXS/FCM", "Novo token recebido")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val messageType = message.data["type"]?.trim()?.lowercase().orEmpty()
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
}
