package com.suportex.app.data

import com.suportex.app.Conn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RecoveredActiveSupportSession(
    val sessionId: String,
    val techName: String?,
    val status: String
)

internal data class ActiveSupportSessionLookupPayload(
    val ok: Boolean,
    val active: Boolean,
    val sessionId: String?,
    val techName: String?,
    val status: String?
)

internal data class ActiveSupportSessionHttpResponse(
    val statusCode: Int,
    val body: String?
)

internal interface ActiveSupportSessionTokenProvider {
    suspend fun getToken(forceRefresh: Boolean): String
}

internal class AuthRepositoryActiveSupportSessionTokenProvider(
    private val authRepository: AuthRepository
) : ActiveSupportSessionTokenProvider {
    override suspend fun getToken(forceRefresh: Boolean): String =
        authRepository.ensureAnonIdToken(forceRefresh)
}

internal interface ActiveSupportSessionHttpTransport {
    suspend fun findActiveSession(
        localSupportSessionId: String,
        bearerToken: String
    ): ActiveSupportSessionHttpResponse
}

internal fun interface ActiveSupportSessionPayloadDecoder {
    fun decode(body: String): ActiveSupportSessionLookupPayload?
}

internal object JsonActiveSupportSessionPayloadDecoder : ActiveSupportSessionPayloadDecoder {
    override fun decode(body: String): ActiveSupportSessionLookupPayload? {
        if (body.isBlank() || body.length > MAX_JSON_CHARS) return null
        return runCatching {
            val root = JSONObject(body)
            ActiveSupportSessionLookupPayload(
                ok = root.optBoolean("ok", false),
                active = root.optBoolean("active", false),
                sessionId = root.optionalString("sessionId"),
                techName = root.optionalString("techName"),
                status = root.optionalString("status")?.lowercase()
            )
        }.getOrNull()
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) {
            null
        } else {
            optString(key, "").trim().takeIf { it.isNotBlank() }
        }

    private const val MAX_JSON_CHARS = 64 * 1024
}

internal class OkHttpActiveSupportSessionTransport(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = Conn.SERVER_BASE
) : ActiveSupportSessionHttpTransport {
    override suspend fun findActiveSession(
        localSupportSessionId: String,
        bearerToken: String
    ): ActiveSupportSessionHttpResponse {
        val url = "${baseUrl.trimEnd('/')}/api/client/support-session/active"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("localSupportSessionId", localSupportSessionId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .build()

        return httpClient.newCall(request).awaitBoundedResponse()
    }

    private suspend fun Call.awaitBoundedResponse(): ActiveSupportSessionHttpResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val body = if (it.isSuccessful) readBoundedBody(it) else null
                            if (continuation.isActive) {
                                continuation.resume(
                                    ActiveSupportSessionHttpResponse(
                                        statusCode = it.code,
                                        body = body
                                    )
                                )
                            }
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun readBoundedBody(response: Response): String? {
        val body = response.body ?: return null
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BYTES) return null

        val output = StringBuilder()
        val buffer = CharArray(READ_BUFFER_CHARS)
        body.charStream().use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (output.length + read > MAX_RESPONSE_CHARS) return null
                output.append(buffer, 0, read)
            }
        }
        return output.toString()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        const val MAX_RESPONSE_CHARS = 64 * 1024
        const val READ_BUFFER_CHARS = 2048

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
    }
}

internal class ActiveSupportSessionRecoveryRepository(
    private val tokenProvider: ActiveSupportSessionTokenProvider,
    private val transport: ActiveSupportSessionHttpTransport,
    private val decoder: ActiveSupportSessionPayloadDecoder =
        JsonActiveSupportSessionPayloadDecoder
) {
    constructor(
        authRepository: AuthRepository = AuthRepository()
    ) : this(
        tokenProvider = AuthRepositoryActiveSupportSessionTokenProvider(authRepository),
        transport = OkHttpActiveSupportSessionTransport()
    )

    suspend fun findActiveSession(
        localSupportSessionId: String?
    ): RecoveredActiveSupportSession? {
        val normalizedLocalId = localSupportSessionId
            ?.trim()
            ?.takeIf(LOCAL_SUPPORT_SESSION_ID_PATTERN::matches)
            ?: return null

        for (attempt in 0..1) {
            val token = try {
                tokenProvider.getToken(forceRefresh = attempt == 1).trim()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
            if (token.isBlank()) return null

            val response = try {
                transport.findActiveSession(
                    localSupportSessionId = normalizedLocalId,
                    bearerToken = token
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }

            if (response.statusCode == HTTP_UNAUTHORIZED && attempt == 0) {
                continue
            }
            if (response.statusCode != HTTP_OK) return null

            val payload = response.body?.let(decoder::decode) ?: return null
            if (!payload.ok || !payload.active) return null

            val sessionId = payload.sessionId
                ?.trim()
                ?.takeIf(REALTIME_SESSION_ID_PATTERN::matches)
                ?: return null
            val status = payload.status
                ?.trim()
                ?.lowercase()
                ?.takeIf(ACTIVE_SESSION_STATUSES::contains)
                ?: return null
            val techName = payload.techName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_TECH_NAME_LENGTH)

            return RecoveredActiveSupportSession(
                sessionId = sessionId,
                techName = techName,
                status = status
            )
        }
        return null
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val MAX_TECH_NAME_LENGTH = 160
        val ACTIVE_SESSION_STATUSES = setOf("active", "accepted", "in_progress")
        val LOCAL_SUPPORT_SESSION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val REALTIME_SESSION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
