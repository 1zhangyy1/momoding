package app.momoding.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import org.json.JSONObject
import java.io.File

/**
 * Keeps an eye on a QR login page in Momoding's browser while its card is up, the way a person
 * would: the code in the card is cut to the code itself and redrawn when the site refreshes it,
 * and once the page moves on (the person scanned) the chat carries on without being told.
 */
internal object QrWatch {
    /** One look at the page: where it is, and the code's box in CSS pixels when there is one. */
    data class Look(val url: String, val box: FloatArray?, val viewportWidth: Float, val key: String?)

    // the largest visible square image, canvas or svg, preferring one that names itself a QR code;
    // the key changes when the site draws a new code
    private const val FIND = """
        var best = null, bestScore = 0;
        document.querySelectorAll('img,canvas,svg').forEach(function (e) {
            var r = e.getBoundingClientRect();
            if (r.width < 80 || r.height < 80 || Math.abs(r.width - r.height) > r.width * 0.15) return;
            if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) return;
            var s = getComputedStyle(e);
            if (s.visibility == 'hidden' || s.display == 'none' || +s.opacity == 0) return;
            var named = /qr|qrcode|二维码|scan/i.test((e.id || '') + ' ' + (e.getAttribute('class') || '') + ' ' + (e.getAttribute('alt') || '') + ' ' + (e.getAttribute('src') || '').slice(0, 200));
            var score = r.width * r.height * (named ? 4 : 1);
            if (score > bestScore) { bestScore = score; best = e; }
        });
        var key = null, box = null;
        if (best) {
            var r = best.getBoundingClientRect();
            box = [r.left, r.top, r.width, r.height];
            var src = best.tagName == 'IMG' ? best.currentSrc || best.src : best.tagName == 'CANVAS' ? (function () { try { return best.toDataURL(); } catch (e) { return ''; } })() : best.outerHTML;
            key = src.length + ':' + src.slice(-64);
        }
        return 'QR' + JSON.stringify({u: location.href, b: box, w: innerWidth, k: key});
    """

    suspend fun look(pool: BrowserTabPool): Look? {
        val r = runCatching { pool.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = FIND)) }.getOrNull() ?: return null
        val o = r.text.trim().takeIf { r.success && it.startsWith("QR") }?.let { runCatching { JSONObject(it.removePrefix("QR")) }.getOrNull() } ?: return null
        val b = o.optJSONArray("b")?.let { a -> FloatArray(4) { a.optDouble(it).toFloat() } }
        return Look(o.optString("u"), b, o.optDouble("w", 0.0).toFloat(), o.optString("k").takeIf { !o.isNull("k") })
    }

    /** A screenshot of the page cut to the code, with a quiet margin so the scanner finds its edges. */
    suspend fun crop(context: Context, pool: BrowserTabPool, look: Look): File? {
        val box = look.box ?: return null
        if (look.viewportWidth <= 0f) return null
        val shot = runCatching { pool.execute(BrowserActionInput(action = BrowserAction.SCREENSHOT)) }.getOrNull()?.imageFilePath ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { cut(context, shot, box, look.viewportWidth) }
    }

    // decoding and encoding a screenful is too slow for the main thread
    private fun cut(context: Context, shot: String, box: FloatArray, viewportWidth: Float): File? {
        val full = BitmapFactory.decodeFile(shot) ?: return null
        val scale = full.width / viewportWidth
        val pad = box[2] * 0.08f
        val left = ((box[0] - pad) * scale).toInt().coerceIn(0, full.width - 1)
        val top = ((box[1] - pad) * scale).toInt().coerceIn(0, full.height - 1)
        val right = ((box[0] + box[2] + pad) * scale).toInt().coerceIn(left + 1, full.width)
        val bottom = ((box[1] + box[3] + pad) * scale).toInt().coerceIn(top + 1, full.height)
        val cut = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        val dir = File(context.cacheDir, "momoding-qr").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the code on screen now is worth keeping
        return File(dir, "qr-${System.currentTimeMillis()}.jpg").also { f -> f.outputStream().use { cut.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }

    /** The page has moved on from [first], the look that showed the code: another address, or the code gone for a few looks. */
    fun signedIn(first: Look, now: Look, goneLooks: Int): Boolean =
        now.url.substringBefore('#') != first.url.substringBefore('#') || (now.key == null && goneLooks >= 3)
}
