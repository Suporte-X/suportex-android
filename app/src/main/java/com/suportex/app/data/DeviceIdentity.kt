package com.suportex.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID

object DeviceIdentity {
    private const val PREFS_NAME = "app"
    private const val KEY_DEVICE_ID = "device_id"

    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_DEVICE_ID, null)
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, generated) }
        return generated
    }

    @SuppressLint("HardwareIds")
    fun deviceAnchor(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
        val raw = androidId ?: deviceId(context)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
