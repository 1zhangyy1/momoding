package app.momoding.feature.newtask

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.attachments.AttachmentImportBatchResult
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.attachments.AttachmentRecord
import app.momoding.core.attachments.AttachmentSource
import app.momoding.core.attachments.AttachmentState
import app.momoding.core.attachments.DraftAttachmentGateway
import app.momoding.core.attachments.CameraCaptureRequest
import app.momoding.core.data.DraftRecord
import app.momoding.core.data.DraftRepository
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskViewModelTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `restored draft is announced and quick Back flushes latest text and selection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(Dispatchers.Unconfined)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft(text = "durable old", selection = 11))
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = Dispatchers.Unconfined)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("No request expected") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
            initialState = restoreDraftState(NewTaskUiState(DRAFT_ID), journal.draft(DRAFT_ID)!!),
        )

        assertTrue(viewModel.state.value.restored)
        assertEquals(DRAFT_RESTORED_NOTICE, viewModel.state.value.notice)
        viewModel.dispatch(NewTaskAction.RestorationAnnouncementConsumed)
        assertFalse(viewModel.state.value.restored)
        assertEquals(null, viewModel.state.value.notice)

        val latest = TextFieldValue("latest text", TextRange(3, 7))
        viewModel.dispatch(NewTaskAction.EditDraft(latest))
        runCurrent()
        assertFalse(viewModel.state.value.restored)
        assertEquals(null, viewModel.state.value.notice)
        val back = async { viewModel.oneShots.first() }
        runCurrent()
        viewModel.dispatch(NewTaskAction.Back)
        advanceUntilIdle()

        assertEquals(NewTaskOneShot.Back, back.await())
        val saved = journal.draft(DRAFT_ID)!!
        assertEquals(latest.text, saved.text)
        assertEquals(latest.selection.start, saved.selectionStart)
        assertEquals(latest.selection.end, saved.selectionEnd)
        applicationScope.cancel()
    }

    @Test
    fun `Back during send persistence waits for durable draft before leaving route`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft(text = "durable old", selection = 11))
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("No request expected while offline") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val latest = TextFieldValue("send then back", TextRange(2, 8))
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
            initialState = NewTaskUiState(
                draftId = DRAFT_ID,
                draft = latest,
                draftInitialized = true,
                connection = NewTaskConnectionState.READY,
                profile = NewTaskProfileState.Ready("Mac Studio", "OpenRouter", "model", "default"),
            ),
        )
        val back = async { viewModel.oneShots.first() }

        viewModel.dispatch(NewTaskAction.Send)
        assertEquals(NewTaskSendState.PERSISTING, viewModel.state.value.sendState)
        viewModel.dispatch(NewTaskAction.Back)
        assertFalse(back.isCompleted)
        runCurrent()

        assertEquals(NewTaskOneShot.Back, back.await())
        val saved = journal.draft(DRAFT_ID)!!
        assertEquals(latest.text, saved.text)
        assertEquals(latest.selection.start, saved.selectionStart)
        assertEquals(latest.selection.end, saved.selectionEnd)
        applicationScope.cancel()
    }

    @Test
    fun `attachment gate stages restores and removes while runtime send remains fail closed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("Attachment gate must not submit before image support is ready") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val gateway = FakeAttachmentGateway()
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
            attachments = gateway,
            photoAttachmentInputEnabled = true,
            textFileAttachmentInputEnabled = true,
            imageAttachmentRuntimeReady = false,
            textFileAttachmentRuntimeReady = false,
        )
        advanceUntilIdle()

        viewModel.dispatch(NewTaskAction.OpenAttachmentMenu)
        assertTrue(viewModel.state.value.attachmentMenuOpen)
        viewModel.dispatch(NewTaskAction.ImportPhotos(listOf(Uri.parse("content://picker/opaque"))))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.attachments.size)
        assertFalse(viewModel.state.value.attachmentImporting)
        assertEquals(
            "Attachment sending stays disabled until its Pi runtime path is validated.",
            viewModel.state.value.sendDisabledReason,
        )
        viewModel.dispatch(NewTaskAction.Send)
        assertEquals(NewTaskSendState.IDLE, viewModel.state.value.sendState)

        val rebuilt = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
            attachments = gateway,
            photoAttachmentInputEnabled = true,
            textFileAttachmentInputEnabled = true,
            imageAttachmentRuntimeReady = false,
            textFileAttachmentRuntimeReady = false,
        )
        advanceUntilIdle()
        assertEquals(gateway.record.attachmentId, rebuilt.state.value.attachments.single().attachmentId)

        rebuilt.dispatch(NewTaskAction.RemoveAttachment(gateway.record.attachmentId))
        advanceUntilIdle()
        assertTrue(rebuilt.state.value.attachments.isEmpty())
        applicationScope.cancel()
    }

    @Test
    fun `attachment only is valid input only after runtime capability is ready`() {
        val attachment = NewTaskAttachmentUiModel(
            attachmentId = "00000000-0000-4000-8000-000000000001",
            kind = AttachmentKind.IMAGE,
            displayName = "image.png",
            mimeType = "image/png",
            byteSize = 128,
            thumbnailPng = null,
            width = 4,
            height = 3,
        )
        val base = NewTaskUiState(
            draftId = DRAFT_ID,
            draftInitialized = true,
            connection = NewTaskConnectionState.READY,
            profile = NewTaskProfileState.Ready("Phone", "OpenRouter", "model", "default"),
            photoAttachmentInputEnabled = true,
            attachments = listOf(attachment),
        )

        assertFalse(base.canSend)
        assertTrue(base.copy(imageAttachmentRuntimeReady = true).canSend)

        val textOnly = base.copy(
            photoAttachmentInputEnabled = false,
            textFileAttachmentInputEnabled = true,
            attachments = listOf(attachment.copy(kind = AttachmentKind.TEXT_FILE)),
            imageAttachmentRuntimeReady = true,
        )
        assertFalse(textOnly.canSend)
        assertTrue(textOnly.copy(textFileAttachmentRuntimeReady = true).canSend)
    }

    @Test
    fun `camera launch keeps the editable draft and imports only a successful result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("Camera capture must stay in the draft") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val gateway = FakeAttachmentGateway()
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
            attachments = gateway,
            cameraAttachmentInputEnabled = true,
            imageAttachmentRuntimeReady = true,
        )
        advanceUntilIdle()
        val prompt = TextFieldValue("Inspect what I photograph")
        viewModel.dispatch(NewTaskAction.EditDraft(prompt))

        val cancelledLaunch = async { viewModel.oneShots.first() }
        runCurrent()
        viewModel.dispatch(NewTaskAction.RequestCameraCapture)
        runCurrent()
        val cancelled = cancelledLaunch.await() as NewTaskOneShot.LaunchCamera
        assertTrue(viewModel.state.value.cameraCaptureActive)
        viewModel.dispatch(NewTaskAction.CameraCaptureFinished(cancelled.captureId, captured = false))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.cameraCaptureActive)
        assertTrue(viewModel.state.value.attachments.isEmpty())
        assertEquals(prompt, viewModel.state.value.draft)

        val capturedLaunch = async { viewModel.oneShots.first() }
        runCurrent()
        viewModel.dispatch(NewTaskAction.RequestCameraCapture)
        runCurrent()
        val captured = capturedLaunch.await() as NewTaskOneShot.LaunchCamera
        viewModel.dispatch(NewTaskAction.CameraCaptureFinished(captured.captureId, captured = true))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.attachments.size)
        assertEquals(prompt, viewModel.state.value.draft)
        assertFalse(viewModel.state.value.attachmentImporting)
        assertEquals(2, gateway.prepareCalls)
        assertEquals(listOf(false, true), gateway.captureResults)
        applicationScope.cancel()
    }

    @Test
    fun `approval mode is optimistically selected durably restored and blocks send while saving`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("No request expected") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
        )
        advanceUntilIdle()

        val fullAccessSetup = async { viewModel.oneShots.first() }
        runCurrent()
        viewModel.dispatch(NewTaskAction.SelectApprovalMode(TaskApprovalMode.FULL_ACCESS))
        assertEquals(TaskApprovalMode.FULL_ACCESS, viewModel.state.value.approvalMode)
        assertTrue(viewModel.state.value.approvalModeSaving)
        assertEquals("The approval mode is being saved.", viewModel.state.value.sendDisabledReason)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.approvalModeSaving)
        assertEquals(NewTaskOneShot.OpenFullAccessSetup, fullAccessSetup.await())
        assertEquals(TaskApprovalMode.FULL_ACCESS, journal.draft(DRAFT_ID)?.approvalMode)
        val restored = restoreDraftState(NewTaskUiState(DRAFT_ID), journal.draft(DRAFT_ID)!!)
        assertEquals(TaskApprovalMode.FULL_ACCESS, restored.approvalMode)
        applicationScope.cancel()
    }

    @Test
    fun `draft input send and approval selection stay gated until durable restoration completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(
            draft(text = "durable content", selection = 4).copy(
                approvalMode = TaskApprovalMode.FULL_ACCESS,
            ),
        )
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("No request expected") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
        )

        viewModel.dispatch(NewTaskAction.EditDraft(TextFieldValue("must not overwrite")))
        viewModel.dispatch(NewTaskAction.SelectApprovalMode(TaskApprovalMode.AUTO_APPROVE))
        viewModel.dispatch(NewTaskAction.Send)
        assertFalse(viewModel.state.value.draftInitialized)
        assertEquals("", viewModel.state.value.draft.text)
        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, viewModel.state.value.approvalMode)
        assertEquals(NewTaskSendState.IDLE, viewModel.state.value.sendState)

        advanceUntilIdle()

        assertTrue(viewModel.state.value.draftInitialized)
        assertEquals("durable content", viewModel.state.value.draft.text)
        assertEquals(TaskApprovalMode.FULL_ACCESS, viewModel.state.value.approvalMode)
        assertEquals("durable content", journal.draft(DRAFT_ID)?.text)
        assertEquals(TaskApprovalMode.FULL_ACCESS, journal.draft(DRAFT_ID)?.approvalMode)
        applicationScope.cancel()
    }

    @Test
    fun `approval persistence failure rolls optimistic selection back`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft(text = "ready", selection = 0))
        val drafts = DraftRepository(database, nowMillis = { 1L }, ioDispatcher = dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val coordinator = TaskCreationCoordinator(
            transportStatus = transport,
            submitExact = { error("No request expected") },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = NewTaskViewModel(
            draftId = DRAFT_ID,
            drafts = drafts,
            transportStatus = transport,
            retryConnectionAction = {},
            refreshProfileAction = {},
            coordinator = coordinator,
            initialState = restoreDraftState(NewTaskUiState(DRAFT_ID), journal.draft(DRAFT_ID)!!),
            approvalModePersistence = { throw IllegalStateException("fixture") },
        )

        viewModel.dispatch(NewTaskAction.SelectApprovalMode(TaskApprovalMode.FULL_ACCESS))
        assertEquals(TaskApprovalMode.FULL_ACCESS, viewModel.state.value.approvalMode)
        assertTrue(viewModel.state.value.approvalModeSaving)
        runCurrent()

        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, viewModel.state.value.approvalMode)
        assertFalse(viewModel.state.value.approvalModeSaving)
        assertEquals("The approval mode could not be saved.", viewModel.state.value.notice)
        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, journal.draft(DRAFT_ID)?.approvalMode)
        applicationScope.cancel()
    }

    private fun draft(text: String, selection: Int) = DraftRecord(
        draftId = DRAFT_ID,
        text = text,
        selectedHostId = null,
        selectedModelId = null,
        selectedMode = null,
        createCommandId = "11111111-1111-4111-8111-111111111111",
        promptCommandId = "22222222-2222-4222-8222-222222222222",
        taskId = null,
        updatedAtMillis = 1L,
        selectionStart = selection,
        selectionEnd = selection,
    )

    private companion object {
        const val DRAFT_ID = "draft-back-flush"
    }
}

private class FakeAttachmentGateway : DraftAttachmentGateway {
    var prepareCalls: Int = 0
    val captureResults = mutableListOf<Boolean>()
    val record = AttachmentRecord(
        attachmentId = "00000000-0000-4000-8000-000000000001",
        draftId = "draft-back-flush",
        ordinal = 0,
        kind = AttachmentKind.IMAGE,
        state = AttachmentState.STAGED,
        source = AttachmentSource.PHOTO_PICKER,
        displayName = "image.png",
        mimeType = "image/png",
        byteSize = 128,
        payloadSha256 = "0".repeat(64),
        hasThumbnail = true,
        width = 4,
        height = 3,
        createdAtMillis = 1L,
    )
    private val records = MutableStateFlow<List<AttachmentRecord>>(emptyList())

    override fun observeDraftAttachments(draftId: String): Flow<List<AttachmentRecord>> = records

    override suspend fun importPhotoPickerSelection(
        draftId: String,
        uris: List<Uri>,
    ): AttachmentImportBatchResult {
        records.value = listOf(record)
        return AttachmentImportBatchResult(listOf(record), emptyList())
    }

    override suspend fun importOpenDocument(draftId: String, uri: Uri): AttachmentImportBatchResult =
        importPhotoPickerSelection(draftId, listOf(uri))

    override suspend fun prepareCameraCapture(draftId: String): CameraCaptureRequest =
        CameraCaptureRequest(
            captureId = "00000000-0000-4000-8000-000000000099",
            outputUri = Uri.parse("content://camera-fixture/capture.jpg"),
        ).also { prepareCalls += 1 }

    override suspend fun completeCameraCapture(
        draftId: String,
        captureId: String,
        captured: Boolean,
    ): AttachmentImportBatchResult {
        captureResults += captured
        return if (captured) {
            importPhotoPickerSelection(draftId, listOf(Uri.EMPTY))
        } else {
            AttachmentImportBatchResult(emptyList(), emptyList())
        }
    }

    override suspend fun thumbnailPng(attachmentId: String): ByteArray = byteArrayOf(1, 2, 3)

    override suspend fun removeDraftAttachment(draftId: String, attachmentId: String): Boolean {
        val existed = records.value.any { it.attachmentId == attachmentId }
        records.value = records.value.filterNot { it.attachmentId == attachmentId }
        return existed
    }
}
