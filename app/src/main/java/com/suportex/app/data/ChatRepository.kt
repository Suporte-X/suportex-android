package com.suportex.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.suportex.app.Conn
import com.suportex.app.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChatRepository {
    private val db = FirebaseDataSource.db
    private val authRepository = AuthRepository()
    private val httpClient = OkHttpClient()
    private val appContext: Context by lazy { FirebaseApp.getInstance().applicationContext }

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
        val uploaded = uploadSessionMedia(
            endpoint = "${Conn.SERVER_BASE}/api/upload/session-attachment",
            sessionId = sessionId,
            messageId = messageId,
            localUri = localUri,
            fallbackMime = "image/jpeg",
            fallbackPrefix = "attachment",
            maxBytes = MAX_ATTACHMENT_BYTES
        )

        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = messageId,
            from = from,
            fromName = null,
            text = null,
            imageUrl = uploaded.downloadURL,
            fileUrl = uploaded.downloadURL,
            audioUrl = null,
            timestamp = timestamp,
            type = "image",
            contentType = uploaded.contentType,
            size = uploaded.size,
            fileName = uploaded.fileName
        )
        db.collection("sessions").document(sessionId)
            .collection("messages").document(messageId).set(payload).await()
        return uploaded.downloadURL
    }

    suspend fun sendAudio(sessionId: String, from: String, localUri: Uri): String {
        authRepository.ensureAnonAuth()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val uploaded = uploadSessionMedia(
            endpoint = "${Conn.SERVER_BASE}/api/upload/session-audio",
            sessionId = sessionId,
            messageId = messageId,
            localUri = localUri,
            fallbackMime = "audio/mp4",
            fallbackPrefix = "audio",
            maxBytes = MAX_AUDIO_BYTES
        )

        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = messageId,
            from = from,
            fromName = null,
            text = null,
            imageUrl = null,
            fileUrl = null,
            audioUrl = uploaded.downloadURL,
            timestamp = timestamp,
            type = "audio",
            contentType = uploaded.contentType,
            size = uploaded.size,
            fileName = uploaded.fileName
        )
        db.collection("sessions").document(sessionId)
            .collection("messages").document(messageId).set(payload).await()
        return uploaded.downloadURL
    }

    private suspend fun uploadSessionMedia(
        endpoint: String,
        sessionId: String,
        messageId: String,
        localUri: Uri,
        fallbackMime: String,
        fallbackPrefix: String,
        maxBytes: Long
    ): UploadedMedia {
        val token = authRepository.ensureAnonIdToken(forceRefresh = false)
        if (token.isBlank()) {
            throw IllegalStateException("N\u00e3o foi poss\u00edvel autenticar upload.")
        }

        val fileName = resolveFileName(localUri, fallbackPrefix)
        val contentType = resolveContentType(localUri, fallbackMime)
        val bytes = readBytes(localUri)
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("Arquivo vazio.")
        }
        if (bytes.size.toLong() > maxBytes) {
            throw IllegalArgumentException("Arquivo excede o limite de upload.")
        }

        val requestBody = bytes.toRequestBody(contentType.toMediaTypeOrNull())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("sessionId", sessionId)
            .addFormDataPart("messageId", messageId)
            .addFormDataPart("file", fileName, requestBody)
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()

        val response = httpClient.newCall(request).await()
        response.use { res ->
            val payload = res.body?.string().orEmpty()
            val json = runCatching {
                if (payload.isBlank()) JSONObject() else JSONObject(payload)
            }.getOrDefault(JSONObject())

            if (!res.isSuccessful) {
                val err = json.optString("error").ifBlank {
                    json.optString("message").ifBlank { "Falha no upload via backend" }
                }
                throw IllegalStateException("$err (HTTP ${res.code})")
            }

            val upload = json.optJSONObject("upload") ?: json
            val downloadURL = upload.optString("downloadURL")
                .ifBlank { upload.optString("downloadUrl") }
                .ifBlank { upload.optString("url") }
            if (downloadURL.isBlank()) {
                throw IllegalStateException("Upload conclu\u00eddo sem URL de download.")
            }

            val uploadedMime = upload.optString("contentType")
                .ifBlank { contentType }
                .trim()
                .lowercase()
            val uploadedSize = upload.optLong("size", bytes.size.toLong())
            val uploadedName = upload.optString("fileName")
                .ifBlank { fileName }

            return UploadedMedia(
                downloadURL = downloadURL,
                contentType = uploadedMime,
                size = uploadedSize,
                fileName = uploadedName
            )
        }
    }

    private fun resolveFileName(localUri: Uri, fallbackPrefix: String): String {
        val resolver = appContext.contentResolver
        val fromCursor = runCatching {
            resolver.query(localUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()

        val raw = fromCursor
            ?: localUri.lastPathSegment
            ?: "$fallbackPrefix-${System.currentTimeMillis()}"
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_.-]"), "_").takeLast(120)
        return cleaned.ifBlank { "$fallbackPrefix-${System.currentTimeMillis()}" }
    }

    private fun resolveContentType(localUri: Uri, fallbackMime: String): String {
        val resolver = appContext.contentResolver
        val direct = resolver.getType(localUri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (direct.isNotBlank()) return direct

        val extension = MimeTypeMap.getFileExtensionFromUrl(localUri.toString())
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (extension.isNotBlank()) {
            val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?.trim()
                ?.lowercase()
                .orEmpty()
            if (guessed.isNotBlank()) return guessed
        }
        return fallbackMime
    }

    private fun readBytes(localUri: Uri): ByteArray {
        return appContext.contentResolver.openInputStream(localUri)?.use { it.readBytes() }
            ?: throw IOException("N\u00e3o foi poss\u00edvel ler o arquivo selecionado.")
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
        type: String?,
        contentType: String? = null,
        size: Long? = null,
        fileName: String? = null
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
        contentType?.takeIf { it.isNotBlank() }?.let {
            payload["contentType"] = it
            payload["mimeType"] = it
        }
        size?.takeIf { it > 0 }?.let {
            payload["size"] = it
            payload["fileSize"] = it
        }
        fileName?.takeIf { it.isNotBlank() }?.let { payload["fileName"] = it }
        return payload
    }

    private data class UploadedMedia(
        val downloadURL: String,
        val contentType: String,
        val size: Long,
        val fileName: String
    )

    private companion object {
        const val TAG = "SXS/ChatRepo"
        const val MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
        const val MAX_AUDIO_BYTES = 20L * 1024L * 1024L
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

private suspend fun Call.await(): okhttp3.Response =
    suspendCancellableCoroutine { cont ->
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (!cont.isCancelled) cont.resume(response)
            }
        })

        cont.invokeOnCancellation {
            runCatching { cancel() }
        }
    }
