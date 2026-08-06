package app.momoding.core.attachments

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import app.momoding.core.data.AttachmentEntity
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.runtime.local.GeneratedImageArtifactStore
import app.momoding.core.runtime.local.PiRuntimeImageInput
import app.momoding.core.runtime.local.PiRuntimeTextAttachmentInput
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AttachmentRepository(
    context: Context,
    private val database: MomodingDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    cameraCaptureIdFactory: () -> String = { UUID.randomUUID().toString() },
    cameraCaptureUriFactory: ((java.io.File) -> Uri)? = null,
) : DraftAttachmentGateway, TaskAttachmentGateway, GeneratedImageArtifactStore {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = database.attachmentDao()
    private val mutationMutex = Mutex()
    private val cameraCaptures = if (cameraCaptureUriFactory == null) {
        CameraCaptureStore(context.applicationContext, cameraCaptureIdFactory)
    } else {
        CameraCaptureStore(context.applicationContext, cameraCaptureIdFactory, cameraCaptureUriFactory)
    }
    private val store by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AttachmentPayloadStore(appContext.filesDir.resolve("attachments/v1"))
    }

    override fun observeDraftAttachments(draftId: String): Flow<List<AttachmentRecord>> {
        requireToken(draftId, "draftId")
        return dao.observeDraftAttachments(draftId)
            .map { entities -> entities.map(::validatedRecord) }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    suspend fun draftAttachmentIds(draftId: String): List<String> = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        dao.draftAttachments(draftId).map(AttachmentEntity::attachmentId)
    }

    suspend fun draftImageIds(draftId: String): List<String> = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        dao.draftAttachments(draftId)
            .filter { it.kind == AttachmentKind.IMAGE.name }
            .map(AttachmentEntity::attachmentId)
    }

    suspend fun taskIdsWithPendingImages(): List<String> = withContext(ioDispatcher) {
        dao.taskIdsWithAttachmentState(AttachmentState.PENDING.name)
    }

    override suspend fun importPhotoPickerSelection(
        draftId: String,
        uris: List<Uri>,
    ): AttachmentImportBatchResult = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        if (uris.isEmpty()) return@withContext AttachmentImportBatchResult(emptyList(), emptyList())
        mutationMutex.withLock {
            val result = importSelection(
                draftId = draftId,
                taskId = null,
                inputs = uris.mapIndexed { index, uri ->
                    AttachmentSelection(index, uri, AttachmentKind.IMAGE)
                },
                source = AttachmentSource.PHOTO_PICKER,
            )
            AttachmentImportBatchResult(result.imported.map(::validatedRecord), result.failures)
        }
    }

    override suspend fun importOpenDocument(
        draftId: String,
        uri: Uri,
    ): AttachmentImportBatchResult = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        mutationMutex.withLock {
            val result = importSelection(
                draftId = draftId,
                taskId = null,
                inputs = listOf(AttachmentSelection(0, uri, AttachmentKind.TEXT_FILE)),
                source = AttachmentSource.OPEN_DOCUMENT,
            )
            AttachmentImportBatchResult(result.imported.map(::validatedRecord), result.failures)
        }
    }

    override suspend fun prepareCameraCapture(draftId: String): CameraCaptureRequest =
        withContext(ioDispatcher) {
            requireToken(draftId, "draftId")
            mutationMutex.withLock { cameraCaptures.prepare(draftId) }
        }

    override suspend fun completeCameraCapture(
        draftId: String,
        captureId: String,
        captured: Boolean,
    ): AttachmentImportBatchResult = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        requireUuid(captureId)
        mutationMutex.withLock {
            if (!captured) {
                cameraCaptures.discard(draftId, captureId)
                return@withLock AttachmentImportBatchResult(emptyList(), emptyList())
            }
            val attachmentId = deterministicCameraAttachmentId(captureId)
            dao.attachment(attachmentId)?.let { existing ->
                check(
                    existing.draftId == draftId &&
                        existing.taskId == null &&
                        existing.source == AttachmentSource.CAMERA.name &&
                        existing.kind == AttachmentKind.IMAGE.name,
                ) { "CAMERA_ATTACHMENT_ID_COLLISION" }
                cameraCaptures.discard(draftId, captureId)
                return@withLock AttachmentImportBatchResult(
                    imported = listOf(validatedRecord(existing)),
                    failures = emptyList(),
                )
            }
            val output = cameraCaptures.pendingFile(draftId, captureId)
            if (output == null) {
                cameraCaptures.discard(draftId, captureId)
                return@withLock AttachmentImportBatchResult(
                    imported = emptyList(),
                    failures = listOf(
                        AttachmentImportFailure(
                            sourceIndex = 0,
                            code = AttachmentImportFailureCode.READ_FAILED,
                            safeMessage = "The captured photo is no longer available on this phone.",
                        ),
                    ),
                )
            }
            try {
                val result = importSelection(
                    draftId = draftId,
                    taskId = null,
                    inputs = listOf(
                        AttachmentSelection(
                            sourceIndex = 0,
                            uri = Uri.fromFile(output),
                            kind = AttachmentKind.IMAGE,
                            attachmentId = attachmentId,
                            displayNameOverride = "Camera photo.jpg",
                            declaredMimeTypeOverride = "image/jpeg",
                            declaredSizeOverride = output.length(),
                            openStreamOverride = output::inputStream,
                        ),
                    ),
                    source = AttachmentSource.CAMERA,
                    allowUriNameFallback = false,
                )
                AttachmentImportBatchResult(result.imported.map(::validatedRecord), result.failures)
            } finally {
                cameraCaptures.discard(draftId, captureId)
            }
        }
    }

    suspend fun importSharedSelection(
        draftId: String,
        receiptId: String,
        inputs: List<SharedAttachmentInput>,
    ): AttachmentImportBatchResult = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        requireUuid(receiptId)
        require(inputs.map(SharedAttachmentInput::sourceIndex).distinct().size == inputs.size) {
            "SHARE_SOURCE_INDEX_DUPLICATE"
        }
        mutationMutex.withLock {
            val result = importSelection(
                draftId = draftId,
                taskId = null,
                inputs = inputs.map { input ->
                    AttachmentSelection(
                        sourceIndex = input.sourceIndex,
                        uri = input.uri,
                        kind = input.kind,
                        attachmentId = deterministicShareAttachmentId(
                            receiptId,
                            input.sourceIndex,
                        ),
                    )
                },
                source = AttachmentSource.ANDROID_SHARE,
                allowUriNameFallback = false,
            )
            AttachmentImportBatchResult(result.imported.map(::validatedRecord), result.failures)
        }
    }

    override fun observeTaskStagedAttachments(taskId: String): Flow<List<TaskAttachmentRecord>> {
        requireToken(taskId, "taskId")
        return dao.observeTaskStagedAttachments(taskId)
            .map { entities -> entities.map(::validatedTaskRecord) }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    override fun observeTaskGeneratedImages(taskId: String): Flow<List<TaskAttachmentRecord>> {
        requireToken(taskId, "taskId")
        return dao.observeTaskAttachmentsBySource(taskId, AttachmentSource.GENERATED_IMAGE.name)
            .map { entities -> entities.map(::validatedTaskRecord) }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    override suspend fun importGeneratedImage(
        taskId: String,
        toolCallId: String,
        displayName: String,
        bytes: ByteArray,
        declaredMimeType: String,
    ): TaskAttachmentRecord = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        requireToken(toolCallId, "toolCallId")
        require(bytes.isNotEmpty()) { "GENERATED_IMAGE_EMPTY" }
        val attachmentId = deterministicGeneratedAttachmentId(taskId, toolCallId)
        mutationMutex.withLock {
            dao.attachment(attachmentId)?.let { existing ->
                check(
                    existing.taskId == taskId &&
                        existing.messageLocalId == toolCallId &&
                        existing.source == AttachmentSource.GENERATED_IMAGE.name &&
                        existing.kind == AttachmentKind.IMAGE.name,
                ) { "GENERATED_IMAGE_ID_COLLISION" }
                return@withLock validatedTaskRecord(existing)
            }
            val safeName = sanitizeDisplayName(displayName, "Generated image.png")
            val stored = store.import(
                attachmentId = attachmentId,
                kind = AttachmentKind.IMAGE,
                source = AttachmentSourceDescriptor(
                    displayName = safeName,
                    declaredMimeType = declaredMimeType,
                    declaredSize = bytes.size.toLong(),
                    openStream = { ByteArrayInputStream(bytes) },
                ),
            )
            try {
                check(stored.mimeType == declaredMimeType) { "GENERATED_IMAGE_MIME_MISMATCH" }
                val timestamp = nowMillis()
                val entity = AttachmentEntity(
                    attachmentId = attachmentId,
                    draftId = null,
                    taskId = taskId,
                    messageLocalId = toolCallId,
                    ordinal = 0,
                    kind = AttachmentKind.IMAGE.name,
                    state = AttachmentState.SENT.name,
                    source = AttachmentSource.GENERATED_IMAGE.name,
                    displayName = safeName,
                    mimeType = stored.mimeType,
                    byteSize = stored.byteSize,
                    payloadSha256 = stored.sha256,
                    payloadFileName = stored.payloadFileName,
                    thumbnailFileName = stored.thumbnailFileName,
                    width = stored.width,
                    height = stored.height,
                    createdAtMillis = timestamp,
                    updatedAtMillis = timestamp,
                )
                dao.insertAttachment(entity)
                validatedTaskRecord(entity)
            } catch (error: Throwable) {
                store.delete(stored.payloadFileName, stored.thumbnailFileName)
                throw error
            }
        }
    }

    override suspend fun discardGeneratedImage(taskId: String, attachmentId: String): Boolean =
        withContext(ioDispatcher) {
            requireToken(taskId, "taskId")
            requireUuid(attachmentId)
            mutationMutex.withLock {
                val existing = dao.attachment(attachmentId)
                    ?.takeIf {
                        it.taskId == taskId && it.source == AttachmentSource.GENERATED_IMAGE.name
                    }
                    ?: return@withLock false
                check(
                    dao.deleteTaskAttachmentBySource(
                        taskId,
                        attachmentId,
                        AttachmentSource.GENERATED_IMAGE.name,
                    ) == 1,
                ) { "GENERATED_IMAGE_DELETE_LOST" }
                store.delete(existing.payloadFileName, existing.thumbnailFileName)
                generatedImageContentDirectory(attachmentId).deleteRecursively()
                true
            }
        }

    override suspend fun generatedImageBytes(taskId: String, attachmentId: String): ByteArray? =
        withContext(ioDispatcher) {
            val entity = generatedImageEntity(taskId, attachmentId) ?: return@withContext null
            val payload = generatedImagePayload(entity) ?: return@withContext null
            runCatching { payload.readBytes() }
                .getOrNull()
                ?.takeIf { it.size.toLong() == entity.byteSize }
        }

    override suspend fun generatedImageContentUri(taskId: String, attachmentId: String): Uri? =
        withContext(ioDispatcher) {
            val entity = generatedImageEntity(taskId, attachmentId) ?: return@withContext null
            val payload = generatedImagePayload(entity) ?: return@withContext null
            val shareFile = runCatching {
                val shareDirectory = generatedImageContentDirectory(attachmentId)
                check(shareDirectory.exists() || shareDirectory.mkdirs())
                val output = shareDirectory.resolve(generatedImageDisplayName(entity))
                val temporary = shareDirectory.resolve(".${output.name}.tmp")
                temporary.delete()
                try {
                    payload.copyTo(temporary, overwrite = true)
                    check(temporary.length() == entity.byteSize)
                    output.delete()
                    check(temporary.renameTo(output))
                    output
                } finally {
                    temporary.delete()
                }
            }.getOrNull() ?: return@withContext null
            runCatching {
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.attachments",
                    shareFile,
                )
            }.getOrNull()
        }

    override suspend fun saveGeneratedImageToPictures(taskId: String, attachmentId: String): Uri? =
        withContext(ioDispatcher) {
            val entity = generatedImageEntity(taskId, attachmentId) ?: return@withContext null
            val payload = generatedImagePayload(entity) ?: return@withContext null
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, generatedImageDisplayName(entity))
                put(MediaStore.Images.Media.MIME_TYPE, entity.mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Momoding",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val output = runCatching {
                resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                )
            }.getOrNull() ?: return@withContext null
            val saved = runCatching {
                resolver.openOutputStream(output, "w")?.use { stream ->
                    payload.inputStream().buffered().use { input -> input.copyTo(stream) }
                } ?: error("GENERATED_IMAGE_OUTPUT_UNAVAILABLE")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(resolver.update(output, values, null, null) == 1)
            }.isSuccess
            if (!saved) {
                runCatching { resolver.delete(output, null, null) }
                return@withContext null
            }
            output
        }

    private fun generatedImageEntity(taskId: String, attachmentId: String): AttachmentEntity? {
        requireToken(taskId, "taskId")
        requireUuid(attachmentId)
        return dao.attachment(attachmentId)?.takeIf {
            it.taskId == taskId && it.source == AttachmentSource.GENERATED_IMAGE.name
        }
    }

    private fun generatedImagePayload(entity: AttachmentEntity) =
        appContext.filesDir.resolve("attachments/v1/${entity.payloadFileName}")
            .takeIf { it.isFile && it.length() == entity.byteSize }

    private fun generatedImageContentDirectory(attachmentId: String) =
        appContext.cacheDir.resolve("generated-image-content/v1/$attachmentId")

    private fun generatedImageDisplayName(entity: AttachmentEntity): String {
        val extension = when (entity.mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "img"
        }
        val displayName = sanitizeDisplayName(entity.displayName, "Momoding image.$extension")
        return if (displayName.endsWith(".$extension", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.$extension"
        }
    }

    override suspend fun importTaskPhotoPickerSelection(
        taskId: String,
        uris: List<Uri>,
    ): TaskAttachmentImportBatchResult = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        if (uris.isEmpty()) return@withContext TaskAttachmentImportBatchResult(emptyList(), emptyList())
        mutationMutex.withLock {
            val result = importSelection(
                draftId = null,
                taskId = taskId,
                inputs = uris.mapIndexed { index, uri ->
                    AttachmentSelection(index, uri, AttachmentKind.IMAGE)
                },
                source = AttachmentSource.PHOTO_PICKER,
            )
            TaskAttachmentImportBatchResult(result.imported.map(::validatedTaskRecord), result.failures)
        }
    }

    override suspend fun importTaskOpenDocument(
        taskId: String,
        uri: Uri,
    ): TaskAttachmentImportBatchResult = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        mutationMutex.withLock {
            val result = importSelection(
                draftId = null,
                taskId = taskId,
                inputs = listOf(AttachmentSelection(0, uri, AttachmentKind.TEXT_FILE)),
                source = AttachmentSource.OPEN_DOCUMENT,
            )
            TaskAttachmentImportBatchResult(result.imported.map(::validatedTaskRecord), result.failures)
        }
    }

    override suspend fun thumbnailPng(attachmentId: String): ByteArray? = withContext(ioDispatcher) {
        requireToken(attachmentId, "attachmentId")
        val entity = dao.attachment(attachmentId) ?: return@withContext null
        entity.thumbnailFileName?.let(store::readThumbnail)
    }

    override suspend fun removeDraftAttachment(
        draftId: String,
        attachmentId: String,
    ): Boolean = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        requireToken(attachmentId, "attachmentId")
        mutationMutex.withLock {
            val deleted = dao.deleteDraftAttachment(draftId, attachmentId) ?: return@withLock false
            store.delete(deleted.payloadFileName, deleted.thumbnailFileName)
            true
        }
    }

    override suspend fun removeTaskStagedAttachment(
        taskId: String,
        attachmentId: String,
    ): Boolean = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        requireToken(attachmentId, "attachmentId")
        mutationMutex.withLock {
            val deleted = dao.deleteTaskStagedAttachment(taskId, attachmentId)
                ?: return@withLock false
            store.delete(deleted.payloadFileName, deleted.thumbnailFileName)
            true
        }
    }

    suspend fun claimDraftImages(
        draftId: String,
        taskId: String,
        messageLocalId: String,
    ): List<PiRuntimeImageInput> {
        val prepared = prepareDraftImages(draftId)
        return claimPreparedDraftImages(draftId, taskId, messageLocalId, prepared)
    }

    suspend fun prepareDraftAttachments(draftId: String): RuntimeAttachmentBatch =
        withContext(ioDispatcher) {
            requireToken(draftId, "draftId")
            mutationMutex.withLock { runtimeAttachments(dao.draftAttachments(draftId)) }
        }

    suspend fun claimPreparedDraftAttachments(
        draftId: String,
        taskId: String,
        messageLocalId: String,
        prepared: RuntimeAttachmentBatch,
    ): RuntimeAttachmentBatch = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        requireToken(taskId, "taskId")
        requireToken(messageLocalId, "messageLocalId")
        mutationMutex.withLock {
            val entities = dao.draftAttachments(draftId)
            check(entities.map(AttachmentEntity::attachmentId) == prepared.attachmentIds) {
                "ATTACHMENT_PREPARED_SET_CHANGED"
            }
            if (entities.isNotEmpty()) {
                database.runInTransaction {
                    check(
                        dao.bindDraftAttachments(
                            draftId = draftId,
                            taskId = taskId,
                            messageLocalId = messageLocalId,
                            state = AttachmentState.PENDING.name,
                            updatedAtMillis = nowMillis(),
                        ) == entities.size,
                    ) { "ATTACHMENT_BINDING_LOST" }
                }
            }
            prepared
        }
    }

    suspend fun claimTaskStagedAttachments(
        taskId: String,
        messageLocalId: String,
    ): RuntimeAttachmentBatch = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        requireToken(messageLocalId, "messageLocalId")
        mutationMutex.withLock {
            val entities = dao.taskStagedAttachments(taskId)
            val attachments = runtimeAttachments(entities)
            if (entities.isNotEmpty()) {
                database.runInTransaction {
                    check(
                        dao.bindTaskStagedAttachments(
                            taskId = taskId,
                            messageLocalId = messageLocalId,
                            state = AttachmentState.PENDING.name,
                            updatedAtMillis = nowMillis(),
                        ) == entities.size,
                    ) { "ATTACHMENT_BINDING_LOST" }
                }
            }
            attachments
        }
    }

    suspend fun prepareDraftImages(draftId: String): List<PiRuntimeImageInput> =
        withContext(ioDispatcher) {
            requireToken(draftId, "draftId")
            mutationMutex.withLock {
                runtimeImages(dao.draftAttachments(draftId))
            }
        }

    suspend fun claimPreparedDraftImages(
        draftId: String,
        taskId: String,
        messageLocalId: String,
        prepared: List<PiRuntimeImageInput>,
    ): List<PiRuntimeImageInput> = withContext(ioDispatcher) {
        requireToken(draftId, "draftId")
        requireToken(taskId, "taskId")
        requireToken(messageLocalId, "messageLocalId")
        mutationMutex.withLock {
            val entities = dao.draftAttachments(draftId)
            check(entities.map(AttachmentEntity::attachmentId) == prepared.map(PiRuntimeImageInput::attachmentId)) {
                "ATTACHMENT_PREPARED_SET_CHANGED"
            }
            if (entities.isNotEmpty()) {
                check(entities.all { it.kind == AttachmentKind.IMAGE.name }) {
                    "PI_MOBILE_TEXT_ATTACHMENT_RUNTIME_NOT_READY"
                }
                database.runInTransaction {
                    check(
                        dao.bindDraftAttachments(
                            draftId = draftId,
                            taskId = taskId,
                            messageLocalId = messageLocalId,
                            state = AttachmentState.PENDING.name,
                            updatedAtMillis = nowMillis(),
                        ) == entities.size,
                    ) { "ATTACHMENT_BINDING_LOST" }
                }
            }
            prepared
        }
    }

    suspend fun claimTaskStagedImages(
        taskId: String,
        messageLocalId: String,
    ): List<PiRuntimeImageInput> = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        requireToken(messageLocalId, "messageLocalId")
        mutationMutex.withLock {
            val entities = dao.taskStagedAttachments(taskId)
            val images = runtimeImages(entities)
            if (entities.isNotEmpty()) {
                check(entities.all { it.kind == AttachmentKind.IMAGE.name }) {
                    "PI_MOBILE_TEXT_ATTACHMENT_RUNTIME_NOT_READY"
                }
                database.runInTransaction {
                    check(
                        dao.bindTaskStagedAttachments(
                            taskId = taskId,
                            messageLocalId = messageLocalId,
                            state = AttachmentState.PENDING.name,
                            updatedAtMillis = nowMillis(),
                        ) == entities.size,
                    ) { "ATTACHMENT_BINDING_LOST" }
                }
            }
            images
        }
    }

    suspend fun runtimeImagesForTask(
        taskId: String,
        attachmentIds: Set<String>,
    ): List<PiRuntimeImageInput> = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        if (attachmentIds.isEmpty()) return@withContext emptyList()
        attachmentIds.forEach { requireUuid(it) }
        mutationMutex.withLock {
            val byId = dao.taskAttachments(taskId).associateBy(AttachmentEntity::attachmentId)
            val exact = attachmentIds.map { attachmentId ->
                requireNotNull(byId[attachmentId]) { "PI_MOBILE_IMAGE_SESSION_REFERENCE_MISSING" }
            }
            runtimeImages(exact)
        }
    }

    suspend fun readTaskTextAttachment(
        taskId: String,
        attachmentId: String,
        offset: Long,
        limit: Int,
    ): TaskTextAttachmentPage = withContext(ioDispatcher) {
        requireToken(taskId, "taskId")
        requireUuid(attachmentId)
        require(offset >= 0) { "ATTACHMENT_OFFSET_INVALID" }
        require(limit in MIN_TEXT_READ_BYTES..MAX_TEXT_READ_BYTES) { "ATTACHMENT_LIMIT_INVALID" }
        val entity = dao.attachment(attachmentId)
        if (
            entity == null || entity.taskId != taskId || entity.kind != AttachmentKind.TEXT_FILE.name ||
            entity.state !in setOf(AttachmentState.PENDING.name, AttachmentState.SENT.name) ||
            entity.messageLocalId.isNullOrBlank()
        ) {
            throw AttachmentReadException(
                "ATTACHMENT_NOT_AVAILABLE",
                "That text attachment is not available in this task.",
            )
        }
        val page = try {
            store.runtimeTextPage(
                payloadFileName = entity.payloadFileName,
                expectedBytes = entity.byteSize,
                expectedSha256 = entity.payloadSha256,
                offset = offset,
                limit = limit,
            )
        } catch (error: AttachmentPayloadException) {
            throw AttachmentReadException(
                "ATTACHMENT_CONTENT_UNAVAILABLE",
                error.message ?: "The text attachment could not be read on this phone.",
            )
        }
        TaskTextAttachmentPage(
            attachmentId = entity.attachmentId,
            displayName = entity.displayName,
            mimeType = entity.mimeType,
            totalBytes = entity.byteSize,
            offset = offset,
            nextOffset = page.nextOffset,
            eof = page.eof,
            content = page.content,
        )
    }

    suspend fun reconcileTaskImages(taskId: String, referencedAttachmentIds: Set<String>) =
        withContext(ioDispatcher) {
            requireToken(taskId, "taskId")
            referencedAttachmentIds.forEach { requireUuid(it) }
            mutationMutex.withLock {
                val entities = dao.taskAttachments(taskId)
                val existingIds = entities.mapTo(mutableSetOf(), AttachmentEntity::attachmentId)
                check(referencedAttachmentIds.all(existingIds::contains)) {
                    "PI_MOBILE_IMAGE_SESSION_REFERENCE_MISSING"
                }
                database.runInTransaction {
                    if (referencedAttachmentIds.isNotEmpty()) {
                        check(
                            dao.updateTaskAttachmentStates(
                                taskId = taskId,
                                attachmentIds = referencedAttachmentIds.toList(),
                                state = AttachmentState.SENT.name,
                                updatedAtMillis = nowMillis(),
                            ) == referencedAttachmentIds.size,
                        ) { "ATTACHMENT_STATE_UPDATE_LOST" }
                    }
                    val unreferencedPendingIds = entities
                        .filter { entity ->
                            entity.state == AttachmentState.PENDING.name &&
                                entity.attachmentId !in referencedAttachmentIds
                        }
                        .map(AttachmentEntity::attachmentId)
                    if (unreferencedPendingIds.isNotEmpty()) {
                        check(
                            dao.releasePendingTaskAttachmentIds(
                                taskId = taskId,
                                attachmentIds = unreferencedPendingIds,
                                pendingState = AttachmentState.PENDING.name,
                                stagedState = AttachmentState.STAGED.name,
                                updatedAtMillis = nowMillis(),
                            ) == unreferencedPendingIds.size,
                        ) { "ATTACHMENT_STATE_UPDATE_LOST" }
                    }
                }
            }
        }

    suspend fun releasePendingTaskImages(taskId: String, messageLocalId: String): Int =
        withContext(ioDispatcher) {
            requireToken(taskId, "taskId")
            requireToken(messageLocalId, "messageLocalId")
            database.runInTransaction<Int> {
                dao.releasePendingTaskAttachments(
                    taskId = taskId,
                    messageLocalId = messageLocalId,
                    pendingState = AttachmentState.PENDING.name,
                    stagedState = AttachmentState.STAGED.name,
                    updatedAtMillis = nowMillis(),
                )
            }
        }

    suspend fun pruneOrphanedPayloads(): Int = withContext(ioDispatcher) {
        mutationMutex.withLock {
            val referenced = dao.allAttachments().flatMapTo(mutableSetOf()) { entity ->
                buildList {
                    add(entity.payloadFileName)
                    entity.thumbnailFileName?.let(::add)
                }
            }
            store.pruneOrphans(referenced) + cameraCaptures.pruneStale()
        }
    }

    private fun runtimeImages(entities: List<AttachmentEntity>): List<PiRuntimeImageInput> {
        val images = entities.map { entity ->
            check(entity.kind == AttachmentKind.IMAGE.name) {
                "PI_MOBILE_TEXT_ATTACHMENT_RUNTIME_NOT_READY"
            }
            val bytes = store.runtimeImage(
                payloadFileName = entity.payloadFileName,
                expectedBytes = entity.byteSize,
                expectedSha256 = entity.payloadSha256,
            )
            PiRuntimeImageInput(
                attachmentId = entity.attachmentId,
                mimeType = "image/jpeg",
                data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
        return images
    }

    private fun runtimeAttachments(entities: List<AttachmentEntity>): RuntimeAttachmentBatch {
        check(entities.none { it.kind == AttachmentKind.VIDEO.name }) {
            "PI_MOBILE_VIDEO_ATTACHMENT_RUNTIME_NOT_READY"
        }
        val images = runtimeImages(entities.filter { it.kind == AttachmentKind.IMAGE.name })
        val textFiles = entities.filter { it.kind == AttachmentKind.TEXT_FILE.name }.map { entity ->
            // Validate the exact private payload and hash before binding it to a Pi message.
            store.runtimeTextPage(
                payloadFileName = entity.payloadFileName,
                expectedBytes = entity.byteSize,
                expectedSha256 = entity.payloadSha256,
                offset = 0,
                limit = MIN_TEXT_READ_BYTES,
            )
            PiRuntimeTextAttachmentInput(
                attachmentId = entity.attachmentId,
                displayName = entity.displayName,
                mimeType = entity.mimeType,
                byteSize = entity.byteSize,
            )
        }
        return RuntimeAttachmentBatch(
            images = images,
            textFiles = textFiles,
            attachmentIds = entities.map(AttachmentEntity::attachmentId),
        )
    }

    private fun importSelection(
        draftId: String?,
        taskId: String?,
        inputs: List<AttachmentSelection>,
        source: AttachmentSource,
        allowUriNameFallback: Boolean = true,
    ): ImportedAttachmentEntities {
        check((draftId == null) != (taskId == null)) { "ATTACHMENT_OWNER_INVALID" }
        val imported = mutableListOf<AttachmentEntity>()
        val failures = mutableListOf<AttachmentImportFailure>()
        inputs.forEach { input ->
            val index = input.sourceIndex
            val uri = input.uri
            val kind = input.kind
            try {
                val attachmentId = (input.attachmentId ?: idFactory()).also { requireUuid(it) }
                val existing = dao.attachment(attachmentId)
                if (existing != null) {
                    check(
                        existing.draftId == draftId &&
                            existing.taskId == taskId &&
                            existing.source == source.name &&
                            existing.kind == kind.name
                    ) { "SHARE_ATTACHMENT_ID_COLLISION" }
                    imported += existing
                    return@forEach
                }
                val metadata = if (input.displayNameOverride != null) {
                    SourceMetadata(
                        displayName = sanitizeDisplayName(input.displayNameOverride, "Attachment"),
                        mimeType = input.declaredMimeTypeOverride,
                        size = input.declaredSizeOverride?.takeIf { it >= 0 },
                    )
                } else {
                    metadata(uri, kind, allowUriNameFallback)
                }
                val stored = store.import(
                    attachmentId = attachmentId,
                    kind = kind,
                    source = AttachmentSourceDescriptor(
                        displayName = metadata.displayName,
                        declaredMimeType = metadata.mimeType,
                        declaredSize = metadata.size,
                        openStream = input.openStreamOverride ?: {
                            resolver.openInputStream(uri) ?: throw FileNotFoundException()
                        },
                    ),
                )
                val timestamp = nowMillis()
                val candidate = AttachmentEntity(
                    attachmentId = attachmentId,
                    draftId = draftId,
                    taskId = taskId,
                    messageLocalId = null,
                    ordinal = -1,
                    kind = kind.name,
                    state = AttachmentState.STAGED.name,
                    source = source.name,
                    displayName = metadata.displayName,
                    mimeType = stored.mimeType,
                    byteSize = stored.byteSize,
                    payloadSha256 = stored.sha256,
                    payloadFileName = stored.payloadFileName,
                    thumbnailFileName = stored.thumbnailFileName,
                    width = stored.width,
                    height = stored.height,
                    createdAtMillis = timestamp,
                    updatedAtMillis = timestamp,
                )
                try {
                    val inserted = if (draftId != null) {
                        dao.insertStagedDraftAttachment(
                            candidate,
                            maxCount = MAX_DRAFT_ATTACHMENTS,
                            maxTotalBytes = MAX_DRAFT_ATTACHMENT_BYTES,
                        )
                    } else {
                        dao.insertStagedTaskAttachment(
                            candidate,
                            maxCount = MAX_DRAFT_ATTACHMENTS,
                            maxTotalBytes = MAX_DRAFT_ATTACHMENT_BYTES,
                        )
                    }
                    imported += inserted
                } catch (cancelled: CancellationException) {
                    store.delete(stored.payloadFileName, stored.thumbnailFileName)
                    throw cancelled
                } catch (error: IllegalStateException) {
                    store.delete(stored.payloadFileName, stored.thumbnailFileName)
                    throw error
                } catch (error: Exception) {
                    store.delete(stored.payloadFileName, stored.thumbnailFileName)
                    throw AttachmentDatabaseException(error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: AttachmentPayloadException) {
                failures += AttachmentImportFailure(index, known.code, known.message.orEmpty())
            } catch (_: AttachmentDatabaseException) {
                failures += AttachmentImportFailure(
                    index,
                    AttachmentImportFailureCode.STORAGE_FAILED,
                    AttachmentImportFailureCode.STORAGE_FAILED.safeMessage(),
                )
            } catch (error: IllegalStateException) {
                val code = when (error.message) {
                    "ATTACHMENT_COUNT_LIMIT" -> AttachmentImportFailureCode.COUNT_LIMIT
                    "ATTACHMENT_TOTAL_BYTES_LIMIT" -> AttachmentImportFailureCode.TOTAL_BYTES_LIMIT
                    else -> AttachmentImportFailureCode.STORAGE_FAILED
                }
                failures += AttachmentImportFailure(index, code, code.safeMessage())
            } catch (_: Exception) {
                failures += AttachmentImportFailure(
                    index,
                    AttachmentImportFailureCode.READ_FAILED,
                    AttachmentImportFailureCode.READ_FAILED.safeMessage(),
                )
            }
        }
        return ImportedAttachmentEntities(imported, failures)
    }

    private fun metadata(
        uri: Uri,
        kind: AttachmentKind,
        allowUriNameFallback: Boolean = true,
    ): SourceMetadata {
        var displayName: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val fallback = when (kind) {
            AttachmentKind.IMAGE -> "Selected image"
            AttachmentKind.TEXT_FILE -> "Selected file"
            AttachmentKind.VIDEO -> "Shared video"
        }
        return SourceMetadata(
            displayName = sanitizeDisplayName(
                displayName ?: uri.lastPathSegment.takeIf { allowUriNameFallback },
                fallback,
            ),
            mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
            size = size?.takeIf { it >= 0 },
        )
    }

    private fun validatedRecord(entity: AttachmentEntity): AttachmentRecord {
        requireUuid(entity.attachmentId)
        val draftId = requireNotNull(entity.draftId) { "ATTACHMENT_OWNER_MISSING" }
        requireToken(draftId, "draftId")
        check(entity.taskId == null && entity.messageLocalId == null) { "ATTACHMENT_OWNER_INVALID" }
        check(entity.ordinal >= 0) { "ATTACHMENT_ORDINAL_INVALID" }
        check(entity.byteSize > 0) { "ATTACHMENT_BYTES_INVALID" }
        check(SHA256.matches(entity.payloadSha256)) { "ATTACHMENT_HASH_INVALID" }
        return AttachmentRecord(
            attachmentId = entity.attachmentId,
            draftId = draftId,
            ordinal = entity.ordinal,
            kind = AttachmentKind.valueOf(entity.kind),
            state = AttachmentState.valueOf(entity.state),
            source = AttachmentSource.valueOf(entity.source),
            displayName = sanitizeDisplayName(entity.displayName, "Attachment"),
            mimeType = entity.mimeType,
            byteSize = entity.byteSize,
            payloadSha256 = entity.payloadSha256,
            hasThumbnail = entity.thumbnailFileName != null,
            width = entity.width,
            height = entity.height,
            createdAtMillis = entity.createdAtMillis,
        )
    }

    private fun validatedTaskRecord(entity: AttachmentEntity): TaskAttachmentRecord {
        requireUuid(entity.attachmentId)
        val taskId = requireNotNull(entity.taskId) { "ATTACHMENT_OWNER_MISSING" }
        requireToken(taskId, "taskId")
        check(entity.draftId == null) { "ATTACHMENT_OWNER_INVALID" }
        entity.messageLocalId?.let { requireToken(it, "messageLocalId") }
        check(entity.ordinal >= 0) { "ATTACHMENT_ORDINAL_INVALID" }
        check(entity.byteSize > 0) { "ATTACHMENT_BYTES_INVALID" }
        check(SHA256.matches(entity.payloadSha256)) { "ATTACHMENT_HASH_INVALID" }
        return TaskAttachmentRecord(
            attachmentId = entity.attachmentId,
            taskId = taskId,
            messageLocalId = entity.messageLocalId,
            ordinal = entity.ordinal,
            kind = AttachmentKind.valueOf(entity.kind),
            state = AttachmentState.valueOf(entity.state),
            source = AttachmentSource.valueOf(entity.source),
            displayName = sanitizeDisplayName(entity.displayName, "Attachment"),
            mimeType = entity.mimeType,
            byteSize = entity.byteSize,
            payloadSha256 = entity.payloadSha256,
            hasThumbnail = entity.thumbnailFileName != null,
            width = entity.width,
            height = entity.height,
            createdAtMillis = entity.createdAtMillis,
        )
    }

    private data class SourceMetadata(val displayName: String, val mimeType: String?, val size: Long?)

    private data class AttachmentSelection(
        val sourceIndex: Int,
        val uri: Uri,
        val kind: AttachmentKind,
        val attachmentId: String? = null,
        val displayNameOverride: String? = null,
        val declaredMimeTypeOverride: String? = null,
        val declaredSizeOverride: Long? = null,
        val openStreamOverride: (() -> InputStream)? = null,
    )

    private data class ImportedAttachmentEntities(
        val imported: List<AttachmentEntity>,
        val failures: List<AttachmentImportFailure>,
    )

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

private fun deterministicShareAttachmentId(receiptId: String, sourceIndex: Int): String {
    require(sourceIndex >= 0) { "sourceIndex is invalid" }
    return UUID.nameUUIDFromBytes(
        "momoding-share-v1:$receiptId:$sourceIndex".toByteArray(StandardCharsets.UTF_8),
    ).toString()
}

private fun deterministicCameraAttachmentId(captureId: String): String = UUID.nameUUIDFromBytes(
    "momoding-camera-v1:$captureId".toByteArray(StandardCharsets.UTF_8),
).toString()

private fun deterministicGeneratedAttachmentId(taskId: String, toolCallId: String): String =
    UUID.nameUUIDFromBytes(
        "momoding-generated-image-v1:$taskId:$toolCallId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

data class RuntimeAttachmentBatch(
    val images: List<PiRuntimeImageInput>,
    val textFiles: List<PiRuntimeTextAttachmentInput>,
    val attachmentIds: List<String>,
)

data class TaskTextAttachmentPage(
    val attachmentId: String,
    val displayName: String,
    val mimeType: String,
    val totalBytes: Long,
    val offset: Long,
    val nextOffset: Long,
    val eof: Boolean,
    val content: String,
)

class AttachmentReadException(
    val code: String,
    safeMessage: String,
) : IllegalStateException(safeMessage)

const val MIN_TEXT_READ_BYTES: Int = 256
const val MAX_TEXT_READ_BYTES: Int = 65_536

private class AttachmentDatabaseException(cause: Exception) : Exception(cause)

private fun AttachmentImportFailureCode.safeMessage(): String = when (this) {
    AttachmentImportFailureCode.COUNT_LIMIT -> "A task can include up to five attachments."
    AttachmentImportFailureCode.TOTAL_BYTES_LIMIT -> "The staged attachments exceed the local size limit."
    AttachmentImportFailureCode.ITEM_TOO_LARGE -> "The selected item is larger than the local attachment limit."
    AttachmentImportFailureCode.UNSUPPORTED_TYPE -> "The selected item type is not supported yet."
    AttachmentImportFailureCode.INVALID_CONTENT -> "The selected item could not be validated."
    AttachmentImportFailureCode.READ_FAILED -> "The selected item could not be read."
    AttachmentImportFailureCode.STORAGE_FAILED -> "The selected item could not be saved on this phone."
}

private fun sanitizeDisplayName(value: String?, fallback: String): String {
    val leaf = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val sanitized = leaf.filterNot { it.isISOControl() }.trim().take(120)
    return sanitized.ifBlank { fallback }
}

private fun requireToken(value: String, field: String) {
    require(value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }) {
        "$field is invalid"
    }
}

private fun requireUuid(value: String) {
    require(ATTACHMENT_UUID.matches(value)) { "attachmentId is invalid" }
}

private val ATTACHMENT_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
