package com.suportex.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import com.suportex.app.BuildConfig

class AppFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Aqui você pode salvar o token em /devices/{deviceId}
        if (BuildConfig.DEBUG) {
            Log.d("SXS/FCM", "Novo token recebido")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Ex.: data["type"] == "call-accepted" -> atualizar UI via broadcast/local storage
        if (BuildConfig.DEBUG) {
            Log.d("SXS/FCM", "FCM recebido: ${message.data.keys}")
        }
    }
}
