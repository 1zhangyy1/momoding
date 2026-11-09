package app.momoding.guard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.ShellExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellApprovalRegressionTest {
    @Test fun pythonEditingJavaScriptDoesNotRequestApproval() {
        val command = listOf(
            "cd /var/minis/apps/snap && python3 - <<'PY'",
            "s=open('js/main.js').read()",
            "s=s.replace('old', '''const z = camera.zoom/10;",
            "for (const v of [1, 2, 3, 5]) stops.push(v);''')",
            "open('js/main.js','w').write(s)", "PY",
        ).joinToString("\n")
        assertEquals(RiskClass.SAFE, ShellGuard.assess(command).riskClass)
        assertFalse(ShellGuard.assess(command).needsApproval)
    }

    @Test fun realOutboundAndDestructiveCommandsStillRequestApproval() {
        assertTrue(ShellGuard.assess("python3 - <<'PY'\nimport requests\nrequests.post('https://example.com', json={})\nPY").needsApproval)
        assertTrue(ShellGuard.assess("sh <<'SH'\nrm -rf /var/minis/workspace/notes\nSH").needsApproval)
    }

    @Test fun approvedLocalHeredocActuallyRunsInsideSandbox() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PRootKernel.boot(context)
        assertTrue("Sandbox must boot; this test must not silently skip", PRootKernel.isBooted)
        val command = "cat <<'JS' > /tmp/momoding-release-check.js\nconst z = camera.zoom/10;\nfor (const v of [1, 2, 3, 5]) stops.push(v);\nJS\ncat /tmp/momoding-release-check.js"
        assertFalse(ShellGuard.assess(command).needsApproval)
        val result = ShellExecutor.execute(context, command)
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("stops.push(v)"))
        assertTrue(result.output.contains("camera.zoom/10"))
    }
}
