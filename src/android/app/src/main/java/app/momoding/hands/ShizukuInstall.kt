package app.momoding.hands

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shizuku ships inside Momoding (the official 13.6.0 APK, unmodified, Apache-2.0; see
 * assets/shizuku/NOTICE.txt), so getting it is one tap to the system's install sheet instead of
 * a trip to GitHub in the browser. Falls back to the download page if the copy cannot be opened.
 */
object ShizukuInstall {
    fun start(context: Context) {
        val ok = runCatching {
            val apk = File(context.cacheDir, "share/shizuku.apk").apply { parentFile?.mkdirs() }
            context.assets.open("shizuku/shizuku.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // straight to the system's installer: Momoding itself also opens APK files, and a
            // "open with" chooser in front of the install sheet is one step too many
            context.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
                ?.let { intent.setClassName(it.activityInfo.packageName, it.activityInfo.name) }
            context.startActivity(intent)
        }.isSuccess
        if (!ok) com.openminis.app.offload.ShizukuManager.openInstallPage(context)
    }
}
