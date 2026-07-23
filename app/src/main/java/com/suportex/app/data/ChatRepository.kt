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
import io.socket.client.Ack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun isEquivalentPersistedOutgoingMessage(
    existing: Map<String, Any?>,
    expected: Map<String, Any?>
): Boolean {
    fun normalizedText(value: Any?): String? =
        (value as? String)?.trim()?.takeIf { it.isNotBlank() }

    fun normalizedNumber(value: Any?): Long? =
        (value as? Number)?.toLong()

    val requiredStringKeys = listOf("id", "sessionId", "from", "type", "status")
    if (requiredStringKeys.any { key ->
            normalizedText(existing[key]) != normalizedText(expected[key])
        }
    ) {
        return false
    }

    val optionalStringKeys = listOf(
        "text",
        "imageUrl",
        "fileUrl",
        "audioUrl",
        "contentType",
        "mimeType",
        "fileName"
    )
    if (optionalStringKeys.any { key ->
            normalizedText(existing[key]) != normalizedText(expected[key])
        }
    ) {
        return false
    }

    return normalizedNumber(existing["size"]) == normalizedNumber(expected["size"]) &&
        normalizedNumber(existing["fileSize"]) == normalizedNumber(expected["fileSize"])
}

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

    suspend fun sendText(
        sessionId: String,
        from: String,
        text: String,
        messageId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): String {
        authRepository.ensureAnonAuth()
        val normalizedText = text.trim().take(MAX_TEXT_CHARS)
        require(normalizedText.isNotBlank()) { "Mensagem vazia." }
        val payload = buildMessagePayload(
            sessionId = sessionId,
            id = messageId,
            from = normalizedClientSender(from),
            fromName = null,
            text = normalizedText,
            imageUrl = null,
            fileUrl = null,
            audioUrl = null,
            timestamp = timestamp,
            type = "text"
        )
        persistOutgoingMessage(sessionId, messageId, payload)
        return messageId
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
            from = normalizedClientSender(from),
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
        persistOutgoingMessage(sessionId, messageId, payload)
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
            from = normalizedClientSender(from),
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
        persistOutgoingMessage(sessionId, messageId, payload)
        return uploaded.downloadURL
    }

    private suspend fun persistOutgoingMessage(
        sessionId: String,
        messageId: String,
        payload: Map<String, Any?>
    ) {
        authRepository.ensureAnonAuth()
        val socketPayload = JSONObject().apply {
            payload.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }
        val socket = Conn.socket
        val persistedByServer = if (socket?.connected() == true) {
            val acknowledgment = CompletableDeferred<Boolean>()
            socket.emit(
                "session:chat:send",
                socketPayload,
                Ack { args ->
                    val response = args.firstOrNull() as? JSONObject
                    if (!acknowledgment.isCompleted) {
                        acknowledgment.complete(response?.optBoolean("ok", false) == true)
                    }
                }
            )
            withTimeoutOrNull(CHAT_ACK_TIMEOUT_MS) { acknowledgment.await() } == true
        } else {
            false
        }

        if (persistedByServer) return

        // Fallback idempotente para uma queda transitória do Socket. As regras
        // permitem apenas criar mensagem do próprio cliente na sessão vinculada.
        val messageRef = db.collection("sessions").document(sessionId)
            .collection("messages").document(messageId)
        try {
            messageRef.set(payload).await()
        } catch (writeError: Throwable) {
            val existing = runCatching { messageRef.get().await() }.getOrNull()
            val existingData = existing
                ?.takeIf { it.exists() }
                ?.data
                .orEmpty()
            if (isEquivalentPersistedOutgoingMessage(existingData, payload)) {
                return
            }
            throw writeError
        }
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
        val fileName = resolveFileName(localUri, fallbackPrefix)
        val contentType = resolveContentType(localUri, fallbackMime)
        val declaredSize = resolveFileSize(localUri)
        if (declaredSize == 0L) {
            throw IllegalArgumentException("Arquivo vazio.")
        }
        if (declaredSize != null && declaredSize > maxBytes) {
            throw IllegalArgumentException("Arquivo excede o limite de upload.")
        }

        val requestBody = BoundedStreamRequestBody(
            mediaType = contentType.toMediaTypeOrNull(),
            declaredLength = declaredSize,
            maxBytes = maxBytes,
            openStream = {
                appContext.contentResolver.openInputStream(localUri)
                    ?: throw IOException("Não foi possível ler o arquivo selecionado.")
            }
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("sessionId", sessionId)
            .addFormDataPart("messageId", messageId)
            .addFormDataPart("file", fileName, requestBody)
            .build()

        var lastError: Throwable? = null
        repeat(2) { attempt ->
            if (attempt > 0) {
                warmUpBackend()
                delay(700)
            }

            val token = authRepository.ensureAnonIdToken(forceRefresh = attempt > 0)
            if (token.isBlank()) {
                throw IllegalStateException("N\u00e3o foi poss\u00edvel autenticar upload.")
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Authorization", "Bearer $token")
                .build()

            val result = runCatching {
                httpClient.newCall(request).await().use { res ->
                    parseUploadResponse(
                        response = res,
                        fallbackContentType = contentType,
                        fallbackSize = declaredSize ?: requestBody.transferredByteCount,
                        fallbackFileName = fileName
                    )
                }
            }

            result.onSuccess { return it }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "uploadSessionMedia failed attempt=${attempt + 1}", lastError)
        }

        throw lastError ?: IllegalStateException("Falha no upload via backend")
    }

    private fun parseUploadResponse(
        response: okhttp3.Response,
        fallbackContentType: String,
        fallbackSize: Long,
        fallbackFileName: String
    ): UploadedMedia {
        val payload = response.body?.string().orEmpty()
        val json = runCatching {
            if (payload.isBlank()) JSONObject() else JSONObject(payload)
        }.getOrDefault(JSONObject())

        if (!response.isSuccessful) {
            val err = json.optString("error").ifBlank {
                json.optString("message").ifBlank { "Falha no upload via backend" }
            }
            throw IllegalStateException("$err (HTTP ${response.code})")
        }

        val upload = json.optJSONObject("upload") ?: json
        val downloadURL = upload.optString("downloadURL")
            .ifBlank { upload.optString("downloadUrl") }
            .ifBlank { upload.optString("url") }
        if (downloadURL.isBlank()) {
            throw IllegalStateException("Upload conclu\u00eddo sem URL de download.")
        }

        val uploadedMime = upload.optString("contentType")
            .ifBlank { fallbackContentType }
            .trim()
            .lowercase()
        val uploadedSize = upload.optLong("size", fallbackSize)
        val uploadedName = upload.optString("fileName")
            .ifBlank { fallbackFileName }

        return UploadedMedia(
            downloadURL = downloadURL,
            contentType = uploadedMime,
            size = uploadedSize,
            fileName = uploadedName
        )
    }

    private suspend fun warmUpBackend() {
        runCatching {
            val request = Request.Builder()
                .url("${Conn.SERVER_BASE}/healthz")
                .get()
                .build()
            httpClient.newCall(request).await().close()
        }.onFailure { err ->
            Log.w(TAG, "warmUpBackend failed before upload retry", err)
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

    private fun resolveFileSize(localUri: Uri): Long? {
        val resolver = appContext.contentResolver
        val cursorSize = runCatching {
            resolver.query(localUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index).coerceAtLeast(0L)
            }
        }.getOrNull()
        if (cursorSize != null) return cursorSize

        return runCatching {
            resolver.openAssetFileDescriptor(localUri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
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

    private fun normalizedClientSender(@Suppress("UNUSED_PARAMETER") requested: String): String =
        "client"

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
        const val MAX_TEXT_CHARS = 2_000
        const val CHAT_ACK_TIMEOUT_MS = 8_000L
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
