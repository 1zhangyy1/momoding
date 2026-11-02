package app.momoding.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Apps in the system's share sheet (plan step 3): an app whose manifest says
 * `"momoding": {"share": true}` is offered by name when the person shares text or a picture
 * ("分享到记账"). What they share arrives as a `share` event (AppEvents' inbox) and the app opens to
 * take it: `momoding.on('share', s => …)` with `s.text`, `s.subject`, `s.image` (a data URL).
 * Sharing is the person's own act, so it needs no further OK.
 */
object AppShare {
    const val CATEGORY = "app.momoding.apps.SHARE_TARGET"
    private const val MAX_IMAGE_EDGE = 1600

    fun wantsShares(app: MoApps.App) = app.manifest().optJSONObject("momoding")?.optBoolean("share") == true

    /** Offer [app] in the share sheet, or stop offering it. Called when it is installed and opened. */
    fun publish(context: Context, app: MoApps.App) {
        val id = "momoding_app_${app.name}"
        if (!wantsShares(app)) { runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id)) }; return }
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(app.title).setLongLabel(app.title)
            .setIcon(IconCompat.createWithAdaptiveBitmap(AppIcon.bitmap(context, app, 432, adaptive = true)))
            .setIntent(MoAppActivity.intent(context, app.name))
            .setCategories(setOf(CATEGORY))
            .setLongLived(true)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info) }
    }

    /**
     * A share aimed at one app through the share sheet (ShareReceiverActivity calls this first):
     * true when it was one, and the app is opening with it.
     */
    fun receive(context: Context, intent: Intent): Boolean {
        val name = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)?.removePrefix("momoding_app_") ?: return false
        val app = MoApps.get(context, name) ?: return false
        if (intent.action != Intent.ACTION_SEND) return false
        val event = JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("type", "share").put("at", System.currentTimeMillis())
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { event.put("text", it) }
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { event.put("subject", it) }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.takeIf { intent.type?.startsWith("image/") == true }
            ?.let { uri -> image(context, uri)?.let { event.put("image", it) } }
        AppEvents.share(app, event)
        context.startActivity(MoAppActivity.intent(context, app.name))
        return true
    }

    /** The picture as a JPEG data URL, no larger than [MAX_IMAGE_EDGE] on its longer side. */
    private fun image(context: Context, uri: Uri): String? = runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
