package com.suportex.app.data

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BoundedStreamRequestBodyTest {

    @Test
    fun `envia arquivo dentro do limite sem carregar tudo antecipadamente`() {
        val content = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val body = BoundedStreamRequestBody(
            mediaType = null,
            declaredLength = content.size.toLong(),
            maxBytes = content.size.toLong(),
            openStream = { ByteArrayInputStream(content) }
        )
        val sink = Buffer()

        body.writeTo(sink)

        assertEquals(content.size.toLong(), body.transferredByteCount)
        assertArrayEquals(content, sink.readByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejeita tamanho conhecido acima do limite antes do envio`() {
        BoundedStreamRequestBody(
            mediaType = null,
            declaredLength = 11L,
            maxBytes = 10L,
            openStream = { ByteArrayInputStream(ByteArray(11)) }
        )
    }

    @Test(expected = IOException::class)
    fun `interrompe fluxo de tamanho desconhecido ao ultrapassar limite`() {
        val body = BoundedStreamRequestBody(
            mediaType = null,
            declaredLength = null,
            maxBytes = 10L,
            openStream = { ByteArrayInputStream(ByteArray(11)) }
        )

        body.writeTo(Buffer())
    }
}
