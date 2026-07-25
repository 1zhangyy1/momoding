package app.momoding.feature.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import org.robolectric.shadows.ShadowContentResolver

internal class ShareTestContentProvider(
    private val root: File,
) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun file(uri: Uri): File {
        val name = requireNotNull(uri.lastPathSegment)
        require('/' !in name && '\\' !in name && name != "." && name != "..")
        return root.resolve(name).canonicalFile.also { candidate ->
            require(candidate.parentFile == root.canonicalFile && candidate.isFile)
        }
    }
}

internal fun registerShareTestProvider(context: Context, root: File): String {
    val authority = "${context.packageName}.share-test.${System.identityHashCode(root)}"
    val provider = ShareTestContentProvider(root)
    provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
    ShadowContentResolver.registerProviderInternal(authority, provider)
    return authority
}
