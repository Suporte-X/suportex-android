package com.suportex.app.data

import com.suportex.app.Conn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface AccountDeletionResult {
    data class Success(
        val deletedAtEpochMillis: Long?
    ) : AccountDeletionResult

    data object PnvRequired : AccountDeletionResult

    data object InvalidPnv : AccountDeletionResult

    data object ActiveSupport : AccountDeletionResult

    data object InProgress : AccountDeletionResult

    data class RecoverableFailure(
        val reason: AccountDeletionFailureReason,
        val httpStatus: Int? = null,
        val serverCode: String? = null
    ) : AccountDeletionResult
}

enum class AccountDeletionFailureReason {
    INVALID_REQUEST,
    AUTHENTICATION,
    TRANSPORT,
    RATE_LIMITED,
    TRANSIENT_HTTP,
    INVALID_RESPONSE,
    SERVER_REJECTED
}

class AccountDeletionRepository internal constructor(
    private val tokenProvider: AccountDeletionTokenProvider,
    private val transport: AccountDeletionHttpTransport,
    private val endpoint: String
) {
    constructor(
        authRepository: AuthRepository = AuthRepository(),
        httpClient: OkHttpClient = OkHttpClient()
    ) : this(
        tokenProvider = AuthRepositoryAccountDeletionTokenProvider(authRepository),
        transport = OkHttpAccountDeletionTransport(httpClient),
        endpoint = "${Conn.SERVER_BASE}/api/client/account/delete"
    )

    suspend fun deleteAccount(
        idempotencyKey: String,
        pnvPhone: String? = null,
        pnvToken: String? = null
    ): AccountDeletionResult {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            return AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.INVALID_REQUEST
            )
        }

        val requestBody = buildAccountDeletionRequestBody(
            pnvPhone = pnvPhone,
            pnvToken = pnvToken
        )

        for (attempt in 0..1) {
            val forceRefresh = attempt == 1
            val idToken = try {
                tokenProvider.getIdToken(forceRefresh).trim()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return AccountDeletionResult.RecoverableFailure(
                    reason = AccountDeletionFailureReason.AUTHENTICATION
                )
            }

            if (idToken.isBlank()) {
                return AccountDeletionResult.RecoverableFailure(
                    reason = AccountDeletionFailureReason.AUTHENTICATION
                )
            }

            val response = try {
                transport.post(
                    AccountDeletionHttpRequest(
                        url = endpoint,
                        bearerToken = idToken,
                        idempotencyKey = idempotencyKey,
                        jsonBody = requestBody
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                return AccountDeletionResult.RecoverableFailure(
                    reason = AccountDeletionFailureReason.TRANSPORT
                )
            } catch (_: Exception) {
                return AccountDeletionResult.RecoverableFailure(
                    reason = AccountDeletionFailureReason.TRANSPORT
                )
            }

            if (response.statusCode == HTTP_UNAUTHORIZED && attempt == 0) {
                continue
            }

            return classifyAccountDeletionResponse(
                statusCode = response.statusCode,
                responseBody = response.body,
                bodyTruncated = response.bodyTruncated
            )
        }

        return AccountDeletionResult.RecoverableFailure(
            reason = AccountDeletionFailureReason.AUTHENTICATION,
            httpStatus = HTTP_UNAUTHORIZED
        )
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

internal interface AccountDeletionTokenProvider {
    suspend fun getIdToken(forceRefresh: Boolean): String
}

internal interface AccountDeletionHttpTransport {
    suspend fun post(request: AccountDeletionHttpRequest): AccountDeletionHttpResponse
}

internal class AccountDeletionHttpRequest(
    val url: String,
    val bearerToken: String,
    val idempotencyKey: String,
    val jsonBody: String
) {
    override fun toString(): String = "AccountDeletionHttpRequest(url=$url, credentials=redacted)"
}

internal class AccountDeletionHttpResponse(
    val statusCode: Int,
    val body: String,
    val bodyTruncated: Boolean = false
) {
    override fun toString(): String =
        "AccountDeletionHttpResponse(statusCode=$statusCode, body=redacted, bodyTruncated=$bodyTruncated)"
}

private class AuthRepositoryAccountDeletionTokenProvider(
    private val authRepository: AuthRepository
) : AccountDeletionTokenProvider {
    override suspend fun getIdToken(forceRefresh: Boolean): String =
        authRepository.ensureAnonIdToken(forceRefresh)
}

private class OkHttpAccountDeletionTransport(
    private val httpClient: OkHttpClient
) : AccountDeletionHttpTransport {
    override suspend fun post(
        request: AccountDeletionHttpRequest
    ): AccountDeletionHttpResponse = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(request.url)
            .post(
                request.jsonBody.toRequestBody(
                    JSON_MEDIA_TYPE.toMediaTypeOrNull()
                )
            )
            .header("Authorization", "Bearer ${request.bearerToken}")
            .header("Idempotency-Key", request.idempotencyKey)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val preview = response.peekBody(MAX_RESPONSE_BYTES + 1L).bytes()
            val truncated = preview.size > MAX_RESPONSE_BYTES
            val boundedBytes = if (truncated) {
                preview.copyOf(MAX_RESPONSE_BYTES)
            } else {
                preview
            }
            AccountDeletionHttpResponse(
                statusCode = response.code,
                body = boundedBytes.toString(Charsets.UTF_8),
                bodyTruncated = truncated
            )
        }
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

internal fun buildAccountDeletionRequestBody(
    pnvPhone: String?,
    pnvToken: String?
): String {
    val values = linkedMapOf(
        "confirmation" to "EXCLUIR CONTA"
    )
    pnvPhone?.trim()?.takeIf { it.isNotBlank() }?.let { values["pnvPhone"] = it }
    pnvToken?.trim()?.takeIf { it.isNotBlank() }?.let { values["pnvToken"] = it }

    return values.entries.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ","
    ) { (key, value) ->
        "\"${escapeJsonString(key)}\":\"${escapeJsonString(value)}\""
    }
}

internal fun classifyAccountDeletionResponse(
    statusCode: Int,
    responseBody: String,
    bodyTruncated: Boolean = false
): AccountDeletionResult {
    if (statusCode == 401) {
        return AccountDeletionResult.RecoverableFailure(
            reason = AccountDeletionFailureReason.AUTHENTICATION,
            httpStatus = statusCode,
            serverCode = extractSafeServerCode(responseBody)
        )
    }

    if (statusCode == 429) {
        return AccountDeletionResult.RecoverableFailure(
            reason = AccountDeletionFailureReason.RATE_LIMITED,
            httpStatus = statusCode,
            serverCode = extractSafeServerCode(responseBody)
        )
    }

    if (statusCode == 408 || statusCode == 425 || statusCode in 500..599) {
        return AccountDeletionResult.RecoverableFailure(
            reason = AccountDeletionFailureReason.TRANSIENT_HTTP,
            httpStatus = statusCode,
            serverCode = extractSafeServerCode(responseBody)
        )
    }

    if (bodyTruncated) {
        return AccountDeletionResult.RecoverableFailure(
            reason = AccountDeletionFailureReason.INVALID_RESPONSE,
            httpStatus = statusCode
        )
    }

    val payload = parseAccountDeletionPayload(responseBody)
        ?: return AccountDeletionResult.RecoverableFailure(
            reason = AccountDeletionFailureReason.INVALID_RESPONSE,
            httpStatus = statusCode
        )
    val serverCode = payload.error?.takeIf(::isSafeServerCode)

    if (statusCode in 200..299) {
        return if (payload.ok == true && payload.deleted == true) {
            AccountDeletionResult.Success(
                deletedAtEpochMillis = payload.deletedAtEpochMillis
            )
        } else {
            AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.INVALID_RESPONSE,
                httpStatus = statusCode,
                serverCode = serverCode
            )
        }
    }

    if (statusCode == 403) {
        return when (serverCode) {
            "pnv_verification_required" -> AccountDeletionResult.PnvRequired
            "invalid_pnv_verification" -> AccountDeletionResult.InvalidPnv
            else -> AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.SERVER_REJECTED,
                httpStatus = statusCode,
                serverCode = serverCode
            )
        }
    }

    if (statusCode == 409) {
        return when (serverCode) {
            "active_support" -> AccountDeletionResult.ActiveSupport
            "deletion_in_progress" -> AccountDeletionResult.InProgress
            else -> AccountDeletionResult.RecoverableFailure(
                reason = AccountDeletionFailureReason.SERVER_REJECTED,
                httpStatus = statusCode,
                serverCode = serverCode
            )
        }
    }

    return AccountDeletionResult.RecoverableFailure(
        reason = AccountDeletionFailureReason.SERVER_REJECTED,
        httpStatus = statusCode,
        serverCode = serverCode
    )
}

internal data class ParsedAccountDeletionPayload(
    val ok: Boolean?,
    val deleted: Boolean?,
    val deletedAtEpochMillis: Long?,
    val error: String?
)

internal fun parseAccountDeletionPayload(
    responseBody: String
): ParsedAccountDeletionPayload? {
    if (responseBody.isBlank() || responseBody.length > MAX_JSON_RESPONSE_CHARS) {
        return null
    }

    val values = try {
        JsonScalarObjectParser(responseBody).parse()
    } catch (_: JsonSyntaxException) {
        return null
    }

    return ParsedAccountDeletionPayload(
        ok = (values["ok"] as? JsonScalar.BooleanValue)?.value,
        deleted = (values["deleted"] as? JsonScalar.BooleanValue)?.value,
        deletedAtEpochMillis = (values["deletedAt"] as? JsonScalar.NumberValue)
            ?.raw
            ?.toLongOrNull(),
        error = (values["error"] as? JsonScalar.StringValue)?.value
    )
}

private fun extractSafeServerCode(responseBody: String): String? =
    parseAccountDeletionPayload(responseBody)
        ?.error
        ?.takeIf(::isSafeServerCode)

private fun isValidIdempotencyKey(value: String): Boolean =
    value.length in 1..160 && IDEMPOTENCY_KEY_REGEX.matches(value)

private fun isSafeServerCode(value: String): Boolean =
    value.length in 1..80 && SERVER_CODE_REGEX.matches(value)

private fun escapeJsonString(value: String): String {
    val escaped = StringBuilder(value.length + 8)
    value.forEach { character ->
        when (character) {
            '"' -> escaped.append("\\\"")
            '\\' -> escaped.append("\\\\")
            '\b' -> escaped.append("\\b")
            '\u000C' -> escaped.append("\\f")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            else -> {
                if (character.code < 0x20) {
                    escaped.append("\\u")
                    escaped.append(character.code.toString(16).padStart(4, '0'))
                } else {
                    escaped.append(character)
                }
            }
        }
    }
    return escaped.toString()
}

private sealed interface JsonScalar {
    data class StringValue(val value: String) : JsonScalar
    data class BooleanValue(val value: Boolean) : JsonScalar
    data class NumberValue(val raw: String) : JsonScalar
    data object NullValue : JsonScalar
    data object ComplexValue : JsonScalar
}

private class JsonScalarObjectParser(
    private val source: String
) {
    private var index = 0

    fun parse(): Map<String, JsonScalar> {
        skipWhitespace()
        val result = parseObject(depth = 0, captureValues = true)
        skipWhitespace()
        if (index != source.length) fail()
        return result
    }

    private fun parseObject(
        depth: Int,
        captureValues: Boolean
    ): MutableMap<String, JsonScalar> {
        ensureDepth(depth)
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonScalar>()
        if (consume('}')) return values

        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val value = parseValue(depth + 1)
            if (captureValues && values.put(key, value) != null) fail()
            skipWhitespace()
            if (consume('}')) return values
            expect(',')
        }
    }

    private fun parseArray(depth: Int) {
        ensureDepth(depth)
        expect('[')
        skipWhitespace()
        if (consume(']')) return

        while (true) {
            skipWhitespace()
            parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun parseValue(depth: Int): JsonScalar {
        ensureDepth(depth)
        return when (peek()) {
            '"' -> JsonScalar.StringValue(parseString())
            '{' -> {
                parseObject(depth, captureValues = false)
                JsonScalar.ComplexValue
            }
            '[' -> {
                parseArray(depth)
                JsonScalar.ComplexValue
            }
            't' -> {
                expectLiteral("true")
                JsonScalar.BooleanValue(true)
            }
            'f' -> {
                expectLiteral("false")
                JsonScalar.BooleanValue(false)
            }
            'n' -> {
                expectLiteral("null")
                JsonScalar.NullValue
            }
            '-', in '0'..'9' -> JsonScalar.NumberValue(parseNumber())
            else -> fail()
        }
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return value.toString()
                character == '\\' -> value.append(parseEscape())
                character.code < 0x20 -> fail()
                else -> value.append(character)
            }
        }
        fail()
    }

    private fun parseEscape(): Char {
        if (index >= source.length) fail()
        return when (val escaped = source[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail()
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > source.length) fail()
        var codePoint = 0
        repeat(4) {
            val digit = source[index++].digitToIntOrNull(radix = 16) ?: fail()
            codePoint = (codePoint shl 4) or digit
        }
        return codePoint.toChar()
    }

    private fun parseNumber(): String {
        val start = index
        consume('-')
        when {
            consume('0') -> Unit
            peek() in '1'..'9' -> {
                index += 1
                while (peek() in '0'..'9') index += 1
            }
            else -> fail()
        }

        if (consume('.')) {
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') index += 1
        }

        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') index += 1
        }
        return source.substring(start, index)
    }

    private fun expectLiteral(value: String) {
        if (!source.startsWith(value, startIndex = index)) fail()
        index += value.length
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) fail()
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        index += 1
        return true
    }

    private fun peek(): Char =
        if (index < source.length) source[index] else '\u0000'

    private fun skipWhitespace() {
        while (
            index < source.length &&
            (
                source[index] == ' ' ||
                    source[index] == '\n' ||
                    source[index] == '\r' ||
                    source[index] == '\t'
                )
        ) {
            index += 1
        }
    }

    private fun ensureDepth(depth: Int) {
        if (depth > MAX_JSON_DEPTH) fail()
    }

    private fun fail(): Nothing = throw JsonSyntaxException()
}

private class JsonSyntaxException : IllegalArgumentException()

private const val MAX_JSON_RESPONSE_CHARS = 64 * 1024
private const val MAX_JSON_DEPTH = 32
private val IDEMPOTENCY_KEY_REGEX = Regex("[A-Za-z0-9._:-]+")
private val SERVER_CODE_REGEX = Regex("[a-z0-9_:-]+")
