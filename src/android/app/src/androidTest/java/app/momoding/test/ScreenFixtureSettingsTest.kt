package app.momoding.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.model.hasImageInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit fixture-only setup/restore. Never enables Android system permissions. */
@RunWith(AndroidJUnit4::class)
class ScreenFixtureSettingsTest {
    @Test fun configureOrRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("m41_screen_settings")
        assumeTrue(mode in listOf("configure", "restore"))
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("momoding", 0)
        val snapshot = File(instrumentation.context.filesDir, "m41-screen-settings.json")
        if (mode == "configure") {
            val id = requireNotNull(args.getString("m41_screen_model"))
            val config = (context.applicationContext as MinisApp).providerRepository.config.value
            val entry = config.modelEntries.single { it.id == id }
            val provider = config.instances.single { it.id == entry.providerInstanceId }
            require(provider.customBaseURL?.startsWith("http://127.0.0.1:") == true) { "only a local fixture model is accepted" }
            require(entry.model.hasImageInput)
            if (!snapshot.exists()) snapshot.writeText(JSONObject()
                .put("hadEnabled", prefs.contains("hands.enabled")).put("enabled", prefs.getBoolean("hands.enabled", false))
                .put("hadModel", prefs.contains("hands.model_entry")).put("model", prefs.getString("hands.model_entry", null)).toString())
            assertTrue(prefs.edit().putBoolean("hands.enabled", true).putString("hands.model_entry", id).commit())
        } else if (snapshot.exists()) {
            val before = JSONObject(snapshot.readText())
            val edit = prefs.edit()
            if (before.getBoolean("hadEnabled")) edit.putBoolean("hands.enabled", before.getBoolean("enabled")) else edit.remove("hands.enabled")
            if (before.getBoolean("hadModel")) edit.putString("hands.model_entry", before.getString("model")) else edit.remove("hands.model_entry")
            assertTrue(edit.commit())
            assertTrue(snapshot.delete())
        }
    }
}
