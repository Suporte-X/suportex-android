package com.suportex.app.data

import android.content.Context
import androidx.core.content.edit

internal data class ActiveSupportSessionState(
    val realtimeSessionId: String,
    val localSupportSessionId: String?,
    val techName: String?,
    val acceptedAtMillis: Long
)

/**
 * Estado mínimo necessário para recompor uma sessão após o processo ser morto.
 *
 * O estado nunca é usado como autorização. A retomada sempre é confirmada pelo
 * endpoint autenticado antes de reabrir a sessão.
 */
internal class ActiveSupportSessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ActiveSupportSessionState? {
        val realtimeSessionId = normalize(prefs.getString(KEY_REALTIME_SESSION_ID, null), 128)
            ?: return null
        return ActiveSupportSessionState(
            realtimeSessionId = realtimeSessionId,
            localSupportSessionId = normalize(
                prefs.getString(KEY_LOCAL_SUPPORT_SESSION_ID, null),
                128
            ),
            techName = normalize(prefs.getString(KEY_TECH_NAME, null), 120),
            acceptedAtMillis = prefs.getLong(KEY_ACCEPTED_AT_MILLIS, 0L)
                .coerceAtLeast(0L)
        )
    }

    fun write(state: ActiveSupportSessionState) {
        val realtimeSessionId = normalize(state.realtimeSessionId, 128) ?: return
        val localSupportSessionId = normalize(state.localSupportSessionId, 128)
        val techName = normalize(state.techName, 120)
        prefs.edit {
            putString(KEY_REALTIME_SESSION_ID, realtimeSessionId)
            if (localSupportSessionId == null) remove(KEY_LOCAL_SUPPORT_SESSION_ID)
            else putString(KEY_LOCAL_SUPPORT_SESSION_ID, localSupportSessionId)
            if (techName == null) remove(KEY_TECH_NAME)
            else putString(KEY_TECH_NAME, techName)
            putLong(
                KEY_ACCEPTED_AT_MILLIS,
                state.acceptedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun normalize(value: String?, maxLength: Int): String? =
        value
            ?.trim()
            ?.take(maxLength)
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val PREFS_NAME = "active_support_session_runtime"
        const val KEY_REALTIME_SESSION_ID = "realtime_session_id"
        const val KEY_LOCAL_SUPPORT_SESSION_ID = "local_support_session_id"
        const val KEY_TECH_NAME = "tech_name"
        const val KEY_ACCEPTED_AT_MILLIS = "accepted_at_millis"
    }
}
