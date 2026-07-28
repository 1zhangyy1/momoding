package app.momoding.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationV16Test {
    @Test
    fun `version sixteen adds nullable typed task failure fields`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")
            database.execSQL("INSERT INTO tasks(taskId) VALUES ('legacy-task')")

            MIGRATION_15_16.migrate(database)

            assertEquals(
                listOf("failureKind", "failureMessage", "failureRecovery"),
                database.columns("tasks").filter { it.startsWithFailure() },
            )
            listOf("failureKind", "failureMessage", "failureRecovery").forEach { column ->
                assertNull(database.stringValue("SELECT $column FROM tasks"))
            }
        }
    }

    private fun String.startsWithFailure(): Boolean = startsWith("failure")

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun SupportSQLiteDatabase.stringValue(query: String): String? = query(query).use { cursor ->
        check(cursor.moveToFirst())
        if (cursor.isNull(0)) null else cursor.getString(0)
    }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
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
