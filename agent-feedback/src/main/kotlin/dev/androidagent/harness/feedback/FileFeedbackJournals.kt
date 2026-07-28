// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.feedback

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.SystemAgentClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface FeedbackJournalMaintenance {
    fun clear(): Int
}

class FileSignalJournal(
    directory: File,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    clock: AgentClock = SystemAgentClock
) : SignalJournal, FeedbackJournalMaintenance {
    private val journal = FileBackedFeedbackJournal(
        file = File(directory, FILE_NAME),
        valueType = FeedbackSignal::class.java,
        maxEntries = maxEntries,
        retentionMillis = retentionMillis,
        clock = clock,
        idOf = FeedbackSignal::id,
        createdAt = FeedbackSignal::createdAtEpochMillis
    )

    override fun append(signal: FeedbackSignal) = journal.append(signal)

    override fun query(sinceEpochMillis: Long): List<FeedbackSignal> =
        journal.query(sinceEpochMillis)

    override fun clear(): Int = journal.clear()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000L
        private const val FILE_NAME = "signals.bin"
    }
}

class FileOutcomeJournal(
    directory: File,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    clock: AgentClock = SystemAgentClock
) : OutcomeJournal, FeedbackJournalMaintenance {
    private val journal = FileBackedFeedbackJournal(
        file = File(directory, FILE_NAME),
        valueType = RunOutcomeRecord::class.java,
        maxEntries = maxEntries,
        retentionMillis = retentionMillis,
        clock = clock,
        idOf = RunOutcomeRecord::id,
        createdAt = RunOutcomeRecord::createdAtEpochMillis
    )

    override fun append(outcome: RunOutcomeRecord) = journal.append(outcome)

    override fun query(sinceEpochMillis: Long): List<RunOutcomeRecord> =
        journal.query(sinceEpochMillis)

    override fun clear(): Int = journal.clear()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1_000
        const val DEFAULT_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1_000L
        private const val FILE_NAME = "outcomes.bin"
    }
}

private class FileBackedFeedbackJournal<T : Serializable>(
    private val file: File,
    private val valueType: Class<T>,
    private val maxEntries: Int,
    private val retentionMillis: Long,
    private val clock: AgentClock,
    private val idOf: (T) -> String,
    private val createdAt: (T) -> Long
) {
    private val values = linkedMapOf<String, T>()

    init {
        require(maxEntries > 0)
        require(retentionMillis > 0)
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create feedback journal directory '${directory.absolutePath}'."
        }
        load().forEach { value -> values[idOf(value)] = value }
        if (prune()) persist()
    }

    @Synchronized
    fun append(value: T) {
        val id = idOf(value)
        require(id.isNotBlank())
        val existing = values[id]
        require(existing == null || existing == value) {
            "Feedback journal id '$id' already exists."
        }
        if (existing == value) return
        values[id] = value
        prune()
        persist()
    }

    @Synchronized
    fun query(sinceEpochMillis: Long): List<T> {
        return values.values
            .filter { value -> createdAt(value) >= sinceEpochMillis }
            .sortedWith(compareBy<T>(createdAt).thenBy(idOf))
    }

    @Synchronized
    fun clear(): Int {
        val count = values.size
        values.clear()
        persist()
        return count
    }

    private fun prune(): Boolean {
        val before = values.size
        val cutoff = clock.nowEpochMillis() - retentionMillis
        val retained = values.values
            .filter { value -> createdAt(value) >= cutoff }
            .sortedWith(compareBy<T>(createdAt).thenBy(idOf))
            .takeLast(maxEntries)
        values.clear()
        retained.forEach { value -> values[idOf(value)] = value }
        return values.size != before
    }

    private fun load(): List<T> {
        if (!file.isFile) return emptyList()
        return try {
            ObjectInputStream(
                BufferedInputStream(FileInputStream(file))
            ).use { input ->
                val serialized = input.readObject() as? List<*>
                    ?: error("Feedback journal root is not a list.")
                serialized.map { value -> valueType.cast(value) }
            }
        } catch (error: RuntimeException) {
            throw IllegalStateException(
                "Could not read feedback journal '${file.absolutePath}'.",
                error
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "Could not read feedback journal '${file.absolutePath}'.",
                error
            )
        }
    }

    private fun persist() {
        val directory = requireNotNull(file.parentFile)
        val temporary = File.createTempFile("${file.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                ObjectOutputStream(
                    BufferedOutputStream(fileOutput)
                ).use { objectOutput ->
                    objectOutput.writeObject(ArrayList(values.values))
                    objectOutput.flush()
                    fileOutput.fd.sync()
                }
            }
            atomicReplace(temporary, file)
        } finally {
            temporary.delete()
        }
    }

    private fun atomicReplace(source: File, target: File) {
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
}
