// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Minimal HTTP boundary so the provider can be tested without sockets. */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): String
}

/** Thrown when the endpoint answers with a non-2xx status. */
class HttpTransportException(
    val statusCode: Int,
    message: String
) : RuntimeException(message)

/** Blocking [HttpTransport] backed by the JDK [HttpClient]. */
class JdkHttpTransport(
    private val requestTimeout: Duration = Duration.ofSeconds(60)
) : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(requestTimeout)
        .build()

    override fun post(url: String, headers: Map<String, String>, body: String): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        val status = response.statusCode()
        if (status !in 200..299) {
            throw HttpTransportException(
                statusCode = status,
                message = "HTTP $status from $url: ${truncate(response.body())}"
            )
        }
        return response.body()
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
    }
}
