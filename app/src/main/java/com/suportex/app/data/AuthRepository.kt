package com.suportex.app.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    suspend fun ensureAnonAuth(): String {
        val currentUid = auth.currentUser?.uid
        if (!currentUid.isNullOrBlank()) {
            if (currentUid != lastLoggedUid) {
                Log.i(TAG, "Sessao autenticada (provider=${auth.currentUser?.providerId ?: "desconhecido"})")
                lastLoggedUid = currentUid
            }
            return currentUid
        }
        throw IllegalStateException("phone_auth_required")
    }

    suspend fun ensureAnonIdToken(forceRefresh: Boolean = false): String {
        runCatching { ensureAnonAuth() }.getOrNull() ?: return ""
        val user = auth.currentUser ?: return ""
        return user.getIdToken(forceRefresh).await().token ?: ""
    }

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    cont.resume(task.result)
                } else {
                    cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
                }
            }
        }

    private companion object {
        const val TAG = "SXS/Auth"
        @Volatile
        private var lastLoggedUid: String? = null
    }
}
