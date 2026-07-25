package app.momoding.feature.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.attachments.AttachmentKind
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntentParserTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var parser: ShareIntentParser
    private lateinit var authority: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = context.cacheDir.resolve("diagnostics-export").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        authority = registerShareTestProvider(context, root)
        parser = ShareIntentParser(context.contentResolver)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `browser text and url become one editable draft value`() {
        val parsed = parser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Android sharing")
                .putExtra(Intent.EXTRA_TEXT, "https://example.test/article"),
        )

        assertEquals("Android sharing\n\nhttps://example.test/article", parsed.text)
        assertTrue(parsed.attachments.isEmpty())
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `stream duplicated between extra and ClipData is imported once`() {
        val uri = contentFile("photo.png", byteArrayOf(1, 2, 3))
        val intent = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newUri(context.contentResolver, "photo", uri)
        val parsed = parser.parse(intent)

        assertEquals(1, parsed.attachments.size)
        assertEquals(AttachmentKind.IMAGE, parsed.attachments.single().kind)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `multiple images preserve order and source indices`() {
        val first = contentFile("first.jpg", byteArrayOf(1))
        val second = contentFile("second.webp", byteArrayOf(2))
        val parsed = parser.parse(
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second)),
        )

        assertEquals(listOf(0, 1), parsed.attachments.map { it.sourceIndex })
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.IMAGE), parsed.attachments.map { it.kind })
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `text file video and exact structured mime are classified truthfully`() {
        val markdown = contentFile("notes.md", "hello".encodeToByteArray())
        val video = contentFile("clip.mp4", byteArrayOf(0, 0, 0, 12, 0x66, 0x74, 0x79, 0x70))
        val json = contentFile("data.json", "{}".encodeToByteArray())

        assertEquals(
            AttachmentKind.TEXT_FILE,
            parseStream(markdown, "text/markdown").attachments.single().kind,
        )
        assertEquals(
            AttachmentKind.VIDEO,
            parseStream(video, "video/mp4").attachments.single().kind,
        )
        assertEquals(
            AttachmentKind.TEXT_FILE,
            parseStream(json, "application/json").attachments.single().kind,
        )
    }

    @Test
    fun `unsafe uri missing grant unknown mime and empty intent fail closed`() {
        val fileUri = Uri.fromFile(root.resolve("private.png").apply { writeBytes(byteArrayOf(1)) })
        val unsafe = parser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, fileUri),
        )
        assertEquals(ShareInputErrorCode.INVALID_URI, unsafe.errors.single().code)

        val content = contentFile("grant.png", byteArrayOf(1))
        val noGrant = parser.parse(
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, content),
        )
        assertEquals(ShareInputErrorCode.MISSING_READ_GRANT, noGrant.errors.single().code)

        val unknown = parser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, contentFile("paper.pdf", byteArrayOf(1))),
        )
        assertEquals(ShareInputErrorCode.UNSUPPORTED_TYPE, unknown.errors.single().code)

        val empty = parser.parse(Intent(Intent.ACTION_SEND).setType("text/plain"))
        assertEquals(ShareInputErrorCode.EMPTY_CONTENT, empty.errors.single().code)
    }

    @Test
    fun `text and attachment count limits reject excess without silently truncating text`() {
        val tooLong = parser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "x".repeat(131_073)),
        )
        assertTrue(tooLong.text.isEmpty())
        assertTrue(tooLong.errors.any { it.code == ShareInputErrorCode.TEXT_TOO_LONG })

        val uris = (1..6).map { index -> contentFile("image-$index.png", byteArrayOf(index.toByte())) }
        val tooMany = parser.parse(
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)),
        )
        assertEquals(5, tooMany.attachments.size)
        assertTrue(tooMany.errors.any { it.code == ShareInputErrorCode.TOO_MANY_ITEMS })
    }

    private fun parseStream(uri: Uri, mimeType: String): IncomingShare = parser.parse(
        Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, uri),
    )

    private fun contentFile(name: String, bytes: ByteArray): Uri {
        val file = root.resolve(name).apply { writeBytes(bytes) }
        return Uri.Builder().scheme("content").authority(authority).appendPath(file.name).build()
    }
}
