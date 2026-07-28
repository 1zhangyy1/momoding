package app.momoding.core.capabilities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.momoding.core.accessibility.MomodingAccessibilityRuntime
import app.momoding.core.accessibility.MomodingScreenCaptureRuntime
import app.momoding.core.files.AuthorizedFolderStatus
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.shizuku.ShizukuController
import app.momoding.core.shizuku.ShizukuLifecycleStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AndroidCapabilityId {
    SAF_FOLDERS,
    PHOTO_LIBRARY,
    ACCESSIBILITY_CONTROL,
    SCREEN_CAPTURE,
    ALL_FILES,
    SHIZUKU_SHELL_UID,
}

enum class CapabilityAvailability {
    UNSUPPORTED,
    MISSING_DEPENDENCY,
    NOT_GRANTED,
    PARTIAL,
    SESSION_REQUIRED,
    READY,
    ERROR,
}

data class AndroidCapabilityState(
    val id: AndroidCapabilityId,
    val availability: CapabilityAvailability,
    val source: String,
    val checkedAtMillis: Long,
    val safeMessage: String,
)

fun interface AndroidCapabilityProbe {
    suspend fun probe(checkedAtMillis: Long): AndroidCapabilityState
}

/**
 * Single live source of truth for Android-side access.
 *
 * Approval modes intentionally do not participate in these probes. A task can choose how often
 * to ask, but it cannot manufacture Android access that the device has not actually granted.
 */
class AndroidCapabilityRegistry(
    private val probes: Map<AndroidCapabilityId, AndroidCapabilityProbe>,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(probes.keys == AndroidCapabilityId.entries.toSet()) {
            "Every Android capability must have exactly one probe"
        }
    }

    private val refreshMutex = Mutex()
    private val _states = MutableStateFlow<List<AndroidCapabilityState>>(emptyList())
    val states: StateFlow<List<AndroidCapabilityState>> = _states.asStateFlow()

    fun refresh() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow(): List<AndroidCapabilityState> = refreshMutex.withLock {
        val checkedAt = nowMillis()
        val refreshed = AndroidCapabilityId.entries.map { id ->
            runCatching {
                probes.getValue(id).probe(checkedAt).also { state ->
                    require(state.id == id) { "Capability probe returned the wrong id" }
                    require(state.checkedAtMillis == checkedAt) {
                        "Capability probe returned a stale timestamp"
                    }
                    require(state.source.isNotBlank()) { "Capability source is blank" }
                    require(state.safeMessage.isNotBlank()) { "Capability message is blank" }
                }
            }
                .getOrElse {
                    AndroidCapabilityState(
                        id = id,
                        availability = CapabilityAvailability.ERROR,
                        source = "Local capability probe",
                        checkedAtMillis = checkedAt,
                        safeMessage = "Android access could not be checked. Try again.",
                    )
                }
        }
        _states.value = refreshed
        refreshed
    }

    companion object {
        fun create(
            context: Context,
            folders: AuthorizedFoldersRepository,
            shizuku: ShizukuController,
            scope: CoroutineScope,
        ): AndroidCapabilityRegistry {
            val applicationContext = context.applicationContext
            return AndroidCapabilityRegistry(
                probes = mapOf(
                    AndroidCapabilityId.SAF_FOLDERS to safFoldersProbe(folders),
                    AndroidCapabilityId.PHOTO_LIBRARY to photoLibraryProbe(applicationContext),
                    AndroidCapabilityId.ACCESSIBILITY_CONTROL to
                        accessibilityProbe(applicationContext),
                    AndroidCapabilityId.SCREEN_CAPTURE to screenCaptureProbe(),
                    AndroidCapabilityId.ALL_FILES to allFilesProbe(applicationContext),
                    AndroidCapabilityId.SHIZUKU_SHELL_UID to shizukuProbe(shizuku),
                ),
                scope = scope,
            )
        }
    }
}

internal fun photoLibraryPermissionRequest(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
        READ_MEDIA_IMAGES_PERMISSION,
        READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION,
    )
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(READ_MEDIA_IMAGES_PERMISSION)
    sdkInt >= Build.VERSION_CODES.M -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> emptyList()
}

internal fun photoLibraryAvailability(
    sdkInt: Int,
    isGranted: (String) -> Boolean,
): CapabilityAvailability = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        isGranted(READ_MEDIA_IMAGES_PERMISSION) -> CapabilityAvailability.READY
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        isGranted(READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION) -> CapabilityAvailability.PARTIAL
    sdkInt <= Build.VERSION_CODES.S_V2 &&
        isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) -> CapabilityAvailability.READY
    else -> CapabilityAvailability.NOT_GRANTED
}

private fun photoLibraryProbe(context: Context) = AndroidCapabilityProbe { checkedAt ->
    val availability = photoLibraryAvailability(Build.VERSION.SDK_INT) { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    AndroidCapabilityState(
        id = AndroidCapabilityId.PHOTO_LIBRARY,
        availability = availability,
        source = "Android media permissions",
        checkedAtMillis = checkedAt,
        safeMessage = when (availability) {
            CapabilityAvailability.READY -> "Full photo library access is enabled."
            CapabilityAvailability.PARTIAL -> "Only photos selected in Android are available."
            CapabilityAvailability.NOT_GRANTED ->
                "Photo library access is not enabled. The Composer Photo Picker still works."
            else -> "Photo library access could not be checked."
        },
    )
}

private fun safFoldersProbe(folders: AuthorizedFoldersRepository) =
    AndroidCapabilityProbe { checkedAt ->
        val summaries = folders.folders()
        val ready = summaries.count { folder ->
            folder.canRead && folder.status in setOf(
                AuthorizedFolderStatus.ACTIVE,
                AuthorizedFolderStatus.READ_ONLY,
            )
        }
        val unavailable = summaries.count {
            it.status == AuthorizedFolderStatus.PROVIDER_UNAVAILABLE
        }
        when {
            ready > 0 -> AndroidCapabilityState(
                id = AndroidCapabilityId.SAF_FOLDERS,
                availability = CapabilityAvailability.READY,
                source = "Android Storage Access Framework",
                checkedAtMillis = checkedAt,
                safeMessage = "$ready authorized folder${if (ready == 1) "" else "s"} available.",
            )
            unavailable > 0 -> AndroidCapabilityState(
                id = AndroidCapabilityId.SAF_FOLDERS,
                availability = CapabilityAvailability.ERROR,
                source = "Android Storage Access Framework",
                checkedAtMillis = checkedAt,
                safeMessage = "An authorized folder provider is unavailable.",
            )
            else -> AndroidCapabilityState(
                id = AndroidCapabilityId.SAF_FOLDERS,
                availability = CapabilityAvailability.NOT_GRANTED,
                source = "Android Storage Access Framework",
                checkedAtMillis = checkedAt,
                safeMessage = if (summaries.isEmpty()) {
                    "No folder has been authorized."
                } else {
                    "Folder access must be authorized again."
                },
            )
        }
    }

private fun accessibilityProbe(context: Context) = AndroidCapabilityProbe { checkedAt ->
    @Suppress("DEPRECATION")
    val services = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS,
    ).services.orEmpty().filter { service ->
        service.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
    }
    val serviceInstalled = services.isNotEmpty()
    if (!serviceInstalled) {
        AndroidCapabilityState(
            id = AndroidCapabilityId.ACCESSIBILITY_CONTROL,
            availability = CapabilityAvailability.UNSUPPORTED,
            source = "App package",
            checkedAtMillis = checkedAt,
            safeMessage = "Accessibility control is not installed in this build.",
        )
    } else {
        val installed = services.map { service ->
            ComponentName(service.packageName, service.name).flattenToString()
        }.toSet()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
            .map(ComponentName::flattenToString)
            .toSet()
        val settingEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1 && installed.any(enabled::contains)
        val availability = accessibilityAvailability(
            serviceInstalled = true,
            settingEnabled = settingEnabled,
            serviceConnected = MomodingAccessibilityRuntime.controller.state.value.connected,
        )
        AndroidCapabilityState(
            id = AndroidCapabilityId.ACCESSIBILITY_CONTROL,
            availability = availability,
            source = "Android accessibility settings",
            checkedAtMillis = checkedAt,
            safeMessage = when (availability) {
                CapabilityAvailability.READY ->
                    "Accessibility observation is connected and ready."
                CapabilityAvailability.SESSION_REQUIRED ->
                    "Accessibility is enabled. Waiting for Android to connect the service."
                else -> "Accessibility observation is not enabled."
            },
        )
    }
}

internal fun accessibilityAvailability(
    serviceInstalled: Boolean,
    settingEnabled: Boolean,
    serviceConnected: Boolean,
): CapabilityAvailability = when {
    !serviceInstalled -> CapabilityAvailability.UNSUPPORTED
    !settingEnabled -> CapabilityAvailability.NOT_GRANTED
    serviceConnected -> CapabilityAvailability.READY
    else -> CapabilityAvailability.SESSION_REQUIRED
}

private fun screenCaptureProbe() = AndroidCapabilityProbe { checkedAt ->
    val accessibilityReady = MomodingAccessibilityRuntime.controller.state.value.connected
    val projectionReady = MomodingScreenCaptureRuntime.mediaProjection.state.value.active
    val availability = if (accessibilityReady || projectionReady) {
        CapabilityAvailability.READY
    } else {
        CapabilityAvailability.SESSION_REQUIRED
    }
    AndroidCapabilityState(
        id = AndroidCapabilityId.SCREEN_CAPTURE,
        availability = availability,
        source = when {
            accessibilityReady -> "Android Accessibility"
            projectionReady -> "Android MediaProjection"
            else -> "Android screen-capture consent"
        },
        checkedAtMillis = checkedAt,
        safeMessage = when {
            accessibilityReady ->
                "Live screen images are available through Accessibility control."
            projectionReady -> "A time-limited screen-capture session is active."
            else -> "Enable Accessibility control or start a one-time screen-capture session."
        },
    )
}

private fun allFilesProbe(context: Context) = AndroidCapabilityProbe { checkedAt ->
    @Suppress("DEPRECATION")
    val requested = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        .orEmpty()
        .contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    val availability = when {
        !requested -> CapabilityAvailability.UNSUPPORTED
        Environment.isExternalStorageManager() -> CapabilityAvailability.READY
        else -> CapabilityAvailability.NOT_GRANTED
    }
    AndroidCapabilityState(
        id = AndroidCapabilityId.ALL_FILES,
        availability = availability,
        source = "Android app access",
        checkedAtMillis = checkedAt,
        safeMessage = when (availability) {
            CapabilityAvailability.READY -> "All files access is enabled."
            CapabilityAvailability.NOT_GRANTED -> "All files access is not enabled."
            else -> "All files access is not installed in this build."
        },
    )
}

// Permission names are stable platform contract strings. Literal values keep minSdk 30 builds
// from inlining fields introduced in API 33/34 before the guarded SDK checks above.
private const val READ_MEDIA_IMAGES_PERMISSION = "android.permission.READ_MEDIA_IMAGES"
private const val READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION =
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

private fun shizukuProbe(controller: ShizukuController) = AndroidCapabilityProbe { checkedAt ->
    controller.refresh()
    val snapshot = controller.state.value
    val availability = when (snapshot.stage) {
        ShizukuLifecycleStage.NOT_INSTALLED -> CapabilityAvailability.MISSING_DEPENDENCY
        ShizukuLifecycleStage.NOT_RUNNING,
        ShizukuLifecycleStage.CONNECTING -> CapabilityAvailability.SESSION_REQUIRED
        ShizukuLifecycleStage.PERMISSION_REQUIRED -> CapabilityAvailability.NOT_GRANTED
        ShizukuLifecycleStage.READY -> CapabilityAvailability.READY
        ShizukuLifecycleStage.UNSUPPORTED_VERSION,
        ShizukuLifecycleStage.ROOT_REJECTED -> CapabilityAvailability.UNSUPPORTED
        ShizukuLifecycleStage.ERROR -> CapabilityAvailability.ERROR
    }
    AndroidCapabilityState(
        id = AndroidCapabilityId.SHIZUKU_SHELL_UID,
        availability = availability,
        source = "Shizuku shell UserService",
        checkedAtMillis = checkedAt,
        safeMessage = snapshot.safeMessage,
    )
}
