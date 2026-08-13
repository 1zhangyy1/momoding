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
class RoomMigrationV17Test {
    @Test
    fun `version seventeen adds bounded private Skill package files`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE skills (skillId TEXT NOT NULL PRIMARY KEY)")

            MIGRATION_16_17.migrate(database)

            assertEquals(
                listOf("skillId", "relativePath", "mimeType", "byteSize", "contentSha256", "content"),
                database.columns("skill_package_files"),
            )
            assertEquals(
                listOf("index_skill_package_files_skillId"),
                database.indexes("skill_package_files"),
            )
        }
    }

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun SupportSQLiteDatabase.indexes(table: String): List<String> =
        query("PRAGMA index_list($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (!name.startsWith("sqlite_autoindex")) add(name)
                }
            }.sorted()
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
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
