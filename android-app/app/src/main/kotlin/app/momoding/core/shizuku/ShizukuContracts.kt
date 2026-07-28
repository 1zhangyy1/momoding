package app.momoding.core.shizuku

import android.os.Parcelable
import android.os.Parcel

internal const val ANDROID_SHELL_UID = 2000
internal const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
internal const val MIN_SHIZUKU_SERVER_API = 13
internal const val MAX_SHIZUKU_PACKAGE_OFFSET = 10_000
internal const val MAX_SHIZUKU_PACKAGE_LIMIT = 100
internal const val MAX_SHIZUKU_PACKAGE_NAME = 255
private val ANDROID_PACKAGE_NAME =
    Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

internal fun validShizukuPackagePage(offset: Int, limit: Int): Boolean =
    offset in 0..MAX_SHIZUKU_PACKAGE_OFFSET && limit in 1..MAX_SHIZUKU_PACKAGE_LIMIT

internal fun validShizukuPackageName(packageName: String): Boolean =
    packageName.length <= MAX_SHIZUKU_PACKAGE_NAME && ANDROID_PACKAGE_NAME.matches(packageName)

enum class ShizukuLifecycleStage {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNSUPPORTED_VERSION,
    PERMISSION_REQUIRED,
    ROOT_REJECTED,
    CONNECTING,
    READY,
    ERROR,
}

data class ShizukuSnapshot(
    val stage: ShizukuLifecycleStage,
    val serverUid: Int? = null,
    val serverApiVersion: Int? = null,
    val clientApiVersion: Int? = null,
    val serviceUid: Int? = null,
    val servicePid: Int? = null,
    val safeMessage: String,
)

internal data class ShizukuPrerequisites(
    val managerInstalled: Boolean,
    val binderAlive: Boolean,
    val preV11: Boolean,
    val permissionGranted: Boolean,
    val serverUid: Int?,
    val serverApiVersion: Int?,
    val clientApiVersion: Int?,
    val serviceUid: Int?,
    val servicePid: Int?,
    val serviceConnected: Boolean,
    val failed: Boolean = false,
)

internal fun shizukuSnapshot(facts: ShizukuPrerequisites): ShizukuSnapshot {
    val stage = when {
        facts.failed -> ShizukuLifecycleStage.ERROR
        !facts.managerInstalled -> ShizukuLifecycleStage.NOT_INSTALLED
        !facts.binderAlive -> ShizukuLifecycleStage.NOT_RUNNING
        facts.preV11 ||
            facts.serverApiVersion == null ||
            facts.serverApiVersion < MIN_SHIZUKU_SERVER_API ->
            ShizukuLifecycleStage.UNSUPPORTED_VERSION
        facts.serverUid != ANDROID_SHELL_UID -> ShizukuLifecycleStage.ROOT_REJECTED
        !facts.permissionGranted -> ShizukuLifecycleStage.PERMISSION_REQUIRED
        !facts.serviceConnected -> ShizukuLifecycleStage.CONNECTING
        facts.serviceUid != ANDROID_SHELL_UID -> ShizukuLifecycleStage.ROOT_REJECTED
        else -> ShizukuLifecycleStage.READY
    }
    return ShizukuSnapshot(
        stage = stage,
        serverUid = facts.serverUid,
        serverApiVersion = facts.serverApiVersion,
        clientApiVersion = facts.clientApiVersion,
        serviceUid = facts.serviceUid,
        servicePid = facts.servicePid,
        safeMessage = when (stage) {
            ShizukuLifecycleStage.NOT_INSTALLED ->
                "Install Shizuku to enable selected Android package queries."
            ShizukuLifecycleStage.NOT_RUNNING ->
                "Open Shizuku and start its service."
            ShizukuLifecycleStage.UNSUPPORTED_VERSION ->
                "This Shizuku version is too old. Update Shizuku before continuing."
            ShizukuLifecycleStage.PERMISSION_REQUIRED ->
                "Authorize Momoding in Shizuku."
            ShizukuLifecycleStage.ROOT_REJECTED ->
                "Root/Sui access is not accepted. Start Shizuku with Android shell access."
            ShizukuLifecycleStage.CONNECTING ->
                "Connecting the bounded Shizuku package service."
            ShizukuLifecycleStage.READY ->
                "Shizuku shell access is ready for selected package queries."
            ShizukuLifecycleStage.ERROR ->
                "Shizuku access could not be checked. Try again."
        },
    )
}

sealed interface ShizukuQueryResult<out T> {
    data class Ready<T>(val value: T) : ShizukuQueryResult<T>
    data class Failed(val code: String, val safeMessage: String) : ShizukuQueryResult<Nothing>
}

data class ShizukuPackageSummary(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        packageName = requireNotNull(parcel.readString()),
        label = requireNotNull(parcel.readString()),
        system = parcel.readInt() != 0,
        enabled = parcel.readInt() != 0,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(packageName)
        parcel.writeString(label)
        parcel.writeInt(if (system) 1 else 0)
        parcel.writeInt(if (enabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ShizukuPackageSummary> {
            override fun createFromParcel(parcel: Parcel) = ShizukuPackageSummary(parcel)
            override fun newArray(size: Int): Array<ShizukuPackageSummary?> = arrayOfNulls(size)
        }
    }
}

data class ShizukuPackageDetails(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        packageName = requireNotNull(parcel.readString()),
        label = requireNotNull(parcel.readString()),
        system = parcel.readInt() != 0,
        enabled = parcel.readInt() != 0,
        versionName = requireNotNull(parcel.readString()),
        versionCode = parcel.readLong(),
        minSdk = parcel.readInt(),
        targetSdk = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(packageName)
        parcel.writeString(label)
        parcel.writeInt(if (system) 1 else 0)
        parcel.writeInt(if (enabled) 1 else 0)
        parcel.writeString(versionName)
        parcel.writeLong(versionCode)
        parcel.writeInt(minSdk)
        parcel.writeInt(targetSdk)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ShizukuPackageDetails> {
            override fun createFromParcel(parcel: Parcel) = ShizukuPackageDetails(parcel)
            override fun newArray(size: Int): Array<ShizukuPackageDetails?> = arrayOfNulls(size)
        }
    }
}
