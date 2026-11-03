package app.momoding.task

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.openminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Momoding (调研/M39): the three tables the main chat runs on.
 *
 * - [InboxEntity]: every word for a conversation (the person's, a hand's report, a routine's fire)
 *   lands here first and stays until that conversation's model has read it. Nothing waits in
 *   memory, so nothing is lost when the process dies.
 * - [TaskEntity]: one row per piece of work that takes more than one step, and the one place its
 *   state lives. Only [TaskEngine] writes it.
 * - [TaskLogEntity]: what happened to a task, append-only: the words that authorized it, the words
 *   passed on, every outward action as "about to" and "result", the reports, the end.
 *
 * All three cascade from their conversation, so deleting a chat leaves nothing behind.
 */
@Entity(
    tableName = "inbox",
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["to_session"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["to_session", "read_at"], name = "index_inbox_to_session_read_at")],
)
data class InboxEntity(
    /** Also the id of the `messages` row it becomes when read, so its bubble never changes identity. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "to_session") val toSession: String,
    @ColumnInfo(name = "task_id") val taskId: String? = null,
    /** [Sender] name. */
    val sender: String,
    val text: String,
    /** JSON array of the attachments, already copied into the app's own storage. */
    val attachments: String? = null,
    /** JSON array of tool calls a routine fire runs before the model is asked. */
    val prefill: String? = null,
    @ColumnInfo(name = "arrived_at") val arrivedAt: Long,
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
) {
    enum class Sender { USER, HAND, COMMAND, ROUTINE }
}

@Entity(
    tableName = "task",
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["conversation"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["conversation"], name = "index_task_conversation"), Index(value = ["hand"], name = "index_task_hand")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val conversation: String,
    /** The person's message this task came from; its result quotes it. */
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    val title: String,
    /** Who does it: a session id (a helper, a Bot, or the conversation itself), or [SCREEN]. */
    val hand: String,
    /** [State] name. */
    val state: String,
    /** One line the hand keeps current; shown on the task's row, never sent as a message. */
    val progress: String? = null,
    /** JSON: the question or approval card this task is waiting on, with its parameters. */
    val ask: String? = null,
    /** The person's "wait for me" condition; outward actions are refused while it is set. */
    val hold: String? = null,
    /** [Outcome] name once [State.ENDED]. */
    val outcome: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    enum class State { QUEUED, RUNNING, WAITING, ENDED }
    enum class Outcome { DONE, FAILED, STOPPED }

    companion object {
        const val SCREEN = "screen"
    }
}

@Entity(
    tableName = "task_log",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["task_id"], name = "index_task_log_task_id")],
)
data class TaskLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: String,
    val at: Long,
    /** [Kind] name. */
    val kind: String,
    val body: String,
) {
    enum class Kind { AUTHORIZED, PASSED_ON, ABOUT_TO, RESULT, REPORT, END }
}

@Dao
interface TaskDao {
    // ── inbox ──
    @Insert
    suspend fun insertInbox(row: InboxEntity)

    @Query("SELECT * FROM inbox WHERE to_session = :to AND read_at IS NULL ORDER BY arrived_at, rowid")
    suspend fun unread(to: String): List<InboxEntity>

    @Query("SELECT DISTINCT to_session FROM inbox WHERE read_at IS NULL")
    suspend fun sessionsWithUnread(): List<String>

    @Query("UPDATE inbox SET read_at = :at WHERE id IN (:ids)")
    suspend fun markRead(ids: List<String>, at: Long)

    @Query("DELETE FROM inbox WHERE id IN (:ids)")
    suspend fun deleteInbox(ids: List<String>)

    @Query("SELECT * FROM inbox ORDER BY arrived_at DESC LIMIT :limit")
    suspend fun recentInbox(limit: Int): List<InboxEntity>

    // ── task ──
    @Insert
    suspend fun insertTask(row: TaskEntity)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Update
    suspend fun updateTask(row: TaskEntity)

    @Query("SELECT * FROM task ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentTasks(limit: Int): List<TaskEntity>

    /** The newest tasks, kept current: what the rows and the main chat's table show. */
    @Query("SELECT * FROM task ORDER BY updated_at DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<TaskEntity>>

    /** The task [hand] is on: one at a time, so its session is enough to find it. */
    @Query("SELECT * FROM task WHERE hand = :hand AND state IN ('RUNNING', 'WAITING') LIMIT 1")
    suspend fun heldBy(hand: String): TaskEntity?

    @Query("SELECT * FROM task WHERE hand = :hand AND state = 'QUEUED' ORDER BY created_at LIMIT 1")
    suspend fun nextFor(hand: String): TaskEntity?

    /** Helpers (hidden child chats) on a task now; they share [Helpers.SLOTS]. */
    @Query("SELECT COUNT(*) FROM task t JOIN sessions s ON s.id = t.hand WHERE s.parent_session_id IS NOT NULL AND t.state IN ('RUNNING', 'WAITING')")
    suspend fun helpersAtWork(): Int

    @Query("SELECT t.* FROM task t JOIN sessions s ON s.id = t.hand WHERE s.parent_session_id IS NOT NULL AND t.state = 'QUEUED' ORDER BY t.created_at LIMIT 1")
    suspend fun nextForHelpers(): TaskEntity?

    @Insert
    suspend fun insertLog(row: TaskLogEntity)

    @Query("SELECT * FROM task_log WHERE task_id = :taskId ORDER BY id")
    suspend fun log(taskId: String): List<TaskLogEntity>
}
