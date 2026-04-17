package com.suportex.app.data

import android.content.Context

class ClientIdentityStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyPrefsIfNeeded()
    }

    fun getPhone(): String? = prefs.getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() }

    fun getDisplayName(): String? = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun save(phone: String, displayName: String?) {
        prefs.edit()
            .putString(KEY_PHONE, phone)
            .putString(KEY_NAME, displayName?.takeIf { it.isNotBlank() })
            .apply()
    }

    private fun migrateLegacyPrefsIfNeeded() {
        val hasCurrentData = !prefs.getString(KEY_PHONE, null).isNullOrBlank() ||
            !prefs.getString(KEY_NAME, null).isNullOrBlank()
        if (hasCurrentData) return

        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyPhone = legacyPrefs.getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() }
        val legacyName = legacyPrefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
        if (legacyPhone.isNullOrBlank() && legacyName.isNullOrBlank()) return

        prefs.edit()
            .putString(KEY_PHONE, legacyPhone)
            .putString(KEY_NAME, legacyName)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "suporte_x_client_identity"
        private const val LEGACY_PREFS_NAME = "supp" + "ortx_client_identity"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "display_name"
    }
}
