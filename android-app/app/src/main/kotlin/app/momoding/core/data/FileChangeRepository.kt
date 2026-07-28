package app.momoding.core.data

import app.momoding.core.files.FileChangeSetState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class FileChangeItem(
    val operationId: String,
    val kind: String,
    val beforeName: String?,
    val afterName: String?,
    val beforeParentAlias: String?,
    val afterParentAlias: String?,
    val beforeParentDisplayPath: String?,
    val afterParentDisplayPath: String?,
    val mimeType: String?,
    val content: String?,
    val contentByteCount: Int?,
    val resultState: String?,
    val errorCode: String?,
)

data class FileChangeRecord(
    val preparedId: String,
    val taskId: String,
    val commitCallId: String?,
    val purpose: String,
    val planDigest: String,
    val state: FileChangeSetState,
    val expiresAtMillis: Long,
    val items: List<FileChangeItem>,
    val approvalReceiptId: String?,
    val failureCode: String?,
    val grantId: String? = null,
)

sealed interface FileChangeRecordState {
    data object Missing : FileChangeRecordState
    data object Corrupt : FileChangeRecordState
    data class Available(val record: FileChangeRecord) : FileChangeRecordState
}

class FileChangeRepository(
    database: MomodingDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.p2Dao()

    fun observe(taskId: String, commitCallId: String?): Flow<FileChangeRecordState> =
        (commitCallId?.let(dao::observeFileChangeSetByCommitCall)
            ?: dao.observeLatestFileChangeSet(taskId))
            .map { entity ->
                if (entity == null || entity.taskId != taskId) {
                    FileChangeRecordState.Missing
                } else {
                    runCatching { project(entity) }
                        .fold(
                            onSuccess = FileChangeRecordState::Available,
                            onFailure = { FileChangeRecordState.Corrupt },
                        )
                }
            }
            .catch { emit(FileChangeRecordState.Corrupt) }
            .flowOn(ioDispatcher)

    private fun project(entity: FileChangeSetEntity): FileChangeRecord {
        val preview = STRICT_JSON.parseToJsonElement(entity.previewCanonicalJson) as JsonArray
        val operations = (STRICT_JSON.parseToJsonElement(
            entity.operationsCanonicalJson,
        ) as JsonArray).associateBy {
            it.jsonObject().getValue("operationId").jsonPrimitive.content
        }
        val results = entity.resultCanonicalJson?.let { raw ->
            val result = STRICT_JSON.parseToJsonElement(raw) as JsonObject
            (result["results"] as? JsonArray).orEmpty().associateBy {
                it.jsonObject().getValue("operationId").jsonPrimitive.content
            }
        }.orEmpty()
        val items = preview.map { rawItem ->
            val item = rawItem.jsonObject()
            val operationId = item.getValue("operationId").jsonPrimitive.content
            val operation = requireNotNull(operations[operationId]).jsonObject()
            val result = results[operationId]?.jsonObject()
            FileChangeItem(
                operationId = operationId,
                kind = item.getValue("kind").jsonPrimitive.content,
                beforeName = item["beforeName"]?.jsonPrimitive?.contentOrNull,
                afterName = item["afterName"]?.jsonPrimitive?.contentOrNull,
                beforeParentAlias = item["beforeParentAlias"]?.jsonPrimitive?.contentOrNull,
                afterParentAlias = item["afterParentAlias"]?.jsonPrimitive?.contentOrNull,
                beforeParentDisplayPath = item["beforeParentDisplayPath"]
                    ?.jsonPrimitive?.contentOrNull,
                afterParentDisplayPath = item["afterParentDisplayPath"]
                    ?.jsonPrimitive?.contentOrNull,
                mimeType = item["mimeType"]?.jsonPrimitive?.contentOrNull,
                content = operation["content"]?.jsonPrimitive?.contentOrNull,
                contentByteCount = item["contentByteCount"]?.jsonPrimitive
                    ?.contentOrNull?.toIntOrNull(),
                resultState = result?.get("state")?.jsonPrimitive?.contentOrNull,
                errorCode = result?.get("errorCode")?.jsonPrimitive?.contentOrNull,
            )
        }
        require(items.size == entity.operationCount)
        return FileChangeRecord(
            preparedId = entity.preparedId,
            taskId = entity.taskId,
            commitCallId = entity.commitCallId,
            purpose = entity.purpose,
            planDigest = entity.planDigest,
            state = FileChangeSetState.valueOf(entity.state),
            expiresAtMillis = entity.expiresAtMillis,
            items = items,
            approvalReceiptId = entity.approvalReceiptId,
            failureCode = entity.failureCode,
            grantId = entity.grantId,
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObject(): JsonObject =
        this as? JsonObject ?: error("Expected JSON object")

    private companion object {
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
    }
}
