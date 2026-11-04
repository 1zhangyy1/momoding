package com.openminis.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Momoding (调研/M39): 14 → 15 adds `inbox`, `task`, `task_log`; 15 → 14 is a no-op that keeps
 * them. NOTE: connectedAndroidTest UNINSTALLS the app under test — run only on a device whose
 * data is disposable.
 */
@RunWith(AndroidJUnit4::class)
class TaskTablesMigrationTest {
    private val dbName = "task-tables-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun upgrade14to15_keepsSessionsAndCascades() {
        helper.createDatabase(dbName, 14).use { db ->
            db.execSQL("INSERT INTO sessions (id, title, model_id, created_at, updated_at, memory_enabled, edit_count) VALUES ('s1', 't', 'm', 1, 1, 1, 0)")
        }
        helper.runMigrationsAndValidate(dbName, 15, true, AppDatabase.MIGRATION_14_15).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO inbox (id, to_session, sender, text, arrived_at) VALUES ('i1', 's1', 'USER', 'hi', 1)")
            db.execSQL("INSERT INTO task (id, conversation, title, hand, state, created_at, updated_at) VALUES ('t1', 's1', 'x', 'screen', 'RUNNING', 1, 1)")
            db.execSQL("INSERT INTO task_log (task_id, at, kind, body) VALUES ('t1', 1, 'AUTHORIZED', 'hi')")
            db.execSQL("DELETE FROM sessions WHERE id='s1'")
            for (table in listOf("inbox", "task", "task_log")) {
                db.query("SELECT COUNT(*) FROM $table").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            }
        }
    }

    @Test
    fun downgrade15to14_keepsTheTables() {
        helper.createDatabase(dbName, 15).use { db ->
            db.execSQL("INSERT INTO sessions (id, title, model_id, created_at, updated_at, memory_enabled, edit_count) VALUES ('s1', 't', 'm', 1, 1, 1, 0)")
            db.execSQL("INSERT INTO inbox (id, to_session, sender, text, arrived_at) VALUES ('i1', 's1', 'USER', 'hi', 1)")
            db.execSQL("INSERT INTO task (id, conversation, title, hand, state, created_at, updated_at) VALUES ('t1', 's1', 'keep me', 'screen', 'RUNNING', 1, 1)")
            db.execSQL("INSERT INTO task_log (task_id, at, kind, body) VALUES ('t1', 1, 'AUTHORIZED', 'original words')")
        }
        // A no-op downgrade intentionally retains the additive tables. Validate the old schema
        // without requiring their deletion, then check the retained rows and integrity ourselves.
        helper.runMigrationsAndValidate(dbName, 14, false, AppDatabase.MIGRATION_15_14).use { db ->
            db.query("SELECT text FROM inbox WHERE id='i1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("hi", c.getString(0)) }
            db.query("SELECT title FROM task WHERE id='t1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("keep me", c.getString(0)) }
            db.query("SELECT body FROM task_log WHERE task_id='t1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("original words", c.getString(0)) }
            db.query("PRAGMA integrity_check").use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
        }
    }

    @Test
    fun upgrade15to16_keepsUnreadWordsAndDoesNotReplayOldReadMessages() {
        helper.createDatabase(dbName, 15).use { db ->
            db.execSQL("INSERT INTO sessions (id, model_id, created_at, updated_at, memory_enabled, edit_count) VALUES ('s1', 'm', 1, 1, 1, 0)")
            db.execSQL("INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order, stream_interrupt_count) VALUES ('read', 's1', 'user', '[{\"type\":\"text\",\"value\":\"already in history\"}]', 1, 0, 0)")
            db.execSQL("INSERT INTO inbox (id, to_session, sender, text, arrived_at) VALUES ('unread', 's1', 'USER', 'keep this', 2)")
            db.execSQL("INSERT INTO inbox (id, to_session, sender, text, arrived_at, read_at) VALUES ('read', 's1', 'USER', 'already in history', 1, 1)")
        }
        helper.runMigrationsAndValidate(dbName, 16, true, AppDatabase.MIGRATION_15_16).use { db ->
            db.query("SELECT id, text, tries, failed_at, parts_json FROM inbox").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("unread", c.getString(0)); assertEquals("keep this", c.getString(1))
                assertEquals(0, c.getInt(2)); assertTrue(c.isNull(3)); assertTrue(c.isNull(4)); assertTrue(!c.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM messages WHERE id='read'").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test
    fun downgrade16to15_keepsThePendingMessageAndAttachmentReferences() {
        helper.createDatabase(dbName, 16).use { db ->
            db.execSQL("INSERT INTO sessions (id, model_id, created_at, updated_at, memory_enabled, edit_count) VALUES ('s1', 'm', 1, 1, 1, 0)")
            db.execSQL("INSERT INTO inbox (id, to_session, sender, text, attachments, parts_json, arrived_at, tries) VALUES ('pending', 's1', 'USER', 'my words', '[{\"uri\":\"file:///fixture.txt\"}]', '[]', 1, 2)")
        }
        helper.runMigrationsAndValidate(dbName, 15, true, AppDatabase.MIGRATION_16_15).use { db ->
            db.query("SELECT id, text, attachments FROM inbox").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("pending", c.getString(0)); assertEquals("my words", c.getString(1))
                assertTrue(c.getString(2).contains("file:///fixture.txt"))
            }
            db.query("PRAGMA integrity_check").use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
        }
        helper.runMigrationsAndValidate(dbName, 16, true, AppDatabase.MIGRATION_15_16).close()
    }
}
