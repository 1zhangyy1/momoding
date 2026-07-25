package app.momoding.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationV10Test {
    @Test
    fun `version ten adds durable local task management without changing existing titles`() {
        withInMemoryDatabase { database ->
            database.execSQL(
                "CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)",
            )
            database.execSQL("INSERT INTO tasks(taskId,title) VALUES ('task-1','Existing title')")

            MIGRATION_9_10.migrate(database)

            assertEquals(
                mapOf(
                    "titleSource" to "'LEGACY'",
                    "pinnedAtMillis" to "NULL",
                    "archivedAtMillis" to "NULL",
                ),
                columnDefaults(database, "tasks").filterKeys {
                    it in setOf("titleSource", "pinnedAtMillis", "archivedAtMillis")
                },
            )
            database.query(
                "SELECT title,titleSource,pinnedAtMillis,archivedAtMillis FROM tasks",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("Existing title", cursor.getString(0))
                assertEquals("LEGACY", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(true, cursor.isNull(3))
            }
        }
    }

    private fun columnDefaults(database: SupportSQLiteDatabase, table: String): Map<String, String> =
        database.query("PRAGMA table_info($table)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(4) ?: "NULL")
            }
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
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
