package app.momoding.core.skills

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkillImportReaderTest {
    @Test
    fun `reads exact bounded SKILL md as strict utf8 without returning uri`() {
        val bytes = ByteArray(MAX_SKILL_DOCUMENT_BYTES) { 'a'.code.toByte() }
        val access = FakeSkillImportAccess(bytes = bytes)
        val document = SkillImportReader(access).read(SKILL_URI)

        assertEquals(MAX_SKILL_DOCUMENT_BYTES, document.byteCount)
        assertEquals(MAX_SKILL_DOCUMENT_BYTES, document.content.length)
        assertEquals("bf718b6f653bebc184e1479f1935b8da974d701b893afcf49e701f3e2f9f9c5a", document.documentSha256)
        assertFalse(document.toString().contains("content://"))
        assertEquals(1, access.openCount)
    }

    @Test
    fun `rejects metadata and streamed overflow before parser`() {
        val declared = FakeSkillImportAccess(
            bytes = byteArrayOf(1),
            sizeBytes = MAX_SKILL_DOCUMENT_BYTES.toLong() + 1,
        )
        assertCode("SKILL_DOCUMENT_TOO_LARGE") { SkillImportReader(declared).read(SKILL_URI) }
        assertEquals(0, declared.openCount)

        val streamed = FakeSkillImportAccess(
            bytes = ByteArray(MAX_SKILL_DOCUMENT_BYTES + 1),
            sizeBytes = null,
        )
        assertCode("SKILL_DOCUMENT_TOO_LARGE") { SkillImportReader(streamed).read(SKILL_URI) }
    }

    @Test
    fun `rejects malformed utf8 wrong name and non content uri with sanitized codes`() {
        val malformed = FakeSkillImportAccess(bytes = byteArrayOf(0xC3.toByte(), 0x28))
        assertCode("SKILL_DOCUMENT_UTF8_INVALID") { SkillImportReader(malformed).read(SKILL_URI) }

        val wrongName = FakeSkillImportAccess(displayName = "skill.md")
        assertCode("SKILL_DOCUMENT_NAME_INVALID") { SkillImportReader(wrongName).read(SKILL_URI) }

        val untouched = FakeSkillImportAccess()
        assertCode("SKILL_DOCUMENT_URI_INVALID") {
            SkillImportReader(untouched).read(Uri.parse("file:///private/SKILL.md"))
        }
        assertEquals(0, untouched.metadataCount)
    }

    @Test
    fun `catalog passes only document text to Pi parser and persists pure parsed data`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, app.momoding.core.data.MomodingDatabase::class.java)
            .build()
        try {
            val raw = "---\nname: review\ndescription: Review safely\n---\nBody"
            val access = FakeSkillImportAccess(bytes = raw.toByteArray())
            var parserInput: String? = null
            val parser = PhoneLocalSkillParser { content ->
                parserInput = content
                SkillDocumentParseResult(
                    resource = PhoneLocalSkillResource(
                        name = "review",
                        description = "Review safely",
                        content = "Body",
                        contentSha256 = "Body".sha256Utf8(),
                        disableModelInvocation = false,
                    ),
                    availability = SkillAvailability.AVAILABLE,
                    diagnosticCode = null,
                    diagnosticMessage = null,
                )
            }
            val service = SkillCatalogService(
                assets = context.assets,
                importReader = SkillImportReader(access),
                parser = parser,
                repository = SkillRepository(database, nowMillis = { 1L }),
            )

            val imported = kotlinx.coroutines.runBlocking { service.importSkill(SKILL_URI) }

            assertEquals(raw, parserInput)
            assertEquals("Body", imported.resource.content)
            assertTrue(kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                database.skillDao().all().none { it.toString().contains("content://") }
            })
        } finally {
            database.close()
        }
    }

    private fun assertCode(expected: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is SkillImportException)
        assertEquals(expected, (error as SkillImportException).code)
        assertFalse(error.message.orEmpty().contains("content://"))
    }

    private class FakeSkillImportAccess(
        private val displayName: String = "SKILL.md",
        private val bytes: ByteArray = "valid".toByteArray(),
        private val sizeBytes: Long? = bytes.size.toLong(),
    ) : SkillImportAccess {
        var metadataCount = 0
        var openCount = 0

        override fun metadata(uri: Uri): SkillImportMetadata {
            metadataCount += 1
            return SkillImportMetadata(displayName, sizeBytes)
        }

        override fun open(uri: Uri): InputStream {
            openCount += 1
            return ByteArrayInputStream(bytes)
        }
    }

    companion object {
        private val SKILL_URI = Uri.parse("content://documents.example/document/skill")
    }
}
