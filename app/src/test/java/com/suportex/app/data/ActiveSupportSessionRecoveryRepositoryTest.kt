package com.suportex.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveSupportSessionRecoveryRepositoryTest {

    @Test
    fun `reinicio do app recupera sessao ativa usando somente o protocolo local`() = runBlocking {
        val tokens = RecordingTokenProvider(listOf("firebase-token"))
        val transport = RecordingTransport(
            mutableListOf(ActiveSupportSessionHttpResponse(200, "active"))
        )
        val repository = repository(tokens, transport)

        val recovered = repository.findActiveSession("local-session-1")

        assertNotNull(recovered)
        assertEquals("realtime-session-9", recovered?.sessionId)
        assertEquals("Técnica Ana", recovered?.techName)
        assertEquals("active", recovered?.status)
        assertEquals(
            listOf(RecoveryRequest("local-session-1", "firebase-token")),
            transport.requests
        )
    }

    @Test
    fun `perda do socket nao bloqueia nova tentativa de recuperacao pelo endpoint`() = runBlocking {
        val tokens = RecordingTokenProvider(listOf("token-1", "token-2"))
        val transport = RecordingTransport(
            mutableListOf(
                ActiveSupportSessionHttpResponse(200, "inactive"),
                ActiveSupportSessionHttpResponse(200, "accepted")
            )
        )
        val repository = repository(tokens, transport)

        assertNull(repository.findActiveSession("local-session-2"))
        val recoveredAfterReconnectWindow =
            repository.findActiveSession("local-session-2")

        assertEquals("realtime-session-9", recoveredAfterReconnectWindow?.sessionId)
        assertEquals("accepted", recoveredAfterReconnectWindow?.status)
        assertEquals(
            listOf(
                RecoveryRequest("local-session-2", "token-1"),
                RecoveryRequest("local-session-2", "token-2")
            ),
            transport.requests
        )
    }

    @Test
    fun `protocolo vazio ou invalido falha fechado sem autenticar nem consultar`() = runBlocking {
        val tokens = RecordingTokenProvider(listOf("unused"))
        val transport = RecordingTransport(mutableListOf())
        val repository = repository(tokens, transport)

        assertNull(repository.findActiveSession(null))
        assertNull(repository.findActiveSession("   "))
        assertNull(repository.findActiveSession("../outra/sessao"))

        assertEquals(emptyList<Boolean>(), tokens.forceRefreshCalls)
        assertEquals(emptyList<RecoveryRequest>(), transport.requests)
    }

    @Test
    fun `id de outra conta devolvido como inativo nao revela sessao`() = runBlocking {
        val tokens = RecordingTokenProvider(listOf("firebase-token"))
        val transport = RecordingTransport(
            mutableListOf(ActiveSupportSessionHttpResponse(200, "inactive"))
        )
        val repository = repository(tokens, transport)

        assertNull(repository.findActiveSession("local-session-de-outra-conta"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `token expirado e renovado uma unica vez antes da recuperacao`() = runBlocking {
        val tokens = RecordingTokenProvider(listOf("expired-token", "fresh-token"))
        val transport = RecordingTransport(
            mutableListOf(
                ActiveSupportSessionHttpResponse(401, null),
                ActiveSupportSessionHttpResponse(200, "in_progress")
            )
        )
        val repository = repository(tokens, transport)

        val recovered = repository.findActiveSession("local-session-3")

        assertEquals("realtime-session-9", recovered?.sessionId)
        assertEquals("in_progress", recovered?.status)
        assertEquals(listOf(false, true), tokens.forceRefreshCalls)
        assertEquals(
            listOf(
                RecoveryRequest("local-session-3", "expired-token"),
                RecoveryRequest("local-session-3", "fresh-token")
            ),
            transport.requests
        )
    }

    @Test
    fun `resposta ativa malformada falha fechado`() = runBlocking {
        val tokens = RecordingTokenProvider(listOf("token-1", "token-2"))
        val transport = RecordingTransport(
            mutableListOf(
                ActiveSupportSessionHttpResponse(200, "invalid-session"),
                ActiveSupportSessionHttpResponse(200, "invalid-status")
            )
        )
        val repository = repository(tokens, transport)

        assertNull(repository.findActiveSession("local-session-4"))
        assertNull(repository.findActiveSession("local-session-4"))
    }

    private fun repository(
        tokenProvider: ActiveSupportSessionTokenProvider,
        transport: ActiveSupportSessionHttpTransport
    ): ActiveSupportSessionRecoveryRepository {
        val decoder = ActiveSupportSessionPayloadDecoder { body ->
            when (body) {
                "active" -> activePayload(status = "active")
                "accepted" -> activePayload(status = "accepted")
                "in_progress" -> activePayload(status = "in_progress")
                "inactive" -> ActiveSupportSessionLookupPayload(
                    ok = true,
                    active = false,
                    sessionId = null,
                    techName = null,
                    status = null
                )
                "invalid-session" -> activePayload(
                    status = "active",
                    sessionId = "../invalid"
                )
                "invalid-status" -> activePayload(status = "deleted")
                else -> null
            }
        }
        return ActiveSupportSessionRecoveryRepository(
            tokenProvider = tokenProvider,
            transport = transport,
            decoder = decoder
        )
    }

    private fun activePayload(
        status: String,
        sessionId: String = "realtime-session-9"
    ): ActiveSupportSessionLookupPayload =
        ActiveSupportSessionLookupPayload(
            ok = true,
            active = true,
            sessionId = sessionId,
            techName = "Técnica Ana",
            status = status
        )

    private class RecordingTokenProvider(
        private val tokens: List<String>
    ) : ActiveSupportSessionTokenProvider {
        val forceRefreshCalls = mutableListOf<Boolean>()

        override suspend fun getToken(forceRefresh: Boolean): String {
            forceRefreshCalls += forceRefresh
            return tokens.getOrElse(forceRefreshCalls.lastIndex) { tokens.lastOrNull().orEmpty() }
        }
    }

    private data class RecoveryRequest(
        val localSupportSessionId: String,
        val bearerToken: String
    )

    private class RecordingTransport(
        private val responses: MutableList<ActiveSupportSessionHttpResponse>
    ) : ActiveSupportSessionHttpTransport {
        val requests = mutableListOf<RecoveryRequest>()

        override suspend fun findActiveSession(
            localSupportSessionId: String,
            bearerToken: String
        ): ActiveSupportSessionHttpResponse {
            requests += RecoveryRequest(localSupportSessionId, bearerToken)
            return responses.removeFirst()
        }
    }
}
