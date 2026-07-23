package com.suportex.app.call

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcIceConfigRepositoryTest {

    @Test
    fun parserAcceptsOnlySupportedIceSchemes() {
        val now = BASE_NOW
        val parser = RtcIceConfigParser(clock = { now }, expirySafetyMarginMillis = 10_000L)

        val parsed = parser.parse(
            payload(
                now = now,
                servers = listOf(
                    RawRtcIceServer(
                        urls = listOf(
                            "stun:stun.example.com:3478",
                            "stuns:stun.example.com:5349",
                            "turn:turn.example.com:3478?transport=udp",
                            "turns:turn.example.com:5349?transport=tcp",
                            "https://example.com/not-ice",
                            "javascript:ignored"
                        ),
                        username = "temporary-user",
                        credential = "temporary-secret"
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "stun:stun.example.com:3478",
                "stuns:stun.example.com:5349",
                "turn:turn.example.com:3478?transport=udp",
                "turns:turn.example.com:5349?transport=tcp"
            ),
            parsed?.iceServers?.single()?.urls
        )
    }

    @Test
    fun parserDropsTurnWithoutCredentialsButKeepsStun() {
        val now = BASE_NOW + 1_000_000L
        val parser = RtcIceConfigParser(clock = { now }, expirySafetyMarginMillis = 10_000L)

        val parsed = parser.parse(
            payload(
                now = now,
                servers = listOf(
                    RawRtcIceServer(
                        urls = listOf(
                            "turn:turn.example.com:3478",
                            "stun:stun.example.com:3478"
                        ),
                        username = null,
                        credential = null
                    )
                )
            )
        )

        val server = parsed?.iceServers?.single()
        assertEquals(listOf("stun:stun.example.com:3478"), server?.urls)
        assertNull(server?.username)
        assertNull(server?.credential)
    }

    @Test
    fun parserBoundsServerAndUrlCounts() {
        val now = BASE_NOW + 2_000_000L
        val parser = RtcIceConfigParser(clock = { now }, expirySafetyMarginMillis = 10_000L)
        val rawServers = (0 until 24).map { serverIndex ->
            RawRtcIceServer(
                urls = (0 until 20).map { urlIndex ->
                    "stun:stun-$serverIndex.example.com:${3400 + urlIndex}"
                },
                username = null,
                credential = null
            )
        }

        val parsed = parser.parse(payload(now = now, servers = rawServers))

        val servers = parsed?.iceServers.orEmpty()
        assertTrue(servers.size <= RtcIceConfigParser.MAX_ICE_SERVERS)
        assertTrue(servers.all { it.urls.size <= RtcIceConfigParser.MAX_URLS_PER_SERVER })
        assertTrue(servers.sumOf { it.urls.size } <= RtcIceConfigParser.MAX_TOTAL_URLS)
    }

    @Test
    fun parserRejectsExpiredOrOversizedTurnCredentials() {
        val now = BASE_NOW + 3_000_000L
        val parser = RtcIceConfigParser(clock = { now }, expirySafetyMarginMillis = 60_000L)
        val oversizedSecret = "x".repeat(RtcIceConfigParser.MAX_SECRET_LENGTH + 1)

        val expired = parser.parse(
            RawRtcIcePayload(
                iceServers = listOf(stunServer()),
                expiresAt = now + 30_000L,
                ttlSeconds = null,
                source = "test"
            )
        )
        val oversized = parser.parse(
            RawRtcIcePayload(
                iceServers = listOf(
                    RawRtcIceServer(
                        urls = listOf("turn:turn.example.com:3478"),
                        username = "user",
                        credential = oversizedSecret
                    )
                ),
                expiresAt = now + 300_000L,
                ttlSeconds = null,
                source = "test"
            )
        )

        assertNull(expired)
        assertNull(oversized)
    }

    @Test
    fun parserUsesEarliestExpiryFromExpiresAtAndTtl() {
        val now = BASE_NOW + 4_000_000L
        val parser = RtcIceConfigParser(clock = { now }, expirySafetyMarginMillis = 10_000L)

        val parsed = parser.parse(
            RawRtcIcePayload(
                iceServers = listOf(stunServer()),
                expiresAt = now + 600_000L,
                ttlSeconds = 120L,
                source = "test"
            )
        )

        assertEquals(now + 120_000L, parsed?.expiresAtEpochMillis)
    }

    @Test
    fun memoryCacheStopsServingBeforeCredentialExpiry() {
        var now = BASE_NOW + 5_000_000L
        val cache = RtcIceMemoryCache(
            clock = { now },
            expirySafetyMarginMillis = 60_000L,
            maximumEntries = 2
        )
        val config = RtcIceConfig(
            iceServers = listOf(
                RtcIceServer(
                    urls = listOf("turn:turn.example.com:3478"),
                    username = "user",
                    credential = "secret"
                )
            ),
            expiresAtEpochMillis = now + 120_000L,
            source = "test"
        )
        cache.put("session-a", config)

        now += 59_999L
        assertEquals(1, cache.size())
        now += 1L
        assertNull(cache.get("session-a"))
        assertEquals(0, cache.size())
    }

    @Test
    fun repositoryRefreshesTokenOnceAfterUnauthorized() = runBlocking {
        val now = BASE_NOW + 6_000_000L
        val forceRefreshCalls = mutableListOf<Boolean>()
        val bearerTokens = mutableListOf<String>()
        val tokenProvider = object : RtcIceAuthTokenProvider {
            override suspend fun getToken(forceRefresh: Boolean): String {
                forceRefreshCalls += forceRefresh
                return if (forceRefresh) "fresh-token" else "stale-token"
            }
        }
        val transport = object : RtcIceHttpTransport {
            override suspend fun getIceConfig(
                sessionId: String,
                bearerToken: String
            ): RtcIceHttpResponse {
                bearerTokens += bearerToken
                return if (bearerTokens.size == 1) {
                    RtcIceHttpResponse(statusCode = 401, body = null)
                } else {
                    RtcIceHttpResponse(statusCode = 200, body = "valid")
                }
            }
        }
        val repository = repository(
            now = now,
            tokenProvider = tokenProvider,
            transport = transport
        )

        val result = repository.getIceConfig("session-1")

        assertFalse(result.isStunFallback)
        assertEquals(listOf(false, true), forceRefreshCalls)
        assertEquals(listOf("stale-token", "fresh-token"), bearerTokens)
        assertFalse(result.toString().contains("fresh-token"))
    }

    @Test
    fun repositoryDeduplicatesConcurrentFetchesForSameSession() = runBlocking {
        val now = BASE_NOW + 7_000_000L
        val requestCount = AtomicInteger(0)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val transport = object : RtcIceHttpTransport {
            override suspend fun getIceConfig(
                sessionId: String,
                bearerToken: String
            ): RtcIceHttpResponse {
                requestCount.incrementAndGet()
                requestStarted.complete(Unit)
                releaseRequest.await()
                return RtcIceHttpResponse(statusCode = 200, body = "valid")
            }
        }
        val repository = repository(
            now = now,
            tokenProvider = fixedTokenProvider(),
            transport = transport
        )

        val first = async { repository.getIceConfig("session-shared") }
        requestStarted.await()
        val second = async { repository.getIceConfig("session-shared") }
        yield()
        delay(20L)
        assertEquals(1, requestCount.get())

        releaseRequest.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, requestCount.get())
    }

    @Test
    fun repositoryFallsBackToSafeStunOnTimeout() = runBlocking {
        val now = BASE_NOW + 8_000_000L
        val transport = object : RtcIceHttpTransport {
            override suspend fun getIceConfig(
                sessionId: String,
                bearerToken: String
            ): RtcIceHttpResponse {
                delay(5_000L)
                return RtcIceHttpResponse(statusCode = 200, body = "valid")
            }
        }
        val repository = repository(
            now = now,
            tokenProvider = fixedTokenProvider(),
            transport = transport,
            timeoutMillis = 20L
        )

        val result = repository.getIceConfig("session-timeout")

        assertTrue(result.isStunFallback)
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), result.iceServers.single().urls)
        assertNull(result.iceServers.single().username)
        assertNull(result.iceServers.single().credential)
    }

    @Test
    fun sensitiveValuesAreRedactedFromStringRepresentations() {
        val server = RtcIceServer(
            urls = listOf("turn:turn.example.com:3478"),
            username = "private-user",
            credential = "private-credential"
        )
        val config = RtcIceConfig(
            iceServers = listOf(server),
            expiresAtEpochMillis = BASE_NOW + 300_000L,
            source = "test"
        )

        assertFalse(server.toString().contains("private-user"))
        assertFalse(server.toString().contains("private-credential"))
        assertFalse(config.toString().contains("private-user"))
        assertFalse(config.toString().contains("private-credential"))
    }

    private fun repository(
        now: Long,
        tokenProvider: RtcIceAuthTokenProvider,
        transport: RtcIceHttpTransport,
        timeoutMillis: Long = 2_000L
    ): RtcIceConfigRepository {
        val decoder = object : RtcIcePayloadDecoder {
            override fun decode(body: String): RawRtcIcePayload? {
                return if (body == "valid") {
                    payload(now = now, servers = listOf(stunServer()))
                } else {
                    null
                }
            }
        }
        return RtcIceConfigRepository(
            tokenProvider = tokenProvider,
            transport = transport,
            decoder = decoder,
            clock = { now },
            parser = RtcIceConfigParser(
                clock = { now },
                expirySafetyMarginMillis = 10_000L
            ),
            cache = RtcIceMemoryCache(
                clock = { now },
                expirySafetyMarginMillis = 10_000L
            ),
            requestTimeoutMillis = timeoutMillis
        )
    }

    private fun fixedTokenProvider(): RtcIceAuthTokenProvider {
        return object : RtcIceAuthTokenProvider {
            override suspend fun getToken(forceRefresh: Boolean): String = "token"
        }
    }

    private fun payload(
        now: Long,
        servers: List<RawRtcIceServer>
    ): RawRtcIcePayload {
        return RawRtcIcePayload(
            iceServers = servers,
            expiresAt = now + 300_000L,
            ttlSeconds = null,
            source = "cloudflare_turn"
        )
    }

    private fun stunServer(): RawRtcIceServer {
        return RawRtcIceServer(
            urls = listOf("stun:stun.cloudflare.com:3478"),
            username = null,
            credential = null
        )
    }

    private companion object {
        const val BASE_NOW = 1_700_000_000_000L
    }
}
