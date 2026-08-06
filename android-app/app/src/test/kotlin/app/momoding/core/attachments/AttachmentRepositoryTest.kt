package app.momoding.core.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftRepository
import app.momoding.core.runtime.local.PhoneLocalPiEventProjector
import java.io.File
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var sourceRoot: File
    private lateinit var repository: AttachmentRepository
    private var clock = 100L
    private val ids = ArrayDeque(
        (1..8).map { index -> "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}" },
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("attachments").deleteRecursively()
        context.cacheDir.resolve("camera-capture").deleteRecursively()
        context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE).edit().clear().commit()
        sourceRoot = context.cacheDir.resolve("attachment-source-test").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DraftRepository(
            database = database,
            nowMillis = { clock++ },
            ioDispatcher = Dispatchers.Unconfined,
        ).createDraft(DRAFT_ID)
        repository = AttachmentRepository(
            context = context,
            database = database,
            nowMillis = { clock++ },
            idFactory = { ids.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
            cameraCaptureUriFactory = Uri::fromFile,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("attachments").deleteRecursively()
        context.cacheDir.resolve("camera-capture").deleteRecursively()
        context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE).edit().clear().commit()
        sourceRoot.deleteRecursively()
    }

    @Test
    fun `photo import validates bytes stores private metadata restores thumbnail and removes payload`() = runBlocking {
        val source = createPng("private-name.png")
        val result = repository.importPhotoPickerSelection(DRAFT_ID, listOf(Uri.fromFile(source)))

        assertTrue(result.failures.toString(), result.failures.isEmpty())
        val imported = result.imported.single()
        assertEquals(AttachmentKind.IMAGE, imported.kind)
        assertEquals("image/png", imported.mimeType)
        assertEquals("private-name.png", imported.displayName)
        assertTrue(imported.payloadSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(imported.hasThumbnail)
        assertEquals(4, imported.width)
        assertEquals(3, imported.height)
        assertNotNull(repository.thumbnailPng(imported.attachmentId))

        val durable = repository.observeDraftAttachments(DRAFT_ID).first().single()
        assertEquals(imported, durable)
        val columns = database.openHelper.writableDatabase.query("PRAGMA table_info(attachments)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        assertTrue(columns.none { it.contains("uri", ignoreCase = true) })
        val entity = requireNotNull(database.attachmentDao().attachment(imported.attachmentId))
        assertFalse(entity.payloadFileName.contains("private-name"))
        assertFalse(entity.thumbnailFileName.orEmpty().contains("private-name"))

        assertTrue(repository.removeDraftAttachment(DRAFT_ID, imported.attachmentId))
        assertTrue(repository.observeDraftAttachments(DRAFT_ID).first().isEmpty())
        assertEquals(null, repository.thumbnailPng(imported.attachmentId))
        assertEquals(0, repository.pruneOrphanedPayloads())
    }

    @Test
    fun `camera capture stores private state and imports into the existing image path`() = runBlocking {
        val request = repository.prepareCameraCapture(DRAFT_ID)

        assertEquals("file", request.outputUri.scheme)
        val preferences = context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE)
        assertTrue(preferences.all.keys.none { it.contains(DRAFT_ID) })
        assertTrue(preferences.all.values.none { value ->
            value.toString().contains("content://") || value.toString().contains("/")
        })

        writeCameraOutput(request, createJpeg("camera-source.jpg"))
        val result = repository.completeCameraCapture(DRAFT_ID, request.captureId, captured = true)

        assertTrue(result.failures.toString(), result.failures.isEmpty())
        val imported = result.imported.single()
        assertEquals(AttachmentSource.CAMERA, imported.source)
        assertEquals(AttachmentKind.IMAGE, imported.kind)
        assertEquals("Camera photo.jpg", imported.displayName)
        assertEquals("image/jpeg", imported.mimeType)
        assertNotNull(repository.thumbnailPng(imported.attachmentId))
        assertTrue(context.cacheDir.resolve("camera-capture/v1").listFiles().orEmpty().isEmpty())
        assertTrue(preferences.all.isEmpty())

        val replay = repository.completeCameraCapture(DRAFT_ID, request.captureId, captured = true)
        assertEquals(imported.attachmentId, replay.imported.single().attachmentId)
        assertEquals(1, repository.observeDraftAttachments(DRAFT_ID).first().size)
    }

    @Test
    fun `stale crashed camera output is pruned with its private pending token`() = runBlocking {
        repository.prepareCameraCapture(DRAFT_ID)
        val output = context.cacheDir.resolve("camera-capture/v1").listFiles().orEmpty().single()
        assertTrue(output.setLastModified(1L))

        assertEquals(1, repository.pruneOrphanedPayloads())
        assertFalse(output.exists())
        assertTrue(
            context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE).all.isEmpty(),
        )
    }

    @Test
    fun `camera cancel and empty output clean up without creating an attachment`() = runBlocking {
        val cancelled = repository.prepareCameraCapture(DRAFT_ID)
        val cancelledResult = repository.completeCameraCapture(
            DRAFT_ID,
            cancelled.captureId,
            captured = false,
        )
        assertTrue(cancelledResult.imported.isEmpty())
        assertTrue(cancelledResult.failures.isEmpty())

        val empty = repository.prepareCameraCapture(DRAFT_ID)
        val emptyResult = repository.completeCameraCapture(DRAFT_ID, empty.captureId, captured = true)
        assertTrue(emptyResult.imported.isEmpty())
        assertEquals(AttachmentImportFailureCode.INVALID_CONTENT, emptyResult.failures.single().code)
        assertTrue(repository.observeDraftAttachments(DRAFT_ID).first().isEmpty())
        assertTrue(context.cacheDir.resolve("camera-capture/v1").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `pending camera capture survives repository recreation and honors exif orientation`() = runBlocking {
        val request = repository.prepareCameraCapture(DRAFT_ID)
        val source = createJpeg("camera-rotated.jpg")
        ExifInterface(source).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        writeCameraOutput(request, source)

        val rebuilt = AttachmentRepository(
            context = context,
            database = database,
            nowMillis = { clock++ },
            ioDispatcher = Dispatchers.Unconfined,
            cameraCaptureUriFactory = Uri::fromFile,
        )
        val imported = rebuilt.completeCameraCapture(DRAFT_ID, request.captureId, captured = true)
            .imported.single()

        assertEquals(3, imported.width)
        assertEquals(4, imported.height)
        val thumbnail = requireNotNull(rebuilt.thumbnailPng(imported.attachmentId))
        val preview = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size)
        assertEquals(3, preview.width)
        assertEquals(4, preview.height)
        preview.recycle()
        val runtimeBytes = Base64.decode(rebuilt.prepareDraftImages(DRAFT_ID).single().data, Base64.NO_WRAP)
        val runtime = BitmapFactory.decodeByteArray(runtimeBytes, 0, runtimeBytes.size)
        assertEquals(3, runtime.width)
        assertEquals(4, runtime.height)
        runtime.recycle()
    }

    @Test
    fun `count limit preserves first five and reports safe per-item failure`() = runBlocking {
        val uris = (1..6).map { index -> Uri.fromFile(createPng("image-$index.png")) }
        val result = repository.importPhotoPickerSelection(DRAFT_ID, uris)

        assertEquals(result.failures.toString(), 5, result.imported.size)
        assertEquals(1, result.failures.size)
        assertEquals(AttachmentImportFailureCode.COUNT_LIMIT, result.failures.single().code)
        assertFalse(result.failures.single().safeMessage.contains("image-6"))
        assertFalse(result.failures.single().safeMessage.contains("file:"))
        assertEquals(5, repository.observeDraftAttachments(DRAFT_ID).first().size)
        assertEquals(0, repository.pruneOrphanedPayloads())
    }

    @Test
    fun `spoofed image and binary text fail closed without durable rows or orphan files`() = runBlocking {
        val fakeImage = sourceRoot.resolve("fake.png").apply { writeText("not an image") }
        val imageResult = repository.importPhotoPickerSelection(DRAFT_ID, listOf(Uri.fromFile(fakeImage)))
        assertEquals(AttachmentImportFailureCode.INVALID_CONTENT, imageResult.failures.single().code)

        val binaryText = sourceRoot.resolve("fake.md").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        val fileResult = repository.importOpenDocument(DRAFT_ID, Uri.fromFile(binaryText))
        assertEquals(AttachmentImportFailureCode.INVALID_CONTENT, fileResult.failures.single().code)

        assertTrue(repository.observeDraftAttachments(DRAFT_ID).first().isEmpty())
        assertEquals(0, repository.pruneOrphanedPayloads())
    }

    @Test
    fun `utf8 text document is staged and product input is enabled`() = runBlocking {
        val text = sourceRoot.resolve("notes.md").apply { writeText("# Safe attachment\n") }
        val result = repository.importOpenDocument(DRAFT_ID, Uri.fromFile(text))

        val imported = result.imported.single()
        assertEquals(AttachmentKind.TEXT_FILE, imported.kind)
        assertEquals("text/plain", imported.mimeType)
        assertFalse(imported.hasThumbnail)
        assertTrue(AttachmentFeatureGate.PHOTO_PRODUCT_INPUT_ENABLED)
        assertTrue(AttachmentFeatureGate.TEXT_FILE_PRODUCT_INPUT_ENABLED)
    }

    @Test
    fun `sent task text attachment reads bounded utf8 pages and rejects another task`() = runBlocking {
        PhoneLocalPiEventProjector(database).createTask(
            taskId = TASK_ID,
            title = "Text attachment task",
            piSessionId = "session-text",
            streamId = "stream-text",
            initialPrompt = "Read the attachment",
        )
        PhoneLocalPiEventProjector(database).createTask(
            taskId = OTHER_TASK_ID,
            title = "Other task",
            piSessionId = "session-other",
            streamId = "stream-other",
            initialPrompt = "Do not leak attachments",
        )
        val expected = buildString {
            append("你")
            repeat(400) { append(('a'.code + (it % 26)).toChar()) }
        }
        val source = sourceRoot.resolve("context.md").apply { writeText(expected) }
        val imported = repository.importTaskOpenDocument(TASK_ID, Uri.fromFile(source))
            .imported.single()
        val unavailable = runCatching {
            repository.readTaskTextAttachment(TASK_ID, imported.attachmentId, 0, 256)
        }.exceptionOrNull() as AttachmentReadException
        assertEquals("ATTACHMENT_NOT_AVAILABLE", unavailable.code)

        val claimed = repository.claimTaskStagedAttachments(TASK_ID, MESSAGE_ID)
        assertEquals(imported.attachmentId, claimed.textFiles.single().attachmentId)
        assertTrue(claimed.images.isEmpty())
        val first = repository.readTaskTextAttachment(TASK_ID, imported.attachmentId, 0, 256)
        assertFalse(first.eof)
        assertTrue(first.content.encodeToByteArray().size <= 256)
        assertTrue(first.nextOffset in 1 until first.totalBytes)
        val second = repository.readTaskTextAttachment(
            TASK_ID,
            imported.attachmentId,
            first.nextOffset,
            256,
        )
        assertTrue(second.eof)
        assertEquals(expected, first.content + second.content)

        repository.reconcileTaskImages(TASK_ID, setOf(imported.attachmentId))
        assertEquals(
            expected,
            repository.readTaskTextAttachment(TASK_ID, imported.attachmentId, 0, 65_536).content,
        )

        val crossTask = runCatching {
            repository.readTaskTextAttachment(OTHER_TASK_ID, imported.attachmentId, 0, 256)
        }.exceptionOrNull() as AttachmentReadException
        assertEquals("ATTACHMENT_NOT_AVAILABLE", crossTask.code)

        val splitUtf8 = runCatching {
            repository.readTaskTextAttachment(TASK_ID, imported.attachmentId, 1, 256)
        }.exceptionOrNull() as AttachmentReadException
        assertEquals("ATTACHMENT_CONTENT_UNAVAILABLE", splitUtf8.code)
    }

    @Test
    fun `draft image binds atomically to task message and produces bounded jpeg runtime input`() = runBlocking {
        val imported = repository.importPhotoPickerSelection(
            DRAFT_ID,
            listOf(Uri.fromFile(createPng("runtime.png"))),
        ).imported.single()
        PhoneLocalPiEventProjector(database).createTask(
            taskId = TASK_ID,
            title = "Image task",
            piSessionId = "session-image",
            streamId = "stream-image",
            initialPrompt = "Inspect this image",
            attachmentIds = listOf(imported.attachmentId),
        )

        val runtime = repository.claimDraftImages(DRAFT_ID, TASK_ID, MESSAGE_ID).single()

        assertEquals(imported.attachmentId, runtime.attachmentId)
        assertEquals("image/jpeg", runtime.mimeType)
        assertTrue(runtime.data.isNotBlank())
        assertTrue(runtime.data.length < 1_500_000)
        val entity = requireNotNull(database.attachmentDao().attachment(imported.attachmentId))
        assertEquals(null, entity.draftId)
        assertEquals(TASK_ID, entity.taskId)
        assertEquals(MESSAGE_ID, entity.messageLocalId)
        assertEquals(AttachmentState.PENDING.name, entity.state)
        assertFalse(entity.toString().contains(runtime.data))

        repository.reconcileTaskImages(TASK_ID, setOf(imported.attachmentId))
        assertEquals(
            AttachmentState.SENT.name,
            database.attachmentDao().attachment(imported.attachmentId)?.state,
        )
    }

    @Test
    fun `generated image is durable previewable copyable downloadable and discardable`() = runBlocking {
        PhoneLocalPiEventProjector(database).createTask(
            taskId = TASK_ID,
            title = "Generated image task",
            piSessionId = "session-generated-image",
            streamId = "stream-generated-image",
            initialPrompt = "Draw a robot",
        )
        val bytes = createPng("generated.png").readBytes()

        val imported = repository.importGeneratedImage(
            taskId = TASK_ID,
            toolCallId = "call-generated-image",
            displayName = "Momoding image.png",
            bytes = bytes,
            declaredMimeType = "image/png",
        )
        val repeated = repository.importGeneratedImage(
            taskId = TASK_ID,
            toolCallId = "call-generated-image",
            displayName = "ignored replay.png",
            bytes = bytes,
            declaredMimeType = "image/png",
        )

        assertEquals(imported.attachmentId, repeated.attachmentId)
        assertEquals(AttachmentSource.GENERATED_IMAGE, imported.source)
        assertEquals(AttachmentState.SENT, imported.state)
        assertEquals("call-generated-image", imported.messageLocalId)
        assertEquals(imported, repository.observeTaskGeneratedImages(TASK_ID).first().single())
        val runtime = repository.runtimeImagesForTask(TASK_ID, setOf(imported.attachmentId)).single()
        assertEquals(imported.attachmentId, runtime.attachmentId)
        assertEquals("image/jpeg", runtime.mimeType)
        assertTrue(repository.generatedImageBytes(TASK_ID, imported.attachmentId).contentEquals(bytes))
        val contentUri = requireNotNull(
            repository.generatedImageContentUri(TASK_ID, imported.attachmentId),
        )
        assertEquals("content", contentUri.scheme)
        assertEquals(imported.displayName, Uri.decode(contentUri.lastPathSegment))
        val savedUri = requireNotNull(
            repository.saveGeneratedImageToPictures(TASK_ID, imported.attachmentId),
        )
        val savedBytes = requireNotNull(context.contentResolver.openInputStream(savedUri)).use {
            it.readBytes()
        }
        assertTrue(savedBytes.contentEquals(bytes))
        context.contentResolver.delete(savedUri, null, null)

        assertTrue(repository.discardGeneratedImage(TASK_ID, imported.attachmentId))
        assertTrue(repository.observeTaskGeneratedImages(TASK_ID).first().isEmpty())
        assertEquals(null, repository.generatedImageBytes(TASK_ID, imported.attachmentId))
        assertEquals(null, repository.generatedImageContentUri(TASK_ID, imported.attachmentId))
        assertEquals(0, repository.pruneOrphanedPayloads())
    }

    @Test
    fun `task composer image failure releases exact pending message without deleting payload`() = runBlocking {
        PhoneLocalPiEventProjector(database).createTask(
            taskId = TASK_ID,
            title = "Existing task",
            piSessionId = "session-image",
            streamId = "stream-image",
            initialPrompt = "Start",
        )
        val imported = repository.importTaskPhotoPickerSelection(
            TASK_ID,
            listOf(Uri.fromFile(createPng("follow-up.png"))),
        ).imported.single()
        val runtime = repository.claimTaskStagedImages(TASK_ID, MESSAGE_ID).single()
        assertEquals(imported.attachmentId, runtime.attachmentId)

        assertEquals(1, repository.releasePendingTaskImages(TASK_ID, MESSAGE_ID))
        val staged = repository.observeTaskStagedAttachments(TASK_ID).first().single()
        assertEquals(imported.attachmentId, staged.attachmentId)
        assertEquals(null, staged.messageLocalId)
        assertEquals(AttachmentState.STAGED, staged.state)
        assertNotNull(repository.thumbnailPng(imported.attachmentId))
    }

    @Test
    fun `session reconciliation sends referenced image and restages an absent sibling`() = runBlocking {
        PhoneLocalPiEventProjector(database).createTask(
            taskId = TASK_ID,
            title = "Partial image session",
            piSessionId = "session-image-partial",
            streamId = "stream-image-partial",
            initialPrompt = "Start",
        )
        val imported = repository.importTaskPhotoPickerSelection(
            TASK_ID,
            listOf(
                Uri.fromFile(createPng("partial-first.png")),
                Uri.fromFile(createPng("partial-second.png")),
            ),
        ).imported
        repository.claimTaskStagedImages(TASK_ID, MESSAGE_ID)

        repository.reconcileTaskImages(TASK_ID, setOf(imported.first().attachmentId))

        assertEquals(
            AttachmentState.SENT.name,
            database.attachmentDao().attachment(imported.first().attachmentId)?.state,
        )
        val restaged = repository.observeTaskStagedAttachments(TASK_ID).first().single()
        assertEquals(imported.last().attachmentId, restaged.attachmentId)
        assertEquals(AttachmentState.STAGED, restaged.state)
        assertEquals(null, restaged.messageLocalId)
    }

    @Test
    fun `task image limit counts sent history instead of resetting after every turn`() = runBlocking {
        PhoneLocalPiEventProjector(database).createTask(
            taskId = TASK_ID,
            title = "Bounded image task",
            piSessionId = "session-image-limit",
            streamId = "stream-image-limit",
            initialPrompt = "Start",
        )
        val firstFive = repository.importTaskPhotoPickerSelection(
            TASK_ID,
            (1..5).map { index -> Uri.fromFile(createPng("task-image-$index.png")) },
        )
        assertEquals(firstFive.failures.toString(), 5, firstFive.imported.size)
        val claimed = repository.claimTaskStagedImages(TASK_ID, MESSAGE_ID)
        repository.reconcileTaskImages(TASK_ID, claimed.mapTo(mutableSetOf()) { it.attachmentId })

        val rejected = repository.importTaskPhotoPickerSelection(
            TASK_ID,
            listOf(Uri.fromFile(createPng("task-image-6.png"))),
        )

        assertTrue(rejected.imported.isEmpty())
        assertEquals(AttachmentImportFailureCode.COUNT_LIMIT, rejected.failures.single().code)
        assertEquals(5, database.attachmentDao().taskAttachments(TASK_ID).size)
        assertTrue(repository.observeTaskStagedAttachments(TASK_ID).first().isEmpty())
    }

    @Test
    fun `owner cascade leaves no durable row and orphan cleanup removes private payloads`() = runBlocking {
        val result = repository.importPhotoPickerSelection(
            DRAFT_ID,
            listOf(Uri.fromFile(createPng("cascade.png"))),
        )
        assertEquals(1, result.imported.size)

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM drafts WHERE draftId = '$DRAFT_ID'",
        )

        assertTrue(database.attachmentDao().allAttachments().isEmpty())
        assertEquals(2, repository.pruneOrphanedPayloads())
        assertTrue(context.filesDir.resolve("attachments/v1").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `database insert failure removes newly written private payloads and reports storage failure`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM drafts WHERE draftId = '$DRAFT_ID'",
        )

        val result = repository.importPhotoPickerSelection(
            DRAFT_ID,
            listOf(Uri.fromFile(createPng("missing-owner.png"))),
        )

        assertTrue(result.imported.isEmpty())
        assertEquals(AttachmentImportFailureCode.STORAGE_FAILED, result.failures.single().code)
        assertTrue(database.attachmentDao().allAttachments().isEmpty())
        assertTrue(context.filesDir.resolve("attachments/v1").listFiles().orEmpty().isEmpty())
        assertEquals(0, repository.pruneOrphanedPayloads())
    }

    @Test
    fun `per item size limit rejects oversized text before creating a durable row`() = runBlocking {
        val oversized = sourceRoot.resolve("oversized.md").apply {
            RandomAccessFile(this, "rw").use { file ->
                file.write("# large\n".toByteArray())
                file.setLength((4L * 1024L * 1024L) + 1L)
            }
        }

        val result = repository.importOpenDocument(DRAFT_ID, Uri.fromFile(oversized))

        assertTrue(result.imported.isEmpty())
        assertEquals(AttachmentImportFailureCode.ITEM_TOO_LARGE, result.failures.single().code)
        assertTrue(repository.observeDraftAttachments(DRAFT_ID).first().isEmpty())
        assertTrue(context.filesDir.resolve("attachments/v1").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `aggregate byte limit preserves accepted attachments and removes the rejected payload`() = runBlocking {
        val first = padToBytes(createPng("first.png"), 20L * 1024L * 1024L)
        val second = padToBytes(createPng("second.png"), 20L * 1024L * 1024L)
        val third = createPng("third.png")

        assertEquals(1, repository.importPhotoPickerSelection(DRAFT_ID, listOf(Uri.fromFile(first))).imported.size)
        assertEquals(1, repository.importPhotoPickerSelection(DRAFT_ID, listOf(Uri.fromFile(second))).imported.size)
        val rejected = repository.importPhotoPickerSelection(DRAFT_ID, listOf(Uri.fromFile(third)))

        assertTrue(rejected.imported.isEmpty())
        assertEquals(AttachmentImportFailureCode.TOTAL_BYTES_LIMIT, rejected.failures.single().code)
        assertEquals(2, repository.observeDraftAttachments(DRAFT_ID).first().size)
        assertEquals(0, repository.pruneOrphanedPayloads())
        assertEquals(4, context.filesDir.resolve("attachments/v1").listFiles().orEmpty().size)
    }

    @Test
    fun `image magic detection tolerates streams that return a short read`() {
        val source = createPng("short-read.png")
        val store = AttachmentPayloadStore(context.filesDir.resolve("attachments/short-read"))

        val stored = store.import(
            attachmentId = "00000000-0000-4000-8000-000000000099",
            kind = AttachmentKind.IMAGE,
            source = AttachmentSourceDescriptor(
                displayName = source.name,
                declaredMimeType = "image/png",
                declaredSize = source.length(),
                openStream = {
                    object : FilterInputStream(source.inputStream()) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                            super.read(buffer, offset, minOf(1, length))
                    }
                },
            ),
        )

        assertEquals("image/png", stored.mimeType)
        assertEquals(source.length(), stored.byteSize)
        assertTrue(stored.thumbnailFileName != null)
    }

    private fun createPng(name: String): File {
        val file = sourceRoot.resolve(name)
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 120, 220))
        file.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
        return file
    }

    private fun createJpeg(name: String): File {
        val file = sourceRoot.resolve(name)
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(220, 90, 40))
        file.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
        return file
    }

    private fun writeCameraOutput(request: CameraCaptureRequest, source: File) {
        context.contentResolver.openOutputStream(request.outputUri, "w").use { output ->
            requireNotNull(output)
            source.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun padToBytes(file: File, byteSize: Long): File = file.apply {
        RandomAccessFile(this, "rw").use { it.setLength(byteSize) }
    }

    private companion object {
        const val DRAFT_ID = "draft-attachments"
        const val TASK_ID = "task-attachments"
        const val OTHER_TASK_ID = "task-attachments-other"
        const val MESSAGE_ID = "00000000-0000-4000-8000-000000000900"
    }
}
