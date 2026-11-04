package app.momoding.task

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.ui.chat.ShowSteps
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, restorable developer view used only for the owned UI fixture. */
class ChatControlsFixtureTest {
    @Test fun configureOrRestoreToolView() {
        val mode = InstrumentationRegistry.getArguments().getString("m41_controls_mode")
        assumeTrue(mode in listOf("configure", "restore"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("momoding", Context.MODE_PRIVATE)
        val snapshot = File(context.filesDir, "m41-controls-settings.json")
        if (mode == "configure") {
            check(!snapshot.exists()) { "Restore the previous fixture first" }
            snapshot.writeText(JSONObject().put("present", prefs.contains("dev.showSteps"))
                .put("value", ShowSteps.get(context)).toString())
            check(prefs.edit().putBoolean("dev.showSteps", true).commit())
            assertTrue(ShowSteps.get(context))
        } else if (snapshot.exists()) {
            val before = JSONObject(snapshot.readText())
            prefs.edit().also { edit ->
                if (before.getBoolean("present")) edit.putBoolean("dev.showSteps", before.getBoolean("value"))
                else edit.remove("dev.showSteps")
            }.commit()
            assertEquals(before.getBoolean("present"), prefs.contains("dev.showSteps"))
            assertEquals(before.getBoolean("value"), ShowSteps.get(context))
            assertTrue(snapshot.delete())
        }
    }
}
