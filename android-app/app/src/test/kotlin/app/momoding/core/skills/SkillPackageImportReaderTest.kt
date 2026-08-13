package app.momoding.core.skills

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkillPackageImportReaderTest {
    @Test
    fun `copies one complete nested package without retaining its tree uri`() {
        val rawSkill = """
            ---
            name: package-review
            description: Review with a local checklist
            ---
            Read [the checklist](references/checklist.md).
        """.trimIndent()
        val access = packageAccess(rawSkill)

        val result = SkillPackageImportReader(access).read(TREE_URI)

        assertEquals("package-review", result.rootDisplayName)
        assertEquals(
            listOf("SKILL.md", "assets/icon.png", "references/checklist.md", "scripts/check.sh"),
            result.files.map(SkillPackageFile::relativePath),
        )
        assertEquals(rawSkill, result.skillDocument.content)
        assertEquals(4, access.opened.size)
        assertFalse(result.toString().contains("content://"))
        assertTrue(result.packageDigest.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `package catalog enables a Pi parsed relative reference only when the file exists`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java).build()
        val rawSkill = """
            ---
            name: package-review
            description: Review with a local checklist
            ---
            Read [the checklist](references/checklist.md).
        """.trimIndent()
        try {
            var parserInput: String? = null
            val service = SkillCatalogService(
                assets = context.assets,
                importReader = SkillImportReader(FailingSingleFileAccess()),
                packageImportReader = SkillPackageImportReader(packageAccess(rawSkill)),
                parser = PhoneLocalSkillParser { content ->
                    parserInput = content
                    val body = "Read [the checklist](references/checklist.md)."
                    SkillDocumentParseResult(
                        resource = PhoneLocalSkillResource(
                            name = "package-review",
                            description = "Review with a local checklist",
                            content = body,
                            contentSha256 = body.sha256Utf8(),
                            disableModelInvocation = false,
                        ),
                        availability = SkillAvailability.UNAVAILABLE,
                        diagnosticCode = "RELATIVE_DEPENDENCY_UNSUPPORTED",
                        diagnosticMessage = "Single-file import cannot use local relative references.",
                        sourceDocumentSha256 = content.sha256Utf8(),
                    )
                },
                repository = SkillRepository(database, nowMillis = { 1L }),
            )

            val installed = service.importSkillPackage(TREE_URI)

            assertEquals(rawSkill, parserInput)
            assertEquals(4, installed.fileCount)
            assertEquals(SkillAvailability.AVAILABLE, installed.record.availability)
            assertEquals(4, installed.record.resource.packageFileCount)
            assertTrue(installed.record.resource.packageDigest.matches(Regex("^[0-9a-f]{64}$")))
        } finally {
            database.close()
        }
    }

    @Test
    fun `rejects unsafe virtual and oversized package entries with stable codes`() {
        val unsafe = FakeTreeAccess(
            childrenById = mapOf(
                "root" to listOf(node("skill", "SKILL.md", "text/markdown"), node("bad", "..", "text/plain")),
            ),
            bytesById = mapOf("skill" to validSkillBytes()),
        )
        assertCode("SKILL_RESOURCE_PATH_INVALID") {
            SkillPackageImportReader(unsafe).read(TREE_URI)
        }

        val virtual = FakeTreeAccess(
            childrenById = mapOf(
                "root" to listOf(
                    node("skill", "SKILL.md", "text/markdown"),
                    node("virtual", "remote.txt", "text/plain", flags = Document.FLAG_VIRTUAL_DOCUMENT),
                ),
            ),
            bytesById = mapOf("skill" to validSkillBytes()),
        )
        assertCode("SKILL_PACKAGE_VIRTUAL_FILE_UNSUPPORTED") {
            SkillPackageImportReader(virtual).read(TREE_URI)
        }
    }

    private fun packageAccess(rawSkill: String): FakeTreeAccess = FakeTreeAccess(
        childrenById = mapOf(
            "root" to listOf(
                node("skill", "SKILL.md", "text/markdown"),
                node("assets", "assets", Document.MIME_TYPE_DIR),
                node("references", "references", Document.MIME_TYPE_DIR),
                node("scripts", "scripts", Document.MIME_TYPE_DIR),
            ),
            "assets" to listOf(node("icon", "icon.png", "image/png")),
            "references" to listOf(node("checklist", "checklist.md", "text/markdown")),
            "scripts" to listOf(node("script", "check.sh", "text/x-shellscript")),
        ),
        bytesById = mapOf(
            "skill" to rawSkill.toByteArray(),
            "icon" to byteArrayOf(0x01, 0x02),
            "checklist" to "Return PASS or FAIL.".toByteArray(),
            "script" to "#!/system/bin/sh\necho review".toByteArray(),
        ),
    )

    private fun assertCode(expected: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is SkillPackageImportException)
        assertEquals(expected, (error as SkillPackageImportException).code)
        assertFalse(error.message.orEmpty().contains("content://"))
    }

    private class FakeTreeAccess(
        private val childrenById: Map<String, List<SkillPackageTreeNode>>,
        private val bytesById: Map<String, ByteArray>,
    ) : SkillPackageTreeAccess {
        val opened = mutableListOf<String>()

        override fun root(treeUri: Uri) = node("root", "package-review", Document.MIME_TYPE_DIR)

        override fun children(treeUri: Uri, parentDocumentId: String) =
            childrenById[parentDocumentId].orEmpty()

        override fun open(treeUri: Uri, documentId: String): InputStream {
            opened += documentId
            return ByteArrayInputStream(requireNotNull(bytesById[documentId]))
        }
    }

    private class FailingSingleFileAccess : SkillImportAccess {
        override fun metadata(uri: Uri): SkillImportMetadata = error("single-file reader was used")
        override fun open(uri: Uri): InputStream = error("single-file reader was used")
    }

    companion object {
        private val TREE_URI = Uri.parse("content://documents.example/tree/root")

        private fun node(
            id: String,
            name: String,
            mime: String,
            flags: Int = 0,
        ) = SkillPackageTreeNode(id, name, mime, null, flags)

        private fun validSkillBytes() = """
            ---
            name: package-review
            description: Review with a local checklist
            ---
            Review.
        """.trimIndent().toByteArray()
    }
}
