package app.momoding.core.data

// Persistent entities owned by the Momoding Room database.

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import app.momoding.core.policy.TaskApprovalMode

@Entity(tableName = "tasks")
data class TaskEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val title: String,
    val runState: String?,
    val recoveryState: String?,
    val readState: String,
    val attentionState: String,
    val streamId: String?,
    val throughSequence: Long,
    val snapshotVersion: Long?,
    val windowStart: Long,
    val windowEndExclusive: Long,
    val nextStageBatchOrdinal: Long,
    val queueJson: String,
    val piSessionId: String?,
    val isStreaming: Boolean,
    val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "1") val listedByHost: Boolean = true,
    val lastListSyncGeneration: String? = null,
    val lastListRevision: Long? = null,
    val hostUpdatedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "'LEGACY'") val titleSource: String = "LEGACY",
    @ColumnInfo(defaultValue = "NULL") val pinnedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val archivedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "'REQUEST_APPROVAL'")
    val approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
    @ColumnInfo(defaultValue = "NULL") val failureKind: String? = null,
    @ColumnInfo(defaultValue = "NULL") val failureMessage: String? = null,
    @ColumnInfo(defaultValue = "NULL") val failureRecovery: String? = null,
)

/**
 * Last settled native Pi Session for one phone-local task.
 *
 * The raw Pi entries are retained without translating them into an Android runtime protocol.
 * Provider credentials, Authorization headers, SAF URIs, and approval tokens are never inputs.
 */
@Entity(
    tableName = "pi_session_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PiSessionSnapshotEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val piSessionId: String,
    val entriesJson: String,
    val schemaVersion: Int,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "task_goals",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["goalId"], unique = true)],
)
data class TaskGoalEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val goalId: String,
    val instruction: String,
    val state: String,
    val progressSummary: String?,
    val progressMarker: String?,
    val terminalReason: String?,
    val pauseReason: String?,
    val automaticTurnCount: Int,
    val lastTurnIndex: Int,
    val nextTurnClaimed: Boolean,
    val generation: Int,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Durable product projection of one Pi child Agent. Runtime childId is only a live cancel token. */
@Entity(
    tableName = "task_child_agents",
    primaryKeys = ["taskId", "parentToolCallId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index(value = ["taskId", "childId"])],
)
data class TaskChildAgentEntity(
    val taskId: String,
    val parentToolCallId: String,
    val childId: String,
    val childName: String,
    val instruction: String,
    val state: String,
    val resultSummary: String?,
    val resultText: String?,
    val resultTruncated: Boolean,
    val terminalReason: String?,
    val stopReason: String?,
    val model: String?,
    val turnCount: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val contextTokens: Int,
    val costUsd: Double,
    val eventTypesJson: String,
    val eventCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Untranslated Pi child event payload plus its stable parent binding. */
@Entity(
    tableName = "task_child_agent_events",
    primaryKeys = ["taskId", "parentToolCallId", "eventOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = TaskChildAgentEntity::class,
            parentColumns = ["taskId", "parentToolCallId"],
            childColumns = ["taskId", "parentToolCallId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId", "parentToolCallId"]), Index(value = ["taskId", "childId"])],
)
data class TaskChildAgentEventEntity(
    val taskId: String,
    val parentToolCallId: String,
    val eventOrdinal: Int,
    val childId: String,
    val childName: String,
    val eventType: String,
    val eventJson: String,
    val digest: String,
)

@Entity(
    tableName = "raw_pi_events",
    primaryKeys = ["taskId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class RawPiEventEntity(
    val taskId: String,
    val sequence: Long,
    val streamId: String,
    val digest: String,
    val rawEnvelope: ByteArray,
    val eventJson: String,
)

@Entity(
    tableName = "pending_resync",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingResyncEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val reason: String,
    val requestedStreamId: String,
    val currentStreamId: String,
    val snapshotVersion: Long,
)

@Entity(
    tableName = "staged_raw_frames",
    primaryKeys = ["taskId", "batchOrdinal", "frameOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class StagedRawFrameEntity(
    val taskId: String,
    val batchOrdinal: Long,
    val frameOrdinal: Int,
    val batchKind: String,
    val kind: String,
    val rawLogicalFrame: ByteArray,
    val sha256: String,
    val byteCount: Long,
)

@Entity(
    tableName = "timeline_projections",
    primaryKeys = ["taskId", "stableItemId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId", "ordinal"], unique = true)],
)
data class TimelineProjectionEntity(
    val taskId: String,
    val stableItemId: String,
    val ordinal: Long,
    val kind: String,
    val rawPayload: String,
    val presentationJson: String,
)

@Entity(
    tableName = "host_pending_attention_observations",
    primaryKeys = ["taskId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("callId")],
)
data class HostPendingAttentionEntity(
    val taskId: String,
    val ordinal: Int,
    val callId: String?,
    val toolName: String?,
    val argumentsJson: String?,
    val state: String?,
    val expiresAt: String?,
    val originFocusKey: String?,
    val terminalResultJson: String?,
    val rawPayload: String,
)

/** One observable Room transaction for S1. Local operation/attention rows stay one snapshot. */
data class TaskListRowEntity(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val deviceOperations: List<DeviceOperationEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val localAttention: List<PendingAttentionEntity>,
)

/** One atomic Room read model for S3. Children are sorted and validated by TaskDetailRepository. */
data class TaskDetailEntity(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val timeline: List<TimelineProjectionEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val rawEvents: List<RawPiEventEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val deviceOperations: List<DeviceOperationEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val localAttention: List<PendingAttentionEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val outboundCommands: List<OutboundCommandEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val goals: List<TaskGoalEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val childAgents: List<TaskChildAgentEntity>,
)

/** Exact local operation with its one-to-one attention projection, read in one transaction. */
data class LocalAttentionPairEntity(
    @Embedded val operation: DeviceOperationEntity,
    @Relation(parentColumn = "callId", entityColumn = "callId")
    val attention: List<PendingAttentionEntity>,
    @Relation(parentColumn = "taskId", entityColumn = "taskId")
    val outboundCommands: List<OutboundCommandEntity>,
)

@Entity(
    tableName = "device_operations",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "callId"], unique = true),
        Index(value = ["operationId"], unique = true),
    ],
)
data class DeviceOperationEntity(
    @androidx.room.PrimaryKey val callId: String,
    val taskId: String,
    val piToolCallId: String,
    val deviceId: String,
    val toolName: String,
    val argumentsCanonicalJson: String,
    val sideEffect: Boolean,
    val operationId: String?,
    val expiresAt: String,
    val capabilityVersion: Long,
    val requestSha256: String,
    val ledgerState: String,
    val terminalKind: String?,
    val terminalFrameCanonicalJson: String?,
    val terminalSha256: String?,
    val hostObservationState: String?,
    val cancelObservationReason: String?,
    val cancelObservedAtMillis: Long?,
    val deliveryState: String,
    val progressSequence: Long,
    val receivedAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "pending_attention",
    foreignKeys = [
        ForeignKey(
            entity = DeviceOperationEntity::class,
            parentColumns = ["taskId", "callId"],
            childColumns = ["taskId", "callId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "callId"], unique = true),
        Index(value = ["taskId", "responseState"]),
    ],
)
data class PendingAttentionEntity(
    @androidx.room.PrimaryKey val callId: String,
    val taskId: String,
    val toolName: String,
    val originFocusKey: String,
    val responseState: String,
    val selectedOptionIndex: Int?,
    val customAnswer: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val validationCode: String?,
    val dismissedAtMillis: Long?,
    val terminalDisplayJson: String?,
    val updatedAtMillis: Long,
)

/**
 * Android-only content scope created after the user approves one exact device_files_read call.
 *
 * The Host receives only opaque aliases and returned content. SAF URIs and Provider document IDs
 * never leave [AuthorizedFolderEntity] / [AuthorizedFoldersRepository].
 */
@Entity(
    tableName = "task_content_grants",
    foreignKeys = [
        ForeignKey(
            entity = DeviceOperationEntity::class,
            parentColumns = ["taskId", "callId"],
            childColumns = ["taskId", "callId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "callId"], unique = true),
        Index(value = ["grantId"]),
    ],
)
data class TaskContentGrantEntity(
    @androidx.room.PrimaryKey val callId: String,
    val taskId: String,
    val grantId: String,
    val documentAliasesJson: String,
    val mimeTypesJson: String,
    val perFileByteBudgetsJson: String,
    val totalByteBudget: Long,
    val consumedBytes: Long,
    val expiresAtMillis: Long,
    val revokedAtMillis: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Android-authoritative prepared file plan.
 *
 * The normalized operations may contain Agent-authored content for a new file, but never a SAF
 * URI or Provider document ID. Existing files remain addressed only by task-scoped opaque aliases.
 */
@Entity(
    tableName = "file_change_sets",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("taskId"),
        Index(value = ["commitCallId"], unique = true),
        Index(value = ["commitOperationId"], unique = true),
    ],
)
data class FileChangeSetEntity(
    @androidx.room.PrimaryKey val preparedId: String,
    val taskId: String,
    val grantId: String,
    val purpose: String,
    val planDigest: String,
    val operationsCanonicalJson: String,
    val previewCanonicalJson: String,
    val operationCount: Int,
    val state: String,
    val expiresAtMillis: Long,
    val commitCallId: String?,
    val commitOperationId: String?,
    val approvalTokenSha256: String?,
    val approvalBindingSha256: String?,
    val approvalReceiptId: String?,
    val resultCanonicalJson: String?,
    val failureCode: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "host_device_call_observations",
    primaryKeys = ["taskId", "callId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("operationId")],
)
data class HostDeviceCallObservationEntity(
    val taskId: String,
    val callId: String,
    val operationId: String?,
    val toolName: String?,
    val hostState: String,
    val terminalSummaryJson: String?,
    val ordinal: Int,
)

@Entity(
    tableName = "outbound_commands",
    indices = [Index(value = ["commandId"], unique = true)],
)
data class OutboundCommandEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val commandId: String?,
    val kind: String,
    val taskId: String?,
    val canonicalPayload: String,
    val payloadSha256: String,
    val state: String,
    val responseJson: String?,
    val responseSha256: String?,
    val stopFenceState: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "drafts",
    indices = [Index(value = ["taskId"], unique = true)],
)
data class DraftEntity(
    @androidx.room.PrimaryKey val draftId: String,
    val text: String,
    val selectedHostId: String?,
    val selectedModelId: String?,
    val selectedMode: String?,
    @ColumnInfo(defaultValue = "NULL") val selectedGrantId: String? = null,
    val createCommandId: String,
    val promptCommandId: String,
    val taskId: String?,
    val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "0") val selectionStart: Int = 0,
    @ColumnInfo(defaultValue = "0") val selectionEnd: Int = 0,
    @ColumnInfo(defaultValue = "0") val attemptOrdinal: Long = 0,
    @ColumnInfo(defaultValue = "'REQUEST_APPROVAL'")
    val approvalMode: TaskApprovalMode = TaskApprovalMode.REQUEST_APPROVAL,
)

/**
 * App-private SAF grant registry.
 *
 * [treeUri] is intentionally confined to the Android process and never enters Pi/Host frames,
 * UI state, diagnostics, or exported evidence. External consumers receive [grantId] only.
 */
@Entity(
    tableName = "authorized_folders",
    indices = [Index(value = ["treeUriSha256"], unique = true)],
)
data class AuthorizedFolderEntity(
    @androidx.room.PrimaryKey val grantId: String,
    val treeUri: String,
    val treeUriSha256: String,
    val displayName: String,
    val authority: String,
    val persistedRead: Boolean,
    val persistedWrite: Boolean,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
