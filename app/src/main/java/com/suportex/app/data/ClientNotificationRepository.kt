package com.suportex.app.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.suportex.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ClientNotificationRecord(
    val id: String,
    val title: String,
    val body: String,
    val type: String,
    val iconType: String,
    val priority: String,
    val status: String,
    val read: Boolean,
    val dismissed: Boolean,
    val actionLabel: String?,
    val actionType: String,
    val createdAt: Long,
    val expiresAt: Long?
)

class ClientNotificationRepository(
    private val db: FirebaseFirestore = FirebaseDataSource.db,
    private val authRepository: AuthRepository = AuthRepository()
) {
    private val clientDevices = db.collection("client_devices")
    private val clientNotifications = db.collection("client_notifications")

    suspend fun registerDevice(
        context: Context,
        fcmToken: String,
        clientId: String? = null,
        deviceAnchor: String = DeviceIdentity.deviceAnchor(context)
    ) {
        val token = fcmToken.trim()
        if (token.isBlank()) return
        val uid = authRepository.ensureAnonAuth()
        val now = System.currentTimeMillis()
        val documentId = "${uid}_${deviceAnchor.take(18)}"
        val payload = mapOf(
            "clientUid" to uid,
            "clientId" to clientId?.trim()?.takeIf { it.isNotBlank() },
            "deviceAnchor" to deviceAnchor,
            "platform" to "android",
            "fcmToken" to token,
            "active" to true,
            "appVersionCode" to BuildConfig.VERSION_CODE,
            "appVersionName" to BuildConfig.VERSION_NAME,
            "deviceBrand" to Build.BRAND.orEmpty(),
            "deviceModel" to Build.MODEL.orEmpty(),
            "androidVersion" to Build.VERSION.SDK_INT,
            "lastSeenAt" to now,
            "tokenUpdatedAt" to now,
            "updatedAt" to now,
            "createdAt" to now
        )
        clientDevices.document(documentId)
            .set(payload, SetOptions.merge())
            .await()
    }

    fun listenClientNotifications(
        clientUid: String?,
        clientId: String?,
        onChanged: (List<ClientNotificationRecord>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration {
        val uid = clientUid?.trim()?.takeIf { it.isNotBlank() }
        val id = clientId?.trim()?.takeIf { it.isNotBlank() }
        if (uid == null && id == null) {
            onChanged(emptyList())
            return CompoundListenerRegistration(emptyList())
        }

        val snapshotsBySource = mutableMapOf<String, Map<String, ClientNotificationRecord>>()
        val registrations = mutableListOf<ListenerRegistration>()

        fun publish() {
            val now = System.currentTimeMillis()
            val rows = snapshotsBySource.values
                .flatMap { it.values }
                .distinctBy { it.id }
                .filter { !it.dismissed && it.status != "dismissed" && (it.expiresAt == null || it.expiresAt > now) }
                .sortedWith(
                    compareByDescending<ClientNotificationRecord> { priorityRank(it.priority) }
                        .thenByDescending { it.createdAt }
                )
            onChanged(rows)
        }

        fun attach(source: String, field: String, value: String) {
            val registration = clientNotifications
                .whereEqualTo(field, value)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Falha ao escutar notificações do cliente", error)
                        onError(error)
                        return@addSnapshotListener
                    }
                    snapshotsBySource[source] = snapshot?.documents
                        ?.mapNotNull { it.toClientNotificationRecord() }
                        ?.associateBy { it.id }
                        ?: emptyMap()
                    publish()
                }
            registrations.add(registration)
        }

        if (uid != null) attach("uid", "clientUid", uid)
        if (id != null) attach("client", "clientId", id)
        return CompoundListenerRegistration(registrations)
    }

    suspend fun markAsRead(notificationId: String) {
        updateNotificationState(notificationId, "read")
    }

    suspend fun dismiss(notificationId: String) {
        updateNotificationState(notificationId, "dismissed")
    }

    private suspend fun updateNotificationState(notificationId: String, status: String) {
        val id = notificationId.trim()
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        val payload = if (status == "dismissed") {
            mapOf(
                "read" to true,
                "status" to "dismissed",
                "readAt" to now,
                "dismissed" to true,
                "dismissedAt" to now,
                "updatedAt" to now
            )
        } else {
            mapOf(
                "read" to true,
                "status" to "read",
                "readAt" to now,
                "updatedAt" to now
            )
        }
        clientNotifications.document(id).set(payload, SetOptions.merge()).await()
    }

    private fun DocumentSnapshot.toClientNotificationRecord(): ClientNotificationRecord? {
        val notificationId = getString("id")?.trim()?.takeIf { it.isNotBlank() } ?: id
        val title = getString("title")?.trim()?.takeIf { it.isNotBlank() } ?: "Notificação"
        val body = getString("body")?.trim().orEmpty()
        val status = getString("status")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: if (getBoolean("read") == true) "read" else "unread"
        return ClientNotificationRecord(
            id = notificationId,
            title = title,
            body = body,
            type = getString("type")?.trim()?.uppercase().orEmpty(),
            iconType = getString("iconType")?.trim().orEmpty(),
            priority = getString("priority")?.trim()?.lowercase().orEmpty(),
            status = status,
            read = getBoolean("read") == true || status == "read",
            dismissed = getBoolean("dismissed") == true || status == "dismissed",
            actionLabel = getString("actionLabel")?.trim()?.takeIf { it.isNotBlank() },
            actionType = getString("actionType")?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: "NONE",
            createdAt = millisOf(get("createdAt")) ?: 0L,
            expiresAt = millisOf(get("expiresAt"))
        )
    }

    private fun millisOf(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is Timestamp -> value.toDate().time
            is Date -> value.time
            else -> null
        }
    }

    private fun priorityRank(priority: String): Int {
        return when (priority.lowercase()) {
            "critical" -> 4
            "high" -> 3
            "normal" -> 2
            "low" -> 1
            else -> 0
        }
    }

    private class CompoundListenerRegistration(
        private val registrations: List<ListenerRegistration>
    ) : ListenerRegistration {
        override fun remove() {
            registrations.forEach { it.remove() }
        }
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
        const val TAG = "SXS/Notifications"
    }
}
