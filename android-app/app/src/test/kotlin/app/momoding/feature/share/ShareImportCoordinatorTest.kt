package app.momoding.feature.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftRepository
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
class ShareImportCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var drafts: DraftRepository
    private lateinit var attachments: AttachmentRepository
    private lateinit var root: File
    private var now = 1_000L
    private lateinit var authority: String
    private val ids = ArrayDeque(
        listOf(
            "10000000-0000-4000-8000-000000000001",
            "10000000-0000-4000-8000-000000000002",
            "10000000-0000-4000-8000-000000000003",
            "10000000-0000-4000-8000-000000000004",
        ),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("share-import-receipts-v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.filesDir.resolve("attachments").deleteRecursively()
        root = context.cacheDir.resolve("diagnostics-export").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        authority = registerShareTestProvider(context, root)
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        drafts = DraftRepository(database, nowMillis = { now++ }, ioDispatcher = Dispatchers.Unconfined)
        attachments = AttachmentRepository(
            context,
            database,
            nowMillis = { now++ },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
        context.filesDir.resolve("attachments").deleteRecursively()
        context.getSharedPreferences("share-import-receipts-v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `text share creates durable editable draft and never creates a task`() = runTest {
        val result = coordinator().import(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Review https://example.test"),
        )

        val draft = database.momodingDao().draft(result.draftId)
        assertEquals("Review https://example.test", draft?.text)
        assertEquals("Review https://example.test".length, draft?.selectionStart)
        assertTrue(database.momodingDao().allTasks().isEmpty())
        assertTrue(attachments.observeDraftAttachments(result.draftId).first().isEmpty())
        assertTrue(result.notice.contains("Nothing has been sent"))
    }

    @Test
    fun `duplicate delivery and coordinator rebuild reuse receipt draft and private attachment`() = runTest {
        val uri = pngUri("private-photo.png")
        val intent = streamIntent(uri, "image/png")
        val first = coordinator().import(intent)
        val rebuilt = coordinator().import(Intent(intent))

        assertEquals(first.receiptId, rebuilt.receiptId)
        assertEquals(first.draftId, rebuilt.draftId)
        val records = attachments.observeDraftAttachments(first.draftId).first()
        assertEquals(1, records.size)
        assertEquals(AttachmentKind.IMAGE, records.single().kind)
        assertFalse(records.single().displayName.contains("content://"))
        assertTrue(database.momodingDao().allTasks().isEmpty())
    }

    @Test
    fun `shared utf8 file uses text attachment and invalid provider stays a safe editable draft`() = runTest {
        val textUri = contentFile("notes.md", "# Shared\nhello".encodeToByteArray())
        val imported = coordinator().import(streamIntent(textUri, "text/markdown"))
        assertEquals(
            AttachmentKind.TEXT_FILE,
            attachments.observeDraftAttachments(imported.draftId).first().single().kind,
        )

        now += 700_000L
        val invalid = coordinator().import(
            streamIntent(android.net.Uri.parse("content://missing.provider/not-found"), "image/png"),
        )
        assertTrue(attachments.observeDraftAttachments(invalid.draftId).first().isEmpty())
        assertTrue(invalid.notice.contains("could not be read"))
        assertTrue(database.momodingDao().allTasks().isEmpty())
    }

    @Test
    fun `video is copied but remains explicitly unavailable to Agent`() = runTest {
        val mp4 = ByteArray(16).apply {
            this[3] = 16
            this[4] = 'f'.code.toByte()
            this[5] = 't'.code.toByte()
            this[6] = 'y'.code.toByte()
            this[7] = 'p'.code.toByte()
        }
        val result = coordinator().import(streamIntent(contentFile("clip.mp4", mp4), "video/mp4"))
        val record = attachments.observeDraftAttachments(result.draftId).first().single()

        assertEquals(AttachmentKind.VIDEO, record.kind)
        assertEquals("video/mp4", record.mimeType)
        assertTrue(result.notice.contains("cannot read video yet"))
        assertTrue(database.momodingDao().allTasks().isEmpty())
    }

    private fun coordinator() = ShareImportCoordinator(
        context = context,
        drafts = drafts,
        attachments = attachments,
        nowMillis = { now++ },
        idFactory = { ids.removeFirst() },
    )

    private fun streamIntent(uri: android.net.Uri, mime: String) = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_STREAM, uri)

    private fun pngUri(name: String): android.net.Uri {
        val file = root.resolve(name)
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 130, 220))
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
        return fileUri(file)
    }

    private fun contentFile(name: String, bytes: ByteArray): android.net.Uri =
        fileUri(root.resolve(name).apply { writeBytes(bytes) })

    private fun fileUri(file: File): android.net.Uri = android.net.Uri.Builder()
        .scheme("content")
        .authority(authority)
        .appendPath(file.name)
        .build()
}
