package app.momoding.core.extensions

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class ExtensionPackageTreeNode(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val flags: Int,
)

internal interface ExtensionPackageTreeAccess {
    fun root(treeUri: Uri): ExtensionPackageTreeNode
    fun children(
        treeUri: Uri,
        parentDocumentId: String,
        maximumChildren: Int,
    ): List<ExtensionPackageTreeNode>
    fun open(treeUri: Uri, documentId: String): InputStream
}

/** One-shot bounded copy. The caller must not persist the selected SAF grant or URI. */
class ExtensionPackageImportReader internal constructor(
    private val access: ExtensionPackageTreeAccess,
) {
    constructor(contentResolver: ContentResolver) : this(
        ContentResolverExtensionPackageTreeAccess(contentResolver),
    )

    fun read(treeUri: Uri): ExtensionPackageDocument = when (val result = readForImport(treeUri)) {
        is ExtensionPackageReadResult.Artifact -> result.document
        is ExtensionPackageReadResult.Diagnostic -> throw ExtensionPackageException(result.diagnostic.code)
    }

    fun readForImport(treeUri: Uri): ExtensionPackageReadResult {
        if (
            treeUri.scheme != ContentResolver.SCHEME_CONTENT || treeUri.authority.isNullOrBlank() ||
            !DocumentsContract.isTreeUri(treeUri)
        ) throw ExtensionPackageException("EXTENSION_PACKAGE_TREE_URI_INVALID")
        try {
            val root = access.root(treeUri)
            if (root.mimeType != Document.MIME_TYPE_DIR) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_ROOT_NOT_DIRECTORY")
            }
            requireSafeSegment(root.displayName)
            val visited = mutableSetOf(root.documentId)
            var nodeCount = 1
            val files = mutableListOf<ExtensionPackageFile>()
            var totalBytes = 0L

            fun visit(parent: ExtensionPackageTreeNode, prefix: String, depth: Int) {
                if (depth > 12) throw ExtensionPackageException("EXTENSION_PACKAGE_DEPTH_EXCEEDED")
                val names = mutableSetOf<String>()
                val remainingNodes = MAX_EXTENSION_PACKAGE_NODES - nodeCount
                val children = access.children(treeUri, parent.documentId, remainingNodes)
                if (children.size > remainingNodes) {
                    throw ExtensionPackageException("EXTENSION_PACKAGE_NODE_LIMIT_EXCEEDED")
                }
                nodeCount += children.size
                children
                    .sortedBy(ExtensionPackageTreeNode::displayName)
                    .forEach { child ->
                        if (!visited.add(child.documentId)) {
                            throw ExtensionPackageException("EXTENSION_PACKAGE_CYCLE_DETECTED")
                        }
                        requireSafeSegment(child.displayName)
                        if (!names.add(child.displayName)) {
                            throw ExtensionPackageException("EXTENSION_PACKAGE_PATH_DUPLICATED")
                        }
                        val path = if (prefix.isEmpty()) child.displayName else "$prefix/${child.displayName}"
                        requireValidExtensionPackagePath(path)
                        if (child.mimeType == Document.MIME_TYPE_DIR) {
                            visit(child, path, depth + 1)
                        } else {
                            if ((child.flags and Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
                                throw ExtensionPackageException("EXTENSION_PACKAGE_VIRTUAL_FILE_UNSUPPORTED")
                            }
                            if (files.size >= MAX_EXTENSION_PACKAGE_FILES) {
                                throw ExtensionPackageException("EXTENSION_PACKAGE_FILE_LIMIT_EXCEEDED")
                            }
                            if (child.sizeBytes != null && child.sizeBytes !in 0..MAX_EXTENSION_PACKAGE_FILE_BYTES.toLong()) {
                                throw ExtensionPackageException("EXTENSION_PACKAGE_FILE_TOO_LARGE")
                            }
                            val remaining = MAX_EXTENSION_PACKAGE_TOTAL_BYTES.toLong() - totalBytes
                            val bytes = access.open(treeUri, child.documentId).use { input ->
                                readBounded(input, minOf(MAX_EXTENSION_PACKAGE_FILE_BYTES.toLong(), remaining))
                            }
                            totalBytes += bytes.size
                            if (totalBytes > MAX_EXTENSION_PACKAGE_TOTAL_BYTES) {
                                throw ExtensionPackageException("EXTENSION_PACKAGE_TOO_LARGE")
                            }
                            files += ExtensionPackageFile(
                                relativePath = path,
                                mimeType = normalizeMime(child.mimeType),
                                content = bytes,
                                contentSha256 = bytes.sha256(),
                            )
                        }
                    }
            }

            visit(root, "", 1)
            val manifest = files.singleOrNull { it.relativePath == EXTENSION_MANIFEST_PATH }
                ?: return ExtensionPackageReadResult.Diagnostic(diagnoseRawPackage(files))
            if (manifest.content.isEmpty() || manifest.content.size > MAX_EXTENSION_MANIFEST_BYTES) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_MANIFEST_TOO_LARGE")
            }
            val manifestContent = decodeStrictUtf8(manifest.content)
            if (manifestContent.isBlank() || '\u0000' in manifestContent) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_MANIFEST_INVALID")
            }
            return ExtensionPackageReadResult.Artifact(
                ExtensionPackageDocument(
                    rootDisplayName = root.displayName,
                    manifestContent = manifestContent,
                    files = files.sortedBy(ExtensionPackageFile::relativePath),
                ),
            )
        } catch (error: ExtensionPackageException) {
            throw error
        } catch (_: Exception) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_UNREADABLE")
        }
    }

    private fun requireSafeSegment(value: String) {
        if (
            value.isBlank() || value == "." || value == ".." || value.length > 128 ||
            '/' in value || '\\' in value || '\u0000' in value
        ) throw ExtensionPackageException("EXTENSION_PACKAGE_PATH_INVALID")
    }

    private fun normalizeMime(value: String): String =
        value.takeIf { it.isNotBlank() && it.length <= 255 && '\u0000' !in it }
            ?: "application/octet-stream"
}

private fun diagnoseRawPackage(
    files: List<ExtensionPackageFile>,
): ExtensionPackageCompatibilityDiagnostic {
    val packageFile = files.singleOrNull { it.relativePath == "package.json" }
        ?: return rejectedDiagnostic(
            "EXTENSION_PACKAGE_MANIFEST_MISSING",
            "This folder contains neither a Momoding artifact nor a Pi package.json.",
        )
    val packageRoot = try {
        Json.parseToJsonElement(decodeStrictUtf8(packageFile.content)) as? JsonObject
            ?: return rejectedDiagnostic(
                "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
                "package.json must be a JSON object.",
            )
    } catch (_: Exception) {
        return rejectedDiagnostic(
            "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
            "package.json is not strict UTF-8 JSON.",
        )
    }
    val packageName = (packageRoot["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.takeIf { it.length <= 214 && '\u0000' !in it }
    val pi = packageRoot["pi"] as? JsonObject
    val extensions = pi?.get("extensions") as? JsonArray
    if (extensions == null || extensions.isEmpty()) {
        return rejectedDiagnostic(
            code = "EXTENSION_PACKAGE_MANIFEST_MISSING",
            detail = "package.json does not declare a Pi Extension entrypoint.",
            packageName = packageName,
        )
    }
    val sourceFiles = files.filter { file ->
        RAW_SOURCE_EXTENSIONS.any(file.relativePath::endsWith)
    }
    val sourceText = sourceFiles.asSequence()
        .mapNotNull { file ->
            runCatching { stripJavaScriptComments(decodeStrictUtf8(file.content)) }.getOrNull()
        }
        .joinToString("\n")
    val sourceCodeTokens = maskJavaScriptStrings(sourceText)
    if (UNSUPPORTED_NODE_PATTERNS.any { it.containsMatchIn(sourceCodeTokens) }) {
        return ExtensionPackageCompatibilityDiagnostic(
            compatibility = ExtensionPackageCompatibility.UNSUPPORTED,
            code = "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED",
            packageName = packageName,
            detail = "This Pi Extension depends on Node, process, filesystem, native, or terminal APIs that are not available in the Android Worker.",
            nextAction = "Use a mobile-safe Extension or replace the desktop dependency with a declared Android Host Tool.",
        )
    }
    if (HOST_SHIM_PATTERNS.any { it.containsMatchIn(sourceCodeTokens) }) {
        return ExtensionPackageCompatibilityDiagnostic(
            compatibility = ExtensionPackageCompatibility.HOST_SHIM_REQUIRED,
            code = "EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED",
            packageName = packageName,
            detail = "This package uses a Pi lifecycle or UI API outside the mobile registerTool profile.",
            nextAction = "Define one bounded Android-owned Host API before this package can be built.",
        )
    }
    val mobileFile = files.singleOrNull { it.relativePath == "momoding-mobile.json" }
        ?: return ExtensionPackageCompatibilityDiagnostic(
            compatibility = ExtensionPackageCompatibility.HOST_SHIM_REQUIRED,
            code = "EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED",
            packageName = packageName,
            detail = "This is Pi source, but it has no Momoding mobile profile declaring Tools and Host access.",
            nextAction = "Add momoding-mobile.json and run the deterministic Momoding packer on a trusted development machine.",
        )
    val mobileRoot = runCatching {
        Json.parseToJsonElement(decodeStrictUtf8(mobileFile.content)) as? JsonObject
    }.getOrNull()
    val entrypoint = mobileRoot?.let { requireRawMobileProfile(packageRoot, it, files) }
    if (entrypoint == null) {
        return rejectedDiagnostic(
            code = "EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID",
            detail = "momoding-mobile.json is malformed or uses an unsupported profile version.",
            packageName = packageName,
        )
    }
    val entrySource = files.single { it.relativePath == entrypoint }
    val entryText = runCatching { decodeStrictUtf8(entrySource.content) }.getOrNull()
        ?: return rejectedDiagnostic(
            code = "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
            detail = "The declared mobile entrypoint is not strict UTF-8 source.",
            packageName = packageName,
        )
    val entryCodeTokens = maskJavaScriptStrings(stripJavaScriptComments(entryText))
    if (!RAW_DEFAULT_FACTORY.containsMatchIn(entryCodeTokens) ||
        !RAW_REGISTER_TOOL.containsMatchIn(entryCodeTokens)
    ) {
        return rejectedDiagnostic(
            code = "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
            detail = "The declared entrypoint is not a static Pi registerTool factory candidate.",
            packageName = packageName,
        )
    }
    if (RAW_DYNAMIC_IMPORT.containsMatchIn(sourceCodeTokens)) {
        return ExtensionPackageCompatibilityDiagnostic(
            compatibility = ExtensionPackageCompatibility.UNSUPPORTED,
            code = "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED",
            packageName = packageName,
            detail = "This source declares a dynamic or runtime dependency outside the bounded mobile profile.",
            nextAction = "Remove the dependency or replace it with @momoding/sdk and declared Android Host access.",
        )
    }
    return ExtensionPackageCompatibilityDiagnostic(
        compatibility = ExtensionPackageCompatibility.BUILD_REQUIRED,
        code = "EXTENSION_PACKAGE_MOBILE_BUILD_REQUIRED",
        packageName = packageName,
        detail = "This is an unverified mobile-profile candidate. Android did not execute or certify the source; only the trusted repository packer can decide compatibility.",
        nextAction = "Run the deterministic repository packer, review its diagnosis, then import only the generated artifact folder.",
    )
}

private fun requireRawMobileProfile(
    packageRoot: JsonObject,
    mobile: JsonObject,
    files: List<ExtensionPackageFile>,
): String? {
    if (mobile.keys != RAW_PROFILE_KEYS) return null
    val profileVersion = mobile["profileVersion"] as? JsonPrimitive ?: return null
    if (profileVersion.isString || profileVersion.content != "1") return null
    val packageValue = mobile["package"] as? JsonObject ?: return null
    if (packageValue.keys != RAW_PACKAGE_KEYS) return null
    val id = packageValue.rawString("id", 3, 76) ?: return null
    if (!RAW_PACKAGE_ID.matches(id)) return null
    packageValue.rawString("name", 1, 80) ?: return null
    val version = packageValue.rawString("version", 1, 40) ?: return null
    val packageVersion = packageRoot.rawString("version", 1, 40) ?: return null
    if (version != packageVersion || !RAW_VERSION.matches(version)) return null
    packageValue.rawString("description", 1, 512) ?: return null

    val source = mobile["source"] as? JsonObject ?: return null
    if (source.keys != setOf("entrypoint")) return null
    val declared = source.rawString("entrypoint", 1, 256) ?: return null
    val normalized = declared.removePrefix("./")
    if (normalized.isBlank() || normalized.startsWith('/') || "\\" in normalized ||
        normalized.split('/').any { it.isBlank() || it == "." || it == ".." } ||
        RAW_SOURCE_EXTENSIONS.none(normalized::endsWith)
    ) return null
    val piExtensions = ((packageRoot["pi"] as? JsonObject)?.get("extensions") as? JsonArray)
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
        ?: return null
    if (piExtensions != listOf(declared) || files.count { it.relativePath == normalized } != 1) return null

    val tools = mobile["tools"] as? JsonArray ?: return null
    if (tools.size !in 1..16) return null
    val toolNames = tools.map { element ->
        val tool = element as? JsonObject ?: return null
        if (tool.keys != RAW_TOOL_KEYS) return null
        val name = tool.rawString("name", 1, 64) ?: return null
        if (!RAW_TOOL_NAME.matches(name)) return null
        tool.rawString("label", 1, 80) ?: return null
        tool.rawString("description", 1, 512) ?: return null
        if (tool["parameters"] !is JsonObject || tool["executionMode"]?.rawString() != "sequential") return null
        val snippet = tool["promptSnippet"]
        if (snippet != kotlinx.serialization.json.JsonNull && snippet?.rawString(1, 512) == null) return null
        val guidelines = tool["promptGuidelines"] as? JsonArray ?: return null
        if (guidelines.size > 16 || guidelines.any { it.rawString(1, 512) == null }) return null
        name
    }
    if (toolNames.distinct().size != toolNames.size) return null
    if (mobile["hostTools"] !is JsonArray || mobile["capabilities"] !is JsonObject ||
        mobile["https"] !is JsonObject || mobile["credentials"] !is JsonArray
    ) return null
    return normalized
}

private fun JsonObject.rawString(key: String, minimum: Int, maximum: Int): String? =
    this[key]?.rawString(minimum, maximum)

private fun kotlinx.serialization.json.JsonElement.rawString(
    minimum: Int = 1,
    maximum: Int = Int.MAX_VALUE,
): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    ?.takeIf { it.length in minimum..maximum && '\u0000' !in it }

private fun stripJavaScriptComments(source: String): String = buildString(source.length) {
    var index = 0
    var quote: Char? = null
    var escaped = false
    while (index < source.length) {
        val current = source[index]
        val next = source.getOrNull(index + 1)
        if (quote != null) {
            append(current)
            if (escaped) escaped = false
            else if (current == '\\') escaped = true
            else if (current == quote) quote = null
            index += 1
        } else if (current == '/' && next == '/') {
            while (index < source.length && source[index] !in "\r\n") index += 1
            append('\n')
        } else if (current == '/' && next == '*') {
            index += 2
            while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index += 1
            index = minOf(index + 2, source.length)
            append(' ')
        } else {
            if (current == '\'' || current == '"' || current == '`') quote = current
            append(current)
            index += 1
        }
    }
}

private fun maskJavaScriptStrings(source: String): String = buildString(source.length) {
    var quote: Char? = null
    var escaped = false
    source.forEach { current ->
        if (quote == null) {
            if (current == '\'' || current == '"' || current == '`') {
                quote = current
                append(' ')
            } else {
                append(current)
            }
        } else {
            append(if (current == '\n' || current == '\r') current else ' ')
            if (escaped) escaped = false
            else if (current == '\\') escaped = true
            else if (current == quote) quote = null
        }
    }
}

private fun rejectedDiagnostic(
    code: String,
    detail: String,
    packageName: String? = null,
) = ExtensionPackageCompatibilityDiagnostic(
    compatibility = ExtensionPackageCompatibility.REJECTED,
    code = code,
    packageName = packageName,
    detail = detail,
    nextAction = "Choose a valid Momoding artifact or inspect the package on a development machine.",
)

private val UNSUPPORTED_NODE_PATTERNS = listOf(
    Regex("""\bprocess\s*\."""),
    Regex("""\b(?:require|WebSocket|EventSource|XMLHttpRequest)\s*\("""),
    Regex("""\bBuffer\s*\."""),
    Regex("""\b(?:registerCommand|registerShortcut|registerFlag)\s*\("""),
    Regex("""\bctx\s*\.\s*ui\s*\."""),
)

private val HOST_SHIM_PATTERNS = listOf(
    Regex("""\bpi\s*\.\s*on\s*\("""),
    Regex("""\b(?:registerProvider|registerMessageRenderer|registerContextProvider)\s*\("""),
)

private val RAW_SOURCE_EXTENSIONS = setOf(".ts", ".tsx", ".js", ".mjs")
private val RAW_PROFILE_KEYS = setOf(
    "profileVersion", "package", "source", "tools", "hostTools", "capabilities", "https",
    "credentials",
)
private val RAW_PACKAGE_KEYS = setOf("id", "name", "version", "description")
private val RAW_TOOL_KEYS = setOf(
    "name", "label", "description", "parameters", "promptSnippet", "promptGuidelines",
    "executionMode",
)
private val RAW_PACKAGE_ID = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$")
private val RAW_TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
private val RAW_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
private val RAW_DEFAULT_FACTORY = Regex(
    """\bexport\s+default\s+(?:(?:async\s+)?function\b|(?:async\s+)?(?:\([^)]*\)|[A-Za-z_$][A-Za-z0-9_$]*)\s*=>)""",
)
private val RAW_REGISTER_TOOL = Regex("""\.\s*registerTool\s*\(""")
private val RAW_DYNAMIC_IMPORT = Regex("""\bimport\s*\(""")

private class ContentResolverExtensionPackageTreeAccess(
    private val resolver: ContentResolver,
) : ExtensionPackageTreeAccess {
    override fun root(treeUri: Uri): ExtensionPackageTreeNode {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        return querySingle(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
    }

    override fun children(
        treeUri: Uri,
        parentDocumentId: String,
        maximumChildren: Int,
    ): List<ExtensionPackageTreeNode> {
        require(maximumChildren >= 0)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        return requireNotNull(resolver.query(uri, PROJECTION, null, null, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size >= maximumChildren) {
                        throw ExtensionPackageException("EXTENSION_PACKAGE_NODE_LIMIT_EXCEEDED")
                    }
                    add(cursor.node())
                }
            }
        }
    }

    override fun open(treeUri: Uri, documentId: String): InputStream = requireNotNull(
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)),
    )

    private fun querySingle(uri: Uri): ExtensionPackageTreeNode =
        requireNotNull(resolver.query(uri, PROJECTION, null, null, null)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.node()
        }

    private fun Cursor.node() = ExtensionPackageTreeNode(
        documentId = requiredString(Document.COLUMN_DOCUMENT_ID),
        displayName = requiredString(Document.COLUMN_DISPLAY_NAME),
        mimeType = requiredString(Document.COLUMN_MIME_TYPE),
        sizeBytes = getColumnIndex(Document.COLUMN_SIZE).let { index ->
            if (index >= 0 && !isNull(index)) getLong(index) else null
        },
        flags = getColumnIndex(Document.COLUMN_FLAGS).let { index ->
            if (index >= 0 && !isNull(index)) getInt(index) else 0
        },
    )

    private fun Cursor.requiredString(column: String): String {
        val index = getColumnIndex(column)
        check(index >= 0 && !isNull(index))
        return getString(index)
    }

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

private fun readBounded(input: InputStream, maximumBytes: Long): ByteArray {
    if (maximumBytes < 0) throw ExtensionPackageException("EXTENSION_PACKAGE_TOO_LARGE")
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (output.size().toLong() + read > maximumBytes) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_FILE_TOO_LARGE")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeStrictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    throw ExtensionPackageException("EXTENSION_PACKAGE_MANIFEST_UTF8_INVALID")
}
