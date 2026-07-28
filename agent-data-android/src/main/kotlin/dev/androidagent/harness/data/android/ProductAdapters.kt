// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import dev.androidagent.harness.context.ContextCandidate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextRiskFlag
import dev.androidagent.harness.context.ContextSource
import dev.androidagent.harness.context.ContextTrust
import dev.androidagent.harness.permission.android.AndroidCapabilityStatus
import dev.androidagent.harness.permission.android.AndroidPermissionRepository
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.time.Instant
import java.time.ZoneId

sealed interface ProductDataResult<out T> {
    data class Available<T>(val value: T) : ProductDataResult<T>

    data class Unavailable(val availability: ProductDataAvailability) : ProductDataResult<Nothing>
}

data class AndroidSystemSnapshot(
    val epochMillis: Long,
    val zoneId: String,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val networkType: String,
    val appVersion: String,
    val sdkInt: Int
) : Serializable

class AndroidSystemContextSource(
    context: Context,
    private val sourceId: String = "android-system",
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : ContextSource {
    private val appContext = context.applicationContext

    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        val snapshot = snapshot()
        return listOf(
            ContextCandidate(
                id = "android-system:${snapshot.epochMillis}",
                logicalId = "android-system-current",
                sourceId = sourceId,
                sourceRevision = snapshot.epochMillis,
                title = "Android system status",
                body = "time=${Instant.ofEpochMilli(snapshot.epochMillis)}; " +
                    "zone=${snapshot.zoneId}; battery=${snapshot.batteryPercent ?: "unknown"}; " +
                    "charging=${snapshot.charging ?: "unknown"}; " +
                    "network=${snapshot.networkType}; app=${snapshot.appVersion}; " +
                    "androidApi=${snapshot.sdkInt}",
                trust = ContextTrust.APPLICATION_STATE,
                privacy = ContextPrivacy.INTERNAL,
                createdAtEpochMillis = snapshot.epochMillis,
                relevance = 550,
                conflictKey = "android-system-current"
            )
        )
    }

    @Suppress("DEPRECATION")
    fun snapshot(): AndroidSystemSnapshot {
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { value -> value in 0..100 }
        val charging = battery?.isCharging
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val networkPermissionGranted =
            appContext.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED
        val capabilities = if (networkPermissionGranted) {
            readNetworkCapabilities(connectivity)
        } else {
            null
        }
        val network = when {
            !networkPermissionGranted -> "permission_unavailable"
            capabilities == null -> "offline_or_unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return AndroidSystemSnapshot(
            epochMillis = nowEpochMillis(),
            zoneId = ZoneId.systemDefault().id,
            batteryPercent = percent,
            charging = charging,
            networkType = network,
            appVersion = packageInfo.versionName.orEmpty().ifBlank { "unknown" },
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    @SuppressLint("MissingPermission")
    private fun readNetworkCapabilities(
        connectivity: ConnectivityManager?
    ): NetworkCapabilities? =
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
}

data class SelectedDocument(
    val id: String,
    val uri: String,
    val displayName: String,
    val mediaType: String,
    val privacy: ContextPrivacy = ContextPrivacy.SENSITIVE
) : Serializable

data class DocumentContent(
    val id: String,
    val displayName: String,
    val mediaType: String,
    val text: String,
    val byteSize: Int,
    val contentHash: String
) : Serializable

class AndroidDocumentReader(
    context: Context,
    private val enabled: () -> Boolean = { false },
    private val maxBytes: Int = 512 * 1024
) {
    private val appContext = context.applicationContext

    init {
        require(maxBytes in 1..16 * 1024 * 1024)
    }

    fun read(document: SelectedDocument): ProductDataResult<DocumentContent> {
        if (!enabled()) {
            return ProductDataResult.Unavailable(
                ProductDataAvailability(
                    ProductDataStatus.DISABLED,
                    "Document access is disabled until the user selects a document."
                )
            )
        }
        return runCatching {
            val uri = Uri.parse(document.uri)
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "Document exceeds the configured size limit." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("Content resolver returned no document stream.")
            ProductDataResult.Available(
                DocumentContent(
                    id = document.id,
                    displayName = document.displayName,
                    mediaType = document.mediaType,
                    text = bytes.toString(Charsets.UTF_8),
                    byteSize = bytes.size,
                    contentHash = DocumentContentHasher.sha256(bytes)
                )
            )
        }.getOrElse { error ->
            ProductDataResult.Unavailable(
                ProductDataAvailability(
                    ProductDataStatus.UNAVAILABLE,
                    error.message ?: "Document could not be read."
                )
            )
        }
    }
}

class SelectedDocumentContextSource(
    private val reader: AndroidDocumentReader,
    private val selected: () -> List<SelectedDocument>,
    private val sourceId: String = "selected-documents"
) : ContextSource {
    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        val requested = if (need.requestedSourceIds.isEmpty()) {
            emptyList()
        } else {
            selected().filter { document ->
                "$sourceId:${document.id}" in need.requestedSourceIds
            }
        }
        return requested.mapNotNull { document ->
            when (val result = reader.read(document)) {
                is ProductDataResult.Available -> ContextCandidate(
                    id = "document:${result.value.id}:${result.value.contentHash}",
                    logicalId = "document:${result.value.id}",
                    sourceId = sourceId,
                    title = result.value.displayName,
                    body = result.value.text,
                    trust = ContextTrust.EXTERNAL_UNTRUSTED,
                    privacy = document.privacy,
                    riskFlags = setOf(
                        ContextRiskFlag.PROMPT_INJECTION_POSSIBLE,
                        ContextRiskFlag.EXTERNAL_INSTRUCTION,
                        ContextRiskFlag.RETENTION_RESTRICTED
                    ),
                    createdAtEpochMillis = request.nowEpochMillis,
                    relevance = 800,
                    conflictKey = "document:${result.value.id}"
                )
                is ProductDataResult.Unavailable -> null
            }
        }
    }
}

data class CoarseLocationSnapshot(
    val latitudeBucket: Double,
    val longitudeBucket: Double,
    val accuracyMeters: Float,
    val observedAtEpochMillis: Long
) : Serializable

class AndroidCoarseLocationRepository(
    context: Context,
    private val permissions: AndroidPermissionRepository,
    private val enabled: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext

    @Suppress("MissingPermission")
    fun latest(): ProductDataResult<CoarseLocationSnapshot> {
        if (!enabled()) {
            return unavailable(ProductDataStatus.DISABLED, "Location adapter is disabled.")
        }
        val permission = permissions.snapshot(LOCATION_CAPABILITY)
        if (permission?.status != AndroidCapabilityStatus.GRANTED) {
            return unavailable(
                if (permission?.status == AndroidCapabilityStatus.NOT_DECLARED) {
                    ProductDataStatus.NOT_DECLARED
                } else {
                    ProductDataStatus.PERMISSION_REQUIRED
                },
                permission?.reason ?: "Location permission capability is unavailable."
            )
        }
        val manager = appContext.getSystemService(LocationManager::class.java)
            ?: return unavailable(
                ProductDataStatus.SERVICE_DISABLED,
                "LocationManager is unavailable."
            )
        if (!manager.isLocationEnabled) {
            return unavailable(ProductDataStatus.SERVICE_DISABLED, "Location services are disabled.")
        }
        val location = manager.getProviders(true).mapNotNull(manager::getLastKnownLocation)
            .maxByOrNull { value -> value.time }
            ?: return unavailable(
                ProductDataStatus.AVAILABLE,
                "Location is authorized but no observation is available yet."
            )
        return ProductDataResult.Available(
            CoarseLocationSnapshot(
                latitudeBucket = (location.latitude * 100).toInt() / 100.0,
                longitudeBucket = (location.longitude * 100).toInt() / 100.0,
                accuracyMeters = location.accuracy,
                observedAtEpochMillis = location.time
            )
        )
    }

    private fun unavailable(status: ProductDataStatus, reason: String) =
        ProductDataResult.Unavailable(ProductDataAvailability(status, reason))

    companion object {
        const val LOCATION_CAPABILITY = "location-coarse"
    }
}

data class CalendarEventSummary(
    val id: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean
) : Serializable

class AndroidCalendarRepository(
    context: Context,
    private val permissions: AndroidPermissionRepository,
    private val enabled: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext

    @Suppress("MissingPermission")
    fun events(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        limit: Int = 32
    ): ProductDataResult<List<CalendarEventSummary>> {
        require(fromEpochMillis <= toEpochMillis)
        require(limit in 1..256)
        if (!enabled()) {
            return unavailable(ProductDataStatus.DISABLED, "Calendar adapter is disabled.")
        }
        val permission = permissions.snapshot(CALENDAR_CAPABILITY)
        if (permission?.status != AndroidCapabilityStatus.GRANTED) {
            return unavailable(
                if (permission?.status == AndroidCapabilityStatus.NOT_DECLARED) {
                    ProductDataStatus.NOT_DECLARED
                } else {
                    ProductDataStatus.PERMISSION_REQUIRED
                },
                permission?.reason ?: "Calendar permission capability is unavailable."
            )
        }
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, fromEpochMillis)
        android.content.ContentUris.appendId(builder, toEpochMillis)
        return runCatching {
            val values = appContext.contentResolver.query(
                builder.build(),
                CALENDAR_PROJECTION,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            ).useSafely { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(
                            CalendarEventSummary(
                                id = cursor.getLong(0).toString(),
                                title = cursor.getString(1).orEmpty().ifBlank { "(untitled)" },
                                startEpochMillis = cursor.getLong(2),
                                endEpochMillis = cursor.getLong(3),
                                allDay = cursor.getInt(4) != 0
                            )
                        )
                    }
                }
            }
            ProductDataResult.Available(values)
        }.getOrElse { error ->
            unavailable(
                ProductDataStatus.UNAVAILABLE,
                error.message ?: "Calendar query failed."
            )
        }
    }

    private fun unavailable(status: ProductDataStatus, reason: String) =
        ProductDataResult.Unavailable(ProductDataAvailability(status, reason))

    private fun <T> Cursor?.useSafely(block: (Cursor) -> T): T {
        val cursor = this ?: error("Calendar provider returned no cursor.")
        return cursor.use(block)
    }

    companion object {
        const val CALENDAR_CAPABILITY = "calendar-read"
        private val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
    }
}

data class NotificationObservation(
    val id: String,
    val appLabel: String,
    val category: String,
    val summary: String,
    val postedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) : Serializable

/**
 * Host-fed notification adapter. A host that owns a NotificationListenerService
 * may submit redacted observations; this SDK module does not declare or enable
 * such a service by itself.
 */
class NotificationObservationRepository(
    private val enabled: () -> Boolean = { false },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private val observations = linkedMapOf<String, NotificationObservation>()

    @Synchronized
    fun submit(observation: NotificationObservation): Boolean {
        if (!enabled()) return false
        if (observation.expiresAtEpochMillis <= nowEpochMillis()) return false
        observations[observation.id] = observation
        purge()
        return true
    }

    @Synchronized
    fun current(): ProductDataResult<List<NotificationObservation>> {
        if (!enabled()) {
            return ProductDataResult.Unavailable(
                ProductDataAvailability(
                    ProductDataStatus.DISABLED,
                    "Notification observations are disabled."
                )
            )
        }
        purge()
        return ProductDataResult.Available(observations.values.toList())
    }

    private fun purge() {
        val now = nowEpochMillis()
        observations.entries.removeAll { entry -> entry.value.expiresAtEpochMillis <= now }
    }
}
