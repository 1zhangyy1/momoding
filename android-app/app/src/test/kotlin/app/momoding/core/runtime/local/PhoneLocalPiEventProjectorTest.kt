package app.momoding.core.runtime.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.TaskRunState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.PiSessionSnapshotEntity
import app.momoding.core.data.RoomProjectionTransactionStore
import app.momoding.core.data.TaskDetailRepository
import app.momoding.core.data.TaskRepository
import app.momoding.feature.taskdetail.PiUiReducer
import app.momoding.feature.taskdetail.TaskDetailRunState
import app.momoding.feature.taskdetail.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalPiEventProjectorTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `one local task is visible immediately streams native events and settles from Pi session`() =
        runTest {
            var now = 1_000L
            val projector = PhoneLocalPiEventProjector(
                database = database,
                emittedAt = { "2026-07-20T00:00:00.000Z" },
                nowMillis = { now++ },
            )
            projector.createTask(
                taskId = TASK_ID,
                title = "Review this Android project",
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                initialPrompt = "Review this Android project.",
            )

            val initialDetail = TaskDetailRepository(database).observe(TASK_ID)
                .filterNotNull()
                .first()
            assertEquals("STARTING", initialDetail.runState)
            assertEquals(1, initialDetail.messages.size)
            assertEquals(
                "user",
                Json.parseToJsonElement(initialDetail.messages.single().rawPayload)
                    .jsonObject["role"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "Review this Android project",
                TaskRepository(database, Dispatchers.Unconfined)
                    .observeTaskRows()
                    .first()
                    .single()
                    .title,
            )

            val events = listOf(
                buildJsonObject { put("type", "agent_start") },
                buildJsonObject {
                    put("type", "message_update")
                    put("message", assistantMessage("A concise review."))
                },
                buildJsonObject { put("type", "settled") },
            )
            projector.append(TASK_ID, SESSION_ID, STREAM_ID, events)
            val streaming = TaskDetailRepository(database).observe(TASK_ID)
                .filterNotNull()
                .first { it.rawEvents.size == events.size }
            assertEquals(3, streaming.throughSequence)
            assertEquals(
                "A concise review.",
                (PiUiReducer().reduce(streaming).timeline.activeItem as? TimelineItem.AssistantText)
                    ?.text
                    ?: (PiUiReducer().reduce(streaming).timeline.settledItems
                        .filterIsInstance<TimelineItem.AssistantText>()
                        .last())
                    .text,
            )

            projector.replaceWithSessionSnapshot(
                taskId = TASK_ID,
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                snapshot = PiNativeTaskSessionSnapshot(
                    taskId = TASK_ID,
                    turnCount = 1,
                    entries = buildJsonArray {
                        add(messageEntry(userMessage("Review this Android project.")))
                        add(messageEntry(assistantMessage("A concise review.")))
                    },
                ),
                runState = TaskRunState.COMPLETED,
            )

            val settled = TaskDetailRepository(database).observe(TASK_ID)
                .filterNotNull()
                .first { it.runState == "COMPLETED" }
            assertTrue(settled.rawEvents.isEmpty())
            assertEquals(3, settled.throughSequence)
            assertEquals(
                listOf("user", "assistant"),
                settled.messages.map { row ->
                    Json.parseToJsonElement(row.rawPayload)
                        .jsonObject["role"]
                        ?.jsonPrimitive
                        ?.content
                },
            )
            val ui = PiUiReducer().reduce(settled)
            assertEquals(TaskDetailRunState.SETTLED, ui.runState)
            assertEquals(
                listOf("Review this Android project.", "A concise review."),
                ui.timeline.settledItems.mapNotNull { item ->
                    when (item) {
                        is TimelineItem.UserMessage -> item.text
                        is TimelineItem.AssistantText -> item.text
                        else -> null
                    }
                },
            )
            val persisted = requireNotNull(projector.persistedSession(TASK_ID))
            assertEquals(SESSION_ID, persisted.piSessionId)
            assertEquals(1, persisted.snapshot.turnCount)
            assertEquals(2, persisted.snapshot.entries.size)
        }

    @Test
    fun `startup marks an unresumable local run interrupted instead of leaving a false running row`() =
        runTest {
            val projector = PhoneLocalPiEventProjector(
                database = database,
                emittedAt = { "2026-07-20T00:00:00.000Z" },
                nowMillis = { 2_000L },
            )
            projector.createTask(
                taskId = TASK_ID,
                title = "Interrupted task",
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                initialPrompt = "Keep working.",
            )

            assertEquals(1, projector.interruptStaleLocalRuns())
            val interrupted = TaskDetailRepository(database).observe(TASK_ID)
                .filterNotNull()
                .first { it.runState == "INTERRUPTED" }
            assertEquals("INTERRUPTED", interrupted.recoveryState)
            assertEquals(false, interrupted.isStreaming)
        }

    @Test
    fun `one append durably commits a streaming delta batch without rewriting its history`() {
        val projector = PhoneLocalPiEventProjector(
            database = database,
            emittedAt = { "2026-07-20T00:00:00.000Z" },
            nowMillis = { 2_500L },
        )
        projector.createTask(
            taskId = TASK_ID,
            title = "Stream Markdown",
            piSessionId = SESSION_ID,
            streamId = STREAM_ID,
            initialPrompt = "Stream a Markdown answer.",
        )
        val events = buildList {
            add(buildJsonObject { put("type", "agent_start") })
            repeat(48) { index ->
                add(
                    buildJsonObject {
                        put("type", "message_update")
                        put(
                            "message",
                            assistantMessage((0..index).joinToString("") { "x" }),
                        )
                    },
                )
            }
        }

        val proof = projector.append(TASK_ID, SESSION_ID, STREAM_ID, events)
        val durable = RoomProjectionTransactionStore(database).read(TASK_ID)

        assertEquals(events.size, proof.eventCount)
        assertEquals(events.size.toLong(), proof.throughSequence)
        assertEquals(events.size, durable?.rawEvents?.size)
        assertEquals(2, durable?.stagedRawFrameBatches?.size)
        assertEquals(events.size, durable?.stagedRawFrameBatches?.last()?.frames?.size)
        assertEquals(1, database.momodingDao().timeline(TASK_ID).size)
    }

    @Test
    fun `corrupt Pi session snapshot never erases the readable task timeline`() = runTest {
        val projector = PhoneLocalPiEventProjector(
            database = database,
            emittedAt = { "2026-07-20T00:00:00.000Z" },
            nowMillis = { 3_000L },
        )
        projector.createTask(
            taskId = TASK_ID,
            title = "Readable interrupted task",
            piSessionId = SESSION_ID,
            streamId = STREAM_ID,
            initialPrompt = "Keep this readable.",
        )
        database.momodingDao().upsertPiSessionSnapshot(
            PiSessionSnapshotEntity(
                taskId = TASK_ID,
                piSessionId = SESSION_ID,
                entriesJson = "[{\"type\":\"message\",\"id\":\"orphan\"," +
                    "\"parentId\":\"missing\",\"timestamp\":\"2026-07-20T00:00:00.000Z\"}]",
                schemaVersion = PhoneLocalPiSessionStore.SCHEMA_VERSION,
                updatedAtMillis = 3_001L,
            ),
        )

        val failure = runCatching { projector.persistedSession(TASK_ID) }.exceptionOrNull()
        assertEquals("PI_MOBILE_SESSION_SNAPSHOT_CORRUPT", failure?.message)
        val detail = TaskDetailRepository(database).observe(TASK_ID)
            .filterNotNull()
            .first()
        assertEquals(1, detail.messages.size)
        assertEquals(
            "Keep this readable.",
            PiUiReducer().reduce(detail).timeline.settledItems
                .filterIsInstance<TimelineItem.UserMessage>()
                .single()
                .text,
        )
    }

    @Test
    fun `first settled reply stabilizes a concise title without overwriting a user rename`() {
        val projector = PhoneLocalPiEventProjector(database)
        projector.createTask(
            taskId = TASK_ID,
            title = "Please   review the Android project. Then explain every small detail that follows",
            piSessionId = SESSION_ID,
            streamId = STREAM_ID,
            initialPrompt = "Please review the Android project.",
        )

        projector.stabilizeTaskTitle(TASK_ID)
        assertEquals("Review the Android project", database.momodingDao().task(TASK_ID)?.title)
        assertEquals("AUTOMATIC", database.momodingDao().task(TASK_ID)?.titleSource)

        database.momodingDao().upsertTask(
            requireNotNull(database.momodingDao().task(TASK_ID)).copy(
                title = "Review the Android project · preserved after another turn",
            ),
        )
        projector.stabilizeTaskTitle(TASK_ID)
        assertEquals(
            "Review the Android project · preserved after another turn",
            database.momodingDao().task(TASK_ID)?.title,
        )

        database.momodingDao().renameTask(TASK_ID, "My durable task name")
        projector.stabilizeTaskTitle(TASK_ID)
        assertEquals("My durable task name", database.momodingDao().task(TASK_ID)?.title)
        assertEquals("USER", database.momodingDao().task(TASK_ID)?.titleSource)
    }

    @Test
    fun `Skill session control keeps the composer command visible without exposing Skill body`() =
        runTest {
            val projector = PhoneLocalPiEventProjector(database)
            projector.createTask(
                taskId = TASK_ID,
                title = "Mobile review",
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                initialPrompt = "/skill:mobile-review Check the current diff.",
            )
            projector.replaceWithSessionSnapshot(
                taskId = TASK_ID,
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                snapshot = PiNativeTaskSessionSnapshot(
                    taskId = TASK_ID,
                    turnCount = 1,
                    entries = buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "custom")
                                put("id", "skill-control")
                                put("timestamp", "2026-07-22T00:00:00.000Z")
                                put("customType", "pi_mobile_skill_invocation")
                                put(
                                    "data",
                                    buildJsonObject {
                                        put("kind", "skill_invocation")
                                        put("name", "mobile-review")
                                        put("additionalInstructions", "Check the current diff.")
                                    },
                                )
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put("id", "skill-user")
                                put("parentId", "skill-control")
                                put("timestamp", "2026-07-22T00:00:01.000Z")
                                put(
                                    "message",
                                    userMessage(
                                        "<skill name=\"mobile-review\">SECRET SKILL BODY</skill>\n" +
                                            "Check the current diff.",
                                    ),
                                )
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put("id", "skill-assistant")
                                put("parentId", "skill-user")
                                put("timestamp", "2026-07-22T00:00:02.000Z")
                                put("message", assistantMessage("PASS"))
                            },
                        )
                    },
                ),
                runState = TaskRunState.COMPLETED,
            )

            val timeline = PiUiReducer().reduce(
                TaskDetailRepository(database).observe(TASK_ID).filterNotNull().first(),
            ).timeline.settledItems
            assertEquals(
                "/skill:mobile-review Check the current diff.",
                timeline.filterIsInstance<TimelineItem.UserMessage>().single().text,
            )
            assertTrue(timeline.none { it.toString().contains("SECRET SKILL BODY") })
        }

    @Test
    fun `text attachment control restores the visible composer message without exposing its manifest`() =
        runTest {
            val attachment = PiRuntimeTextAttachmentInput(
                attachmentId = "55555555-5555-4555-8555-555555555555",
                displayName = "context.md",
                mimeType = "text/plain",
                byteSize = 42,
            )
            val projector = PhoneLocalPiEventProjector(database)
            projector.createTask(
                taskId = TASK_ID,
                title = "Text attachment",
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                initialPrompt = "Review this file",
                textAttachments = listOf(attachment),
            )
            val manifest =
                "[{\"attachmentId\":\"${attachment.attachmentId}\"," +
                "\"displayName\":\"context.md\",\"mimeType\":\"text/plain\",\"byteSize\":42}]"
            projector.replaceWithSessionSnapshot(
                taskId = TASK_ID,
                piSessionId = SESSION_ID,
                streamId = STREAM_ID,
                snapshot = PiNativeTaskSessionSnapshot(
                    taskId = TASK_ID,
                    turnCount = 1,
                    entries = buildJsonArray {
                        add(buildJsonObject {
                            put("type", "custom")
                            put("id", "text-attachment-control")
                            put("timestamp", "2026-07-22T00:00:00.000Z")
                            put("customType", "pi_mobile_text_attachments")
                            put("data", buildJsonObject {
                                put("kind", "text_attachments")
                                put("originalText", "Review this file")
                                put("attachments", buildJsonArray {
                                    add(buildJsonObject {
                                        put("attachmentId", attachment.attachmentId)
                                        put("displayName", attachment.displayName)
                                        put("mimeType", attachment.mimeType)
                                        put("byteSize", attachment.byteSize)
                                    })
                                })
                            })
                        })
                        add(buildJsonObject {
                            put("type", "message")
                            put("id", "text-attachment-user")
                            put("parentId", "text-attachment-control")
                            put("timestamp", "2026-07-22T00:00:01.000Z")
                            put(
                                "message",
                                userMessage(
                                    "[momoding:text-attachments control=text-attachment-control]\n" +
                                        "Use attachment_read.\n<user_message>\nReview this file\n" +
                                        "</user_message>\nattachments=$manifest",
                                ),
                            )
                        })
                        add(buildJsonObject {
                            put("type", "message")
                            put("id", "text-attachment-assistant")
                            put("parentId", "text-attachment-user")
                            put("timestamp", "2026-07-22T00:00:02.000Z")
                            put("message", assistantMessage("Reviewed."))
                        })
                    },
                ),
                runState = TaskRunState.COMPLETED,
            )

            val detail = TaskDetailRepository(database).observe(TASK_ID).filterNotNull().first()
            val timeline = PiUiReducer().reduce(detail).timeline.settledItems
            val user = timeline.filterIsInstance<TimelineItem.UserMessage>().single()
            assertEquals("Review this file", user.text)
            assertEquals(listOf(attachment.attachmentId), user.attachmentIds)
            assertTrue(detail.messages.none { it.rawPayload.contains("momoding:text-attachments") })
        }

    private fun messageEntry(message: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        val role = requireNotNull(message["role"]?.jsonPrimitive?.content)
        put("type", "message")
        put("id", "entry-$role")
        if (role == "assistant") {
            put("parentId", "entry-user")
        }
        put("timestamp", "2026-07-20T00:00:00.000Z")
        put("message", message)
    }

    private fun userMessage(text: String) = buildJsonObject {
        put("role", "user")
        put("content", textContent(text))
        put("timestamp", 1)
    }

    private fun assistantMessage(text: String) = buildJsonObject {
        put("role", "assistant")
        put("content", textContent(text))
        put("timestamp", 2)
    }

    private fun textContent(text: String): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }

    private companion object {
        const val TASK_ID = "10000000-0000-4000-8000-000000000001"
        const val SESSION_ID = "10000000-0000-4000-8000-000000000002"
        const val STREAM_ID = "10000000-0000-4000-8000-000000000003"
    }
}
