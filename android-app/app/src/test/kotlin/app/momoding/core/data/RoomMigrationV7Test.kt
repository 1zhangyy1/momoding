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
class RoomMigrationV7Test {
    @Test
    fun `version seven adds Android-only bounded task content grants`() {
        withInMemoryDatabase { database ->
            database.execSQL(
                "CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)",
            )
            database.execSQL(
                "CREATE TABLE device_operations (" +
                    "callId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, " +
                    "UNIQUE(taskId, callId))",
            )

            MIGRATION_6_7.migrate(database)

            assertEquals(
                setOf(
                    "callId",
                    "taskId",
                    "grantId",
                    "documentAliasesJson",
                    "mimeTypesJson",
                    "perFileByteBudgetsJson",
                    "totalByteBudget",
                    "consumedBytes",
                    "expiresAtMillis",
                    "revokedAtMillis",
                    "createdAtMillis",
                    "updatedAtMillis",
                ),
                columns(database, "task_content_grants"),
            )
            val indices = database.query("PRAGMA index_list(task_content_grants)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
            assertTrue("index_task_content_grants_taskId_callId" in indices)
            assertTrue("index_task_content_grants_grantId" in indices)
        }
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
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
