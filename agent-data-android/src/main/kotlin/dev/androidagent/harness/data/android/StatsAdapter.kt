// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dev.androidagent.harness.context.ContextCandidate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextSource
import dev.androidagent.harness.context.ContextTrust
import dev.androidagent.harness.permission.android.AndroidCapabilityStatus
import dev.androidagent.harness.permission.android.AndroidPermissionRepository
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ProductDataStatus {
    AVAILABLE,
    DISABLED,
    PERMISSION_REQUIRED,
    NOT_DECLARED,
    SERVICE_DISABLED,
    UNAVAILABLE
}

data class ProductDataAvailability(
    val status: ProductDataStatus,
    val reason: String
) : Serializable {
    val available: Boolean
        get() = status == ProductDataStatus.AVAILABLE
}

data class AppUsageSummary(
    val packageName: String,
    val foregroundMillis: Long,
    val lastUsedEpochMillis: Long
) : Serializable

data class UsageTimelineSession(
    val packageName: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long
) : Serializable {
    val durationMillis: Long
        get() = (endedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0)
}

data class UsageStatsSnapshot(
    val date: String,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val totalForegroundMillis: Long,
    val unlockCount: Int,
    val longestSessionMillis: Long,
    val topApps: List<AppUsageSummary>,
    val timeline: List<UsageTimelineSession>,
    val availability: ProductDataAvailability,
    val collectedAtEpochMillis: Long
) : Serializable {
    val isRealZero: Boolean
        get() = availability.available &&
            totalForegroundMillis == 0L &&
            unlockCount == 0 &&
            topApps.isEmpty()
}

data class UsageStatsConfiguration(
    val enabled: Boolean = false,
    val includeTopApps: Boolean = true,
    val includeTimeline: Boolean = false,
    val maxTopApps: Int = 8,
    val maxTimelineSessions: Int = 256
) {
    init {
        require(maxTopApps in 0..100)
        require(maxTimelineSessions in 0..10_000)
    }
}

interface UsageStatsRepository {
    fun snapshot(
        date: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): UsageStatsSnapshot
}

class AndroidUsageStatsRepository(
    context: Context,
    private val permissionRepository: AndroidPermissionRepository,
    private val configuration: () -> UsageStatsConfiguration,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : UsageStatsRepository {
    private val appContext = context.applicationContext

    override fun snapshot(date: LocalDate, zoneId: ZoneId): UsageStatsSnapshot {
        val config = configuration()
        val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            .coerceAtMost(nowEpochMillis())
        if (!config.enabled) {
            return unavailable(
                date,
                from,
                to,
                ProductDataStatus.DISABLED,
                "Usage Stats collection is disabled by the user."
            )
        }
        val permission = permissionRepository.snapshot(USAGE_CAPABILITY_ID)
        val availability = permission?.status.toProductStatus()
        if (availability != ProductDataStatus.AVAILABLE) {
            return unavailable(
                date,
                from,
                to,
                availability,
                permission?.reason ?: "Usage access capability was not registered."
            )
        }
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return unavailable(
                date,
                from,
                to,
                ProductDataStatus.SERVICE_DISABLED,
                "UsageStatsManager is unavailable."
            )
        return runCatching {
            val raw = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
                .orEmpty()
                .filter { stats -> stats.totalTimeInForeground > 0 }
            val top = if (config.includeTopApps) {
                raw.sortedByDescending { stats -> stats.totalTimeInForeground }
                    .take(config.maxTopApps)
                    .map { stats ->
                        AppUsageSummary(
                            packageName = stats.packageName,
                            foregroundMillis = stats.totalTimeInForeground,
                            lastUsedEpochMillis = stats.lastTimeUsed
                        )
                    }
            } else {
                emptyList()
            }
            val eventSummary = collectEvents(manager, from, to, config)
            UsageStatsSnapshot(
                date = date.toString(),
                fromEpochMillis = from,
                toEpochMillis = to,
                totalForegroundMillis = raw.sumOf { stats -> stats.totalTimeInForeground },
                unlockCount = eventSummary.unlockCount,
                longestSessionMillis = eventSummary.sessions.maxOfOrNull {
                    session -> session.durationMillis
                } ?: 0L,
                topApps = top,
                timeline = if (config.includeTimeline) {
                    eventSummary.sessions.takeLast(config.maxTimelineSessions)
                } else {
                    emptyList()
                },
                availability = ProductDataAvailability(
                    ProductDataStatus.AVAILABLE,
                    if (raw.isEmpty() && eventSummary.unlockCount == 0) {
                        "Authorized query completed with no usage in this interval."
                    } else {
                        "Authorized usage query completed."
                    }
                ),
                collectedAtEpochMillis = nowEpochMillis()
            )
        }.getOrElse { error ->
            unavailable(
                date,
                from,
                to,
                ProductDataStatus.UNAVAILABLE,
                error.message ?: "Usage Stats query failed."
            )
        }
    }

    private fun collectEvents(
        manager: UsageStatsManager,
        from: Long,
        to: Long,
        configuration: UsageStatsConfiguration
    ): EventSummary {
        val events = manager.queryEvents(from, to)
        val event = UsageEvents.Event()
        val foreground = mutableMapOf<String, Long>()
        val sessions = mutableListOf<UsageTimelineSession>()
        var unlockCount = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    foreground[event.packageName] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = foreground.remove(event.packageName)
                    if (start != null && event.timeStamp >= start) {
                        if (
                            configuration.includeTimeline ||
                            sessions.size < configuration.maxTimelineSessions
                        ) {
                            sessions += UsageTimelineSession(
                                event.packageName,
                                start,
                                event.timeStamp
                            )
                        }
                    }
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlockCount += 1
            }
        }
        foreground.forEach { (packageName, start) ->
            sessions += UsageTimelineSession(packageName, start, to)
        }
        return EventSummary(unlockCount, sessions)
    }

    private fun unavailable(
        date: LocalDate,
        from: Long,
        to: Long,
        status: ProductDataStatus,
        reason: String
    ) = UsageStatsSnapshot(
        date = date.toString(),
        fromEpochMillis = from,
        toEpochMillis = to,
        totalForegroundMillis = 0,
        unlockCount = 0,
        longestSessionMillis = 0,
        topApps = emptyList(),
        timeline = emptyList(),
        availability = ProductDataAvailability(status, reason),
        collectedAtEpochMillis = nowEpochMillis()
    )

    private fun AndroidCapabilityStatus?.toProductStatus(): ProductDataStatus = when (this) {
        AndroidCapabilityStatus.GRANTED -> ProductDataStatus.AVAILABLE
        AndroidCapabilityStatus.NOT_DECLARED -> ProductDataStatus.NOT_DECLARED
        AndroidCapabilityStatus.SERVICE_DISABLED -> ProductDataStatus.SERVICE_DISABLED
        AndroidCapabilityStatus.DENIED,
        AndroidCapabilityStatus.RESTRICTED,
        AndroidCapabilityStatus.SPECIAL_ACCESS_REQUIRED -> ProductDataStatus.PERMISSION_REQUIRED
        AndroidCapabilityStatus.UNAVAILABLE,
        null -> ProductDataStatus.UNAVAILABLE
    }

    private data class EventSummary(
        val unlockCount: Int,
        val sessions: List<UsageTimelineSession>
    )

    companion object {
        const val USAGE_CAPABILITY_ID = "usage-stats"
    }
}

class UsageStatsContextSource(
    private val repository: UsageStatsRepository,
    private val date: () -> LocalDate = LocalDate::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val sourceId: String = "mirror-stats"
) : ContextSource {
    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        val snapshot = repository.snapshot(date(), zoneId())
        val body = if (!snapshot.availability.available) {
            "status=${snapshot.availability.status.name.lowercase()}; " +
                "reason=${snapshot.availability.reason}"
        } else {
            buildString {
                append("date=${snapshot.date}; ")
                append("foregroundMillis=${snapshot.totalForegroundMillis}; ")
                append("unlockCount=${snapshot.unlockCount}; ")
                append("longestSessionMillis=${snapshot.longestSessionMillis}")
                if (snapshot.isRealZero) append("; realZero=true")
                snapshot.topApps.take(5).forEach { app ->
                    append("\n- ${app.packageName}: ${app.foregroundMillis} ms")
                }
            }
        }
        return listOf(
            ContextCandidate(
                id = "usage-stats:${snapshot.date}:${snapshot.collectedAtEpochMillis}",
                logicalId = "usage-stats:${snapshot.date}",
                sourceId = sourceId,
                sourceRevision = snapshot.collectedAtEpochMillis,
                title = "Daily device usage summary",
                body = body,
                trust = ContextTrust.TOOL_OBSERVED,
                privacy = ContextPrivacy.SENSITIVE,
                createdAtEpochMillis = snapshot.collectedAtEpochMillis,
                relevance = if (need.taskType.name == "BACKGROUND") 700 else 450,
                conflictKey = "usage-stats:${snapshot.date}"
            )
        )
    }
}
