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
class RoomMigrationV11Test {
    @Test
    fun `version eleven adds one durable goal per task with a unique goal identity`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")
            database.execSQL("INSERT INTO tasks(taskId) VALUES ('task-1')")

            MIGRATION_10_11.migrate(database)

            val columns = database.query("PRAGMA table_info(task_goals)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
            assertEquals(
                listOf(
                    "taskId", "goalId", "instruction", "state", "progressSummary",
                    "progressMarker", "terminalReason", "pauseReason", "automaticTurnCount",
                    "lastTurnIndex", "nextTurnClaimed", "generation", "startedAtMillis",
                    "updatedAtMillis",
                ),
                columns,
            )
            database.execSQL(
                "INSERT INTO task_goals(" +
                    "taskId,goalId,instruction,state,automaticTurnCount,lastTurnIndex,nextTurnClaimed," +
                    "generation,startedAtMillis,updatedAtMillis" +
                    ") VALUES ('task-1','goal-1','Finish work','ACTIVE',0,0,1,1,1000,1000)",
            )
            database.query(
                "SELECT goalId,instruction,state,nextTurnClaimed FROM task_goals WHERE taskId='task-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("goal-1", cursor.getString(0))
                assertEquals("Finish work", cursor.getString(1))
                assertEquals("ACTIVE", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
            }
            val uniqueGoalIndex = database.query("PRAGMA index_list(task_goals)").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(1), cursor.getInt(2))
                }
            }
            assertEquals(1, uniqueGoalIndex["index_task_goals_goalId"])
        }
    }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
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
}
