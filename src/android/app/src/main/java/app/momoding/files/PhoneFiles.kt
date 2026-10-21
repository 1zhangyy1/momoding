package app.momoding.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.openminis.app.MinisApp

/**
 * The phone's own storage (Download, Documents, DCIM, Pictures…) for Momoding to read and tidy:
 * one switch ("All files access"), then the whole storage is mounted at /var/minis/mounts/phone,
 * instead of the person mounting folders one by one through the picker.
 */
object PhoneFiles {
    const val MOUNT = "phone"
    private const val AUTHORITY = "com.android.externalstorage.documents"

    fun allowed(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Momoding's own "All files access" switch (the system page; the person turns it on). */
    fun openSwitch(context: Context) {
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (i in listOf(own, list)) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** Mounts the whole primary storage once the switch is on; true when it is (or already was) there. */
    suspend fun ensureMounted(context: Context): Boolean {
        if (!allowed()) return false
        val store = (context.applicationContext as? MinisApp)?.mountedFoldersStore ?: return false
        if (store.entries.value.any { it.name.equals(MOUNT, ignoreCase = true) }) return true
        val root = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:")
        return store.add(root, MOUNT, userAllowWrite = true) != null
    }
}
