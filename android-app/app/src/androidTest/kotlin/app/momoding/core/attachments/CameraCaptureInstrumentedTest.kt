package app.momoding.core.attachments

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftRepository
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

@RunWith(AndroidJUnit4::class)
class CameraCaptureInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var repository: AttachmentRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.cacheDir.resolve("camera-capture").deleteRecursively()
        context.filesDir.resolve("attachments").deleteRecursively()
        context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DraftRepository(database, ioDispatcher = Dispatchers.IO).createDraft(DRAFT_ID)
        repository = AttachmentRepository(context, database, ioDispatcher = Dispatchers.IO)
    }

    @After
    fun tearDown() {
        database.close()
        context.cacheDir.resolve("camera-capture").deleteRecursively()
        context.filesDir.resolve("attachments").deleteRecursively()
        context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun takePictureContractWritesThroughNarrowProviderAndRebuildImportsOneDraftImage() = runBlocking {
        val request = repository.prepareCameraCapture(DRAFT_ID)
        assertEquals("content", request.outputUri.scheme)
        assertEquals("${context.packageName}.camera-fileprovider", request.outputUri.authority)

        val intent = ActivityResultContracts.TakePicture().createIntent(context, request.outputUri)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        assertEquals(
            request.outputUri,
            IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, android.net.Uri::class.java),
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertNotNull(context.contentResolver.openFileDescriptor(request.outputUri, "rw"))

        writeJpeg(request)
        val rebuilt = AttachmentRepository(context, database, ioDispatcher = Dispatchers.IO)
        val result = rebuilt.completeCameraCapture(DRAFT_ID, request.captureId, captured = true)

        assertTrue(result.failures.toString(), result.failures.isEmpty())
        val image = result.imported.single()
        assertEquals(AttachmentSource.CAMERA, image.source)
        assertEquals("Camera photo.jpg", image.displayName)
        assertEquals("image/jpeg", image.mimeType)
        assertNotNull(rebuilt.thumbnailPng(image.attachmentId))
        assertEquals(1, rebuilt.prepareDraftImages(DRAFT_ID).size)
        assertEquals(1, rebuilt.observeDraftAttachments(DRAFT_ID).first().size)
        assertTrue(context.cacheDir.resolve("camera-capture/v1").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancelAndEmptyCameraResultsNeverCreateOrLeakAnAttachment() = runBlocking {
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
        assertTrue(
            context.getSharedPreferences("camera-capture-v1", Context.MODE_PRIVATE).all.isEmpty(),
        )
    }

    private fun writeJpeg(request: CameraCaptureRequest) {
        val bitmap = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 130, 210))
        context.contentResolver.openOutputStream(request.outputUri, "w").use { output ->
            requireNotNull(output)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
    }

    private companion object {
        const val DRAFT_ID = "camera-capture-instrumented"
    }
}
