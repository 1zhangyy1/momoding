package app.momoding.task

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.util.Calendar
import java.util.UUID

/** Opt-in real broadcasts/notification entry; the same fixture runs against the pre-fix app. */
class ScheduledClaimFixtureTest {
    @Test fun ownedClaimFault(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); val sid = args.getString("m41_claim_parent")
        assumeTrue(!sid.isNullOrBlank()); val owner = requireNotNull(sid)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        val parent = requireNotNull(app.chatRepository.getSession(owner)); require(parent.title?.startsWith("M41 claim") == true)
        val manager = ScheduledTaskManager(app); val sql = app.database.openHelper.writableDatabase
        val file = File(app.filesDir, "m41-claim-proof.json"); val trigger = "m41_claim_abort_" + owner.replace("-", "")
        val phase = args.getString("m41_claim_phase") ?: "prepare"
        fun broadcast(id: String) = app.sendBroadcast(Intent(app, ScheduledTaskAlarmReceiver::class.java)
            .setAction(ScheduledTaskManager.ACTION_FIRE).putExtra(ScheduledTaskManager.EXTRA_TASK_ID, id))
        if (phase != "prepare") {
            val proof = JSONObject(file.readText()); require(proof.getString("owner") == owner)
            val id = proof.getString("routine")
            when (phase) {
                "drop" -> {
                    // Complete the startup retry while the SQL fault still exists. Then
                    // dropping it cannot race an instrumentation shutdown with a fresh model.
                    manager.get(id)?.toJson()?.optJSONObject("pendingDispatch")?.let { json ->
                        val pending = PendingScheduledFire.parse(json); ScheduledAgentRunner.deliverPending(app, pending)
                        val field = ScheduledAgentRunner::class.java.getDeclaredField("deliveries").apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST") val jobs = field.get(null) as Map<String, Job>
                        withTimeout(15_000) { jobs[pending.id]?.join() }
                    }
                    sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
                    sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = '$trigger'").use {
                        check(it.moveToFirst()); check(it.getInt(0) == 0); proof.put("trigger_dropped", true)
                    }; file.writeText(proof.toString())
                }
                "requeue" -> manager.store().upsert(ScheduledTask.fromJson(manager.get(id)!!.toJson()
                    .put("pendingDispatch", proof.getJSONObject("definition").getJSONObject("pendingDispatch"))))
                "duplicate" -> {
                    val prefs = app.getSharedPreferences("logging_prefs", 0)
                    val had = prefs.contains("logging_enabled"); val enabled = AppLogger.isEnabled(app)
                    fun deliveries() = AppLogger.listLogFileMetas("minis-", 2).sumOf { meta ->
                        AppLogger.readLog(meta.name)?.lineSequence()?.count { id in it && "handoff done in" in it } ?: 0
                    }
                    try {
                        AppLogger.setEnabled(app, true)
                        val before = deliveries(); broadcast(id); broadcast(id)
                        withTimeout(10_000) { while (deliveries() < before + 2) delay(25) }
                        proof.put("duplicate_broadcasts_finished", 2); file.writeText(proof.toString())
                    } finally {
                        AppLogger.setEnabled(app, enabled)
                        if (!had) prefs.edit().remove("logging_enabled").commit()
                    }
                }
                "cleanup" -> {
                    manager.delete(id); proof.optString("dependent").takeIf(String::isNotBlank)?.let(manager::delete)
                    sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
                }
            }
            return@runBlocking
        }
        val kind = args.getString("m41_claim_kind") ?: "after"
        val routine = UUID.randomUUID().toString(); val token = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        val clock = Calendar.getInstance().apply { timeInMillis = now }
        val slot = (clock.clone() as Calendar).apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val json = JSONObject().put("id", routine).put("label", "M41 claim $token").put("prompt", "M41_CLAIM $token {{notification}}")
            .put("createdAt", now - 3_600_000).put("targetMode", if (kind == "interval") "CHILD_OF:$owner" else "APPEND_TO:$owner")
            .put("trigger", when (kind) { "notification" -> "ON_NOTIFICATION"; "calendar" -> "CALENDAR"; "interval" -> "INTERVAL"; else -> "AFTER" })
            .put("repeatMode", if (kind == "calendar") "DAILY" else "ONCE").put("delaySec", 60).put("intervalSec", 3600).put("maxFires", 2)
            .put("hour", clock.get(Calendar.HOUR_OF_DAY)).put("minute", clock.get(Calendar.MINUTE))
            .put("anchorMs", when (kind) { "calendar" -> slot - 1; "interval" -> now - 3_600_000; else -> now - 60_000 })
            .put("onNotificationApp", "owned.claim.fixture").put("modelBinding", parent.modelBinding).put("modelId", parent.modelId)
        val proof = JSONObject().put("owner", owner).put("routine", routine).put("kind", kind).put("token", token)
        val logPrefs = app.getSharedPreferences("logging_prefs", 0)
        val hadLogging = logPrefs.contains("logging_enabled"); val logging = AppLogger.isEnabled(app)
        var prepared = false
        try {
            AppLogger.setEnabled(app, true)
            val table = if (kind == "interval") "task" else "inbox"
            val condition = if (kind == "interval") "NEW.conversation = '$owner' AND EXISTS(SELECT 1 FROM task WHERE conversation = '$owner')"
                else "NEW.sender = 'ROUTINE' AND instr(NEW.text, '$token') > 0"
            sql.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'owned scheduled claim fault'); END")
            if (kind == "interval") {
                val dep = UUID.randomUUID().toString(); proof.put("dependent", dep)
                manager.create(ScheduledTask.fromJson(JSONObject().put("id", dep).put("label", "M41 claim dependent")
                    .put("prompt", "M41_CLAIM_NEXT {{result}}").put("targetMode", "APPEND_TO:$owner")
                    .put("trigger", "ON_COMPLETION").put("onCompletionOf", routine)))
            }
            manager.store().upsert(ScheduledTask.fromJson(json))
            if (kind == "notification") {
                val posted = ScheduledNotificationTriggers.Posted("owned.claim.fixture", "Fixture", "$token title", "original body")
                proof.put("claimed", ScheduledNotificationTriggers.dispatch(app, posted))
                proof.put("burst_claimed", ScheduledNotificationTriggers.dispatch(app, posted.copy(text = "replacement body")))
            } else broadcast(routine)
            if (kind == "interval") {
                withTimeout(20_000) {
                    while (JSONObject(URL("http://127.0.0.1:18791/status").readText()).optInt(routine) < 1) delay(25)
                }
                // Advance only the owned interval's deadline, without changing global time.
                val saved = manager.get(routine)!!.toJson().put("anchorMs", System.currentTimeMillis() - 3_600_000)
                manager.store().upsert(ScheduledTask.fromJson(saved)); broadcast(routine)
            }
            val failure = withTimeout(20_000) {
                var line: String? = null
                while (line == null) {
                    line = AppLogger.listLogFileMetas("minis-", 2).asSequence().mapNotNull { AppLogger.readLog(it.name) }
                        .flatMap { it.lineSequence() }.lastOrNull { routine in it && "owned scheduled claim fault" in it }
                    if (line == null) delay(25)
                }; line
            }
            proof.put("actual_dispatch_failure", failure)
            if (kind == "interval") {
                URL("http://127.0.0.1:18791/release").readText()
                val work = withTimeout(20_000) {
                    var found: TaskEntity? = null
                    while (found == null) {
                        found = app.database.taskDao().relatedTo(listOf(owner)).firstOrNull {
                            it.conversation == owner && it.state == "ENDED" &&
                                app.database.taskDao().log(it.id).any { log -> log.kind == "ROUTINE_RECORDED" }
                        }; if (found == null) delay(25)
                    }; found
                }
                val text = app.database.taskDao().log(work.id).last { it.kind == "END" }.body
                ScheduledCompletionTriggers.onScheduledRunFinished(app, routine, text)
                proof.put("first_work", work.id).put("first_result", text)
                proof.put("dependent_definition", manager.get(proof.getString("dependent"))!!.toJson())
            }
            proof.put("definition", manager.get(routine)!!.toJson())
            proof.put("children", JSONArray(app.database.chatDao().childSessionIds(owner)))
            proof.put("tasks", app.database.taskDao().relatedTo(listOf(owner)).count { it.conversation == owner })
            proof.put("inputs", app.database.taskDao().inboxFor(owner).size)
            file.writeText(proof.toString()); prepared = true
        } finally {
            AppLogger.setEnabled(app, logging)
            if (!hadLogging) logPrefs.edit().remove("logging_enabled").commit()
            if (!prepared) {
                sql.execSQL("DROP TRIGGER IF EXISTS $trigger"); manager.delete(routine)
                proof.optString("dependent").takeIf(String::isNotBlank)?.let(manager::delete)
                if (kind == "interval") runCatching { URL("http://127.0.0.1:18791/release").readText() }
            }
        }
    }
}
