package app.momoding.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The app's two link names.
 *
 * Inside the app a link is `minis://…`: upstream's vocabulary, shared with the sandbox, the model
 * and every saved conversation, so it stays. What the app registers with Android is `momoding`
 * (AndroidManifest.xml), so a phone that also has OpenMinis installed never shows a chooser for
 * these links. A link the app opens on itself goes to its own activity by name, never through
 * the system's resolution of a scheme. Ported from nanoMuse (`io.github.nanomuse.deeplink.OwnScheme`).
 */
object OwnScheme {
    /** What the manifest registers. */
    const val SYSTEM = "momoding"

    /** Upstream's in-app vocabulary; never registered with the system. */
    const val INTERNAL = "minis"

    /** The activity that takes every deep link. */
    private const val ACTIVITY = "com.openminis.app.MainActivity"

    fun isOwn(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == INTERNAL || s == SYSTEM
    }

    /** A VIEW intent for [uri] addressed to the app's own activity. */
    fun intent(context: Context, uri: Uri, flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK): Intent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            setClassName(context.packageName, ACTIVITY)
            if (flags != 0) addFlags(flags)
        }
}
