package app.momoding.core.runtime.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.skills.PhoneLocalSkillResource
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillDocumentParseResult
import app.momoding.core.skills.SkillPackageFile
import app.momoding.core.skills.SkillRepository
import app.momoding.core.skills.sha256
import app.momoding.core.skills.sha256Utf8
import app.momoding.core.skills.skillPackageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
class PhoneLocalSkillResourceToolExecutorTest {
    private lateinit var database: MomodingDatabase
    private lateinit var repository: SkillRepository
    private lateinit var executor: PhoneLocalSkillResourceToolExecutor

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).build()
        repository = SkillRepository(database, nowMillis = { 1L })
        executor = PhoneLocalSkillResourceToolExecutor(repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `lists and reads only an enabled installed Skill resource`() = runTest {
        install("review", "Return PASS.")
        repository.setEnabled("review", true)

        val listed = executor.execute(
            "task-1",
            request(buildJsonObject {
                put("action", "list")
                put("skillName", "review")
                put("offset", 0)
                put("limit", 64)
            }),
        )
        assertTrue(listed["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SKILL.md", listed["items"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)

        val read = executor.execute(
            "task-1",
            request(buildJsonObject {
                put("action", "read")
                put("skillName", "review")
                put("path", "SKILL.md")
                put("offset", 0)
                put("limit", 256)
            }),
        )
        assertEquals("Return PASS.", read["content"]!!.jsonPrimitive.content)
        assertTrue(read["eof"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `disabled or malformed requests fail with stable redacted errors`() = runTest {
        install("review", "Return PASS.")

        val disabled = executor.execute(
            "task-1",
            request(buildJsonObject {
                put("action", "list")
                put("skillName", "review")
                put("offset", 0)
                put("limit", 64)
            }),
        )
        assertFalse(disabled["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SKILL_NOT_ENABLED", disabled["errorCode"]!!.jsonPrimitive.content)

        val malformed = executor.execute(
            "task-1",
            request(buildJsonObject {
                put("action", "list")
                put("skillName", "review")
                put("offset", 0)
                put("limit", 64)
                put("unexpected", "private")
            }),
        )
        assertEquals("SKILL_RESOURCE_ARGUMENTS_INVALID", malformed["errorCode"]!!.jsonPrimitive.content)
        assertFalse(malformed.toString().contains("private"))
    }

    @Test
    fun `accepts Pi virtual absolute path and rejects textual-looking binary`() = runTest {
        val body = "Return PASS."
        val skillBytes = body.toByteArray()
        val binaryBytes = "ASCII but declared binary".toByteArray()
        val files = listOf(
            SkillPackageFile("SKILL.md", "text/markdown", skillBytes, skillBytes.sha256()),
            SkillPackageFile(
                "assets/payload.bin",
                "application/octet-stream",
                binaryBytes,
                binaryBytes.sha256(),
            ),
        )
        repository.importSkill(
            SkillDocumentParseResult(
                resource = PhoneLocalSkillResource(
                    name = "review",
                    description = "A safe review Skill",
                    content = body,
                    contentSha256 = body.sha256Utf8(),
                    disableModelInvocation = false,
                    packageDigest = skillPackageDigest(files),
                    packageFileCount = files.size,
                ),
                availability = SkillAvailability.AVAILABLE,
                diagnosticCode = null,
                diagnosticMessage = null,
                sourceDocumentSha256 = body.sha256Utf8(),
            ),
            files,
        )
        repository.setEnabled("review", true)

        val absolute = executor.execute(
            "task-1",
            request(buildJsonObject {
                put("action", "read")
                put("skillName", "review")
                put("path", "/mobile-skills/review/SKILL.md")
                put("offset", 0)
                put("limit", 256)
            }),
        )
        assertEquals(body, absolute["content"]!!.jsonPrimitive.content)

        val binary = executor.execute(
            "task-1",
            request(buildJsonObject {
                put("action", "read")
                put("skillName", "review")
                put("path", "assets/payload.bin")
                put("offset", 0)
                put("limit", 256)
            }),
        )
        assertEquals("SKILL_RESOURCE_BINARY", binary["errorCode"]!!.jsonPrimitive.content)
    }

    private suspend fun install(name: String, body: String) {
        repository.importSkill(
            SkillDocumentParseResult(
                resource = PhoneLocalSkillResource(
                    name = name,
                    description = "A safe review Skill",
                    content = body,
                    contentSha256 = body.sha256Utf8(),
                    disableModelInvocation = false,
                ),
                availability = SkillAvailability.AVAILABLE,
                diagnosticCode = null,
                diagnosticMessage = null,
            ),
        )
    }

    private fun request(arguments: kotlinx.serialization.json.JsonObject) = PiNativeToolRequest(
        id = "native-skill-resource",
        kind = "android_skill_tool",
        toolCallId = "call-skill-resource",
        toolName = "skill_resource",
        arguments = arguments,
    )
}
