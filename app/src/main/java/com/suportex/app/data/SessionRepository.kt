package com.suportex.app.data

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SessionRepository {
    private val db = FirebaseDataSource.db
    private val authRepository = AuthRepository()

    suspend fun bindClient(sessionId: String): String {
        val uid = authRepository.ensureAnonAuth()
        db.collection("sessions").document(sessionId)
            .set(
                mapOf(
                    "clientUid" to uid,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        return uid
    }

    suspend fun startSession(
        sessionId: String,
        client: SessionClientInfo,
        _tech: SessionTechInfo?
    ) {
        val uid = authRepository.ensureAnonAuth()
        val now = System.currentTimeMillis()
        val payload = buildMap<String, Any> {
            put("client", client.toMap())
            put("clientUid", uid)
            put("updatedAt", now)
        }
        db.collection("sessions").document(sessionId)
            .set(payload, SetOptions.merge()).await()
    }

    suspend fun markSessionClosed(sessionId: String) {
        authRepository.ensureAnonAuth()
        val payload = mapOf(
            "status" to "closed",
            "closedAt" to System.currentTimeMillis(),
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("sessions").document(sessionId)
            .set(payload, SetOptions.merge()).await()
    }

    suspend fun updateRealtimeState(
        sessionId: String,
        state: SessionState,
        telemetry: SessionTelemetry? = null
    ) {
        authRepository.ensureAnonAuth()
        val data = mutableMapOf<String, Any>("state" to state.toMap())
        telemetry?.toMap()?.let { data["telemetry"] = it }
        db.collection("sessions").document(sessionId)
            .set(data, SetOptions.merge()).await()
    }

    suspend fun addEvent(
        sessionId: String,
        type: String,
        timestamp: Long = System.currentTimeMillis(),
        payload: Map<String, Any?>? = null
    ) {
        authRepository.ensureAnonAuth()
        val data = mutableMapOf<String, Any>(
            "ts" to timestamp,
            "type" to type
        )
        payload?.let { data["payload"] = cleanPayload(it) }
        db.collection("sessions").document(sessionId)
            .collection("events")
            .add(data).await()
    }

    private fun cleanPayload(payload: Map<String, Any?>): Map<String, Any?> {
        return payload.filterValues { it != null }
    }
}

data class SessionClientInfo(
    val deviceModel: String?,
    val androidVersion: String?
) {
    fun toMap(): Map<String, Any> = buildMap {
        deviceModel?.let { put("deviceModel", it) }
        androidVersion?.let { put("androidVersion", it) }
    }
}

data class SessionTechInfo(
    val uid: String? = null,
    val name: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "uid" to uid,
        "name" to name
    ).filterValues { it != null }
}

data class SessionState(
    val sharing: Boolean,
    val remoteEnabled: Boolean,
    val calling: Boolean,
    val callConnected: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sharing" to sharing,
        "remoteEnabled" to remoteEnabled,
        "calling" to calling,
        "callConnected" to callConnected,
        "updatedAt" to updatedAt
    )
}

data class SessionTelemetry(
    val battery: Int?,
    val net: String?,
    val network: String? = null,
    val batteryLevel: Int? = null,
    val batteryCharging: Boolean? = null,
    val temperatureC: Double? = null,
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val health: String? = null,
    val permissionsSummary: String? = null,
    val permissions: Map<String, Any>? = null,
    val alerts: String? = null,
    val sharing: Boolean,
    val remoteEnabled: Boolean,
    val calling: Boolean,
    val callConnected: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = buildMap {
        battery?.let { put("battery", it) }
        net?.let { put("net", it) }
        network?.let { put("network", it) }
        batteryLevel?.let { put("batteryLevel", it) }
        batteryCharging?.let { put("batteryCharging", it) }
        temperatureC?.let { put("temperatureC", it) }
        storageFreeBytes?.let { put("storageFreeBytes", it) }
        storageTotalBytes?.let { put("storageTotalBytes", it) }
        health?.let { put("health", it) }
        permissionsSummary?.let { put("permissionsSummary", it) }
        permissions?.takeIf { it.isNotEmpty() }?.let { put("permissions", it) }
        alerts?.let { put("alerts", it) }
        put("sharing", sharing)
        put("remoteEnabled", remoteEnabled)
        put("calling", calling)
        put("callConnected", callConnected)
        put("updatedAt", updatedAt)
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
