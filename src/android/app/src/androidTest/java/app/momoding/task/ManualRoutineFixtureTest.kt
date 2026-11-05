package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only prepares an owned stored recipe. The probe executes the actual Linux CLI afterwards. */
class ManualRoutineFixtureTest {
    @Test fun prepareOwnedManualRecipe(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); val sid = args.getString("m41_manual_parent")
        assumeTrue(!sid.isNullOrBlank()); val owner = requireNotNull(sid)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        val parent = requireNotNull(app.chatRepository.getSession(owner)); require(parent.title?.startsWith("M41 manual") == true)
        val id = requireNotNull(args.getString("m41_manual_routine")); java.util.UUID.fromString(id)
        val manager = ScheduledTaskManager(app)
        if (args.getString("m41_manual_phase") == "cleanup") { manager.delete(id); return@runBlocking }
        val mode = args.getString("m41_manual_mode") ?: "child"
        val target = when (mode) {
            "child" -> "CHILD_OF:$owner"
            "new" -> "NEW_SESSION"
            "rerun" -> "RERUN:$owner:" + app.database.chatDao().loadMessages(owner).first { it.role == "user" }.id
            else -> "APPEND_TO:$owner"
        }
        manager.create(ScheduledTask.fromJson(JSONObject().put("id", id).put("label", "M41 manual $id")
            .put("prompt", "M41_MANUAL_WORK $id").put("targetMode", target).put("enabled", false)
            .put("modelBinding", parent.modelBinding).put("modelId", parent.modelId)))
    }
}
