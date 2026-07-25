package app.momoding.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import app.momoding.wire.DurableTaskProjection
import app.momoding.wire.ProjectionTransactionStore
import app.momoding.wire.ProjectionWriteStage
import app.momoding.wire.RawFrameRetentionPolicy
import app.momoding.wire.StagedBatchMode
import app.momoding.wire.StagedRawFrame
import app.momoding.wire.StagedRawFrameBatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledRoomLargeBlobInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MomodingDatabase::class.java,
    )

    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private val executor = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = onIo { MomodingDatabase.open(context, DATABASE_NAME) }
    }

    @After
    fun tearDown() {
        onIo { database.close() }
        context.deleteDatabase(DATABASE_NAME)
        executor.shutdownNow()
    }

    @Test(timeout = 240_000)
    fun bundledDriverRoundTripsReplaceAuditAtEveryRequiredBoundary() {
        val sizes = listOf(
            (8 * 1024 * 1024) - 1,
            8 * 1024 * 1024,
            (8 * 1024 * 1024) + 1,
            64 * 1024 * 1024,
        )
        sizes.forEachIndexed { index, size ->
            val expectedSha = writeBoundary(index, size)
            reopenDatabase()
            val restored = onIo {
                RoomProjectionTransactionStore(database).read(TASK_ID)
            }!!.stagedRawFrameBatches.single().frames.single()
            assertEquals(size.toLong(), restored.byteCount)
            assertEquals(expectedSha, restored.sha256)
        }
    }

    @Test(timeout = 240_000)
    fun bundledDriverRestartsWholeBatchHistoryCompactionWithinBothBounds() {
        var batches = emptyList<StagedRawFrameBatch>()
        var nextOrdinal = 1L
        repeat(10) { index ->
            val retained = RawFrameRetentionPolicy.retainBatch(
                priorBatches = batches,
                nextBatchOrdinal = nextOrdinal,
                batchKind = "task.snapshot.prepend_history",
                currentFrames = listOf(
                    StagedRawFrame(
                        "task.snapshot.page",
                        ByteArray(7 * 1024 * 1024) { (index + 1).toByte() },
                    ),
                ),
                mode = StagedBatchMode.APPEND,
            )
            batches = retained.batches
            nextOrdinal = retained.nextBatchOrdinal
        }
        assertEquals((2L..10L).toList(), batches.map { it.batchOrdinal })
        assertTrue(batches.sumOf { it.frames.size } <= RawFrameRetentionPolicy.MAX_STAGED_AUDIT_FRAMES)
        assertTrue(batches.sumOf { it.byteCount } <= RawFrameRetentionPolicy.MAX_STAGED_AUDIT_BYTES)

        onIo {
            writeProjection(
                RoomProjectionTransactionStore(database),
                DurableTaskProjection(
                    taskId = TASK_ID,
                    nextStageBatchOrdinal = nextOrdinal,
                    stagedRawFrameBatches = batches,
                ),
            )
        }
        reopenDatabase()
        val restored = onIo { RoomProjectionTransactionStore(database).read(TASK_ID) }!!
        assertEquals((2L..10L).toList(), restored.stagedRawFrameBatches.map { it.batchOrdinal })
        restored.stagedRawFrameBatches.forEach { batch ->
            assertEquals(7L * 1024 * 1024, batch.byteCount)
        }
    }

    @Test
    fun productionDatabaseStillRejectsMainThreadQueries() {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                database.momodingDao().task(TASK_ID)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue(failure.get() is IllegalStateException)
    }

    @Test
    fun exportedVersionOneSchemaMigratesListAndAttentionOwnershipWithProductionDriver() {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 1).use { versionOne ->
            versionOne.execSQL(
                "INSERT INTO tasks(" +
                    "taskId,title,runState,recoveryState,readState,attentionState,streamId," +
                    "throughSequence,snapshotVersion,windowStart,windowEndExclusive," +
                    "nextStageBatchOrdinal,queueJson,piSessionId,isStreaming,updatedAtMillis" +
                    ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    TASK_ID,
                    "migrated",
                    "IDLE",
                    "NORMAL",
                    "UNREAD",
                    "NONE",
                    null,
                    0L,
                    1L,
                    0L,
                    0L,
                    1L,
                    "[]",
                    null,
                    0,
                    4_242L,
                ),
            )
            versionOne.execSQL(
                "INSERT INTO pending_attention(" +
                    "taskId,ordinal,callId,toolName,argumentsJson,state,expiresAt," +
                    "originFocusKey,terminalResultJson,rawPayload" +
                    ") VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    TASK_ID,
                    0,
                    ATTENTION_CALL_ID,
                    "request_user_question",
                    "{\"question\":\"Continue?\"}",
                    "waiting",
                    "2026-07-17T01:15:00.000Z",
                    "composer",
                    null,
                    "{\"callId\":\"$ATTENTION_CALL_ID\"}",
                ),
            )
            versionOne.execSQL(
                "INSERT INTO device_operations(" +
                    "taskId,callId,operationId,toolName,ledgerState,terminalSummaryJson,ordinal" +
                    ") VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    TASK_ID,
                    ATTENTION_CALL_ID,
                    null,
                    "request_user_question",
                    "running",
                    null,
                    0,
                ),
            )
        }
        val migrated = onIo { MomodingDatabase.open(context, MIGRATION_DATABASE_NAME) }
        try {
            val tables = onIo { migrated.momodingDao().tableNames() }
            assertTrue("tasks" in tables)
            assertTrue("staged_raw_frames" in tables)
            assertTrue("host_pending_attention_observations" in tables)
            assertTrue("host_device_call_observations" in tables)
            assertTrue("authorized_folders" in tables)
            assertTrue("task_content_grants" in tables)
            val task = onIo { migrated.momodingDao().task(TASK_ID) }!!
            assertTrue(task.listedByHost)
            assertNull(task.lastListSyncGeneration)
            assertNull(task.lastListRevision)
            assertEquals(4_242L, task.hostUpdatedAtMillis)
            assertEquals(1, onIo { migrated.momodingDao().hostPendingAttention(TASK_ID) }.size)
            assertEquals(1, onIo { migrated.momodingDao().hostDeviceCallObservations(TASK_ID) }.size)
            assertTrue(onIo { migrated.momodingDao().localPendingAttention(TASK_ID) }.isEmpty())
            assertTrue(onIo { migrated.momodingDao().deviceOperations(TASK_ID) }.isEmpty())
        } finally {
            onIo { migrated.close() }
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun exportedVersionThirteenSchemaMigratesAndValidatesAttachmentSchema() {
        context.deleteDatabase(ATTACHMENT_MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(ATTACHMENT_MIGRATION_DATABASE_NAME, 13).close()

            migrationHelper.runMigrationsAndValidate(
                ATTACHMENT_MIGRATION_DATABASE_NAME,
                14,
                true,
                MIGRATION_13_14,
            ).use { migrated ->
                val columns = migrated.query("PRAGMA table_info(attachments)").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                }
                assertTrue("attachmentId" in columns)
                assertTrue(columns.none { it.contains("uri", ignoreCase = true) })
            }
        } finally {
            context.deleteDatabase(ATTACHMENT_MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun exportedVersionFourteenSchemaMigratesAndValidatesApprovalModes() {
        context.deleteDatabase(APPROVAL_MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(APPROVAL_MIGRATION_DATABASE_NAME, 14).close()

            migrationHelper.runMigrationsAndValidate(
                APPROVAL_MIGRATION_DATABASE_NAME,
                15,
                true,
                MIGRATION_14_15,
            ).use { migrated ->
                listOf("tasks", "drafts").forEach { table ->
                    val approval = migrated.query("PRAGMA table_info($table)").use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                if (cursor.getString(1) == "approvalMode") {
                                    add(
                                        Triple(
                                            cursor.getString(2),
                                            cursor.getInt(3),
                                            cursor.getString(4),
                                        ),
                                    )
                                }
                            }
                        }.single()
                    }
                    assertEquals(Triple("TEXT", 1, "'REQUEST_APPROVAL'"), approval)
                }
            }
        } finally {
            context.deleteDatabase(APPROVAL_MIGRATION_DATABASE_NAME)
        }
    }

    private fun writeBoundary(index: Int, size: Int): String = onIo {
        val frame = StagedRawFrame(
            "task.snapshot.page",
            ByteArray(size).also { bytes ->
                bytes[0] = index.toByte()
                bytes[bytes.lastIndex] = (index + 11).toByte()
            },
        )
        val expectedSha = frame.sha256
        writeProjection(
            RoomProjectionTransactionStore(database),
            DurableTaskProjection(
                taskId = TASK_ID,
                nextStageBatchOrdinal = (index + 2).toLong(),
                stagedRawFrameBatches = listOf(
                    StagedRawFrameBatch(
                        batchOrdinal = (index + 1).toLong(),
                        batchKind = "task.snapshot.replace",
                        frames = listOf(frame),
                    ),
                ),
            ),
        )
        expectedSha
    }

    private fun writeProjection(
        store: ProjectionTransactionStore,
        projection: DurableTaskProjection,
    ) {
        store.transaction(projection.taskId) { transaction ->
            transaction.checkpoint(ProjectionWriteStage.RAW)
            transaction.checkpoint(ProjectionWriteStage.PROJECTION)
            transaction.checkpoint(ProjectionWriteStage.CURSOR)
            transaction.replace(projection)
            transaction.checkpoint(ProjectionWriteStage.FINAL)
        }
    }

    private fun reopenDatabase() {
        onIo { database.close() }
        database = onIo { MomodingDatabase.open(context, DATABASE_NAME) }
    }

    private fun <T> onIo(block: () -> T): T = try {
        executor.submit<T> { block() }.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }

    private companion object {
        const val DATABASE_NAME = "bundled-room-large-blob-test.db"
        const val MIGRATION_DATABASE_NAME = "bundled-room-schema-v1-test.db"
        const val ATTACHMENT_MIGRATION_DATABASE_NAME = "bundled-room-schema-v13-test.db"
        const val APPROVAL_MIGRATION_DATABASE_NAME = "bundled-room-schema-v14-test.db"
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val ATTENTION_CALL_ID = "22222222-2222-4222-8222-222222222222"
    }
}
