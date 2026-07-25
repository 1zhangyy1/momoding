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
class RoomMigrationV9Test {
    @Test
    fun `version nine adds settled native Pi session snapshots`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")

            MIGRATION_8_9.migrate(database)

            assertEquals(
                setOf(
                    "taskId",
                    "piSessionId",
                    "entriesJson",
                    "schemaVersion",
                    "updatedAtMillis",
                ),
                columns(database, "pi_session_snapshots"),
            )
            assertTrue("pi_session_snapshots" in tables(database))
            val foreignKeys = database.query("PRAGMA foreign_key_list(pi_session_snapshots)")
                .use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) {
                            add("${cursor.getString(2)}:${cursor.getString(3)}:${cursor.getString(4)}")
                        }
                    }
                }
            assertEquals(setOf("tasks:taskId:taskId"), foreignKeys)
        }
    }

    private fun columns(database: SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA table_info($table)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun tables(database: SupportSQLiteDatabase): Set<String> =
        database.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
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
