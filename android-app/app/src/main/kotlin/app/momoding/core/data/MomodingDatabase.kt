package app.momoding.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.db.SupportSQLiteDatabase

const val MOMODING_DATABASE_SCHEMA_VERSION: Int = 16

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_1_2_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_1_2_STATEMENTS = listOf(
    "ALTER TABLE tasks ADD COLUMN listedByHost INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE tasks ADD COLUMN lastListSyncGeneration TEXT",
    "ALTER TABLE tasks ADD COLUMN lastListRevision INTEGER",
    "ALTER TABLE tasks ADD COLUMN hostUpdatedAtMillis INTEGER",
    "UPDATE tasks SET hostUpdatedAtMillis = updatedAtMillis",
)

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_2_3_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_2_3_STATEMENTS = listOf(
    // v2 never persisted selection. Zero is the only honest and UTF-16-safe legacy value.
    "ALTER TABLE drafts ADD COLUMN selectionStart INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE drafts ADD COLUMN selectionEnd INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE drafts ADD COLUMN attemptOrdinal INTEGER NOT NULL DEFAULT 0",
)

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_3_4_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_3_4_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS host_pending_attention_observations (" +
        "taskId TEXT NOT NULL, ordinal INTEGER NOT NULL, callId TEXT, toolName TEXT, " +
        "argumentsJson TEXT, state TEXT, expiresAt TEXT, originFocusKey TEXT, " +
        "terminalResultJson TEXT, rawPayload TEXT NOT NULL, PRIMARY KEY(taskId, ordinal), " +
        "FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "INSERT INTO host_pending_attention_observations(" +
        "taskId,ordinal,callId,toolName,argumentsJson,state,expiresAt,originFocusKey," +
        "terminalResultJson,rawPayload) SELECT taskId,ordinal,callId,toolName,argumentsJson," +
        "state,expiresAt,originFocusKey,terminalResultJson,rawPayload FROM pending_attention",
    "DROP TABLE pending_attention",
    "CREATE INDEX IF NOT EXISTS index_host_pending_attention_observations_taskId " +
        "ON host_pending_attention_observations(taskId)",
    "CREATE INDEX IF NOT EXISTS index_host_pending_attention_observations_callId " +
        "ON host_pending_attention_observations(callId)",
    "CREATE TABLE IF NOT EXISTS host_device_call_observations (" +
        "taskId TEXT NOT NULL, callId TEXT NOT NULL, operationId TEXT, toolName TEXT, " +
        "hostState TEXT NOT NULL, terminalSummaryJson TEXT, ordinal INTEGER NOT NULL, " +
        "PRIMARY KEY(taskId, callId), FOREIGN KEY(taskId) REFERENCES tasks(taskId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "INSERT INTO host_device_call_observations(" +
        "taskId,callId,operationId,toolName,hostState,terminalSummaryJson,ordinal) " +
        "SELECT taskId,callId,operationId,toolName,ledgerState,terminalSummaryJson,ordinal " +
        "FROM device_operations",
    "DROP TABLE device_operations",
    "CREATE INDEX IF NOT EXISTS index_host_device_call_observations_taskId " +
        "ON host_device_call_observations(taskId)",
    "CREATE INDEX IF NOT EXISTS index_host_device_call_observations_operationId " +
        "ON host_device_call_observations(operationId)",
    "CREATE TABLE IF NOT EXISTS device_operations (" +
        "callId TEXT NOT NULL, taskId TEXT NOT NULL, piToolCallId TEXT NOT NULL, " +
        "deviceId TEXT NOT NULL, toolName TEXT NOT NULL, argumentsCanonicalJson TEXT NOT NULL, " +
        "sideEffect INTEGER NOT NULL, operationId TEXT, expiresAt TEXT NOT NULL, " +
        "capabilityVersion INTEGER NOT NULL, requestSha256 TEXT NOT NULL, " +
        "ledgerState TEXT NOT NULL, terminalKind TEXT, terminalFrameCanonicalJson TEXT, " +
        "terminalSha256 TEXT, hostObservationState TEXT, cancelObservationReason TEXT, " +
        "cancelObservedAtMillis INTEGER, deliveryState TEXT NOT NULL, " +
        "progressSequence INTEGER NOT NULL, receivedAtMillis INTEGER NOT NULL, " +
        "updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(callId), " +
        "FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_device_operations_taskId_callId " +
        "ON device_operations(taskId, callId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_device_operations_operationId " +
        "ON device_operations(operationId)",
    "CREATE TABLE IF NOT EXISTS pending_attention (" +
        "callId TEXT NOT NULL, taskId TEXT NOT NULL, toolName TEXT NOT NULL, " +
        "originFocusKey TEXT NOT NULL, responseState TEXT NOT NULL, " +
        "selectedOptionIndex INTEGER, customAnswer TEXT NOT NULL, " +
        "selectionStart INTEGER NOT NULL, selectionEnd INTEGER NOT NULL, " +
        "validationCode TEXT, dismissedAtMillis INTEGER, terminalDisplayJson TEXT, " +
        "updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(callId), " +
        "FOREIGN KEY(taskId, callId) REFERENCES device_operations(taskId, callId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_attention_taskId_callId " +
        "ON pending_attention(taskId, callId)",
    "CREATE INDEX IF NOT EXISTS index_pending_attention_taskId_responseState " +
        "ON pending_attention(taskId, responseState)",
)

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_4_5_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_4_5_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_4_5_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS authorized_folders (" +
        "grantId TEXT NOT NULL, treeUri TEXT NOT NULL, treeUriSha256 TEXT NOT NULL, " +
        "displayName TEXT NOT NULL, authority TEXT NOT NULL, persistedRead INTEGER NOT NULL, " +
        "persistedWrite INTEGER NOT NULL, status TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, " +
        "updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(grantId))",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_authorized_folders_treeUriSha256 " +
        "ON authorized_folders(treeUriSha256)",
)

val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_5_6_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_5_6_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_5_6_STATEMENTS = listOf(
    "ALTER TABLE drafts ADD COLUMN selectedGrantId TEXT DEFAULT NULL",
)

val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_6_7_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_6_7_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_6_7_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS task_content_grants (" +
        "callId TEXT NOT NULL, taskId TEXT NOT NULL, grantId TEXT NOT NULL, " +
        "documentAliasesJson TEXT NOT NULL, mimeTypesJson TEXT NOT NULL, " +
        "perFileByteBudgetsJson TEXT NOT NULL, totalByteBudget INTEGER NOT NULL, " +
        "consumedBytes INTEGER NOT NULL, expiresAtMillis INTEGER NOT NULL, " +
        "revokedAtMillis INTEGER, createdAtMillis INTEGER NOT NULL, " +
        "updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(callId), " +
        "FOREIGN KEY(taskId, callId) REFERENCES device_operations(taskId, callId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_task_content_grants_taskId_callId " +
        "ON task_content_grants(taskId, callId)",
    "CREATE INDEX IF NOT EXISTS index_task_content_grants_grantId " +
        "ON task_content_grants(grantId)",
)

val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_7_8_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_7_8_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_7_8_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS file_change_sets (" +
        "preparedId TEXT NOT NULL, taskId TEXT NOT NULL, grantId TEXT NOT NULL, " +
        "purpose TEXT NOT NULL, planDigest TEXT NOT NULL, operationsCanonicalJson TEXT NOT NULL, " +
        "previewCanonicalJson TEXT NOT NULL, operationCount INTEGER NOT NULL, state TEXT NOT NULL, " +
        "expiresAtMillis INTEGER NOT NULL, commitCallId TEXT, commitOperationId TEXT, " +
        "approvalTokenSha256 TEXT, approvalBindingSha256 TEXT, approvalReceiptId TEXT, " +
        "resultCanonicalJson TEXT, failureCode TEXT, createdAtMillis INTEGER NOT NULL, " +
        "updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(preparedId), " +
        "FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_file_change_sets_taskId ON file_change_sets(taskId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_file_change_sets_commitCallId " +
        "ON file_change_sets(commitCallId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_file_change_sets_commitOperationId " +
        "ON file_change_sets(commitOperationId)",
)

val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_8_9_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_8_9_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_8_9_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS pi_session_snapshots (" +
        "taskId TEXT NOT NULL, piSessionId TEXT NOT NULL, entriesJson TEXT NOT NULL, " +
        "schemaVersion INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, " +
        "PRIMARY KEY(taskId), FOREIGN KEY(taskId) REFERENCES tasks(taskId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
)

val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_9_10_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_9_10_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_9_10_STATEMENTS = listOf(
    "ALTER TABLE tasks ADD COLUMN titleSource TEXT NOT NULL DEFAULT 'LEGACY'",
    "ALTER TABLE tasks ADD COLUMN pinnedAtMillis INTEGER DEFAULT NULL",
    "ALTER TABLE tasks ADD COLUMN archivedAtMillis INTEGER DEFAULT NULL",
)

val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_10_11_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_10_11_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_10_11_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS task_goals (" +
        "taskId TEXT NOT NULL, goalId TEXT NOT NULL, instruction TEXT NOT NULL, " +
        "state TEXT NOT NULL, progressSummary TEXT, progressMarker TEXT, terminalReason TEXT, " +
        "pauseReason TEXT, automaticTurnCount INTEGER NOT NULL, " +
        "lastTurnIndex INTEGER NOT NULL, " +
        "nextTurnClaimed INTEGER NOT NULL, generation INTEGER NOT NULL, " +
        "startedAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, " +
        "PRIMARY KEY(taskId), FOREIGN KEY(taskId) REFERENCES tasks(taskId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_task_goals_goalId ON task_goals(goalId)",
)

val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_11_12_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_11_12_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_11_12_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS task_child_agents (" +
        "taskId TEXT NOT NULL, parentToolCallId TEXT NOT NULL, childId TEXT NOT NULL, " +
        "childName TEXT NOT NULL, instruction TEXT NOT NULL, state TEXT NOT NULL, " +
        "resultSummary TEXT, resultText TEXT, resultTruncated INTEGER NOT NULL, " +
        "terminalReason TEXT, stopReason TEXT, model TEXT, turnCount INTEGER NOT NULL, " +
        "inputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL, " +
        "cacheReadTokens INTEGER NOT NULL, cacheWriteTokens INTEGER NOT NULL, " +
        "contextTokens INTEGER NOT NULL, costUsd REAL NOT NULL, eventTypesJson TEXT NOT NULL, " +
        "eventCount INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, " +
        "PRIMARY KEY(taskId, parentToolCallId), FOREIGN KEY(taskId) REFERENCES tasks(taskId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_task_child_agents_taskId ON task_child_agents(taskId)",
    "CREATE INDEX IF NOT EXISTS index_task_child_agents_taskId_childId " +
        "ON task_child_agents(taskId, childId)",
    "CREATE TABLE IF NOT EXISTS task_child_agent_events (" +
        "taskId TEXT NOT NULL, parentToolCallId TEXT NOT NULL, eventOrdinal INTEGER NOT NULL, " +
        "childId TEXT NOT NULL, childName TEXT NOT NULL, eventType TEXT NOT NULL, " +
        "eventJson TEXT NOT NULL, digest TEXT NOT NULL, " +
        "PRIMARY KEY(taskId, parentToolCallId, eventOrdinal), " +
        "FOREIGN KEY(taskId, parentToolCallId) REFERENCES task_child_agents(taskId, parentToolCallId) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_task_child_agent_events_taskId_parentToolCallId " +
        "ON task_child_agent_events(taskId, parentToolCallId)",
    "CREATE INDEX IF NOT EXISTS index_task_child_agent_events_taskId_childId " +
        "ON task_child_agent_events(taskId, childId)",
)

val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_12_13_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_12_13_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_12_13_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS skills (" +
        "skillId TEXT NOT NULL, source TEXT NOT NULL, name TEXT NOT NULL, " +
        "description TEXT NOT NULL, content TEXT NOT NULL, contentSha256 TEXT NOT NULL, " +
        "disableModelInvocation INTEGER NOT NULL, enabled INTEGER NOT NULL, " +
        "availability TEXT NOT NULL, diagnosticCode TEXT, diagnosticMessage TEXT, " +
        "createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, " +
        "PRIMARY KEY(skillId))",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_skills_name ON skills(name)",
)

val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_13_14_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_13_14_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_13_14_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS attachments (" +
        "attachmentId TEXT NOT NULL, draftId TEXT, taskId TEXT, messageLocalId TEXT, " +
        "ordinal INTEGER NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, source TEXT NOT NULL, " +
        "displayName TEXT NOT NULL, mimeType TEXT NOT NULL, byteSize INTEGER NOT NULL, " +
        "payloadSha256 TEXT NOT NULL, payloadFileName TEXT NOT NULL, thumbnailFileName TEXT, " +
        "width INTEGER, height INTEGER, createdAtMillis INTEGER NOT NULL, " +
        "updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(attachmentId), " +
        "FOREIGN KEY(draftId) REFERENCES drafts(draftId) ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_attachments_draftId ON attachments(draftId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_attachments_draftId_ordinal " +
        "ON attachments(draftId, ordinal)",
    "CREATE INDEX IF NOT EXISTS index_attachments_taskId_messageLocalId " +
        "ON attachments(taskId, messageLocalId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_attachments_taskId_messageLocalId_ordinal " +
        "ON attachments(taskId, messageLocalId, ordinal)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_attachments_payloadFileName " +
        "ON attachments(payloadFileName)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_attachments_thumbnailFileName " +
        "ON attachments(thumbnailFileName)",
)

val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_14_15_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_14_15_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_14_15_STATEMENTS = listOf(
    "ALTER TABLE tasks ADD COLUMN approvalMode TEXT NOT NULL DEFAULT 'REQUEST_APPROVAL'",
    "ALTER TABLE drafts ADD COLUMN approvalMode TEXT NOT NULL DEFAULT 'REQUEST_APPROVAL'",
    // v14 did not enforce one Draft per Task. Keep a deterministic legacy winner and fail closed
    // by unbinding duplicate Drafts before installing the durable uniqueness invariant.
    "UPDATE drafts SET taskId = NULL WHERE taskId IS NOT NULL AND draftId NOT IN " +
        "(SELECT MIN(draftId) FROM drafts WHERE taskId IS NOT NULL GROUP BY taskId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_drafts_taskId ON drafts(taskId)",
)

val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_15_16_STATEMENTS.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_15_16_STATEMENTS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                check(!statement.step()) { "Migration statement unexpectedly returned a row" }
            }
        }
    }
}

private val MIGRATION_15_16_STATEMENTS = listOf(
    "ALTER TABLE tasks ADD COLUMN failureKind TEXT DEFAULT NULL",
    "ALTER TABLE tasks ADD COLUMN failureMessage TEXT DEFAULT NULL",
    "ALTER TABLE tasks ADD COLUMN failureRecovery TEXT DEFAULT NULL",
)

@Database(
    entities = [
        TaskEntity::class,
        PiSessionSnapshotEntity::class,
        TaskGoalEntity::class,
        TaskChildAgentEntity::class,
        TaskChildAgentEventEntity::class,
        SkillEntity::class,
        RawPiEventEntity::class,
        PendingResyncEntity::class,
        StagedRawFrameEntity::class,
        TimelineProjectionEntity::class,
        PendingAttentionEntity::class,
        DeviceOperationEntity::class,
        HostPendingAttentionEntity::class,
        HostDeviceCallObservationEntity::class,
        OutboundCommandEntity::class,
        DraftEntity::class,
        AuthorizedFolderEntity::class,
        TaskContentGrantEntity::class,
        FileChangeSetEntity::class,
        AttachmentEntity::class,
    ],
    version = MOMODING_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(TaskApprovalModeRoomCodec::class)
abstract class MomodingDatabase : RoomDatabase() {
    abstract fun momodingDao(): MomodingDao
    abstract fun skillDao(): SkillDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val SCHEMA_VERSION: Int = MOMODING_DATABASE_SCHEMA_VERSION
        const val DATABASE_NAME: String = "momoding.db"

        fun open(context: Context, name: String = DATABASE_NAME): MomodingDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MomodingDatabase::class.java,
                name,
            )
                .setDriver(BundledSQLiteDriver())
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                )
                .build()
    }
}
