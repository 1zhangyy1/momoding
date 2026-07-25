package app.momoding.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationV4Test {
    @Test
    fun `version three migrates snapshot foundations to observation tables and preserves durable data`() {
        withInMemoryDatabase { database ->
            createVersionThreeSlice(database)
            insertLegacyRows(database)

            MIGRATION_3_4.migrate(database)

            assertEquals(1, count(database, "tasks"))
            assertEquals(1, count(database, "timeline_projections"))
            assertEquals(1, count(database, "outbound_commands"))
            assertEquals(1, count(database, "drafts"))
            assertEquals(1, count(database, "host_pending_attention_observations"))
            assertEquals(1, count(database, "host_device_call_observations"))
            assertEquals(0, count(database, "pending_attention"))
            assertEquals(0, count(database, "device_operations"))

            database.query(
                "SELECT callId,toolName,state,rawPayload " +
                    "FROM host_pending_attention_observations",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(CALL_ID, cursor.getString(0))
                assertEquals("request_user_question", cursor.getString(1))
                assertEquals("waiting", cursor.getString(2))
                assertEquals("{\"callId\":\"$CALL_ID\"}", cursor.getString(3))
            }
            database.query(
                "SELECT callId,operationId,hostState,ordinal " +
                    "FROM host_device_call_observations",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(CALL_ID, cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("running", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }

            val tables = mutableSetOf<String>()
            database.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue("pending_attention" in tables)
            assertTrue("device_operations" in tables)

            val operationColumns = columns(database, "device_operations")
            assertTrue(setOf(
                "piToolCallId",
                "deviceId",
                "argumentsCanonicalJson",
                "requestSha256",
                "terminalFrameCanonicalJson",
                "terminalSha256",
                "hostObservationState",
                "deliveryState",
            ).all(operationColumns::contains))
            val attentionColumns = columns(database, "pending_attention")
            assertTrue(setOf(
                "responseState",
                "selectedOptionIndex",
                "customAnswer",
                "selectionStart",
                "selectionEnd",
                "terminalDisplayJson",
            ).all(attentionColumns::contains))
        }
    }

    private fun createVersionThreeSlice(database: SupportSQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys=ON")
        database.execSQL(
            "CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE timeline_projections (" +
                "taskId TEXT NOT NULL, stableItemId TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, rawPayload TEXT NOT NULL, presentationJson TEXT NOT NULL, " +
                "PRIMARY KEY(taskId,stableItemId))",
        )
        database.execSQL(
            "CREATE TABLE outbound_commands (" +
                "requestId TEXT NOT NULL PRIMARY KEY, commandId TEXT, kind TEXT NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE drafts (" +
                "draftId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, " +
                "selectionStart INTEGER NOT NULL, selectionEnd INTEGER NOT NULL, " +
                "attemptOrdinal INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE pending_attention (" +
                "taskId TEXT NOT NULL, ordinal INTEGER NOT NULL, callId TEXT, toolName TEXT, " +
                "argumentsJson TEXT, state TEXT, expiresAt TEXT, originFocusKey TEXT, " +
                "terminalResultJson TEXT, rawPayload TEXT NOT NULL, PRIMARY KEY(taskId,ordinal), " +
                "FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON DELETE CASCADE)",
        )
        database.execSQL(
            "CREATE TABLE device_operations (" +
                "taskId TEXT NOT NULL, callId TEXT NOT NULL, operationId TEXT, toolName TEXT, " +
                "ledgerState TEXT NOT NULL, terminalSummaryJson TEXT, ordinal INTEGER NOT NULL, " +
                "PRIMARY KEY(taskId,callId), " +
                "FOREIGN KEY(taskId) REFERENCES tasks(taskId) ON DELETE CASCADE)",
        )
    }

    private fun insertLegacyRows(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO tasks VALUES(?,?)", arrayOf<Any?>(TASK_ID, "legacy"))
        database.execSQL(
            "INSERT INTO timeline_projections VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(TASK_ID, "message-0", 0, "message", "{}", "{}"),
        )
        database.execSQL(
            "INSERT INTO outbound_commands VALUES(?,?,?)",
            arrayOf<Any?>("request-legacy", null, "task.open"),
        )
        database.execSQL(
            "INSERT INTO drafts VALUES(?,?,?,?,?)",
            arrayOf<Any?>("draft-legacy", "text", 0, 0, 0),
        )
        database.execSQL(
            "INSERT INTO pending_attention VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                TASK_ID,
                0,
                CALL_ID,
                "request_user_question",
                "{\"question\":\"Continue?\"}",
                "waiting",
                "2026-07-17T01:15:00.000Z",
                "composer",
                null,
                "{\"callId\":\"$CALL_ID\"}",
            ),
        )
        database.execSQL(
            "INSERT INTO device_operations VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(TASK_ID, CALL_ID, null, "request_user_question", "running", null, 0),
        )
    }

    private fun count(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun columns(database: SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA table_info($table)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .name(null)
                .build(),
        )
        try {
            block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CALL_ID = "22222222-2222-4222-8222-222222222222"
    }
}
