package com.suportex.app.data

import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.suportex.app.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChatRepository {
    private val db = FirebaseDataSource.db
    private val storage = FirebaseDataSource.storage
    private val authRepository = AuthRepository()

    fun observeMessages(sessionId: String) = callbackFlow<List<Message>> {
        var reg: ListenerRegistration? = null
        val authAndListenJob = launch(Dispatchers.IO) {
            runCatching { authRepository.ensureAnonAuth() }
                .onFailure { err ->
                    Log.e(TAG, "observeMessages auth failed for session=$sessionId", err)
                    close(err)
                    return@launch
                }

            reg = db.collection("sessions").document(sessionId)
                .collection("messages")
                .orderBy("ts", Query.Direction.ASCENDING)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        Log.e(TAG, "observeMessages listener error for session=$sessionId", error)
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.map { doc ->
                        val data = doc.data ?: emptyMap()
                        val text = (data["text"] as? String)?.takeIf { it.isNotBlank() }
                        val imageUrl = (data["imageUrl"] as? String)?.takeIf { it.isNotBlank() }
                        val fileUrl = (data["fileUrl"] as? String)?.takeIf { it.isNotBlank() }
                        val audioUrl = (data["audioUrl"] as? String)?.takeIf { it.isNotBlank() }
                        Message(
                            id = (data["id"] as? String)?.takeIf { it.isNotBlank() } ?: doc.id,
                            sessionId = (data["sessionId"] as? String)?.takeIf { it.isNotBlank() } ?: sessionId,
                            from = data["from"] as? String ?: "",
                            fromName = data["fromName"] as? String?,
                            text = text,
                            imageUrl = imageUrl ?: fileUrl,
                            fileUrl = fileUrl ?: imageUrl,
                            audioUrl = audioUrl,
                            type = data["type"] as? String ?: when {
                                audioUrl != null -> "audio"
                                imageUrl != null || fileUrl != null -> "image"
                                text != null -> "text"
                                else -> "file"
                            },
                            status = data["status"] as? String ?: "sent",
                            createdAt = when (val ts = data["ts"] ?: data["createdAt"]) {
                                is Number -> ts.toLong()
                                else -> System.currentTimeMillis()
                            }
                        )
                    } ?: emptyList()
                    trySend(list)
                }
        }
        awaitClose {
            authAndListenJob.cancel()
            reg?.remove()
        }
    }

    suspend fun sendText(sessionId: String, from: String, text: String) {
        authRepository.ensureAnonAuth()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = messageId,
            from = from,
            fromName = null,
            text = text,
            imageUrl = null,
            fileUrl = null,
            audioUrl = null,
            timestamp = timestamp,
            type = "text"
        )
        db.collection("sessions").document(sessionId)
            .collection("messages").document(messageId).set(payload).await()
    }

    suspend fun upsertIncoming(sessionId: String, message: Message) {
        authRepository.ensureAnonAuth()
        val collection = db.collection("sessions").document(sessionId)
            .collection("messages")
        val docId = message.id.takeIf { it.isNotBlank() } ?: collection.document().id
        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = docId,
            from = message.from,
            fromName = message.fromName,
            text = message.text,
            imageUrl = message.imageUrl ?: message.fileUrl,
            fileUrl = message.fileUrl ?: message.imageUrl,
            audioUrl = message.audioUrl,
            timestamp = message.createdAt,
            type = message.type
        )
        collection.document(docId).set(payload).await()
    }

    suspend fun sendFile(sessionId: String, from: String, localUri: Uri): String =
        sendAttachment(sessionId = sessionId, from = from, localUri = localUri)


    suspend fun sendAttachment(sessionId: String, from: String, localUri: Uri): String {
        authRepository.ensureAnonAuth()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val ref = storage.reference
            .child("sessions/$sessionId/attachments/$timestamp")
        ref.putFile(localUri).await()
        val url = ref.downloadUrl.await().toString()
        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = messageId,
            from = from,
            fromName = null,
            text = null,
            imageUrl = url,
            fileUrl = url,
            audioUrl = null,
            timestamp = timestamp,
            type = "image"
        )
        db.collection("sessions").document(sessionId)
            .collection("messages").document(messageId).set(payload).await()
        return url
    }

    suspend fun sendAudio(sessionId: String, from: String, localUri: Uri): String {
        authRepository.ensureAnonAuth()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val ref = storage.reference
            .child("sessions/$sessionId/audio/$timestamp.m4a")
        ref.putFile(localUri).await()
        val url = ref.downloadUrl.await().toString()
        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = messageId,
            from = from,
            fromName = null,
            text = null,
            imageUrl = null,
            fileUrl = null,
            audioUrl = url,
            timestamp = timestamp,
            type = "audio"
        )
        db.collection("sessions").document(sessionId)
            .collection("messages").document(messageId).set(payload).await()
        return url
    }

    private fun buildMessagePayload(
        sessionId: String,
        id: String,
        from: String,
        fromName: String?,
        text: String?,
        imageUrl: String?,
        fileUrl: String?,
        audioUrl: String?,
        timestamp: Long,
        type: String?
    ): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "id" to id,
            "sessionId" to sessionId,
            "from" to from,
            "ts" to timestamp,
            "createdAt" to timestamp,
            "type" to (type ?: when {
                audioUrl != null -> "audio"
                imageUrl != null || fileUrl != null -> "image"
                text != null -> "text"
                else -> "file"
            }),
            "status" to "sent"
        )
        fromName?.let { payload["fromName"] = it }
        text?.let { payload["text"] = it }
        imageUrl?.let { payload["imageUrl"] = it }
        fileUrl?.let { payload["fileUrl"] = it }
        audioUrl?.let { payload["audioUrl"] = it }
        return payload
    }
}

/* ---- Task<T>.await sem dependências extras ---- */
private const val TAG = "SXS/ChatRepo"

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
