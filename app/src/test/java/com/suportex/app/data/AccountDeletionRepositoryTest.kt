package com.suportex.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountDeletionRepositoryTest {

    @Test
    fun `envia contrato autenticado com confirmacao chave estavel e PNV opcional`() = runBlocking {
        val tokenProvider = RecordingTokenProvider(listOf("firebase-token"))
        val transport = RecordingTransport {
            AccountDeletionHttpResponse(
                statusCode = 200,
                body = """{"ok":true,"deleted":true,"deletedAt":1720000000000,"deletedCounts":{},"retained":[]}"""
            )
        }
        val repository = repository(tokenProvider, transport)

        val result = repository.deleteAccount(
            idempotencyKey = "delete-request-123",
            pnvPhone = "+5565999999999",
            pnvToken = "pnv-secret-token"
        )

        assertEquals(
            AccountDeletionResult.Success(deletedAtEpochMillis = 1_720_000_000_000),
            result
        )
        assertEquals(listOf(false), tokenProvider.forceRefreshCalls)
        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals(
            "https://suportex.test/api/client/account/delete",
            request.url
        )
        assertEquals("firebase-token", request.bearerToken)
        assertEquals("delete-request-123", request.idempotencyKey)
        assertEquals(
            """{"confirmation":"EXCLUIR CONTA","pnvPhone":"+5565999999999","pnvToken":"pnv-secret-token"}""",
            request.jsonBody
        )
        assertFalse(request.toString().contains("firebase-token"))
        assertFalse(request.toString().contains("pnv-secret-token"))
        assertFalse(request.toString().contains("+5565999999999"))
    }

    @Test
    fun `omite PNV quando telefone e token nao foram fornecidos`() = runBlocking {
        val transport = RecordingTransport {
            AccountDeletionHttpResponse(
                statusCode = 200,
                body = """{"ok":true,"deleted":true}"""
            )
        }
        val repository = repository(
            tokenProvider = RecordingTokenProvider(listOf("firebase-token")),
            transport = transport
        )

        val result = repository.deleteAccount(idempotencyKey = "stable-key")

        assertTrue(result is AccountDeletionResult.Success)
        assertEquals(
            """{"confirmation":"EXCLUIR CONTA"}""",
            transport.requests.single().jsonBody
        )
    }

    @Test
    fun `401 repete uma vez com token atualizado e preserva chave e corpo`() = runBlocking {
        val tokenProvider = RecordingTokenProvider(
            tokens = listOf("stale-token", "fresh-token")
        )
        val responses = ArrayDeque(
            listOf(
                AccountDeletionHttpResponse(
                    statusCode = 401,
                    body = """{"error":"invalid_token"}"""
                ),
                AccountDeletionHttpResponse(
                    statusCode = 200,
                    body = """{"ok":true,"deleted":true}"""
                )
            )
        )
        val transport = RecordingTransport { responses.removeFirst() }
        val repository = repository(tokenProvider, transport)

        val result = repository.deleteAccount(
            idempotencyKey = "same-idempotency-key",
            pnvPhone = "+5565999999999",
            pnvToken = "pnv-token"
        )

        assertTrue(result is AccountDeletionResult.Success)
        assertEquals(listOf(false, true), tokenProvider.forceRefreshCalls)
        assertEquals(listOf("stale-token", "fresh-token"), transport.requests.map { it.bearerToken })
        assertEquals(
            listOf("same-idempotency-key", "same-idempotency-key"),
            transport.requests.map { it.idempotencyKey }
        )
        assertEquals(
            transport.requests.first().jsonBody,
            transport.requests.last().jsonBody
        )
    }

    @Test
    fun `segundo 401 retorna falha de autenticacao sem terceira tentativa`() = runBlocking {
        val tokenProvider = RecordingTokenProvider(
            tokens = listOf("stale-token", "still-invalid-token")
        )
        val transport = RecordingTransport {
            AccountDeletionHttpResponse(
                statusCode = 401,
                body = """{"error":"invalid_token"}"""
            )
        }
        val repository = repository(tokenProvider, transport)

        val result = repository.deleteAccount(idempotencyKey = "delete-key")

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.AUTHENTICATION,
                httpStatus = 401,
                serverCode = "invalid_token"
            ),
            result
        )
        assertEquals(listOf(false, true), tokenProvider.forceRefreshCalls)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `nao repete em erro de rede ou status diferente de 401`() = runBlocking {
        val networkTokens = RecordingTokenProvider(listOf("token"))
        val networkTransport = RecordingTransport {
            throw IOException("offline")
        }
        val networkResult = repository(networkTokens, networkTransport)
            .deleteAccount(idempotencyKey = "network-key")

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.TRANSPORT
            ),
            networkResult
        )
        assertEquals(listOf(false), networkTokens.forceRefreshCalls)
        assertEquals(1, networkTransport.requests.size)

        val serverTokens = RecordingTokenProvider(listOf("token"))
        val serverTransport = RecordingTransport {
            AccountDeletionHttpResponse(
                statusCode = 503,
                body = """{"error":"temporarily_unavailable"}"""
            )
        }
        val serverResult = repository(serverTokens, serverTransport)
            .deleteAccount(idempotencyKey = "server-key")

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.TRANSIENT_HTTP,
                httpStatus = 503,
                serverCode = "temporarily_unavailable"
            ),
            serverResult
        )
        assertEquals(listOf(false), serverTokens.forceRefreshCalls)
        assertEquals(1, serverTransport.requests.size)
    }

    @Test
    fun `classifica codigos PNV suporte ativo e operacao em andamento`() {
        assertEquals(
            AccountDeletionResult.PnvRequired,
            classifyAccountDeletionResponse(
                statusCode = 403,
                responseBody = """{"error":"pnv_verification_required"}"""
            )
        )
        assertEquals(
            AccountDeletionResult.InvalidPnv,
            classifyAccountDeletionResponse(
                statusCode = 403,
                responseBody = """{"error":"invalid_pnv_verification"}"""
            )
        )
        assertEquals(
            AccountDeletionResult.ActiveSupport,
            classifyAccountDeletionResponse(
                statusCode = 409,
                responseBody = """{"error":"active_support"}"""
            )
        )
        assertEquals(
            AccountDeletionResult.InProgress,
            classifyAccountDeletionResponse(
                statusCode = 409,
                responseBody = """{"error":"deletion_in_progress"}"""
            )
        )
    }

    @Test
    fun `resposta de sucesso exige JSON valido e flags positivas`() {
        val malformed = classifyAccountDeletionResponse(
            statusCode = 200,
            responseBody = """{"ok":true,"deleted":"""
        )
        val missingDeleted = classifyAccountDeletionResponse(
            statusCode = 200,
            responseBody = """{"ok":true}"""
        )
        val trailingGarbage = classifyAccountDeletionResponse(
            statusCode = 200,
            responseBody = """{"ok":true,"deleted":true} trailing"""
        )

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.INVALID_RESPONSE,
                httpStatus = 200
            ),
            malformed
        )
        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.INVALID_RESPONSE,
                httpStatus = 200
            ),
            missingDeleted
        )
        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.INVALID_RESPONSE,
                httpStatus = 200
            ),
            trailingGarbage
        )
    }

    @Test
    fun `parser aceita objeto completo e rejeita JSON malformado ou duplicado`() {
        val parsed = parseAccountDeletionPayload(
            """
                {
                  "ok": true,
                  "deleted": true,
                  "deletedAt": 1720000000000,
                  "deletedCounts": {"sessions": 2},
                  "retained": [],
                  "error": null
                }
            """.trimIndent()
        )

        assertEquals(true, parsed?.ok)
        assertEquals(true, parsed?.deleted)
        assertEquals(1_720_000_000_000, parsed?.deletedAtEpochMillis)
        assertNull(parsed?.error)
        assertNull(parseAccountDeletionPayload("""{"ok":true,"ok":false}"""))
        assertNull(parseAccountDeletionPayload("""["not","an","object"]"""))
        assertNull(parseAccountDeletionPayload("""{"error":"bad\u00ZZ"}"""))
    }

    @Test
    fun `codigo desconhecido nao pode carregar PII para o resultado`() {
        val result = classifyAccountDeletionResponse(
            statusCode = 409,
            responseBody = """{"error":"cliente@example.com"}"""
        )

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.SERVER_REJECTED,
                httpStatus = 409,
                serverCode = null
            ),
            result
        )
    }

    @Test
    fun `chave invalida ou token vazio falham antes do transporte`() = runBlocking {
        val invalidKeyTokens = RecordingTokenProvider(listOf("token"))
        val invalidKeyTransport = RecordingTransport {
            error("transport should not run")
        }
        val invalidKeyResult = repository(invalidKeyTokens, invalidKeyTransport)
            .deleteAccount(idempotencyKey = "invalid key with spaces")

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.INVALID_REQUEST
            ),
            invalidKeyResult
        )
        assertTrue(invalidKeyTokens.forceRefreshCalls.isEmpty())
        assertTrue(invalidKeyTransport.requests.isEmpty())

        val blankTokenProvider = RecordingTokenProvider(listOf("   "))
        val blankTokenTransport = RecordingTransport {
            error("transport should not run")
        }
        val blankTokenResult = repository(blankTokenProvider, blankTokenTransport)
            .deleteAccount(idempotencyKey = "valid-key")

        assertEquals(
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.AUTHENTICATION
            ),
            blankTokenResult
        )
        assertEquals(listOf(false), blankTokenProvider.forceRefreshCalls)
        assertTrue(blankTokenTransport.requests.isEmpty())
    }

    private fun repository(
        tokenProvider: AccountDeletionTokenProvider,
        transport: AccountDeletionHttpTransport
    ): AccountDeletionRepository =
        AccountDeletionRepository(
            tokenProvider = tokenProvider,
            transport = transport,
            endpoint = "https://suportex.test/api/client/account/delete"
        )

    private class RecordingTokenProvider(
        tokens: List<String>
    ) : AccountDeletionTokenProvider {
        private val remainingTokens = ArrayDeque(tokens)
        val forceRefreshCalls = mutableListOf<Boolean>()

        override suspend fun getIdToken(forceRefresh: Boolean): String {
            forceRefreshCalls += forceRefresh
            return remainingTokens.removeFirst()
        }
    }

    private class RecordingTransport(
        private val handler: suspend (AccountDeletionHttpRequest) -> AccountDeletionHttpResponse
    ) : AccountDeletionHttpTransport {
        val requests = mutableListOf<AccountDeletionHttpRequest>()

        override suspend fun post(
            request: AccountDeletionHttpRequest
        ): AccountDeletionHttpResponse {
            requests += request
            return handler(request)
        }
    }
}
