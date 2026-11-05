package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledCompletionTriggers
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in faults in the actual runner, using stable public APIs so the same fixture runs on the baseline APK. */
class RoutineDeliveryFixtureTest {
    @Test fun ownedDeliveryFault(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val sid = args.getString("m41_delivery_parent"); assumeTrue(!sid.isNullOrBlank()); val owner = requireNotNull(sid)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        val parent = requireNotNull(app.chatRepository.getSession(owner)); require(parent.title?.startsWith("M41 delivery") == true)
        val manager = ScheduledTaskManager(app); val sql = app.database.openHelper.writableDatabase
        val file = File(app.filesDir, "m41-delivery-proof.json"); val trigger = "m41_delivery_abort_" + owner.replace("-", "")
        val phase = args.getString("m41_delivery_phase") ?: "prepare"
        if (phase == "cleanup") {
            val proof = JSONObject(file.readText()); require(proof.getString("owner") == owner)
            manager.delete(proof.getString("routine")); proof.optString("dependent").takeIf(String::isNotBlank)?.let(manager::delete)
            sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
            return@runBlocking
        }
        if (phase == "requeue") {
            val proof = JSONObject(file.readText()); require(proof.getString("owner") == owner)
            val task = manager.get(proof.getString("routine"))!!
            // Reconstruct the crash boundary: Room has accepted this exact ID,
            // while the preference claim still awaits acknowledgement.
            manager.store().upsert(ScheduledTask.fromJson(task.toJson()
                .put("pendingDispatch", proof.getJSONObject("definition").getJSONObject("pendingDispatch"))))
            return@runBlocking
        }
        if (phase == "drop") {
            val proof = JSONObject(file.readText()); require(proof.getString("owner") == owner)
            sql.execSQL("DROP TRIGGER IF EXISTS $trigger"); proof.put("trigger_dropped", true)
            proof.put("definition", manager.get(proof.getString("routine"))!!.toJson())
            file.writeText(proof.toString()); return@runBlocking
        }
        val mode = args.getString("m41_delivery_mode") ?: "append"
        val token = UUID.randomUUID().toString(); val routine = UUID.randomUUID().toString(); val upstream = UUID.randomUUID().toString()
        val label = "M41 delivery $token"
        val target = when (mode) {
            "child" -> "CHILD_OF:$owner"
            "new" -> "NEW_SESSION"
            "rerun" -> "RERUN:$owner:" + app.database.chatDao().loadMessages(owner).first { it.role == "user" }.id
            else -> "APPEND_TO:$owner"
        }
        val json = JSONObject().put("id", routine).put("label", label).put("prompt", "M41_DELIVERY $token {{result}}")
            .put("targetMode", target).put("trigger", "ON_COMPLETION").put("onCompletionOf", upstream).put("repeatMode", "ONCE")
            .put("modelBinding", parent.modelBinding).put("modelId", parent.modelId).put("thinkingLevel", "HIGH")
        val loggingPrefs = app.getSharedPreferences("logging_prefs", 0)
        val hadLogging = loggingPrefs.contains("logging_enabled"); val logging = AppLogger.isEnabled(app)
        var prepared = false
        try {
            AppLogger.setEnabled(app, true)
            if (phase == "hold") {
                val field = com.openminis.app.scheduled.ScheduledAgentRunner::class.java.getDeclaredField("completionMutex").apply { isAccessible = true }
                (field.get(null) as Mutex).lock() // Process death, not an unlock, ends this opt-in fault.
                val dependent = UUID.randomUUID().toString()
                manager.create(ScheduledTask.fromJson(JSONObject().put("id", dependent).put("label", "M41 delivery dependent")
                    .put("prompt", "M41_DELIVERY_NEXT {{result}}").put("targetMode", "APPEND_TO:$owner")
                    .put("trigger", "ON_COMPLETION").put("onCompletionOf", routine).put("repeatMode", "ONCE")))
                json.put("dependent", dependent)
            } else {
                val table = if (mode == "child") "task" else "inbox"
                val condition = if (mode == "child") "NEW.conversation = '$owner'" else "NEW.sender = 'ROUTINE' AND instr(NEW.text, '$token') > 0"
                sql.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'owned scheduled delivery fault'); END")
            }
            manager.create(ScheduledTask.fromJson(json)); ScheduledCompletionTriggers.onUpstreamFinished(app, upstream, "UPSTREAM_FULL_RESULT")
            val proof = JSONObject().put("owner", owner).put("routine", routine).put("upstream", upstream).put("mode", mode).put("token", token)
            if (phase == "hold") {
                val received = withTimeout(20_000) {
                    var row: com.openminis.app.data.db.MessageEntity? = null
                    while (row == null) {
                        row = app.database.chatDao().loadMessages(owner).firstOrNull { it.role == "user" && "\"routineResult\":" in it.partsJson }
                        if (row == null) delay(25)
                    }; row
                }
                proof.put("input", received.id).put("input_parts", received.partsJson).put("dependent", json.getString("dependent"))
                proof.put("reply_count", app.database.chatDao().loadMessages(owner).count { it.role == "assistant" })
            } else {
                val failure = withTimeout(15_000) {
                    var line: String? = null
                    while (line == null) {
                        line = AppLogger.listLogFileMetas("minis-", 2).asSequence().mapNotNull { AppLogger.readLog(it.name) }
                            .flatMap { it.lineSequence() }.lastOrNull { routine in it && "owned scheduled delivery fault" in it }
                        if (line == null) delay(25)
                    }; line
                }
                proof.put("actual_dispatch_failure", failure)
            }
            proof.put("definition", manager.get(routine)!!.toJson())
            proof.put("children", JSONArray(app.database.chatDao().childSessionIds(owner)))
            proof.put("inputs", app.database.taskDao().inboxFor(owner).size)
            proof.put("tasks", app.database.taskDao().openIn(owner).size)
            sql.query("SELECT COUNT(*) FROM sessions WHERE title = '$label'").use { check(it.moveToFirst()); proof.put("new_sessions", it.getInt(0)) }
            file.writeText(proof.toString()); prepared = true
        } finally {
            AppLogger.setEnabled(app, logging)
            if (!hadLogging) loggingPrefs.edit().remove("logging_enabled").commit()
            if (!prepared) { sql.execSQL("DROP TRIGGER IF EXISTS $trigger"); manager.delete(routine) }
        }
    }
}
