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
class RoomMigrationV14Test {
    @Test
    fun `version fourteen adds private attachment metadata and owner cascades without uri`() {
        withInMemoryDatabase { database ->
            database.execSQL("CREATE TABLE drafts (draftId TEXT NOT NULL PRIMARY KEY)")
            database.execSQL("CREATE TABLE tasks (taskId TEXT NOT NULL PRIMARY KEY)")
            MIGRATION_13_14.migrate(database)

            assertEquals(
                listOf(
                    "attachmentId", "draftId", "taskId", "messageLocalId", "ordinal", "kind",
                    "state", "source", "displayName", "mimeType", "byteSize", "payloadSha256",
                    "payloadFileName", "thumbnailFileName", "width", "height", "createdAtMillis",
                    "updatedAtMillis",
                ),
                database.columns("attachments"),
            )
            assertTrue(database.columns("attachments").none { it.contains("uri", ignoreCase = true) })

            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL("INSERT INTO drafts VALUES ('draft-1')")
            database.execSQL(attachmentInsertSql("attachment-1", "payload-1.bin", "thumb-1.png", 0))
            assertEquals(1, database.rowCount("attachments"))

            val duplicateOrdinal = runCatching {
                database.execSQL(attachmentInsertSql("attachment-2", "payload-2.bin", "thumb-2.png", 0))
            }.exceptionOrNull()
            assertTrue(duplicateOrdinal != null)

            database.execSQL("DELETE FROM drafts WHERE draftId = 'draft-1'")
            assertEquals(0, database.rowCount("attachments"))
        }
    }

    private fun attachmentInsertSql(
        attachmentId: String,
        payloadFileName: String,
        thumbnailFileName: String,
        ordinal: Int,
    ): String = "INSERT INTO attachments VALUES (" +
        "'$attachmentId','draft-1',NULL,NULL,$ordinal,'IMAGE','STAGED','PHOTO_PICKER'," +
        "'photo.png','image/png',68,'${"a".repeat(64)}','$payloadFileName'," +
        "'$thumbnailFileName',1,1,1,1)"

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun withInMemoryDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
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
