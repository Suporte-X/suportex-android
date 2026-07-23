package com.suportex.app.call

import com.suportex.app.Conn
import com.suportex.app.data.AuthRepository
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class RtcIceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
) {
    override fun toString(): String {
        return "RtcIceServer(urlCount=${urls.size}, hasUsername=${username != null}, " +
            "hasCredential=${credential != null})"
    }
}

internal data class RtcIceConfig(
    val iceServers: List<RtcIceServer>,
    val expiresAtEpochMillis: Long,
    val source: String
) {
    val isStunFallback: Boolean
        get() = source == SOURCE_CLIENT_STUN_FALLBACK

    fun copyForCaller(): RtcIceConfig {
        return copy(
            iceServers = iceServers.map { server ->
                server.copy(urls = server.urls.toList())
            }
        )
    }

    internal companion object {
        const val SOURCE_CLIENT_STUN_FALLBACK = "client_stun_fallback"
        private const val FALLBACK_TTL_MILLIS = 2 * 60 * 1000L
        private const val SAFE_STUN_URL = "stun:stun.cloudflare.com:3478"

        fun safeStunFallback(nowEpochMillis: Long): RtcIceConfig {
            return RtcIceConfig(
                iceServers = listOf(RtcIceServer(urls = listOf(SAFE_STUN_URL))),
                expiresAtEpochMillis = nowEpochMillis + FALLBACK_TTL_MILLIS,
                source = SOURCE_CLIENT_STUN_FALLBACK
            )
        }
    }
}

internal data class RawRtcIceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

internal data class RawRtcIcePayload(
    val iceServers: List<RawRtcIceServer>,
    val expiresAt: Long?,
    val ttlSeconds: Long?,
    val source: String?
)

internal class RtcIceConfigParser(
    private val clock: () -> Long = System::currentTimeMillis,
    private val expirySafetyMarginMillis: Long = DEFAULT_EXPIRY_MARGIN_MILLIS
) {
    fun parse(payload: RawRtcIcePayload): RtcIceConfig? {
        val now = clock()
        val expiresAt = resolveExpiry(payload, now) ?: return null
        if (expiresAt - now <= expirySafetyMarginMillis.coerceAtLeast(0L)) return null

        val parsedServers = mutableListOf<RtcIceServer>()
        var totalUrlCount = 0

        payload.iceServers
            .take(MAX_INPUT_SERVERS)
            .forEach { rawServer ->
                if (
                    parsedServers.size >= MAX_ICE_SERVERS ||
                    totalUrlCount >= MAX_TOTAL_URLS
                ) {
                    return@forEach
                }

                val remainingUrls = MAX_TOTAL_URLS - totalUrlCount
                val validUrls = rawServer.urls
                    .asSequence()
                    .map(String::trim)
                    .filter(::isAllowedIceUrl)
                    .distinct()
                    .take(minOf(MAX_URLS_PER_SERVER, remainingUrls))
                    .toList()
                if (validUrls.isEmpty()) return@forEach

                val stunUrls = validUrls.filter(::isStunUrl)
                val turnUrls = validUrls.filter(::isTurnUrl)
                val username = boundedSecret(rawServer.username)
                val credential = boundedSecret(rawServer.credential)

                val parsedServer = when {
                    turnUrls.isNotEmpty() && username != null && credential != null -> {
                        RtcIceServer(
                            urls = validUrls,
                            username = username,
                            credential = credential
                        )
                    }
                    stunUrls.isNotEmpty() -> RtcIceServer(urls = stunUrls)
                    else -> null
                } ?: return@forEach

                parsedServers += parsedServer
                totalUrlCount += parsedServer.urls.size
            }

        if (parsedServers.isEmpty()) return null
        val source = payload.source
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SOURCE_LENGTH)
            ?: "server"

        return RtcIceConfig(
            iceServers = parsedServers.toList(),
            expiresAtEpochMillis = expiresAt,
            source = source
        )
    }

    private fun resolveExpiry(payload: RawRtcIcePayload, now: Long): Long? {
        val candidates = mutableListOf<Long>()
        payload.expiresAt
            ?.let(::normalizeEpochMillis)
            ?.takeIf { it > now }
            ?.let(candidates::add)

        payload.ttlSeconds
            ?.takeIf { it > 0L }
            ?.coerceAtMost(MAX_TTL_SECONDS)
            ?.let { ttlSeconds ->
                val ttlMillis = ttlSeconds * 1000L
                candidates += safeAdd(now, ttlMillis)
            }

        if (candidates.isEmpty()) return null
        val maximumAllowedExpiry = safeAdd(now, MAX_TTL_SECONDS * 1000L)
        return candidates.minOrNull()?.coerceAtMost(maximumAllowedExpiry)
    }

    private fun normalizeEpochMillis(value: Long): Long? {
        if (value <= 0L) return null
        return if (value < MIN_REASONABLE_EPOCH_MILLIS) {
            if (value > Long.MAX_VALUE / 1000L) null else value * 1000L
        } else {
            value
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        return if (right > 0L && left > Long.MAX_VALUE - right) {
            Long.MAX_VALUE
        } else {
            left + right
        }
    }

    private fun boundedSecret(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalized.takeIf { it.length <= MAX_SECRET_LENGTH }
    }

    private fun isAllowedIceUrl(value: String): Boolean {
        if (
            value.isBlank() ||
            value.length > MAX_URL_LENGTH ||
            value.any(Char::isISOControl)
        ) {
            return false
        }
        val scheme = runCatching { URI(value).scheme }
            .getOrNull()
            ?.lowercase()
            ?: return false
        return scheme in ALLOWED_SCHEMES
    }

    private fun isStunUrl(value: String): Boolean {
        val scheme = value.substringBefore(':', "").lowercase()
        return scheme == "stun" || scheme == "stuns"
    }

    private fun isTurnUrl(value: String): Boolean {
        val scheme = value.substringBefore(':', "").lowercase()
        return scheme == "turn" || scheme == "turns"
    }

    internal companion object {
        const val DEFAULT_EXPIRY_MARGIN_MILLIS = 60_000L
        const val MAX_ICE_SERVERS = 8
        const val MAX_INPUT_SERVERS = 16
        const val MAX_URLS_PER_SERVER = 12
        const val MAX_TOTAL_URLS = 32
        const val MAX_URL_LENGTH = 512
        const val MAX_SECRET_LENGTH = 4096
        const val MAX_SOURCE_LENGTH = 64
        const val MAX_TTL_SECONDS = 86_400L
        private const val MIN_REASONABLE_EPOCH_MILLIS = 10_000_000_000L
        private val ALLOWED_SCHEMES = setOf("stun", "stuns", "turn", "turns")
    }
}

internal class RtcIceMemoryCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val expirySafetyMarginMillis: Long =
        RtcIceConfigParser.DEFAULT_EXPIRY_MARGIN_MILLIS,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES
) {
    private val entries = LinkedHashMap<String, RtcIceConfig>()

    @Synchronized
    fun get(sessionId: String): RtcIceConfig? {
        pruneExpired()
        return entries[sessionId]?.copyForCaller()
    }

    @Synchronized
    fun put(sessionId: String, config: RtcIceConfig) {
        pruneExpired()
        if (!isUsable(config)) return
        entries.remove(sessionId)
        entries[sessionId] = config.copyForCaller()
        while (entries.size > maximumEntries.coerceAtLeast(1)) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun remove(sessionId: String) {
        entries.remove(sessionId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        pruneExpired()
        return entries.size
    }

    private fun pruneExpired() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (!isUsable(iterator.next().value)) iterator.remove()
        }
    }

    private fun isUsable(config: RtcIceConfig): Boolean {
        return config.expiresAtEpochMillis - clock() >
            expirySafetyMarginMillis.coerceAtLeast(0L)
    }

    private companion object {
        const val DEFAULT_MAXIMUM_ENTRIES = 16
    }
}

internal interface RtcIceAuthTokenProvider {
    suspend fun getToken(forceRefresh: Boolean): String
}

internal class AuthRepositoryRtcIceTokenProvider(
    private val authRepository: AuthRepository
) : RtcIceAuthTokenProvider {
    override suspend fun getToken(forceRefresh: Boolean): String {
        return authRepository.ensureAnonIdToken(forceRefresh)
    }
}

internal data class RtcIceHttpResponse(
    val statusCode: Int,
    val body: String?
)

internal interface RtcIceHttpTransport {
    suspend fun getIceConfig(
        sessionId: String,
        bearerToken: String
    ): RtcIceHttpResponse
}

internal class OkHttpRtcIceTransport(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = Conn.SERVER_BASE
) : RtcIceHttpTransport {
    override suspend fun getIceConfig(
        sessionId: String,
        bearerToken: String
    ): RtcIceHttpResponse {
        val url = "${baseUrl.trimEnd('/')}/api/webrtc/ice-config"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .build()

        return httpClient.newCall(request).awaitBoundedResponse()
    }

    private suspend fun Call.awaitBoundedResponse(): RtcIceHttpResponse {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val body = if (it.isSuccessful) {
                                readBoundedBody(it)
                            } else {
                                null
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    RtcIceHttpResponse(
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
    }

    private fun readBoundedBody(response: Response): String? {
        val body = response.body ?: return null
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BYTES) return null

        val result = StringBuilder()
        val buffer = CharArray(READ_BUFFER_CHARS)
        body.charStream().use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (result.length + read > MAX_RESPONSE_CHARS) return null
                result.append(buffer, 0, read)
            }
        }
        return result.toString()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        const val MAX_RESPONSE_CHARS = 64 * 1024
        const val READ_BUFFER_CHARS = 2048

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal interface RtcIcePayloadDecoder {
    fun decode(body: String): RawRtcIcePayload?
}

internal object JsonRtcIcePayloadDecoder : RtcIcePayloadDecoder {
    override fun decode(body: String): RawRtcIcePayload? {
        if (body.isBlank() || body.length > MAX_JSON_CHARS) return null
        return runCatching {
            val root = JSONObject(body)
            val serversJson = root.optJSONArray("iceServers") ?: return null
            val servers = buildList {
                repeat(minOf(serversJson.length(), RtcIceConfigParser.MAX_INPUT_SERVERS)) { index ->
                    val server = serversJson.optJSONObject(index) ?: return@repeat
                    val urls = decodeUrls(server.opt("urls"))
                    add(
                        RawRtcIceServer(
                            urls = urls,
                            username = server.optionalString("username"),
                            credential = server.optionalString("credential")
                        )
                    )
                }
            }
            RawRtcIcePayload(
                iceServers = servers,
                expiresAt = readLong(root, "expiresAt"),
                ttlSeconds = readLong(root, "ttlSeconds") ?: readLong(root, "ttl"),
                source = root.optionalString("source")
            )
        }.getOrNull()
    }

    private fun decodeUrls(value: Any?): List<String> {
        return when (value) {
            is String -> listOf(value)
            is JSONArray -> buildList {
                repeat(minOf(value.length(), RtcIceConfigParser.MAX_URLS_PER_SERVER * 2)) { index ->
                    val url = value.optString(index, "")
                    if (url.isNotBlank()) add(url)
                }
            }
            else -> emptyList()
        }
    }

    private fun readLong(root: JSONObject, key: String): Long? {
        return when (val value = root.opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) {
            null
        } else {
            optString(key, "").trim().takeIf { it.isNotBlank() }
        }

    private const val MAX_JSON_CHARS = 64 * 1024
}

internal class RtcIceConfigRepository(
    private val tokenProvider: RtcIceAuthTokenProvider =
        AuthRepositoryRtcIceTokenProvider(AuthRepository()),
    private val transport: RtcIceHttpTransport = OkHttpRtcIceTransport(),
    private val decoder: RtcIcePayloadDecoder = JsonRtcIcePayloadDecoder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val parser: RtcIceConfigParser = RtcIceConfigParser(clock),
    private val cache: RtcIceMemoryCache = RtcIceMemoryCache(clock),
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val repositoryScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private sealed interface LoadSelection {
        data class Cached(val config: RtcIceConfig) : LoadSelection
        data class Pending(val deferred: Deferred<RtcIceConfig>) : LoadSelection
    }

    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<RtcIceConfig>>()

    suspend fun getIceConfig(sessionId: String): RtcIceConfig {
        val normalizedSessionId = sessionId.trim()
        if (!SESSION_ID_PATTERN.matches(normalizedSessionId)) {
            return RtcIceConfig.safeStunFallback(clock())
        }

        val selection = inFlightMutex.withLock {
            cache.get(normalizedSessionId)?.let { cached ->
                return@withLock LoadSelection.Cached(cached)
            }
            inFlight[normalizedSessionId]?.let { pending ->
                return@withLock LoadSelection.Pending(pending)
            }

            val created = repositoryScope.async(start = CoroutineStart.LAZY) {
                loadRemoteOrFallback(normalizedSessionId).also { loaded ->
                    cache.put(normalizedSessionId, loaded)
                }
            }
            inFlight[normalizedSessionId] = created
            created.invokeOnCompletion {
                repositoryScope.launch {
                    inFlightMutex.withLock {
                        if (inFlight[normalizedSessionId] === created) {
                            inFlight.remove(normalizedSessionId)
                        }
                    }
                }
            }
            created.start()
            LoadSelection.Pending(created)
        }

        return when (selection) {
            is LoadSelection.Cached -> selection.config.copyForCaller()
            is LoadSelection.Pending -> selection.deferred.await().copyForCaller()
        }
    }

    suspend fun clearSession(sessionId: String) {
        val normalizedSessionId = sessionId.trim()
        cache.remove(normalizedSessionId)
        inFlightMutex.withLock {
            inFlight.remove(normalizedSessionId)?.cancel()
        }
    }

    suspend fun clearMemory() {
        cache.clear()
        inFlightMutex.withLock {
            inFlight.values.forEach(Deferred<RtcIceConfig>::cancel)
            inFlight.clear()
        }
    }

    private suspend fun loadRemoteOrFallback(sessionId: String): RtcIceConfig {
        val firstResponse = request(sessionId, forceTokenRefresh = false)
            ?: return fallback()
        val finalResponse = if (firstResponse.statusCode == HTTP_UNAUTHORIZED) {
            request(sessionId, forceTokenRefresh = true)
                ?: return fallback()
        } else {
            firstResponse
        }

        if (finalResponse.statusCode !in 200..299) return fallback()
        val body = finalResponse.body ?: return fallback()
        val payload = decoder.decode(body) ?: return fallback()
        return parser.parse(payload) ?: fallback()
    }

    private suspend fun request(
        sessionId: String,
        forceTokenRefresh: Boolean
    ): RtcIceHttpResponse? {
        val token = try {
            tokenProvider.getToken(forceTokenRefresh).trim()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        if (token.isBlank() || token.length > MAX_BEARER_TOKEN_LENGTH) return null

        return try {
            withTimeoutOrNull(requestTimeoutMillis.coerceAtLeast(1L)) {
                transport.getIceConfig(sessionId, token)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun fallback(): RtcIceConfig {
        return RtcIceConfig.safeStunFallback(clock())
    }

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 8_000L
        const val HTTP_UNAUTHORIZED = 401
        const val MAX_BEARER_TOKEN_LENGTH = 16_384
        val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
