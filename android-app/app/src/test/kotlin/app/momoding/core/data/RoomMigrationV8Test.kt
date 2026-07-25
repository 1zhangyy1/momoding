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
class RoomMigrationV8Test {
    @Test
    fun `version eight adds Android authoritative prepared file plans`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")

            MIGRATION_7_8.migrate(database)

            assertEquals(
                setOf(
                    "preparedId",
                    "taskId",
                    "grantId",
                    "purpose",
                    "planDigest",
                    "operationsCanonicalJson",
                    "previewCanonicalJson",
                    "operationCount",
                    "state",
                    "expiresAtMillis",
                    "commitCallId",
                    "commitOperationId",
                    "approvalTokenSha256",
                    "approvalBindingSha256",
                    "approvalReceiptId",
                    "resultCanonicalJson",
                    "failureCode",
                    "createdAtMillis",
                    "updatedAtMillis",
                ),
                columns(database, "file_change_sets"),
            )
            val indices = database.query("PRAGMA index_list(file_change_sets)").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(1), cursor.getInt(2))
                }
            }
            assertEquals(0, indices["index_file_change_sets_taskId"])
            assertEquals(1, indices["index_file_change_sets_commitCallId"])
            assertEquals(1, indices["index_file_change_sets_commitOperationId"])
            assertTrue("file_change_sets" in tables(database))
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
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
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
