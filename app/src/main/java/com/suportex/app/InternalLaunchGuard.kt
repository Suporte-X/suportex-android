package com.suportex.app

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.util.UUID

/**
 * Protege ações internas entregues à MainActivity exportada.
 *
 * A Activity precisa ser exportada por ser o launcher do app, mas ações de
 * notificações não podem ser aceitas apenas pelo nome da action, pois outro app
 * também consegue construir uma Intent explícita. O nonce permanece no sandbox
 * privado do Suporte X e só é anexado aos PendingIntents criados pelo próprio app.
 */
internal object InternalLaunchGuard {
    private const val PREFS_NAME = "internal_launch_guard"
    private const val KEY_NONCE = "nonce"
    private const val EXTRA_NONCE = "com.suportex.app.extra.INTERNAL_LAUNCH_NONCE"

    fun attach(context: Context, intent: Intent): Intent {
        intent.putExtra(EXTRA_NONCE, getOrCreateNonce(context))
        return intent
    }

    fun isTrusted(context: Context, intent: Intent?): Boolean {
        val supplied = intent?.getStringExtra(EXTRA_NONCE).orEmpty()
        val expected = getOrCreateNonce(context)
        return noncesMatch(expected, supplied)
    }

    fun sanitize(intent: Intent?) {
        intent?.removeExtra(EXTRA_NONCE)
    }

    internal fun noncesMatch(expected: String, supplied: String): Boolean {
        if (expected.isBlank() || supplied.isBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8)
        )
    }

    @Synchronized
    private fun getOrCreateNonce(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_NONCE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val generated = "${UUID.randomUUID()}-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_NONCE, generated).commit()
        return generated
    }
}
