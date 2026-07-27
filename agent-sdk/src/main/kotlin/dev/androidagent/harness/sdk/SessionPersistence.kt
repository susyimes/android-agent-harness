// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AgentSessionStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class AgentSessionSummary(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val messageCount: Int
)

/**
 * Optional management surface for durable [AgentSessionStore] implementations.
 *
 * The runtime only needs load/save. Hosts that expose a conversation list can
 * opt into this contract without coupling the core loop to a database.
 */
interface AgentSessionCatalog : AgentSessionStore {
    fun listSessions(): List<AgentSessionSummary>

    fun deleteSession(sessionId: String): Boolean

    fun clearSessions(): Int
}

/**
 * Small, dependency-free, app-private file store for Agent sessions.
 *
 * Session ids are SHA-256 hashed before they become file names, so arbitrary
 * host ids cannot escape [directory]. Writes use a temporary sibling followed
 * by an atomic replace when the file system supports it. A corrupt file is
 * ignored in listings and never prevents healthy sessions from loading.
 *
 * This class does not encrypt content. Android hosts should point it at an
 * app-private directory; products with stronger requirements can implement
 * [AgentSessionCatalog] on top of their encrypted database.
 */
class FileAgentSessionStore(
    private val directory: File
) : AgentSessionCatalog {

    init {
        require(directory.exists() || directory.mkdirs()) {
            "Could not create Agent session directory."
        }
        require(directory.isDirectory) { "Agent session path must be a directory." }
    }

    @Synchronized
    override fun load(sessionId: String): AgentSession? {
        require(sessionId.isNotBlank()) { "Session id must not be blank." }
        val file = sessionFile(sessionId)
        if (!file.isFile) return null
        return readSession(file)?.takeIf { session -> session.id == sessionId }
    }

    @Synchronized
    override fun save(session: AgentSession) {
        val target = sessionFile(session.id)
        val temporary = File.createTempFile(".agent-session-", ".tmp", directory)
        try {
            DataOutputStream(
                BufferedOutputStream(temporary.outputStream())
            ).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeText(session.id)
                output.writeLong(session.createdAtEpochMillis)
                output.writeLong(session.updatedAtEpochMillis)
                require(session.messages.size <= MAX_MESSAGES) {
                    "A session cannot contain more than $MAX_MESSAGES messages."
                }
                output.writeInt(session.messages.size)
                session.messages.forEach { message ->
                    output.writeText(message.id)
                    output.writeText(message.sessionId)
                    output.writeText(message.role.name)
                    output.writeText(message.content)
                    output.writeLong(message.createdAtEpochMillis)
                    output.writeNullableText(message.toolCallId)
                    output.writeNullableText(message.toolName)
                }
            }
            replaceAtomically(temporary, target)
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    @Synchronized
    override fun listSessions(): List<AgentSessionSummary> {
        return directory.listFiles { file ->
            file.isFile && file.name.endsWith(FILE_SUFFIX)
        }.orEmpty()
            .mapNotNull(::readSession)
            .map { session -> session.toSummary() }
            .sortedWith(
                compareByDescending<AgentSessionSummary> { summary ->
                    summary.updatedAtEpochMillis
                }.thenBy { summary -> summary.id }
            )
    }

    @Synchronized
    override fun deleteSession(sessionId: String): Boolean {
        require(sessionId.isNotBlank()) { "Session id must not be blank." }
        val file = sessionFile(sessionId)
        return file.exists() && file.delete()
    }

    @Synchronized
    override fun clearSessions(): Int {
        return directory.listFiles { file ->
            file.isFile && file.name.endsWith(FILE_SUFFIX)
        }.orEmpty().count { file -> file.delete() }
    }

    private fun readSession(file: File): AgentSession? {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return null
        return try {
            DataInputStream(
                BufferedInputStream(file.inputStream())
            ).use { input ->
                check(input.readInt() == MAGIC) { "Unknown Agent session file." }
                check(input.readInt() == FORMAT_VERSION) {
                    "Unsupported Agent session format."
                }
                val id = input.readText()
                val createdAt = input.readLong()
                val updatedAt = input.readLong()
                val count = input.readInt()
                check(count in 0..MAX_MESSAGES) { "Invalid Agent message count." }
                val messages = ArrayList<AgentMessage>(count)
                repeat(count) {
                    val messageId = input.readText()
                    val messageSessionId = input.readText()
                    val role = AgentRole.valueOf(input.readText())
                    val content = input.readText()
                    val createdAtEpochMillis = input.readLong()
                    val toolCallId = input.readNullableText()
                    val toolName = input.readNullableText()
                    messages += AgentMessage(
                        id = messageId,
                        sessionId = messageSessionId,
                        role = role,
                        content = content,
                        createdAtEpochMillis = createdAtEpochMillis,
                        toolCallId = toolCallId,
                        toolName = toolName
                    )
                }
                AgentSession(
                    id = id,
                    createdAtEpochMillis = createdAt,
                    updatedAtEpochMillis = updatedAt,
                    messages = messages
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun sessionFile(sessionId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return File(directory, digest + FILE_SUFFIX)
    }

    private fun AgentSession.toSummary(): AgentSessionSummary {
        val firstUserLine = messages.firstOrNull { message ->
            message.role == AgentRole.USER
        }?.content
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .replace(WHITESPACE, " ")
        val title = when {
            firstUserLine.isBlank() -> "新会话"
            firstUserLine.length <= MAX_TITLE_CHARS -> firstUserLine
            else -> firstUserLine.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
        }
        return AgentSessionSummary(
            id = id,
            title = title,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
            messageCount = messages.size
        )
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) {
            "A persisted Agent string cannot exceed $MAX_STRING_BYTES bytes."
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeText(value)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        check(length in 0..MAX_STRING_BYTES) { "Invalid Agent string length." }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableText(): String? {
        return when (readByte().toInt()) {
            0 -> null
            1 -> readText()
            else -> throw EOFException("Invalid nullable Agent string marker.")
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private companion object {
        const val MAGIC = 0x41485331
        const val FORMAT_VERSION = 1
        const val FILE_SUFFIX = ".agent-session"
        const val MAX_MESSAGES = 10_000
        const val MAX_STRING_BYTES = 4 * 1024 * 1024
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L
        const val MAX_TITLE_CHARS = 64
        val WHITESPACE = Regex("\\s+")
    }
}
