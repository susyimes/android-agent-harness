// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Receives one PKCE redirect on the phone's localhost interface. */
class CodexOAuthCallbackServer(private val expectedState: String) {

    private val result = CompletableFuture<String>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(): CodexOAuthCallbackServer {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(HOST, PORT))
        } catch (error: IOException) {
            runCatching { socket.close() }
            throw CodexAuthException(
                503,
                "手机本机回调端口 $PORT 无法监听，请改用设备码登录。"
            )
        }
        serverSocket = socket
        Thread({ acceptOnce(socket) }, "harness-codex-oauth-callback").apply {
            isDaemon = true
            start()
        }
        return this
    }

    fun awaitCode(timeoutMs: Long): String {
        return try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            close()
            throw CodexAuthException(408, "Codex 浏览器登录超时，请重新发起或使用设备码。")
        } catch (error: ExecutionException) {
            close()
            throw CodexAuthException(400, error.cause?.message ?: "Codex OAuth 回调无效。")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw CodexAuthException(499, "Codex 登录已取消。")
        }
    }

    fun submitManualInput(input: String) {
        val callback = parseAuthorizationInput(input)
        when {
            callback.state.isNotBlank() && callback.state != expectedState ->
                throw CodexAuthException(400, "OAuth state 不匹配，请粘贴本次登录的回调链接。")
            callback.code.isBlank() ->
                throw CodexAuthException(400, "没有找到授权码，请粘贴完整回调链接。")
            !result.complete(callback.code) ->
                throw CodexAuthException(409, "本次登录已经收到授权结果。")
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptOnce(socket: ServerSocket) {
        try {
            socket.accept().use(::handleClient)
        } catch (error: IOException) {
            if (!result.isDone) result.completeExceptionally(error)
        } finally {
            close()
        }
    }

    private fun handleClient(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        while (reader.readLine()?.isNotEmpty() == true) {
            // Drain request headers.
        }
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val callback = parseCallback(target)
        when {
            callback.path != PATH -> {
                writeResponse(client, 404, "回调地址不匹配。")
                result.completeExceptionally(
                    IllegalStateException("OAuth callback path mismatch.")
                )
            }
            callback.state != expectedState -> {
                writeResponse(client, 400, "登录校验失败，请返回应用重新发起。")
                result.completeExceptionally(IllegalStateException("OAuth state mismatch."))
            }
            callback.code.isBlank() -> {
                writeResponse(client, 400, "回调缺少授权码。")
                result.completeExceptionally(IllegalStateException("OAuth callback has no code."))
            }
            else -> {
                writeResponse(client, 200, "Codex 登录完成，可以关闭此页面并返回 Agent Harness。")
                result.complete(callback.code)
            }
        }
    }

    private fun writeResponse(client: Socket, status: Int, message: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <head><meta charset="utf-8"><title>Agent Harness Codex 登录</title></head>
            <body style="font-family:sans-serif;padding:24px">
            <h2>${if (status == 200) "登录完成" else "登录失败"}</h2><p>$message</p>
            </body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${html.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        client.getOutputStream().use { output ->
            output.write(headers)
            output.write(html)
            output.flush()
        }
    }

    private fun parseCallback(target: String): Callback {
        val pathAndQuery = target.substringBefore('#')
        val path = pathAndQuery.substringBefore('?').ifBlank { "/" }
        val params = parseQuery(pathAndQuery.substringAfter('?', ""))
        return Callback(path, params["code"].orEmpty(), params["state"].orEmpty())
    }

    private fun parseAuthorizationInput(input: String): Callback {
        val value = input.trim()
        if (value.isBlank()) return Callback("", "", "")
        runCatching {
            val uri = URI(value)
            if (!uri.rawQuery.isNullOrBlank()) {
                val params = parseQuery(uri.rawQuery)
                return Callback(
                    uri.path.orEmpty(),
                    params["code"].orEmpty(),
                    params["state"].orEmpty()
                )
            }
        }
        if ("code=" in value) {
            val params = parseQuery(value.substringAfter('?', value).substringAfter('#', value))
            return Callback("", params["code"].orEmpty(), params["state"].orEmpty())
        }
        return Callback("", value, "")
    }

    private fun parseQuery(query: String): Map<String, String> {
        return query.split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                item.substringBefore('=').urlDecode() to
                    item.substringAfter('=', "").urlDecode()
            }
    }

    private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

    private data class Callback(
        val path: String,
        val code: String,
        val state: String
    )

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 1455
        const val PATH = "/auth/callback"
        const val REDIRECT_URI = "http://localhost:$PORT$PATH"
    }
}
