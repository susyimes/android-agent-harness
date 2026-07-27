// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Minimal HTTP boundary so the provider can be tested without sockets. */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): String
}

/** Thrown when the endpoint answers with a non-2xx status. */
class HttpTransportException(
    val statusCode: Int,
    message: String
) : RuntimeException(message)

/** Thrown when the owner explicitly cancels an in-flight HTTP request. */
class HttpRequestCancelledException(
    message: String = "HTTP request was cancelled.",
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Blocking [HttpTransport] built on [HttpURLConnection].
 *
 * Android-compatible: [HttpURLConnection] is part of both the JVM and the
 * Android platform, so this transport works unchanged in an Android app
 * (unlike the JDK 11+ `HttpClient` API, which Android does not provide).
 *
 * Requests are sent as POST with a UTF-8 body. Behaviour worth knowing:
 *
 * - **Redirects are never followed.** `Authorization` headers would otherwise
 *   be replayed to whatever host the endpoint names in `Location`, handing the
 *   credential to a third party. A 3xx answer raises [HttpTransportException]
 *   carrying the status and the *host* of the `Location` target only; response
 *   headers and the redirect path/query are never surfaced, since those are the
 *   parts that tend to carry tokens. Point the configured base URL at the final
 *   endpoint instead.
 * - **Responses are decoded with the `Content-Type` charset** when the header
 *   declares one, and with UTF-8 otherwise (also when the declared charset is
 *   unknown to the platform).
 * - A non-2xx, non-3xx status raises [HttpTransportException] carrying the
 *   status code and the error body truncated to roughly 500 characters.
 *
 * Streams are always closed and the connection is always disconnected.
 *
 * @property requestTimeout applied as both the connect timeout and the read
 *   timeout of every request.
 */
class UrlConnectionHttpTransport(
    private val requestTimeout: Duration = Duration.ofSeconds(60)
) : HttpTransport {
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override fun post(url: String, headers: Map<String, String>, body: String): String {
        ensureNotCancelled()
        val connection = URI.create(url).toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("Not an HTTP(S) URL: $url")
        check(activeConnection.compareAndSet(null, connection)) {
            "UrlConnectionHttpTransport supports one active request at a time."
        }
        try {
            ensureNotCancelled()
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis()
            connection.readTimeout = timeoutMillis()
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            if (status in 300..399) {
                throw HttpTransportException(
                    statusCode = status,
                    message = "HTTP $status redirect refused for $url: this request carries " +
                        "credentials that must not be replayed to another host. Redirect " +
                        "target host: ${redirectHost(connection)}. Configure the base URL to " +
                        "point at the final endpoint."
                )
            }
            val charset = responseCharset(connection)
            if (status !in 200..299) {
                ensureNotCancelled()
                throw HttpTransportException(
                    statusCode = status,
                    message = "HTTP $status from $url: " +
                        truncate(readAll(connection.errorStream, charset))
                )
            }
            val response = readAll(connection.inputStream, charset)
            ensureNotCancelled()
            return response
        } catch (error: IOException) {
            if (cancelled.get()) {
                throw HttpRequestCancelledException(cause = error)
            }
            throw error
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    /**
     * Permanently cancels this turn-scoped transport and disconnects its
     * current socket. A cancelled transport cannot be reused.
     */
    fun cancel() {
        cancelled.set(true)
        activeConnection.get()?.disconnect()
    }

    private fun ensureNotCancelled() {
        if (cancelled.get()) {
            throw HttpRequestCancelledException()
        }
    }

    /**
     * Host of the `Location` header, or a bracketed marker when it is absent,
     * relative, or unparseable. Only the host is returned: the scheme, path,
     * query, and every other response header stay out of the message.
     */
    private fun redirectHost(connection: HttpURLConnection): String {
        val location = connection.getHeaderField(LOCATION_HEADER) ?: return "<absent>"
        return try {
            URI(location).host ?: "<same-origin-or-relative>"
        } catch (error: URISyntaxException) {
            "<unparseable>"
        }
    }

    private fun responseCharset(connection: HttpURLConnection): Charset {
        return charsetOf(connection.getHeaderField(CONTENT_TYPE_HEADER))
    }

    private fun charsetOf(contentType: String?): Charset {
        if (contentType == null) {
            return StandardCharsets.UTF_8
        }
        contentType.split(';').drop(1).forEach { parameter ->
            val trimmed = parameter.trim()
            if (trimmed.startsWith(CHARSET_PARAMETER, ignoreCase = true)) {
                val name = trimmed.substring(CHARSET_PARAMETER.length).trim().trim('"', '\'')
                if (name.isNotEmpty()) {
                    return try {
                        Charset.forName(name)
                    } catch (error: IllegalArgumentException) {
                        StandardCharsets.UTF_8
                    }
                }
            }
        }
        return StandardCharsets.UTF_8
    }

    private fun readAll(stream: InputStream?, charset: Charset): String {
        if (stream == null) {
            return ""
        }
        return stream.use { open -> open.readBytes().toString(charset) }
    }

    private fun timeoutMillis(): Int {
        val millis = requestTimeout.toMillis()
        return if (millis > Int.MAX_VALUE) Int.MAX_VALUE else millis.toInt()
    }

    private fun truncate(body: String): String {
        return if (body.length <= MAX_ERROR_BODY_CHARS) {
            body
        } else {
            body.take(MAX_ERROR_BODY_CHARS) + "... (truncated)"
        }
    }

    private companion object {
        const val MAX_ERROR_BODY_CHARS = 500
        const val LOCATION_HEADER = "Location"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val CHARSET_PARAMETER = "charset="
    }
}
