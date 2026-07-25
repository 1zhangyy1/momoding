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
