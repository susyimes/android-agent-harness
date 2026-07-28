// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling.android

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.system.Os
import dev.androidagent.harness.scheduling.JobCompletionDispositionStore
import dev.androidagent.harness.scheduling.JobLeaseStore
import dev.androidagent.harness.scheduling.LeaseResult
import dev.androidagent.harness.scheduling.LongTaskCheckpoint
import dev.androidagent.harness.scheduling.LongTaskCheckpointMaintenance
import dev.androidagent.harness.scheduling.LongTaskCheckpointStore
import dev.androidagent.harness.scheduling.ScheduleDataMaintenance
import dev.androidagent.harness.scheduling.ScheduleRepository
import dev.androidagent.harness.scheduling.ScheduleSpec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FileScheduleRepository(
    directory: File
) : ScheduleRepository, ScheduleDataMaintenance {
    private val store = AtomicSerializableStore(
        directory,
        "schedules.bin",
        MAGIC,
        ScheduleDatabase()
    )

    @Synchronized
    override fun get(id: String): ScheduleSpec? = store.value().schedules[id]

    @Synchronized
    override fun list(): List<ScheduleSpec> =
        store.value().schedules.values.sortedBy(ScheduleSpec::id)

    @Synchronized
    override fun put(spec: ScheduleSpec, expectedRevision: Long?) {
        val current = store.value().schedules[spec.id]
        require((current?.revision ?: 0L) == (expectedRevision ?: 0L)) {
            "Schedule '${spec.id}' revision conflict."
        }
        require(spec.revision == (current?.revision ?: 0L) + 1L)
        store.update { value ->
            value.copy(schedules = value.schedules + (spec.id to spec))
        }
    }

    @Synchronized
    override fun remove(id: String, expectedRevision: Long): Boolean {
        val current = store.value().schedules[id] ?: return false
        require(current.revision == expectedRevision)
        store.update { value -> value.copy(schedules = value.schedules - id) }
        return true
    }

    @Synchronized
    override fun exportSchedules(): List<ScheduleSpec> = list()

    @Synchronized
    override fun removeAll(expectedRevisions: Map<String, Long>): Boolean {
        val current = store.value().schedules
            .mapValues { (_, spec) -> spec.revision }
        if (current != expectedRevisions) return false
        store.update { value -> value.copy(schedules = emptyMap()) }
        return true
    }

    private data class ScheduleDatabase(
        val schedules: Map<String, ScheduleSpec> = emptyMap()
    ) : Serializable

    private companion object {
        const val MAGIC = 0x41534331
    }
}

class AndroidScheduleRepository(
    context: Context,
    directoryName: String = "agent-schedules"
) : ScheduleRepository by FileScheduleRepository(
    File(context.applicationContext.filesDir, directoryName)
)

class AndroidRunCheckpointStore(
    directory: File
) : LongTaskCheckpointStore, LongTaskCheckpointMaintenance {
    private val store = AtomicSerializableStore(
        directory,
        "long-task-checkpoints.bin",
        MAGIC,
        CheckpointDatabase()
    )

    constructor(
        context: Context,
        directoryName: String = "agent-run-checkpoints"
    ) : this(File(context.applicationContext.filesDir, directoryName))

    @Synchronized
    override fun get(jobId: String): LongTaskCheckpoint? = store.value().values[jobId]

    @Synchronized
    override fun put(checkpoint: LongTaskCheckpoint, expectedRevision: Long?) {
        val current = store.value().values[checkpoint.jobId]
        require(current?.revision == expectedRevision) {
            "LongTask '${checkpoint.jobId}' checkpoint conflict."
        }
        require(checkpoint.revision == (current?.revision ?: 0L) + 1L) {
            "LongTask checkpoint revision must advance exactly once."
        }
        require(
            current == null ||
                checkpoint.burst == current.burst ||
                checkpoint.burst == current.burst + 1
        )
        store.update { value ->
            value.copy(values = value.values + (checkpoint.jobId to checkpoint))
        }
    }

    @Synchronized
    override fun list(): List<LongTaskCheckpoint> =
        store.value().values.values.sortedBy(LongTaskCheckpoint::jobId)

    @Synchronized
    override fun clear(): Int {
        val count = store.value().values.size
        store.update { CheckpointDatabase() }
        return count
    }

    private data class CheckpointDatabase(
        val values: Map<String, LongTaskCheckpoint> = emptyMap()
    ) : Serializable

    private companion object {
        const val MAGIC = 0x41435031
    }
}

class AndroidJobLeaseStore(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : JobLeaseStore, JobCompletionDispositionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "agent_job_leases",
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun tryAcquire(
        jobId: String,
        occurrenceId: String,
        expiresAtEpochMillis: Long
    ): LeaseResult {
        require(expiresAtEpochMillis > nowEpochMillis())
        val key = key(jobId, occurrenceId)
        val current = preferences.getString(key, null)
        if (current?.startsWith("C:") == true) return LeaseResult.DuplicateCompleted
        val currentExpiry = current?.removePrefix("L:")?.toLongOrNull()
        if (currentExpiry != null && currentExpiry > nowEpochMillis()) {
            return LeaseResult.Busy(currentExpiry)
        }
        check(preferences.edit().putString(key, "L:$expiresAtEpochMillis").commit()) {
            "Could not persist job lease."
        }
        return LeaseResult.Acquired(expiresAtEpochMillis)
    }

    @Synchronized
    override fun release(jobId: String, occurrenceId: String, completed: Boolean) {
        releaseWithDisposition(
            jobId,
            occurrenceId,
            completed,
            continueSchedule = true
        )
    }

    @Synchronized
    override fun releaseWithDisposition(
        jobId: String,
        occurrenceId: String,
        completed: Boolean,
        continueSchedule: Boolean
    ) {
        val editor = preferences.edit()
        val key = key(jobId, occurrenceId)
        if (completed) {
            editor.putString(
                key,
                "C:${nowEpochMillis()}:${if (continueSchedule) 1 else 0}"
            )
        } else {
            editor.remove(key)
        }
        check(editor.commit()) { "Could not persist job lease release." }
        compactCompleted()
    }

    @Synchronized
    override fun shouldContinueSchedule(
        jobId: String,
        occurrenceId: String
    ): Boolean? {
        val value = preferences.getString(key(jobId, occurrenceId), null)
            ?.takeIf { raw -> raw.startsWith("C:") }
            ?: return null
        val parts = value.split(':')
        return parts.getOrNull(2)?.let { flag -> flag != "0" } ?: true
    }

    private fun compactCompleted() {
        val completed = preferences.all.mapNotNull { (key, raw) ->
            val value = raw as? String ?: return@mapNotNull null
            value.takeIf { it.startsWith("C:") }
                ?.split(':')
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.let { timestamp -> key to timestamp }
        }.sortedByDescending(Pair<String, Long>::second)
        if (completed.size <= MAX_COMPLETED) return
        val editor = preferences.edit()
        completed.drop(MAX_COMPLETED).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun key(jobId: String, occurrenceId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$jobId:$occurrenceId".toByteArray())
        return "lease:" + bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MAX_COMPLETED = 2_000
    }
}

private class AtomicSerializableStore<T : Serializable>(
    private val directory: File,
    private val fileName: String,
    private val magic: Int,
    defaultValue: T
) {
    private val file = File(directory, fileName)
    private var current: T

    init {
        require(directory.exists() || directory.mkdirs())
        require(directory.isDirectory)
        current = if (file.isFile) read() else defaultValue
    }

    fun value(): T = current

    fun update(transform: (T) -> T) {
        val next = transform(current)
        write(next)
        current = next
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(): T {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == magic)
            require(input.readInt() == VERSION)
            val size = input.readInt()
            require(size in 1..MAX_BYTES)
            val expected = ByteArray(32)
            input.readFully(expected)
            val payload = ByteArray(size)
            input.readFully(payload)
            require(input.read() == -1)
            require(MessageDigest.isEqual(expected, sha256(payload)))
            return ObjectInputStream(ByteArrayInputStream(payload)).use { stream ->
                stream.readObject() as T
            }
        }
    }

    private fun write(value: T) {
        val payload = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { output -> output.writeObject(value) }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_BYTES)
        val temporary = File(directory, "$fileName.tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(magic)
            output.writeInt(VERSION)
            output.writeInt(payload.size)
            output.write(sha256(payload))
            output.write(payload)
        }
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> replaceApi26(temporary)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                    Os.rename(temporary.absolutePath, file.absolutePath)
                else -> replaceInHostUnitTest(temporary)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun replaceApi26(temporary: File) {
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /**
     * Local JVM tests expose SDK_INT=0 and cannot execute android.system.Os.
     * Production is minSdk 29 and therefore always uses one of the branches above.
     */
    private fun replaceInHostUnitTest(temporary: File) {
        val backup = File(directory, "$fileName.bak")
        if (backup.exists()) check(backup.delete()) { "Could not remove stale backup." }
        if (file.exists()) check(file.renameTo(backup)) { "Could not stage current store." }
        if (!temporary.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            error("Could not replace store.")
        }
        if (backup.exists()) check(backup.delete()) { "Could not remove store backup." }
    }

    private companion object {
        const val VERSION = 1
        const val MAX_BYTES = 16 * 1024 * 1024
        fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)
    }
}
