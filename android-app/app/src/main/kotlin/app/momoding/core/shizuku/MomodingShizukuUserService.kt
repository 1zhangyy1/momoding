package app.momoding.core.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Process
import android.util.Log
import androidx.annotation.Keep

class MomodingShizukuUserService @Keep constructor(
    private val context: Context,
) : IMomodingShizukuService.Stub() {
    private val expectedCallerUid = context.applicationInfo.uid

    constructor() : this(
        error("Shizuku API $MIN_SHIZUKU_SERVER_API or newer is required"),
    )

    override fun getProcessUid(): Int {
        enforceAllowedCaller()
        return Process.myUid()
    }

    override fun getProcessPid(): Int {
        enforceAllowedCaller()
        return Process.myPid()
    }

    @SuppressLint("QueryPermissionsNeeded")
    override fun listPackages(
        includeSystem: Boolean,
        offset: Int,
        limit: Int,
    ): List<ShizukuPackageSummary> {
        enforceAllowedCaller()
        require(validShizukuPackagePage(offset, limit)) { "package page is out of range" }
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        return packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { includeSystem || !it.isSystemPackage() }
            .map { info ->
                ShizukuPackageSummary(
                    packageName = info.packageName,
                    label = runCatching {
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(info.packageName).boundedLabel(info.packageName),
                    system = info.isSystemPackage(),
                    enabled = info.enabled,
                )
            }
            .sortedBy(ShizukuPackageSummary::packageName)
            .drop(offset)
            .take(limit)
            .toList()
    }

    override fun inspectPackage(packageName: String): ShizukuPackageDetails? {
        enforceAllowedCaller()
        require(validShizukuPackageName(packageName)) {
            "invalid package name"
        }
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return null
        }
        val applicationInfo = requireNotNull(packageInfo.applicationInfo) {
            "package has no application metadata"
        }
        return ShizukuPackageDetails(
            packageName = packageName,
            label = runCatching {
                packageManager.getApplicationLabel(applicationInfo).toString()
            }.getOrDefault(packageName).boundedLabel(packageName),
            system = applicationInfo.isSystemPackage(),
            enabled = applicationInfo.enabled,
            versionName = packageInfo.versionName.orEmpty().take(MAX_VERSION_NAME),
            versionCode = packageInfo.longVersionCode,
            minSdk = applicationInfo.minSdkVersion,
            targetSdk = applicationInfo.targetSdkVersion,
        )
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun enforceAllowedCaller() {
        check(Process.myUid() == ANDROID_SHELL_UID) {
            "service is not running as Android shell"
        }
        val callingUid = Binder.getCallingUid()
        if (callingUid != expectedCallerUid) {
            Log.w(TAG, "Rejected caller uid=$callingUid expected=$expectedCallerUid")
        }
        check(callingUid == expectedCallerUid) {
            "caller is not the Momoding application"
        }
    }

    private fun ApplicationInfo.isSystemPackage(): Boolean =
        flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun String.boundedLabel(fallback: String): String =
        trim().take(MAX_LABEL).ifBlank { fallback }

    companion object {
        private const val MAX_LABEL = 120
        private const val MAX_VERSION_NAME = 120
        private const val TAG = "MomodingShizukuService"
    }
}
