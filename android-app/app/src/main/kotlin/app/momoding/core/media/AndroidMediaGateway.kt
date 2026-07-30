package app.momoding.core.media

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaGateway(
    private val resolver: ContentResolver,
) : MediaGateway {
    override suspend fun get(mediaId: Long): MediaItemSnapshot? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaId,
        )
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.IS_FAVORITE,
            MediaStore.Images.Media.IS_TRASHED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(uri, projection, queryArgs, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val favorite = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)
            val trashed = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_TRASHED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val captured = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            MediaItemSnapshot(
                mediaId = cursor.getLong(id),
                mimeType = cursor.getString(mime)?.takeIf(MEDIA_MIME_PATTERN::matches)
                    ?: "image/*",
                favorite = cursor.getInt(favorite) != 0,
                trashed = cursor.getInt(trashed) != 0,
                byteCount = if (cursor.isNull(size)) null else cursor.getLong(size),
                capturedAtMillis =
                    if (cursor.isNull(captured)) null else cursor.getLong(captured),
            )
        }
    }

    override fun consentRequest(
        action: MediaToolAction,
        mediaId: Long,
        desired: Boolean?,
    ): PendingIntent {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaId,
        )
        return when (action) {
            MediaToolAction.SET_FAVORITE ->
                MediaStore.createFavoriteRequest(
                    resolver,
                    listOf(uri),
                    requireNotNull(desired),
                )
            MediaToolAction.SET_TRASHED ->
                MediaStore.createTrashRequest(
                    resolver,
                    listOf(uri),
                    requireNotNull(desired),
                )
            MediaToolAction.DELETE ->
                MediaStore.createDeleteRequest(resolver, listOf(uri))
        }
    }

    private companion object {
        val MEDIA_MIME_PATTERN = Regex("^image/[A-Za-z0-9.+-]{1,120}$")
    }
}
