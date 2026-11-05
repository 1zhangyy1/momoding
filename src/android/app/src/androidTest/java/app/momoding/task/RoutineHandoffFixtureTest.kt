package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Fault the actual scheduled runner's task INSERT, without modifying its implementation. */
class RoutineHandoffFixtureTest {
    @Test fun failOwnedRoutineHandoff(): Unit = runBlocking {
        val sid = InstrumentationRegistry.getArguments().getString("m41_routine_handoff_parent")
        assumeTrue(!sid.isNullOrBlank()); val owner = requireNotNull(sid)
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(owner)?.title?.startsWith("M41 routine handoff") == true)
        val sql = app.database.openHelper.writableDatabase; val trigger = "m41_routine_abort_" + owner.replace("-", "")
        val file = File(context.filesDir, "m41-routine-handoff-proof.json")
        if (InstrumentationRegistry.getArguments().getString("m41_routine_handoff_phase") == "inspect") {
            val proof = JSONObject(file.readText()); require(proof.getString("owner") == owner)
            val children = app.database.chatDao().childSessionIds(owner)
            proof.put("children", JSONArray(children)).put("tasks", app.database.taskDao().openIn(owner).size)
            sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
            sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = '$trigger'").use {
                check(it.moveToFirst()); proof.put("trigger_dropped", it.getInt(0) == 0)
            }
            for (child in children) app.database.chatDao().deleteSession(child)
            file.writeText(proof.toString()); return@runBlocking
        }
        var prepared = false
        val logPrefs = context.getSharedPreferences("logging_prefs", 0)
        val hadLogging = logPrefs.contains("logging_enabled"); val logging = AppLogger.isEnabled(context)
        try {
            AppLogger.setEnabled(context, true)
            sql.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON task WHEN NEW.conversation = '$owner' BEGIN SELECT RAISE(ABORT, 'owned routine handoff fault'); END")
            val routine = ScheduledTask(label = "M41 routine fault", prompt = "owned failure check", timeOfDayHour = 0, timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.ONCE, targetMode = ScheduledTargetMode.ChildOfCurrent(owner), enabled = false, thinkingLevel = ThinkingLevel.HIGH)
            val returned = ScheduledAgentRunner.run(context, routine, waitForCompletion = false)
            val failure = withTimeout(10_000) {
                var line: String? = null
                while (line == null) {
                    line = AppLogger.listLogFileMetas("minis-", 2).asSequence().mapNotNull { AppLogger.readLog(it.name) }
                        .flatMap { it.lineSequence() }.lastOrNull { routine.id in it && "owned routine handoff fault" in it }
                    if (line == null) delay(25)
                }
                line
            }
            file.writeText(JSONObject().put("owner", owner).put("routine", routine.id).put("returned", returned ?: JSONObject.NULL)
                .put("actual_dispatch_failure", failure).toString())
            prepared = true // The controller waits for this routine's real SQL error before inspecting and dropping the trigger.
        } finally {
            AppLogger.setEnabled(context, logging)
            if (!hadLogging) logPrefs.edit().remove("logging_enabled").commit()
            if (!prepared) sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
        }
    }
}
