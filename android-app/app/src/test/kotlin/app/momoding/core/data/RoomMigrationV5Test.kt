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
class RoomMigrationV5Test {
    @Test
    fun `version four adds private authorized folder registry with unique tree hash`() {
        withInMemoryDatabase { database ->
            MIGRATION_4_5.migrate(database)

            val columns = columns(database, "authorized_folders")
            assertTrue(
                setOf(
                    "grantId",
                    "treeUri",
                    "treeUriSha256",
                    "displayName",
                    "authority",
                    "persistedRead",
                    "persistedWrite",
                    "status",
                    "createdAtMillis",
                    "updatedAtMillis",
                ).all(columns::contains),
            )
            database.execSQL(
                "INSERT INTO authorized_folders VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    "11111111-1111-4111-8111-111111111111",
                    "content://provider/tree/root",
                    "hash",
                    "Project",
                    "provider",
                    1,
                    1,
                    "ACTIVE",
                    1L,
                    1L,
                ),
            )
            val duplicateFailure = runCatching {
                database.execSQL(
                    "INSERT INTO authorized_folders VALUES(?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        "22222222-2222-4222-8222-222222222222",
                        "content://provider/tree/root-copy",
                        "hash",
                        "Duplicate",
                        "provider",
                        1,
                        0,
                        "READ_ONLY",
                        2L,
                        2L,
                    ),
                )
            }.exceptionOrNull()
            assertTrue(duplicateFailure != null)
            assertEquals(1, count(database, "authorized_folders"))
        }
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
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
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
