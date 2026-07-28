// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.permission.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.androidagent.harness.context.ContextCandidate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextSource
import dev.androidagent.harness.context.ContextTrust
import java.io.Serializable

enum class AndroidCapabilityStatus {
    GRANTED,
    DENIED,
    RESTRICTED,
    NOT_DECLARED,
    SPECIAL_ACCESS_REQUIRED,
    SERVICE_DISABLED,
    UNAVAILABLE
}

enum class AndroidPermissionKind {
    RUNTIME,
    USAGE_ACCESS,
    ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER,
    OVERLAY,
    EXACT_ALARM,
    HOST_REPORTED
}

enum class AndroidSettingsAction {
    APP_DETAILS,
    USAGE_ACCESS,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    OVERLAY,
    EXACT_ALARM,
    NONE
}

data class AndroidPermissionSpec(
    val capabilityId: String,
    val displayName: String,
    val kind: AndroidPermissionKind,
    val manifestPermission: String? = null,
    val serviceComponent: ComponentName? = null,
    val minApi: Int = 24,
    val settingsAction: AndroidSettingsAction = AndroidSettingsAction.APP_DETAILS
) {
    init {
        require(capabilityId.isNotBlank()) { "Capability id must not be blank." }
        require(displayName.isNotBlank()) { "Capability display name must not be blank." }
        require(manifestPermission == null || manifestPermission.isNotBlank()) {
            "Manifest permission must not be blank."
        }
        require(minApi >= 1) { "Capability min API must be positive." }
    }
}

data class AndroidPermissionFacts(
    val platformSupported: Boolean,
    val manifestDeclared: Boolean,
    val platformGranted: Boolean,
    val serviceEnabled: Boolean = true,
    val restricted: Boolean = false,
    val available: Boolean = true,
    val reason: String = ""
)

data class PermissionSnapshot(
    val capabilityId: String,
    val displayName: String,
    val status: AndroidCapabilityStatus,
    val reason: String,
    val requestable: Boolean,
    val settingsAction: AndroidSettingsAction,
    val manifestDeclared: Boolean,
    val checkedAtEpochMillis: Long
) : Serializable {
    init {
        require(capabilityId.isNotBlank())
        require(displayName.isNotBlank())
        require(reason.isNotBlank())
    }
}

object AndroidPermissionStateResolver {
    fun resolve(
        spec: AndroidPermissionSpec,
        facts: AndroidPermissionFacts,
        checkedAtEpochMillis: Long
    ): PermissionSnapshot {
        val status = when {
            !facts.platformSupported || !facts.available -> AndroidCapabilityStatus.UNAVAILABLE
            spec.manifestPermission != null && !facts.manifestDeclared ->
                AndroidCapabilityStatus.NOT_DECLARED
            facts.restricted -> AndroidCapabilityStatus.RESTRICTED
            spec.kind in SERVICE_KINDS && !facts.serviceEnabled ->
                AndroidCapabilityStatus.SERVICE_DISABLED
            facts.platformGranted -> AndroidCapabilityStatus.GRANTED
            spec.kind == AndroidPermissionKind.RUNTIME -> AndroidCapabilityStatus.DENIED
            else -> AndroidCapabilityStatus.SPECIAL_ACCESS_REQUIRED
        }
        val reason = facts.reason.ifBlank {
            when (status) {
                AndroidCapabilityStatus.GRANTED -> "Capability is available."
                AndroidCapabilityStatus.DENIED -> "Runtime permission is denied."
                AndroidCapabilityStatus.RESTRICTED -> "Capability is restricted by device policy."
                AndroidCapabilityStatus.NOT_DECLARED ->
                    "The host manifest does not declare the required permission."
                AndroidCapabilityStatus.SPECIAL_ACCESS_REQUIRED ->
                    "Android special access has not been granted."
                AndroidCapabilityStatus.SERVICE_DISABLED ->
                    "The required Android service is disabled."
                AndroidCapabilityStatus.UNAVAILABLE ->
                    "Capability is unavailable on this device or Android version."
            }
        }
        return PermissionSnapshot(
            capabilityId = spec.capabilityId,
            displayName = spec.displayName,
            status = status,
            reason = reason,
            requestable = status == AndroidCapabilityStatus.DENIED &&
                spec.kind == AndroidPermissionKind.RUNTIME &&
                facts.manifestDeclared,
            settingsAction = if (status == AndroidCapabilityStatus.GRANTED) {
                AndroidSettingsAction.NONE
            } else {
                spec.settingsAction
            },
            manifestDeclared = facts.manifestDeclared,
            checkedAtEpochMillis = checkedAtEpochMillis
        )
    }

    private val SERVICE_KINDS = setOf(
        AndroidPermissionKind.ACCESSIBILITY_SERVICE,
        AndroidPermissionKind.NOTIFICATION_LISTENER
    )
}

interface AndroidPermissionRepository {
    fun snapshot(capabilityId: String): PermissionSnapshot?

    fun snapshots(): List<PermissionSnapshot>
}

class PlatformAndroidPermissionRepository(
    context: Context,
    private val specs: List<AndroidPermissionSpec>,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AndroidPermissionRepository {
    private val appContext = context.applicationContext

    init {
        require(specs.map(AndroidPermissionSpec::capabilityId).distinct().size == specs.size) {
            "Capability ids must be unique."
        }
    }

    override fun snapshot(capabilityId: String): PermissionSnapshot? =
        specs.firstOrNull { spec -> spec.capabilityId == capabilityId }
            ?.let(::inspect)

    override fun snapshots(): List<PermissionSnapshot> =
        specs.sortedBy(AndroidPermissionSpec::capabilityId).map(::inspect)

    private fun inspect(spec: AndroidPermissionSpec): PermissionSnapshot {
        val declared = spec.manifestPermission?.let(::isDeclared) ?: true
        val supported = Build.VERSION.SDK_INT >= spec.minApi
        val facts = if (!supported) {
            AndroidPermissionFacts(
                platformSupported = false,
                manifestDeclared = declared,
                platformGranted = false,
                available = false,
                reason = "Requires Android API ${spec.minApi}; device is API ${Build.VERSION.SDK_INT}."
            )
        } else {
            platformFacts(spec, declared)
        }
        return AndroidPermissionStateResolver.resolve(spec, facts, nowEpochMillis())
    }

    private fun platformFacts(
        spec: AndroidPermissionSpec,
        declared: Boolean
    ): AndroidPermissionFacts {
        return runCatching {
            when (spec.kind) {
                AndroidPermissionKind.RUNTIME -> AndroidPermissionFacts(
                    platformSupported = true,
                    manifestDeclared = declared,
                    platformGranted = spec.manifestPermission != null &&
                        appContext.checkSelfPermission(spec.manifestPermission) ==
                        PackageManager.PERMISSION_GRANTED
                )

                AndroidPermissionKind.USAGE_ACCESS -> AndroidPermissionFacts(
                    platformSupported = true,
                    manifestDeclared = declared,
                    platformGranted = hasUsageAccess()
                )

                AndroidPermissionKind.ACCESSIBILITY_SERVICE -> {
                    val manager = appContext.getSystemService(AccessibilityManager::class.java)
                    val enabled = manager?.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                    ).orEmpty().any { info ->
                        spec.serviceComponent == null ||
                            info.resolveInfo.serviceInfo.packageName ==
                            spec.serviceComponent.packageName &&
                            info.resolveInfo.serviceInfo.name == spec.serviceComponent.className
                    }
                    AndroidPermissionFacts(
                        platformSupported = manager != null,
                        manifestDeclared = declared,
                        platformGranted = enabled,
                        serviceEnabled = enabled
                    )
                }

                AndroidPermissionKind.NOTIFICATION_LISTENER -> {
                    val enabled = Settings.Secure.getString(
                        appContext.contentResolver,
                        "enabled_notification_listeners"
                    ).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
                        .any { component ->
                            spec.serviceComponent == null ||
                                component == spec.serviceComponent
                        }
                    AndroidPermissionFacts(
                        platformSupported = true,
                        manifestDeclared = declared,
                        platformGranted = enabled,
                        serviceEnabled = enabled
                    )
                }

                AndroidPermissionKind.OVERLAY -> AndroidPermissionFacts(
                    platformSupported = true,
                    manifestDeclared = declared,
                    platformGranted = Settings.canDrawOverlays(appContext)
                )

                AndroidPermissionKind.EXACT_ALARM -> {
                    val manager = appContext.getSystemService(AlarmManager::class.java)
                    val granted = Build.VERSION.SDK_INT < 31 ||
                        manager?.canScheduleExactAlarms() == true
                    AndroidPermissionFacts(
                        platformSupported = manager != null,
                        manifestDeclared = declared,
                        platformGranted = granted,
                        available = manager != null
                    )
                }

                AndroidPermissionKind.HOST_REPORTED -> AndroidPermissionFacts(
                    platformSupported = true,
                    manifestDeclared = declared,
                    platformGranted = false,
                    available = false,
                    reason = "Host must provide the capability state."
                )
            }
        }.getOrElse { error ->
            AndroidPermissionFacts(
                platformSupported = true,
                manifestDeclared = declared,
                platformGranted = false,
                available = false,
                reason = error.message ?: "Android capability probe failed."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val manager = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            manager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                appContext.applicationInfo.uid,
                appContext.packageName
            )
        } else {
            manager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                appContext.applicationInfo.uid,
                appContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun isDeclared(permission: String): Boolean {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_PERMISSIONS
            )
        }
        return permission in info.requestedPermissions.orEmpty()
    }
}

class PermissionContextSource(
    private val repository: AndroidPermissionRepository,
    private val sourceId: String = "android-permissions"
) : ContextSource {
    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        val snapshots = repository.snapshots()
        if (snapshots.isEmpty()) return emptyList()
        val body = snapshots.joinToString("\n") { snapshot ->
            "${snapshot.capabilityId}: ${snapshot.status.name.lowercase()} — ${snapshot.reason}"
        }
        return listOf(
            ContextCandidate(
                id = "permission-snapshot:${snapshots.maxOf { it.checkedAtEpochMillis }}",
                logicalId = "permission-snapshot",
                sourceId = sourceId,
                title = "Android capability status",
                body = body,
                trust = ContextTrust.APPLICATION_STATE,
                privacy = ContextPrivacy.INTERNAL,
                createdAtEpochMillis = snapshots.maxOf { it.checkedAtEpochMillis },
                relevance = 650,
                conflictKey = "android-permission-snapshot"
            )
        )
    }
}

object AndroidPermissionSettingsNavigator {
    fun intent(context: Context, snapshot: PermissionSnapshot): Intent? {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (snapshot.settingsAction) {
            AndroidSettingsAction.APP_DETAILS ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            AndroidSettingsAction.USAGE_ACCESS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AndroidSettingsAction.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AndroidSettingsAction.NOTIFICATION_LISTENER ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            AndroidSettingsAction.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            AndroidSettingsAction.EXACT_ALARM ->
                if (Build.VERSION.SDK_INT >= 31) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                }
            AndroidSettingsAction.NONE -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

object StandardAndroidPermissionSpecs {
    fun audioRecording() = AndroidPermissionSpec(
        capabilityId = "voice-input",
        displayName = "Microphone",
        kind = AndroidPermissionKind.RUNTIME,
        manifestPermission = Manifest.permission.RECORD_AUDIO
    )

    fun coarseLocation() = AndroidPermissionSpec(
        capabilityId = "coarse-location",
        displayName = "Approximate location (optional)",
        kind = AndroidPermissionKind.RUNTIME,
        manifestPermission = Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun calendarRead() = AndroidPermissionSpec(
        capabilityId = "calendar-read",
        displayName = "Calendar read (optional)",
        kind = AndroidPermissionKind.RUNTIME,
        manifestPermission = Manifest.permission.READ_CALENDAR
    )

    fun notifications() = AndroidPermissionSpec(
        capabilityId = "post-notifications",
        displayName = "Notifications (optional)",
        kind = AndroidPermissionKind.RUNTIME,
        manifestPermission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        },
        minApi = 33
    )

    fun usageStats() = AndroidPermissionSpec(
        capabilityId = "usage-stats",
        displayName = "Usage access",
        kind = AndroidPermissionKind.USAGE_ACCESS,
        manifestPermission = Manifest.permission.PACKAGE_USAGE_STATS,
        settingsAction = AndroidSettingsAction.USAGE_ACCESS
    )

    fun overlay() = AndroidPermissionSpec(
        capabilityId = "overlay",
        displayName = "Display over other apps",
        kind = AndroidPermissionKind.OVERLAY,
        manifestPermission = Manifest.permission.SYSTEM_ALERT_WINDOW,
        settingsAction = AndroidSettingsAction.OVERLAY
    )

    fun accessibility(component: ComponentName) = AndroidPermissionSpec(
        capabilityId = "phone-use",
        displayName = "Phone Use accessibility service",
        kind = AndroidPermissionKind.ACCESSIBILITY_SERVICE,
        serviceComponent = component,
        settingsAction = AndroidSettingsAction.ACCESSIBILITY
    )

    fun exactAlarm() = AndroidPermissionSpec(
        capabilityId = "exact-alarm",
        displayName = "Exact alarms",
        kind = AndroidPermissionKind.EXACT_ALARM,
        manifestPermission = if (Build.VERSION.SDK_INT >= 31) {
            Manifest.permission.SCHEDULE_EXACT_ALARM
        } else {
            null
        },
        minApi = 31,
        settingsAction = AndroidSettingsAction.EXACT_ALARM
    )
}
