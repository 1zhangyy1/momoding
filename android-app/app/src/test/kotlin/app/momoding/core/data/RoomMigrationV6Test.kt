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
class RoomMigrationV6Test {
    @Test
    fun `version five adds nullable task folder selection without changing existing drafts`() {
        withInMemoryDatabase { database ->
            database.execSQL(
                "CREATE TABLE drafts (" +
                    "draftId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, " +
                    "selectedHostId TEXT, selectedModelId TEXT, selectedMode TEXT, " +
                    "createCommandId TEXT NOT NULL, promptCommandId TEXT NOT NULL, " +
                    "taskId TEXT, updatedAtMillis INTEGER NOT NULL, " +
                    "selectionStart INTEGER NOT NULL DEFAULT 0, " +
                    "selectionEnd INTEGER NOT NULL DEFAULT 0, " +
                    "attemptOrdinal INTEGER NOT NULL DEFAULT 0)",
            )
            database.execSQL(
                "INSERT INTO drafts(" +
                    "draftId,text,createCommandId,promptCommandId,updatedAtMillis" +
                    ") VALUES(?,?,?,?,?)",
                arrayOf<Any?>(
                    "draft",
                    "existing",
                    "11111111-1111-4111-8111-111111111111",
                    "22222222-2222-4222-8222-222222222222",
                    1L,
                ),
            )

            MIGRATION_5_6.migrate(database)

            assertTrue(columns(database, "drafts").contains("selectedGrantId"))
            database.query(
                "SELECT text, selectedGrantId FROM drafts WHERE draftId = 'draft'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
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
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
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
