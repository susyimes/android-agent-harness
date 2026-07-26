// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.time.Duration
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlConnectionHttpTransportTest {

    private lateinit var server: HttpServer
    private val receivedBodies = Collections.synchronizedList(mutableListOf<String>())
    private val receivedHeaders = Collections.synchronizedList(mutableListOf<Map<String, String>>())
    private val receivedPaths = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            recordRequest(exchange)
            respond(exchange, 200, "{\"status\":\"fine\"}", "application/json")
        }
        server.createContext("/short-error") { exchange ->
            recordRequest(exchange)
            respond(exchange, 429, "quota exceeded", "text/plain")
        }
        server.createContext("/long-error") { exchange ->
            recordRequest(exchange)
            respond(exchange, 500, LONG_ERROR_BODY, "text/plain")
        }
        server.createContext("/latin1") { exchange ->
            recordRequest(exchange)
            respond(exchange, 200, ACCENTED_BODY, "application/json; charset=ISO-8859-1")
        }
        server.createContext("/quoted-charset") { exchange ->
            recordRequest(exchange)
            respond(exchange, 200, ACCENTED_BODY, "application/json; charset=\"UTF-8\"")
        }
        server.createContext("/unknown-charset") { exchange ->
            recordRequest(exchange)
            respond(exchange, 200, ACCENTED_BODY, "application/json; charset=not-a-real-charset")
        }
        server.createContext("/no-charset") { exchange ->
            recordRequest(exchange)
            respond(exchange, 200, ACCENTED_BODY, "application/json")
        }
        server.createContext("/latin1-error") { exchange ->
            recordRequest(exchange)
            respond(exchange, 400, ACCENTED_BODY, "application/json; charset=ISO-8859-1")
        }
        server.createContext("/redirect-302") { exchange ->
            recordRequest(exchange)
            redirect(exchange, 302, "http://127.0.0.1:${server.address.port}$LEAKY_REDIRECT_PATH")
        }
        server.createContext("/redirect-307") { exchange ->
            recordRequest(exchange)
            redirect(exchange, 307, "https://relocated.invalid/v1/chat/completions")
        }
        server.createContext("/redirect-relative") { exchange ->
            recordRequest(exchange)
            redirect(exchange, 308, "/ok")
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun recordRequest(exchange: com.sun.net.httpserver.HttpExchange) {
        receivedPaths.add(exchange.requestURI.path)
        receivedBodies.add(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
        val flattened = linkedMapOf<String, String>()
        exchange.requestHeaders.forEach { (name, values) -> flattened[name] = values.joinToString(",") }
        receivedHeaders.add(flattened)
    }

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
        contentType: String
    ) {
        exchange.responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray(charsetOfContentType(contentType))
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { stream -> stream.write(bytes) }
    }

    private fun redirect(
        exchange: com.sun.net.httpserver.HttpExchange,
        status: Int,
        location: String
    ) {
        exchange.responseHeaders.add("Location", location)
        exchange.responseHeaders.add(SENSITIVE_HEADER_NAME, SENSITIVE_HEADER_VALUE)
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun charsetOfContentType(contentType: String): Charset {
        return if (contentType.contains("ISO-8859-1")) Charsets.ISO_8859_1 else Charsets.UTF_8
    }

    private fun url(path: String): String {
        return "http://127.0.0.1:${server.address.port}$path"
    }

    private fun transport(): UrlConnectionHttpTransport {
        return UrlConnectionHttpTransport(Duration.ofSeconds(10))
    }

    @Test
    fun postsUtf8BodyAndHeadersAndReturnsResponseBody() {
        val response = transport().post(
            url = url("/ok"),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer canned-value"
            ),
            body = "{\"greeting\":\"héllo 世界\"}"
        )

        assertEquals("{\"status\":\"fine\"}", response)
        assertEquals(listOf("{\"greeting\":\"héllo 世界\"}"), receivedBodies)
        assertEquals("application/json", receivedHeaders[0]["Content-type"])
        assertEquals("Bearer canned-value", receivedHeaders[0]["Authorization"])
    }

    @Test
    fun non2xxSurfacesStatusCodeAndBody() {
        val error = assertThrows(HttpTransportException::class.java) {
            transport().post(url("/short-error"), emptyMap(), "{}")
        }

        assertEquals(429, error.statusCode)
        val message = error.message ?: ""
        assertTrue("Expected status in: $message", message.contains("HTTP 429"))
        assertTrue("Expected body in: $message", message.contains("quota exceeded"))
        assertFalse("Short body must not be truncated: $message", message.contains("(truncated)"))
    }

    @Test
    fun non2xxTruncatesLongErrorBody() {
        val error = assertThrows(HttpTransportException::class.java) {
            transport().post(url("/long-error"), emptyMap(), "{}")
        }

        assertEquals(500, error.statusCode)
        val message = error.message ?: ""
        assertTrue("Expected status in: $message", message.contains("HTTP 500"))
        assertTrue(
            "Expected truncation marker in: $message",
            message.contains("... (truncated)")
        )
        assertTrue(
            "Expected the first 500 chars of the body in: $message",
            message.contains(LONG_ERROR_BODY.take(500))
        )
        assertFalse(
            "Expected the tail of the body to be dropped: $message",
            message.contains(LONG_ERROR_BODY)
        )
    }

    @Test
    fun redirectIsRefusedWithStatusAndTargetHostOnly() {
        val error = assertThrows(HttpTransportException::class.java) {
            transport().post(
                url = url("/redirect-302"),
                headers = mapOf("Authorization" to "Bearer redirect-credential-value"),
                body = "{}"
            )
        }

        assertEquals(302, error.statusCode)
        val message = error.message ?: ""
        assertTrue("Expected status in: $message", message.contains("302"))
        assertTrue("Expected the target host in: $message", message.contains("127.0.0.1"))
        assertFalse(
            "The redirect path and query must stay out of: $message",
            message.contains(LEAKY_QUERY_MARKER)
        )
        assertFalse(
            "The redirect path must stay out of: $message",
            message.contains(LEAKY_REDIRECT_PATH)
        )
        assertFalse(
            "Response headers must stay out of: $message",
            message.contains(SENSITIVE_HEADER_VALUE) || message.contains(SENSITIVE_HEADER_NAME)
        )
        assertFalse(
            "The credential must stay out of: $message",
            message.contains("redirect-credential-value")
        )
        assertEquals(
            "The transport must not replay the request to the redirect target.",
            listOf("/redirect-302"),
            receivedPaths.toList()
        )
    }

    @Test
    fun redirectToForeignHostNamesOnlyThatHost() {
        val error = assertThrows(HttpTransportException::class.java) {
            transport().post(
                url = url("/redirect-307"),
                headers = mapOf("Authorization" to "Bearer redirect-credential-value"),
                body = "{}"
            )
        }

        assertEquals(307, error.statusCode)
        val message = error.message ?: ""
        assertTrue("Expected the target host in: $message", message.contains("relocated.invalid"))
        assertFalse(
            "The redirect path must stay out of: $message",
            message.contains("/v1/chat/completions")
        )
    }

    @Test
    fun relativeRedirectIsRefusedWithoutFollowing() {
        val error = assertThrows(HttpTransportException::class.java) {
            transport().post(url("/redirect-relative"), emptyMap(), "{}")
        }

        assertEquals(308, error.statusCode)
        assertEquals(listOf("/redirect-relative"), receivedPaths.toList())
    }

    @Test
    fun decodesResponseUsingDeclaredContentTypeCharset() {
        assertEquals(ACCENTED_BODY, transport().post(url("/latin1"), emptyMap(), "{}"))
    }

    @Test
    fun decodesResponseUsingQuotedContentTypeCharset() {
        assertEquals(ACCENTED_BODY, transport().post(url("/quoted-charset"), emptyMap(), "{}"))
    }

    @Test
    fun fallsBackToUtf8WithoutOrWithUnknownCharset() {
        assertEquals(ACCENTED_BODY, transport().post(url("/no-charset"), emptyMap(), "{}"))
        assertEquals(ACCENTED_BODY, transport().post(url("/unknown-charset"), emptyMap(), "{}"))
    }

    @Test
    fun decodesErrorBodyUsingDeclaredContentTypeCharset() {
        val error = assertThrows(HttpTransportException::class.java) {
            transport().post(url("/latin1-error"), emptyMap(), "{}")
        }

        assertEquals(400, error.statusCode)
        assertTrue(
            "Expected a correctly decoded error body in: ${error.message}",
            (error.message ?: "").contains(ACCENTED_BODY)
        )
    }

    private companion object {
        const val ACCENTED_BODY = "{\"note\":\"héllo à côté\"}"
        const val LEAKY_QUERY_MARKER = "handover-marker-9182"
        const val LEAKY_REDIRECT_PATH = "/ok?handover=$LEAKY_QUERY_MARKER"
        const val SENSITIVE_HEADER_NAME = "X-Session-Handover"
        const val SENSITIVE_HEADER_VALUE = "handover-header-value-4471"

        val LONG_ERROR_BODY: String = buildString {
            var index = 0
            while (length < 800) {
                append("segment-").append(index).append(';')
                index++
            }
        }
    }
}
