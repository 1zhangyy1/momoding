package app.momoding.core.skills

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.ByteArrayOutputStream
import java.io.InputStream

class SkillPackageImportException(
    val code: String,
) : IllegalArgumentException(code)

internal data class SkillPackageTreeNode(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val flags: Int,
)

internal interface SkillPackageTreeAccess {
    fun root(treeUri: Uri): SkillPackageTreeNode
    fun children(treeUri: Uri, parentDocumentId: String): List<SkillPackageTreeNode>
    fun open(treeUri: Uri, documentId: String): InputStream
}

/** Copies one user-selected Skill directory into a bounded, URI-free package document. */
class SkillPackageImportReader internal constructor(
    private val access: SkillPackageTreeAccess,
) {
    constructor(contentResolver: ContentResolver) : this(
        ContentResolverSkillPackageTreeAccess(contentResolver),
    )

    fun read(treeUri: Uri): SkillPackageDocument {
        if (
            treeUri.scheme != ContentResolver.SCHEME_CONTENT ||
            treeUri.authority.isNullOrBlank() ||
            !DocumentsContract.isTreeUri(treeUri)
        ) {
            throw SkillPackageImportException("SKILL_PACKAGE_TREE_URI_INVALID")
        }
        try {
            val root = access.root(treeUri)
            requireDirectory(root)
            requireSafeSegment(root.displayName)
            val visited = mutableSetOf(root.documentId)
            val files = mutableListOf<SkillPackageFile>()
            var totalBytes = 0L

            fun visit(parent: SkillPackageTreeNode, prefix: String, depth: Int) {
                if (depth > MAX_SKILL_PACKAGE_DEPTH) {
                    throw SkillPackageImportException("SKILL_PACKAGE_DEPTH_EXCEEDED")
                }
                val children = access.children(treeUri, parent.documentId)
                val names = mutableSetOf<String>()
                children.sortedBy(SkillPackageTreeNode::displayName).forEach { child ->
                    if (!visited.add(child.documentId)) {
                        throw SkillPackageImportException("SKILL_PACKAGE_CYCLE_DETECTED")
                    }
                    requireSafeSegment(child.displayName)
                    if (!names.add(child.displayName)) {
                        throw SkillPackageImportException("SKILL_PACKAGE_PATH_DUPLICATED")
                    }
                    val relativePath = if (prefix.isEmpty()) {
                        child.displayName
                    } else {
                        "$prefix/${child.displayName}"
                    }
                    try {
                        requireValidSkillResourcePath(relativePath)
                    } catch (_: IllegalArgumentException) {
                        throw SkillPackageImportException("SKILL_RESOURCE_PATH_INVALID")
                    }
                    if (child.mimeType == Document.MIME_TYPE_DIR) {
                        visit(child, relativePath, depth + 1)
                    } else {
                        if ((child.flags and Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
                            throw SkillPackageImportException("SKILL_PACKAGE_VIRTUAL_FILE_UNSUPPORTED")
                        }
                        if (files.size >= MAX_SKILL_PACKAGE_FILES) {
                            throw SkillPackageImportException("SKILL_PACKAGE_FILE_LIMIT_EXCEEDED")
                        }
                        if (
                            child.sizeBytes != null &&
                            child.sizeBytes !in 0..MAX_SKILL_PACKAGE_FILE_BYTES.toLong()
                        ) {
                            throw SkillPackageImportException("SKILL_PACKAGE_FILE_TOO_LARGE")
                        }
                        val remaining = MAX_SKILL_PACKAGE_TOTAL_BYTES.toLong() - totalBytes
                        val bytes = access.open(treeUri, child.documentId).use { input ->
                            readBoundedPackageFile(input, minOf(MAX_SKILL_PACKAGE_FILE_BYTES.toLong(), remaining))
                        }
                        totalBytes += bytes.size
                        if (totalBytes > MAX_SKILL_PACKAGE_TOTAL_BYTES) {
                            throw SkillPackageImportException("SKILL_PACKAGE_TOO_LARGE")
                        }
                        files += SkillPackageFile(
                            relativePath = relativePath,
                            mimeType = normalizedMimeType(child.mimeType),
                            content = bytes,
                            contentSha256 = bytes.sha256(),
                        )
                    }
                }
            }

            visit(root, "", 1)
            val skillFile = files.singleOrNull { it.relativePath == "SKILL.md" }
                ?: throw SkillPackageImportException("SKILL_PACKAGE_DOCUMENT_MISSING")
            if (skillFile.content.isEmpty()) {
                throw SkillPackageImportException("SKILL_DOCUMENT_EMPTY")
            }
            if (skillFile.content.size > MAX_SKILL_DOCUMENT_BYTES) {
                throw SkillPackageImportException("SKILL_DOCUMENT_TOO_LARGE")
            }
            val skillContent = try {
                decodeStrictUtf8(skillFile.content)
            } catch (_: SkillImportException) {
                throw SkillPackageImportException("SKILL_DOCUMENT_UTF8_INVALID")
            }
            if (
                skillContent.isEmpty() ||
                skillContent.length > MAX_SKILL_DOCUMENT_UTF16_UNITS ||
                '\u0000' in skillContent
            ) {
                throw SkillPackageImportException("SKILL_DOCUMENT_INVALID")
            }
            val normalizedFiles = files.sortedBy(SkillPackageFile::relativePath)
            return SkillPackageDocument(
                rootDisplayName = root.displayName,
                skillDocument = SkillImportDocument(
                    content = skillContent,
                    byteCount = skillFile.content.size,
                    documentSha256 = skillFile.contentSha256,
                ),
                files = normalizedFiles,
                packageDigest = skillPackageDigest(normalizedFiles),
            )
        } catch (error: SkillPackageImportException) {
            throw error
        } catch (_: Exception) {
            throw SkillPackageImportException("SKILL_PACKAGE_UNREADABLE")
        }
    }

    private fun requireDirectory(node: SkillPackageTreeNode) {
        if (node.mimeType != Document.MIME_TYPE_DIR) {
            throw SkillPackageImportException("SKILL_PACKAGE_ROOT_NOT_DIRECTORY")
        }
    }

    private fun requireSafeSegment(name: String) {
        if (
            name.isBlank() || name == "." || name == ".." || name.length > 128 ||
            '/' in name || '\\' in name || '\u0000' in name
        ) {
            throw SkillPackageImportException("SKILL_RESOURCE_PATH_INVALID")
        }
    }

    private fun normalizedMimeType(mimeType: String): String =
        mimeType.takeIf { it.isNotBlank() && it.length <= 255 && '\u0000' !in it }
            ?: "application/octet-stream"

    private companion object {
        const val MAX_SKILL_PACKAGE_DEPTH = 12
    }
}

private class ContentResolverSkillPackageTreeAccess(
    private val resolver: ContentResolver,
) : SkillPackageTreeAccess {
    override fun root(treeUri: Uri): SkillPackageTreeNode {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        return querySingle(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
    }

    override fun children(treeUri: Uri, parentDocumentId: String): List<SkillPackageTreeNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = requireNotNull(resolver.query(childrenUri, PROJECTION, null, null, null)) {
            "SKILL_PACKAGE_CHILDREN_UNAVAILABLE"
        }
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) add(rows.node())
            }
        }
    }

    override fun open(treeUri: Uri, documentId: String): InputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return requireNotNull(resolver.openInputStream(uri)) { "SKILL_PACKAGE_STREAM_UNAVAILABLE" }
    }

    private fun querySingle(uri: Uri): SkillPackageTreeNode {
        val cursor = requireNotNull(resolver.query(uri, PROJECTION, null, null, null)) {
            "SKILL_PACKAGE_ROOT_UNAVAILABLE"
        }
        return cursor.use { rows ->
            check(rows.moveToFirst()) { "SKILL_PACKAGE_ROOT_MISSING" }
            rows.node()
        }
    }

    private fun Cursor.node(): SkillPackageTreeNode = SkillPackageTreeNode(
        documentId = requiredString(Document.COLUMN_DOCUMENT_ID),
        displayName = requiredString(Document.COLUMN_DISPLAY_NAME),
        mimeType = requiredString(Document.COLUMN_MIME_TYPE),
        sizeBytes = columnIndex(Document.COLUMN_SIZE).let { index ->
            if (index >= 0 && !isNull(index)) getLong(index) else null
        },
        flags = columnIndex(Document.COLUMN_FLAGS).let { index ->
            if (index >= 0 && !isNull(index)) getInt(index) else 0
        },
    )

    private fun Cursor.requiredString(column: String): String {
        val index = columnIndex(column)
        check(index >= 0 && !isNull(index)) { "SKILL_PACKAGE_METADATA_MISSING" }
        return getString(index)
    }

    private fun Cursor.columnIndex(column: String): Int = getColumnIndex(column)

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
    }
}

private fun readBoundedPackageFile(input: InputStream, maximumBytes: Long): ByteArray {
    if (maximumBytes < 0) throw SkillPackageImportException("SKILL_PACKAGE_TOO_LARGE")
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (output.size().toLong() + read > maximumBytes) {
            throw SkillPackageImportException("SKILL_PACKAGE_FILE_TOO_LARGE")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
