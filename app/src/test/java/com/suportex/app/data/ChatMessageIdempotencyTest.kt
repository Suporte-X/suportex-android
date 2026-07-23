package com.suportex.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageIdempotencyTest {

    @Test
    fun `confirmacao atrasada reconhece mensagem equivalente salva pelo servidor`() {
        val expected = mapOf<String, Any?>(
            "id" to "message-1",
            "sessionId" to "session-1",
            "from" to "client",
            "type" to "text",
            "status" to "sent",
            "text" to "Olá",
            "ts" to 100L,
            "createdAt" to 100L
        )
        val persistedByServer = mapOf<String, Any?>(
            "id" to "message-1",
            "sessionId" to "session-1",
            "from" to "client",
            "senderUid" to "firebase-user",
            "fromName" to "Cliente",
            "type" to "text",
            "status" to "sent",
            "text" to "Olá",
            "audioUrl" to "",
            "imageUrl" to "",
            "fileUrl" to "",
            "ts" to 250L,
            "createdAt" to 250L
        )

        assertTrue(
            isEquivalentPersistedOutgoingMessage(
                existing = persistedByServer,
                expected = expected
            )
        )
    }

    @Test
    fun `colisao com conteudo ou identificador diferente nao e aceita`() {
        val expected = mapOf<String, Any?>(
            "id" to "message-1",
            "sessionId" to "session-1",
            "from" to "client",
            "type" to "text",
            "status" to "sent",
            "text" to "Mensagem correta"
        )

        assertFalse(
            isEquivalentPersistedOutgoingMessage(
                existing = expected + ("text" to "Mensagem diferente"),
                expected = expected
            )
        )
        assertFalse(
            isEquivalentPersistedOutgoingMessage(
                existing = expected + ("id" to "message-2"),
                expected = expected
            )
        )
    }
}
