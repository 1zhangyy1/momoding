package app.momoding.core.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle

class AndroidClipboardGateway(
    private val clipboard: ClipboardManager,
) : ClipboardGateway {
    override suspend fun read(): ClipboardSnapshot {
        if (!clipboard.hasPrimaryClip()) return ClipboardSnapshot.Empty
        val clip = clipboard.primaryClip ?: return ClipboardSnapshot.Empty
        if (clip.itemCount < 1) return ClipboardSnapshot.Empty
        val directText = clip.getItemAt(0).text
            ?: return ClipboardSnapshot.Unsupported
        val value = directText.toString()
        if (value.isEmpty()) return ClipboardSnapshot.Empty
        return ClipboardSnapshot.Text(
            value = value,
            sourceMarkedSensitive = clip.description.extras
                ?.getBoolean(EXTRA_IS_SENSITIVE, false)
                ?: false,
        )
    }

    override suspend fun setText(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    override suspend fun clear() {
        clipboard.clearPrimaryClip()
    }

    private companion object {
        const val CLIP_LABEL = "Momoding"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
