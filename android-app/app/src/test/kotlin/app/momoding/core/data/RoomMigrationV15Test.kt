package app.momoding.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationV15Test {
    @Test
    fun `version fifteen gives legacy tasks and drafts the fail-closed request mode`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")
            database.execSQL(
                "CREATE TABLE drafts (draftId TEXT NOT NULL PRIMARY KEY, taskId TEXT)",
            )
            database.execSQL("INSERT INTO tasks(taskId) VALUES ('task-legacy')")
            database.execSQL(
                "INSERT INTO drafts(draftId, taskId) VALUES " +
                    "('draft-b', 'task-duplicate'), ('draft-a', 'task-duplicate'), " +
                    "('draft-unbound', NULL)",
            )

            MIGRATION_14_15.migrate(database)

            assertEquals("REQUEST_APPROVAL", database.stringValue("SELECT approvalMode FROM tasks"))
            assertEquals("REQUEST_APPROVAL", database.stringValue("SELECT approvalMode FROM drafts"))
            listOf("tasks", "drafts").forEach { table ->
                val column = database.column(table, "approvalMode")
                assertEquals("TEXT", column.type)
                assertTrue(column.notNull)
                assertEquals("'REQUEST_APPROVAL'", column.defaultValue)
                val nullWrite = runCatching {
                    database.execSQL("UPDATE $table SET approvalMode = NULL")
                }.exceptionOrNull()
                assertTrue(nullWrite != null)
                assertFalse(database.stringValue("SELECT approvalMode FROM $table").isBlank())
            }
            assertEquals(
                "draft-a",
                database.stringValue(
                    "SELECT draftId FROM drafts WHERE taskId = 'task-duplicate'",
                ),
            )
            assertEquals(
                2,
                database.intValue("SELECT COUNT(*) FROM drafts WHERE taskId IS NULL"),
            )
            val duplicateWrite = runCatching {
                database.execSQL(
                    "UPDATE drafts SET taskId = 'task-duplicate' WHERE draftId = 'draft-unbound'",
                )
            }.exceptionOrNull()
            assertTrue(duplicateWrite != null)
        }
    }

    private data class Column(val type: String, val notNull: Boolean, val defaultValue: String?)

    private fun SupportSQLiteDatabase.column(table: String, name: String): Column =
        query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return Column(cursor.getString(2), cursor.getInt(3) == 1, cursor.getString(4))
                }
            }
            error("Missing $table.$name")
        }

    private fun SupportSQLiteDatabase.stringValue(query: String): String = query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun SupportSQLiteDatabase.intValue(query: String): Int = query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
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
