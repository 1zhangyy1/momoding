package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.guard.Grants
import app.momoding.guard.RiskClass
import app.momoding.guard.ShellGuard
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** A restorable workspace permission used only by an isolated counter fixture. */
class TranscriptFixtureSettingsTest {
    @Test fun configureOrRestoreOwnedFixture() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("m41_transcript_mode")
        assumeTrue(mode in listOf("configure", "restore"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "minis-global/momoding/grants.json")
        val snapshot = File(context.filesDir, "m41-transcript-grants.json")
        if (mode == "restore") {
            if (snapshot.exists()) {
                val before = JSONObject(snapshot.readText())
                if (before.getBoolean("existed")) file.writeText(before.getString("text")) else file.delete()
                assertTrue(snapshot.delete())
            }
            return
        }
        val session = args.getString("m41_transcript_session")
        require(!session.isNullOrBlank())
        val command = requireNotNull(args.getString("m41_transcript_command"))
        val assessment = ShellGuard.assess(command)
        assertEquals(RiskClass.DESTRUCTIVE, assessment.riskClass)
        assertEquals(ShellGuard.WORKSPACE, assessment.target)
        require(command.startsWith("rm -f /var/minis/workspace/m41-transcript-"))
        require(command.contains("M41_TRANSCRIPT_EFFECT"))
        check(!snapshot.exists()) { "Restore the previous fixture before configuring another." }
        snapshot.writeText(JSONObject().put("existed", file.exists()).put("text", if (file.exists()) file.readText() else "").toString())
        Grants.grantAlways(assessment.riskClass, requireNotNull(assessment.target), "M41 transcript counter fixture")
    }
}
