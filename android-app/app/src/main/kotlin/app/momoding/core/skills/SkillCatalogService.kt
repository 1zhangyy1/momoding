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
    private val parser: PhoneLocalSkillParser,
    private val repository: SkillRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importSkill(uri: Uri): SkillRecord {
        val document = withContext(ioDispatcher) { importReader.read(uri) }
        val parsed = parser.parseSkillDocument(document.content)
        return repository.importSkill(parsed)
    }

    suspend fun seedBundledSkills(): List<SkillRecord> {
        val directories = withContext(ioDispatcher) {
            assets.list(BUNDLED_SKILLS_ASSET_ROOT)
                ?.filter(String::isNotBlank)
                ?.sorted()
                .orEmpty()
        }
        require(directories.size <= MAX_SKILL_RESOURCES) { "SKILL_RESOURCE_LIMIT_EXCEEDED" }
        val parsed = directories.map { directory ->
            val path = "$BUNDLED_SKILLS_ASSET_ROOT/$directory/SKILL.md"
            val content = withContext(ioDispatcher) {
                assets.open(path).use(::readBundledSkillDocument)
            }
            val result = parser.parseSkillDocument(content)
            require(result.resource.name == directory) { "BUNDLED_SKILL_DIRECTORY_MISMATCH" }
            result
        }
        return repository.seedBundledSkills(parsed)
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
