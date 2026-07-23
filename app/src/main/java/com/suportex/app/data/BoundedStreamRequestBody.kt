package com.suportex.app.data

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

internal class BoundedStreamRequestBody(
    private val mediaType: MediaType?,
    private val declaredLength: Long?,
    private val maxBytes: Long,
    private val openStream: () -> InputStream
) : RequestBody() {

    @Volatile
    var transferredByteCount: Long = 0L
        private set

    init {
        require(maxBytes > 0L) { "O limite do upload precisa ser positivo." }
        require(declaredLength == null || declaredLength >= 0L) {
            "O tamanho declarado do upload não pode ser negativo."
        }
        require(declaredLength == null || declaredLength <= maxBytes) {
            "Arquivo excede o limite de upload."
        }
    }

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = declaredLength ?: -1L

    override fun writeTo(sink: BufferedSink) {
        var total = 0L
        openStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue

                total += read
                if (total > maxBytes) {
                    transferredByteCount = total
                    throw IOException("Arquivo excede o limite de upload.")
                }
                sink.write(buffer, 0, read)
            }
        }
        transferredByteCount = total
    }
}
