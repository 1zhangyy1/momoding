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
class RoomMigrationV3Test {
    @Test
    fun `version two legacy draft migrates with honest zero selection and attempt`() {
        withInMemoryDatabase { database ->
            createVersionTwoSchema(database)
            insertLegacyDraft(database, "v2-draft", "A\uD83D\uDE00B")
            MIGRATION_2_3.migrate(database)
            database.query(
                "SELECT text, selectionStart, selectionEnd, attemptOrdinal " +
                    "FROM drafts WHERE draftId = 'v2-draft'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("A\uD83D\uDE00B", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0L, cursor.getLong(3))
            }
        }
    }

    @Test
    fun `version one migrates through two and three without destructive fallback`() {
        withInMemoryDatabase { database ->
            createVersionOneSchema(database)
            insertLegacyDraft(database, "v1-draft", "legacy")
            MIGRATION_1_2.migrate(database)
            MIGRATION_2_3.migrate(database)
            database.query(
                "SELECT selectionStart, selectionEnd, attemptOrdinal " +
                    "FROM drafts WHERE draftId = 'v1-draft'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0L, cursor.getLong(2))
            }
        }
    }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .name(null)
                .build(),
        )
        try {
            block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    private fun createVersionOneSchema(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE tasks (" +
                "taskId TEXT NOT NULL PRIMARY KEY, updatedAtMillis INTEGER NOT NULL" +
                ")",
        )
        createLegacyDraftsTable(database)
    }

    private fun createVersionTwoSchema(database: SupportSQLiteDatabase) {
        createLegacyDraftsTable(database)
    }

    private fun createLegacyDraftsTable(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE drafts (" +
                "draftId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, " +
                "selectedHostId TEXT, selectedModelId TEXT, selectedMode TEXT, " +
                "createCommandId TEXT NOT NULL, promptCommandId TEXT NOT NULL, " +
                "taskId TEXT, updatedAtMillis INTEGER NOT NULL" +
                ")",
        )
    }

    private fun insertLegacyDraft(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        draftId: String,
        text: String,
    ) {
        database.execSQL(
            "INSERT INTO drafts(" +
                "draftId,text,selectedHostId,selectedModelId,selectedMode," +
                "createCommandId,promptCommandId,taskId,updatedAtMillis" +
                ") VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                draftId,
                text,
                null,
                null,
                null,
                CREATE_ID,
                PROMPT_ID,
                null,
                1L,
            ),
        )
    }

    private companion object {
        const val CREATE_ID = "11111111-1111-4111-8111-111111111111"
        const val PROMPT_ID = "22222222-2222-4222-8222-222222222222"
    }
}
