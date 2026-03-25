package com.suportex.app.data

import android.content.Context
import androidx.core.content.edit

class ClientIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPhone(): String? = prefs.getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() }

    fun getDisplayName(): String? = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun save(phone: String, displayName: String?) {
        prefs.edit {
            putString(KEY_PHONE, phone)
            putString(KEY_NAME, displayName?.takeIf { it.isNotBlank() })
        }
    }

    companion object {
        private const val PREFS_NAME = "supportx_client_identity"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "display_name"
    }
}
