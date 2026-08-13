package app.momoding.core.skills

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.SkillEntity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkillRepositoryTest {
    private lateinit var database: MomodingDatabase
    private lateinit var repository: SkillRepository
    private val clock = AtomicLong(100)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .build()
        repository = SkillRepository(database, nowMillis = { clock.getAndIncrement() })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `import is disabled by default unique by name and stores no uri`() = runTest {
        val imported = repository.importSkill(parsed("review", "Review safely", "Do the review."))

        assertEquals(SkillSource.IMPORTED, imported.source)
        assertFalse(imported.enabled)
        assertEquals("skill:imported:review", imported.skillId)
        assertFalse(withContext(Dispatchers.IO) {
            database.skillDao().all().single().toString().contains("content://")
        })
        assertTrue(
            runCatching {
                repository.importSkill(parsed("review", "Another description", "Other body"))
            }.exceptionOrNull()?.message == "SKILL_NAME_DUPLICATED",
        )
    }

    @Test
    fun `enable produces deterministic resource digest and disable removes the resource`() = runTest {
        repository.importSkill(parsed("review", "Review safely", "Do the review."))
        repository.setEnabled("review", true)

        val enabled = repository.enabledResourceSet()

        assertEquals(listOf("review"), enabled.resources.map(PhoneLocalSkillResource::name))
        assertEquals("ba9d145db8f3640bf10c828982147cdb2d4f4908986aef452a5bdab484339f01", enabled.digest)
        repository.setEnabled("review", false)
        assertTrue(repository.enabledResourceSet().resources.isEmpty())
    }

    @Test
    fun `invocation context returns requested state and one atomic enabled resource set`() = runTest {
        repository.importSkill(parsed("disabled", "Disabled Skill", "Do disabled work."))
        repository.importSkill(parsed("enabled", "Enabled Skill", "Do enabled work."))
        repository.setEnabled("enabled", true)

        val disabled = repository.invocationContext("disabled")
        val missing = repository.invocationContext("missing")

        assertEquals("disabled", disabled.requested?.resource?.name)
        assertFalse(requireNotNull(disabled.requested).enabled)
        assertEquals(listOf("enabled"), disabled.enabledResourceSet.resources.map { it.name })
        assertEquals(disabled.enabledResourceSet, missing.enabledResourceSet)
        assertEquals(null, missing.requested)
    }

    @Test
    fun `bundled reseed updates content but preserves disabled choice`() = runTest {
        repository.seedBundledSkills(listOf(parsed("review", "First", "Version one")))
        assertTrue(repository.skills().single().enabled)
        repository.setEnabled("review", false)

        val reseeded = repository.seedBundledSkills(
            listOf(parsed("review", "Second", "Version two")),
        ).single()

        assertFalse(reseeded.enabled)
        assertEquals("Second", reseeded.resource.description)
        assertEquals("Version two", reseeded.resource.content)
        assertEquals(100L, reseeded.createdAtMillis)
        assertEquals(102L, reseeded.updatedAtMillis)
    }

    @Test
    fun `unavailable and corrupt records fail closed before resources`() = runTest {
        val unavailable = parsed(
            name = "relative",
            description = "Needs a relative file",
            content = "Read [details](notes.md)",
            availability = SkillAvailability.UNAVAILABLE,
            diagnosticCode = "RELATIVE_DEPENDENCY_UNSUPPORTED",
            diagnosticMessage = "Single-file import cannot use local relative references.",
        )
        repository.importSkill(unavailable)
        assertTrue(
            runCatching { repository.setEnabled("relative", true) }
                .exceptionOrNull()?.message == "SKILL_UNAVAILABLE",
        )

        val valid = parsed("valid", "Valid record", "Valid body")
        withContext(Dispatchers.IO) {
            database.skillDao().insert(
                SkillEntity(
                    skillId = "skill:imported:valid",
                    source = SkillSource.IMPORTED.name,
                    name = valid.resource.name,
                    description = valid.resource.description,
                    content = valid.resource.content,
                    contentSha256 = "0".repeat(64),
                    disableModelInvocation = false,
                    enabled = true,
                    availability = SkillAvailability.AVAILABLE.name,
                    diagnosticCode = null,
                    diagnosticMessage = null,
                    createdAtMillis = 1,
                    updatedAtMillis = 1,
                ),
            )
        }
        assertTrue(
            runCatching { repository.enabledResourceSet() }
                .exceptionOrNull()?.message == "SKILL_RECORD_CORRUPT",
        )
    }

    @Test
    fun `enabled choice survives database reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "skills-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        fun open(): MomodingDatabase = Room.databaseBuilder(context, MomodingDatabase::class.java, databaseName)
            .build()
        try {
            val first = open()
            try {
                val firstRepository = SkillRepository(first, nowMillis = { 1L })
                firstRepository.importSkill(parsed("durable", "Durable Skill", "Persist me"))
                firstRepository.setEnabled("durable", true)
            } finally {
                first.close()
            }
            val reopened = open()
            try {
                val record = SkillRepository(reopened).skills().single()
                assertTrue(record.enabled)
                assertEquals("durable", record.resource.name)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `complete package is atomic listable and readable only while enabled`() = runTest {
        val body = "Use [the guide](references/guide.md)."
        val rawSkill = """
            ---
            name: packaged
            description: Uses local package resources
            ---
            $body
        """.trimIndent()
        val files = listOf(
            packageFile("SKILL.md", "text/markdown", rawSkill.toByteArray()),
            packageFile("references/guide.md", "text/markdown", "Return PASS.".toByteArray()),
            packageFile("scripts/check.py", "text/x-python", "print('ok')".toByteArray()),
        )
        val parsed = parsed("packaged", "Uses local package resources", body).let { result ->
            result.copy(
                resource = result.resource.copy(
                    packageDigest = skillPackageDigest(files),
                    packageFileCount = files.size,
                ),
                sourceDocumentSha256 = rawSkill.sha256Utf8(),
            )
        }

        repository.importSkill(parsed, files)
        assertReadCode("SKILL_NOT_ENABLED") {
            repository.readEnabledTextResource("packaged", "references/guide.md", 0, 256)
        }
        repository.setEnabled("packaged", true)

        val enabled = repository.enabledResourceSet().resources.single()
        assertEquals(3, enabled.packageFileCount)
        assertEquals(skillPackageDigest(files), enabled.packageDigest)
        assertEquals(
            listOf("references/guide.md"),
            repository.listEnabledResources("packaged", "references", 0, 64)
                .items.map(SkillResourceEntry::path),
        )
        val page = repository.readEnabledTextResource("packaged", "scripts/check.py", 0, 256)
        assertEquals("print('ok')", page.content)
        assertTrue(page.eof)

        assertTrue(repository.deleteImported("packaged"))
        assertTrue(withContext(Dispatchers.IO) {
            database.skillPackageFileDao().metadataForSkill("skill:imported:packaged").isEmpty()
        })
    }

    @Test
    fun `package rejects mismatched metadata and binary resources fail closed`() = runTest {
        val files = listOf(
            packageFile("SKILL.md", "text/markdown", "Body".toByteArray()),
            packageFile("assets/data.bin", "application/octet-stream", byteArrayOf(0xC3.toByte(), 0x28)),
        )
        val invalid = parsed("binary", "Binary package", "Body")
        assertTrue(
            runCatching { repository.importSkill(invalid, files) }
                .exceptionOrNull()?.message == "SKILL_PACKAGE_DIGEST_MISMATCH",
        )
        val mismatchedDocument = files.map { file ->
            if (file.relativePath == "SKILL.md") {
                packageFile("SKILL.md", "text/markdown", "Other body".toByteArray())
            } else {
                file
            }
        }
        val mismatchedParsed = invalid.copy(
            resource = invalid.resource.copy(
                packageDigest = skillPackageDigest(mismatchedDocument),
                packageFileCount = mismatchedDocument.size,
            ),
            sourceDocumentSha256 = "Body".sha256Utf8(),
        )
        assertEquals(
            "SKILL_PACKAGE_DOCUMENT_MISMATCH",
            runCatching { repository.importSkill(mismatchedParsed, mismatchedDocument) }
                .exceptionOrNull()?.message,
        )
        val valid = invalid.copy(
            resource = invalid.resource.copy(
                packageDigest = skillPackageDigest(files),
                packageFileCount = files.size,
            ),
            sourceDocumentSha256 = "Body".sha256Utf8(),
        )
        repository.importSkill(valid, files)
        repository.setEnabled("binary", true)

        assertReadCode("SKILL_RESOURCE_BINARY") {
            repository.readEnabledTextResource("binary", "assets/data.bin", 0, 256)
        }
    }

    private suspend fun assertReadCode(expected: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is SkillResourceReadException)
        assertEquals(expected, (error as SkillResourceReadException).code)
    }

    private fun packageFile(path: String, mimeType: String, content: ByteArray) = SkillPackageFile(
        relativePath = path,
        mimeType = mimeType,
        content = content,
        contentSha256 = content.sha256(),
    )

    private fun parsed(
        name: String,
        description: String,
        content: String,
        availability: SkillAvailability = SkillAvailability.AVAILABLE,
        diagnosticCode: String? = null,
        diagnosticMessage: String? = null,
    ): SkillDocumentParseResult = SkillDocumentParseResult(
        resource = PhoneLocalSkillResource(
            name = name,
            description = description,
            content = content,
            contentSha256 = content.sha256Utf8(),
            disableModelInvocation = false,
        ),
        availability = availability,
        diagnosticCode = diagnosticCode,
        diagnosticMessage = diagnosticMessage,
    )
}
