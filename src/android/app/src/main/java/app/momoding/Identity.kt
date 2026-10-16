package app.momoding

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.R
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore

/** Who the Bot is to the user: its name (SOUL.md) and its face (the launcher icon). */
object Identity {
    private const val FACE_PX = 128
    @Volatile private var cachedFace: Bitmap? = null

    fun name(context: Context): String {
        val onDisk = SoulStore.load(context)?.metadata?.name?.trim()
        val cached = SoulStore.cachedMetadata.value.name.trim()
        return (onDisk ?: cached).ifEmpty { SoulMetadata.DEFAULT.name }
    }

    /** The face for notifications; null when the icon cannot be rendered. */
    fun face(context: Context): Bitmap? {
        cachedFace?.let { return it }
        return runCatching {
            ContextCompat.getDrawable(context.applicationContext, R.mipmap.ic_launcher)?.toBitmap(FACE_PX, FACE_PX)
        }.getOrNull()?.also { cachedFace = it }
    }
}
