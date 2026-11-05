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
    /** Prepared transcript parts, including durable media references, captured before acceptance. */
    @ColumnInfo(name = "parts_json") val partsJson: String? = null,
    @ColumnInfo(name = "arrived_at") val arrivedAt: Long,
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val tries: Int = 0,
    @ColumnInfo(name = "failed_at") val failedAt: Long? = null,
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
    enum class Kind { AUTHORIZED, PASSED_ON, STARTED, PAUSED, ROUTINE, ROUTINE_RECORDED, COMMAND, COMMAND_RESULT, CHILD, SCREEN, SCREEN_RESULT, APPROVAL, APPROVAL_DECISION, APPROVAL_USED, APPROVAL_CLOSED, ABOUT_TO, RESULT, DRAFT, HANDOFF, TRANSCRIBED, RECOVERY, REPORT, END }
}

@Dao
interface TaskDao {
    /** An effect or handoff whose original tool return has not reached the transcript. */
    @Query("SELECT a.* FROM task_log a WHERE a.kind IN ('ABOUT_TO', 'HANDOFF') AND instr(a.body, '\"tool\":') > 0 AND instr(a.body, :messageNeedle) > 0 AND NOT EXISTS (SELECT 1 FROM task_log done WHERE done.task_id = a.task_id AND ((done.kind = 'TRANSCRIBED' AND done.body = CAST(a.id AS TEXT)) OR (done.kind = 'RECOVERY' AND done.body = 'record:' || a.id))) ORDER BY a.id")
    suspend fun untranscribedCalls(messageNeedle: String = ""): List<TaskLogEntity>

    @Query("SELECT t.* FROM task t JOIN task_log r ON r.task_id = t.id WHERE r.kind = 'RECOVERY' AND r.body = :key LIMIT 1")
    suspend fun recovered(key: String): TaskEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun routineMessage(id: String): com.openminis.app.data.db.MessageEntity?

    @Query("UPDATE messages SET parts_json = :parts WHERE id = :id")
    suspend fun routineMessageParts(id: String, parts: String)

    @Query("SELECT * FROM messages WHERE role = 'user' AND instr(parts_json, '\"routineResult\":') > 0 AND instr(parts_json, '\"recorded\":false') > 0 ORDER BY created_at, sort_order")
    fun routineResultsFlow(): Flow<List<com.openminis.app.data.db.MessageEntity>>

    @Query("SELECT * FROM inbox WHERE to_session = :session AND read_at IS NOT NULL")
    suspend fun readInbox(session: String): List<InboxEntity>

    @Query("SELECT * FROM task WHERE id IN (SELECT task_id FROM task_log WHERE kind = 'ROUTINE' AND instr(body, :key) > 0) ORDER BY created_at")
    suspend fun routineTasks(key: String): List<TaskEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM inbox WHERE sender = 'ROUTINE' AND failed_at IS NULL AND instr(parts_json, :key) > 0 AND instr(parts_json, '\"scheduled\":true') > 0)")
    suspend fun hasRoutineInput(key: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE role = 'user' AND instr(parts_json, :key) > 0 AND instr(parts_json, '\"routineResult\":') > 0 AND instr(parts_json, '\"recorded\":false') > 0)")
    suspend fun hasRoutineResult(key: String): Boolean

    @Query("SELECT parts_json FROM messages WHERE id = :message AND session_id = :session AND role = 'assistant'")
    suspend fun toolMessage(session: String, message: String): String?

    @Query("SELECT i.* FROM inbox i JOIN messages received ON received.id = i.id JOIN messages call ON call.id = :message WHERE i.to_session = :session AND received.session_id = :session AND call.session_id = :session AND received.sort_order < call.sort_order AND i.read_at IS NOT NULL ORDER BY i.read_at DESC, CASE WHEN i.sender = 'USER' THEN 0 ELSE 1 END, received.sort_order DESC LIMIT 1")
    suspend fun inputForCall(session: String, message: String): InboxEntity?

    @Query("SELECT i.* FROM inbox i JOIN messages received ON received.id = i.id WHERE i.to_session = :session AND received.session_id = :session AND i.read_at IS NOT NULL ORDER BY i.read_at DESC, CASE WHEN i.sender = 'USER' THEN 0 ELSE 1 END, received.sort_order DESC LIMIT 1")
    suspend fun consumedInput(session: String): InboxEntity?

    @Query("SELECT * FROM task WHERE conversation = :conversation AND (source_message_id = :message OR EXISTS (SELECT 1 FROM task_log r WHERE r.task_id = task.id AND r.kind = 'RECOVERY' AND r.body = 'input:' || :message))")
    suspend fun sourceTasks(conversation: String, message: String): List<TaskEntity>

    // ── inbox ──
    @Insert
    suspend fun insertInbox(row: InboxEntity)

    @Query("SELECT * FROM inbox WHERE to_session = :to AND read_at IS NULL AND failed_at IS NULL ORDER BY arrived_at, rowid")
    suspend fun unread(to: String): List<InboxEntity>

    @Query("SELECT * FROM inbox WHERE to_session = :to ORDER BY arrived_at, rowid")
    suspend fun inboxFor(to: String): List<InboxEntity>

    @Query("SELECT * FROM inbox WHERE to_session = :to ORDER BY arrived_at, rowid")
    fun inboxFlow(to: String): Flow<List<InboxEntity>>

    @Query("SELECT * FROM inbox WHERE id = :id")
    suspend fun inbox(id: String): InboxEntity?

    @Query("SELECT * FROM inbox WHERE read_at IS NOT NULL AND failed_at IS NULL ORDER BY arrived_at, rowid")
    suspend fun interruptedInbox(): List<InboxEntity>

    @Query("UPDATE inbox SET prefill = NULL WHERE to_session = :to AND read_at IS NOT NULL")
    suspend fun clearReadPrefill(to: String)

    @Query("SELECT DISTINCT to_session FROM inbox WHERE read_at IS NULL AND failed_at IS NULL")
    suspend fun sessionsWithUnread(): List<String>

    @Query("UPDATE inbox SET read_at = :at WHERE id IN (:ids)")
    suspend fun markRead(ids: List<String>, at: Long)

    @Query("DELETE FROM inbox WHERE to_session = :to AND read_at IS NOT NULL")
    suspend fun completeRead(to: String)

    @Query("UPDATE inbox SET read_at = NULL, tries = tries + 1, failed_at = CASE WHEN :terminal OR tries + 1 >= 3 THEN :at ELSE NULL END WHERE to_session = :to AND read_at IS NOT NULL")
    suspend fun failRead(to: String, at: Long, terminal: Boolean)

    @Query("UPDATE inbox SET read_at = NULL, tries = tries + 1, failed_at = CASE WHEN :terminal OR tries + 1 >= 3 THEN :at ELSE NULL END WHERE id IN (:ids) AND failed_at IS NULL")
    suspend fun failInbox(ids: List<String>, at: Long, terminal: Boolean)

    @Query("UPDATE inbox SET read_at = NULL, tries = 0, failed_at = NULL WHERE id = :id AND failed_at IS NOT NULL")
    suspend fun retryInbox(id: String): Int

    /** Called only after the durable approval ledger proves its exact ticket is unclaimed. */
    @Query("UPDATE inbox SET read_at = NULL, tries = 0, failed_at = NULL WHERE id = :id AND task_id = :task AND to_session = :to AND sender = 'COMMAND' AND read_at IS NOT NULL AND prefill IS NOT NULL")
    suspend fun restoreApprovalEnvelope(id: String, task: String, to: String)

    @Query("UPDATE inbox SET failed_at = :at WHERE id = :id AND read_at IS NULL")
    suspend fun rejectInbox(id: String, at: Long)

    @Query("UPDATE inbox SET failed_at = :at WHERE task_id = :task AND to_session = :to AND read_at IS NULL")
    suspend fun rejectTaskInbox(task: String, to: String, at: Long)

    @Query("UPDATE inbox SET failed_at = NULL, tries = 0 WHERE task_id = :task AND to_session = :to AND read_at IS NULL")
    suspend fun retryTaskInbox(task: String, to: String)

    @Query("DELETE FROM inbox WHERE to_session = :to")
    suspend fun clearInbox(to: String)

    @Query("UPDATE inbox SET parts_json = :parts WHERE id = :id")
    suspend fun inboxParts(id: String, parts: String)

    @Query("DELETE FROM inbox WHERE id IN (:ids)")
    suspend fun deleteInbox(ids: List<String>)

    @Query("SELECT * FROM inbox ORDER BY arrived_at DESC LIMIT :limit")
    suspend fun recentInbox(limit: Int): List<InboxEntity>

    // ── task ──
    @Insert
    suspend fun insertTask(row: TaskEntity)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Query("SELECT * FROM task WHERE id = :id")
    fun taskFlow(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM task WHERE state = 'ENDED' ORDER BY updated_at")
    fun endedFlow(): Flow<List<TaskEntity>>

    @Query("SELECT DISTINCT hand FROM task WHERE state = 'QUEUED'")
    suspend fun queuedHands(): List<String>

    @Query("SELECT * FROM task WHERE state != 'ENDED' ORDER BY created_at")
    fun activeFlow(): Flow<List<TaskEntity>>

    /** A live clock, including a read while waiting; queued and ended work never keeps it ticking. */
    @Query("SELECT EXISTS(SELECT 1 FROM task t JOIN task_log l ON l.task_id = t.id WHERE t.state IN ('RUNNING', 'WAITING') AND l.id = (SELECT MAX(c.id) FROM task_log c WHERE c.task_id = t.id AND c.kind IN ('STARTED', 'PAUSED')) AND l.kind = 'STARTED')")
    fun runningClockFlow(): Flow<Boolean>

    @Query("SELECT * FROM task WHERE state != 'ENDED' ORDER BY created_at")
    suspend fun unfinishedTasks(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE conversation = :conversation AND state != 'ENDED' ORDER BY created_at")
    suspend fun openIn(conversation: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE conversation = :conversation AND state = 'ENDED' ORDER BY updated_at DESC LIMIT 3")
    suspend fun endedIn(conversation: String): List<TaskEntity>


    @Update
    suspend fun updateTask(row: TaskEntity)

    @Query("SELECT * FROM task WHERE conversation IN (:sessions) OR hand IN (:sessions)")
    suspend fun relatedTo(sessions: List<String>): List<TaskEntity>

    @Query("SELECT t.* FROM task t JOIN task_log l ON l.task_id = t.id WHERE t.conversation = :conversation AND t.source_message_id = :source AND l.kind = 'DRAFT' AND instr(l.body, :draft) > 0 ORDER BY l.id DESC LIMIT 1")
    suspend fun draftTask(conversation: String, source: String, draft: String): TaskEntity?

    @Query("SELECT t.* FROM task t JOIN task_log l ON l.task_id = t.id WHERE t.conversation = :conversation AND t.source_message_id = :source AND l.kind = 'DRAFT' AND instr(l.body, :draft) > 0 ORDER BY l.id DESC LIMIT 1")
    fun draftFlow(conversation: String, source: String, draft: String): Flow<TaskEntity?>

    @Query("DELETE FROM inbox WHERE task_id = :task")
    suspend fun deleteTaskInbox(task: String)

    @Query("SELECT * FROM task ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentTasks(limit: Int): List<TaskEntity>

    /** The task [hand] is on: one at a time, so its session is enough to find it. */
    @Query("SELECT * FROM task WHERE hand = :hand AND state IN ('RUNNING', 'WAITING') LIMIT 1")
    suspend fun heldBy(hand: String): TaskEntity?

    @Query("SELECT * FROM task WHERE hand = :hand AND state IN ('RUNNING', 'WAITING') ORDER BY created_at")
    suspend fun onHand(hand: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE state != 'ENDED' AND id IN (SELECT task_id FROM task_log WHERE kind = 'COMMAND')")
    suspend fun commandTasks(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE state != 'ENDED' AND id IN (SELECT task_id FROM task_log WHERE kind = 'APPROVAL')")
    suspend fun approvalTasks(): List<TaskEntity>

    @Query("SELECT t.* FROM task t JOIN task_log l ON l.task_id = t.id WHERE t.hand = :hand AND l.kind = 'COMMAND' AND instr(l.body, :processNeedle) > 0 ORDER BY l.id DESC LIMIT 1")
    suspend fun byExecution(hand: String, processNeedle: String): TaskEntity?

    @Query("SELECT * FROM task WHERE id IN (SELECT body FROM task_log WHERE task_id = :parent AND kind = 'CHILD') ORDER BY created_at, rowid")
    suspend fun children(parent: String): List<TaskEntity>

    @Query("SELECT body FROM task_log WHERE task_id = :id AND kind = 'SCREEN' ORDER BY id LIMIT 1")
    suspend fun screenSpec(id: String): String?

    @Query("SELECT * FROM task WHERE hand = 'screen' AND state = 'RUNNING'")
    suspend fun interruptedScreens(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE hand = :hand AND state = 'QUEUED' ORDER BY created_at, rowid LIMIT 1")
    suspend fun nextFor(hand: String): TaskEntity?

    @Query("SELECT COUNT(*) FROM task WHERE hand = :hand AND state = 'QUEUED'")
    fun queuedCountFlow(hand: String): Flow<Int>

    /** Helpers (hidden child chats) on a task now; they share [Helpers.SLOTS]. */
    @Query("SELECT COUNT(*) FROM task t JOIN sessions s ON s.id = t.hand WHERE s.parent_session_id IS NOT NULL AND t.state IN ('RUNNING', 'WAITING')")
    suspend fun helpersAtWork(): Int

    @Query("SELECT t.* FROM task t JOIN sessions s ON s.id = t.hand WHERE s.parent_session_id IS NOT NULL AND t.state = 'QUEUED' ORDER BY t.created_at LIMIT 1")
    suspend fun nextForHelpers(): TaskEntity?

    @Query("SELECT t.* FROM task t JOIN sessions s ON s.id = t.hand WHERE s.parent_session_id IS NOT NULL AND t.state = 'QUEUED' ORDER BY t.created_at")
    suspend fun queuedHelpers(): List<TaskEntity>

    @Insert
    suspend fun insertLog(row: TaskLogEntity): Long

    @Query("SELECT * FROM task_log WHERE task_id = :taskId ORDER BY id")
    suspend fun log(taskId: String): List<TaskLogEntity>
}
