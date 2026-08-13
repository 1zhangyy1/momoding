package app.momoding.core.skills

import android.content.res.AssetManager
import android.net.Uri
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SkillCatalogService(
    private val assets: AssetManager,
    private val importReader: SkillImportReader,
    private val packageImportReader: SkillPackageImportReader? = null,
    private val parser: PhoneLocalSkillParser,
    private val repository: SkillRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importSkill(uri: Uri): SkillRecord {
        val document = withContext(ioDispatcher) { importReader.read(uri) }
        val parsed = parser.parseSkillDocument(document.content)
        return repository.importSkill(parsed)
    }

    suspend fun importSkillPackage(treeUri: Uri): SkillPackageInstallResult {
        val reader = requireNotNull(packageImportReader) { "SKILL_PACKAGE_IMPORT_UNAVAILABLE" }
        val packageDocument = withContext(ioDispatcher) { reader.read(treeUri) }
        val parsed = parser.parseSkillDocument(packageDocument.skillDocument.content)
        val normalized = normalizePackageParseResult(parsed, packageDocument.files)
        val resource = normalized.resource.copy(
            packageDigest = packageDocument.packageDigest,
            packageFileCount = packageDocument.files.size,
        )
        val record = repository.importSkill(normalized.copy(resource = resource), packageDocument.files)
        return SkillPackageInstallResult(record, packageDocument.files.size)
    }

    suspend fun seedBundledSkills(): List<SkillRecord> {
        val directories = withContext(ioDispatcher) {
            assets.list(BUNDLED_SKILLS_ASSET_ROOT)
                ?.filter(String::isNotBlank)
                ?.sorted()
                .orEmpty()
        }
        require(directories.size <= MAX_SKILL_RESOURCES) { "SKILL_RESOURCE_LIMIT_EXCEEDED" }
        val packages = directories.map { directory ->
            withContext(ioDispatcher) { readBundledPackage(directory) }
        }
        val parsed = packages.map { (directory, files) ->
            val skillFile = files.single { it.relativePath == "SKILL.md" }
            val content = readBundledSkillDocument(skillFile.content.inputStream())
            val parsedDocument = normalizePackageParseResult(parser.parseSkillDocument(content), files)
            val result = parsedDocument.copy(
                resource = parsedDocument.resource.copy(
                    packageDigest = skillPackageDigest(files),
                    packageFileCount = files.size,
                ),
            )
            require(result.resource.name == directory) { "BUNDLED_SKILL_DIRECTORY_MISMATCH" }
            result
        }
        return repository.seedBundledSkills(
            parsed,
            packages.associate { (directory, files) -> directory to files },
        )
    }

    private fun readBundledPackage(directory: String): Pair<String, List<SkillPackageFile>> {
        requireValidSkillResourcePath(directory)
        val files = mutableListOf<SkillPackageFile>()
        var totalBytes = 0L
        fun visit(prefix: String, depth: Int) {
            require(depth <= 12) { "BUNDLED_SKILL_DEPTH_EXCEEDED" }
            val assetPath = "$BUNDLED_SKILLS_ASSET_ROOT/$directory" +
                if (prefix.isEmpty()) "" else "/$prefix"
            val children = assets.list(assetPath)?.filter(String::isNotBlank)?.sorted().orEmpty()
            children.forEach { child ->
                val relativePath = if (prefix.isEmpty()) child else "$prefix/$child"
                requireValidSkillResourcePath(relativePath)
                val childPath = "$BUNDLED_SKILLS_ASSET_ROOT/$directory/$relativePath"
                val descendants = assets.list(childPath)?.filter(String::isNotBlank).orEmpty()
                if (descendants.isNotEmpty()) {
                    visit(relativePath, depth + 1)
                } else {
                    require(files.size < MAX_SKILL_PACKAGE_FILES) { "SKILL_PACKAGE_FILE_LIMIT_EXCEEDED" }
                    val remaining = MAX_SKILL_PACKAGE_TOTAL_BYTES.toLong() - totalBytes
                    val bytes = assets.open(childPath).use { input ->
                        readBoundedBundledPackageFile(input, remaining)
                    }
                    totalBytes += bytes.size
                    files += SkillPackageFile(
                        relativePath = relativePath,
                        mimeType = bundledMimeType(relativePath),
                        content = bytes,
                        contentSha256 = bytes.sha256(),
                    )
                }
            }
        }
        visit("", 1)
        requireValidSkillPackageFiles(files)
        return directory to files.sortedBy(SkillPackageFile::relativePath)
    }

    private fun normalizePackageParseResult(
        parsed: SkillDocumentParseResult,
        files: List<SkillPackageFile>,
    ): SkillDocumentParseResult {
        if (
            parsed.availability != SkillAvailability.UNAVAILABLE ||
            parsed.diagnosticCode != "RELATIVE_DEPENDENCY_UNSUPPORTED"
        ) return parsed
        val missing = missingRelativeReferences(
            parsed.resource.content,
            files.map(SkillPackageFile::relativePath).toSet(),
        )
        return if (missing.isEmpty()) {
            parsed.copy(
                availability = SkillAvailability.AVAILABLE,
                diagnosticCode = null,
                diagnosticMessage = null,
            )
        } else {
            parsed.copy(
                diagnosticCode = "PACKAGE_REFERENCE_MISSING",
                diagnosticMessage = "The Skill package is missing a referenced local resource.",
            )
        }
    }

    private fun readBundledSkillDocument(input: InputStream): String {
        val bytes = readBoundedSkillDocument(input)
        require(bytes.isNotEmpty()) { "BUNDLED_SKILL_EMPTY" }
        return decodeStrictUtf8(bytes).also { content ->
            require(content.length <= MAX_SKILL_DOCUMENT_UTF16_UNITS && '\u0000' !in content) {
                "BUNDLED_SKILL_INVALID"
            }
        }
    }

    companion object {
        private const val BUNDLED_SKILLS_ASSET_ROOT = "skills"
    }
}

data class SkillPackageInstallResult(
    val record: SkillRecord,
    val fileCount: Int,
)

private fun readBoundedBundledPackageFile(input: InputStream, maximumBytes: Long): ByteArray {
    require(maximumBytes >= 0) { "SKILL_PACKAGE_TOO_LARGE" }
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(output.size().toLong() + read <= maximumBytes) { "SKILL_PACKAGE_TOO_LARGE" }
        require(output.size() + read <= MAX_SKILL_PACKAGE_FILE_BYTES) { "SKILL_PACKAGE_FILE_TOO_LARGE" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun bundledMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "txt", "log" -> "text/plain"
    "json" -> "application/json"
    "yaml", "yml" -> "application/yaml"
    "js", "mjs", "cjs" -> "text/javascript"
    "ts" -> "text/typescript"
    "py" -> "text/x-python"
    "sh" -> "text/x-shellscript"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "xml" -> "application/xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}

private fun missingRelativeReferences(content: String, packagePaths: Set<String>): Set<String> {
    val targets = buildList {
        Regex("!?\\[[^]]*]\\(\\s*([^\\s)]+)(?:\\s+[^)]*)?\\)")
            .findAll(content).forEach { add(it.groupValues[1]) }
        Regex("(?m)^\\s*\\[[^]]+]:\\s*(\\S+)")
            .findAll(content).forEach { add(it.groupValues[1]) }
        Regex("(?i)\\b(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']")
            .findAll(content).forEach { add(it.groupValues[1]) }
    }
    return targets.mapNotNull { raw ->
        val target = raw.trim().removePrefix("<").removeSuffix(">")
        if (
            target.isEmpty() || target.startsWith('/') || target.startsWith('#') ||
            target.startsWith("//") || Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(target)
        ) return@mapNotNull null
        val path = target.substringBefore('#').substringBefore('?')
        runCatching { requireValidSkillResourcePath(path) }.getOrElse { return@mapNotNull path }
        path.takeUnless(packagePaths::contains)
    }.toSet()
}
