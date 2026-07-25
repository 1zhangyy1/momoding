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
class RoomMigrationV12Test {
    @Test
    fun `version twelve adds durable child identity usage and native event tables`() {
        withInMemoryDatabase { database ->
            database.execSQL("PRAGMA foreign_keys=ON")
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")
            database.execSQL("INSERT INTO tasks(taskId) VALUES ('task-1')")

            MIGRATION_11_12.migrate(database)

            val childColumns = database.columns("task_child_agents")
            assertTrue(childColumns.containsAll(listOf(
                "taskId", "parentToolCallId", "childId", "state", "resultText", "model",
                "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens",
                "contextTokens", "costUsd", "eventCount",
            )))
            assertEquals(
                listOf("taskId", "parentToolCallId", "eventOrdinal", "childId", "childName", "eventType", "eventJson", "digest"),
                database.columns("task_child_agent_events"),
            )
            database.execSQL(
                "INSERT INTO task_child_agents(" +
                    "taskId,parentToolCallId,childId,childName,instruction,state,resultTruncated," +
                    "turnCount,inputTokens,outputTokens,cacheReadTokens,cacheWriteTokens," +
                    "contextTokens,costUsd,eventTypesJson,eventCount,createdAtMillis,updatedAtMillis" +
                    ") VALUES ('task-1','delegate-a','child-1','Analyst','Analyze','RUNNING',0," +
                    "0,0,0,0,0,0,0.0,'[]',0,1,1)",
            )
            database.execSQL(
                "INSERT INTO task_child_agent_events(" +
                    "taskId,parentToolCallId,eventOrdinal,childId,childName,eventType,eventJson,digest" +
                    ") VALUES ('task-1','delegate-a',0,'child-1','Analyst','agent_start','{}','digest')",
            )
            database.execSQL("DELETE FROM tasks WHERE taskId='task-1'")
            database.query("SELECT COUNT(*) FROM task_child_agents").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM task_child_agent_events").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
}
