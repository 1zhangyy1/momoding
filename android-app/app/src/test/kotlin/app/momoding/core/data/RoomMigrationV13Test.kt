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
class RoomMigrationV13Test {
    @Test
    fun `version thirteen adds global skills with unique name and no uri column`() {
        withInMemoryDatabase { database ->
            MIGRATION_12_13.migrate(database)

            assertEquals(
                listOf(
                    "skillId", "source", "name", "description", "content", "contentSha256",
                    "disableModelInvocation", "enabled", "availability", "diagnosticCode",
                    "diagnosticMessage", "createdAtMillis", "updatedAtMillis",
                ),
                database.columns("skills"),
            )
            assertTrue(database.columns("skills").none { it.contains("uri", ignoreCase = true) })
            database.execSQL(
                "INSERT INTO skills VALUES (" +
                    "'skill:imported:review','IMPORTED','review','Review safely','body','digest'," +
                    "0,0,'AVAILABLE',NULL,NULL,1,1)",
            )
            val duplicate = runCatching {
                database.execSQL(
                    "INSERT INTO skills VALUES (" +
                        "'skill:bundled:review','BUNDLED','review','Duplicate','body','digest'," +
                        "0,1,'AVAILABLE',NULL,NULL,1,1)",
                )
            }.exceptionOrNull()
            assertTrue(duplicate != null)
        }
    }

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
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
