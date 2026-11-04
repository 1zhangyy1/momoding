package app.momoding.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShellGuardHereDocumentTest {
    @Test fun `editing app JavaScript in a Python heredoc does not ask to send it`() {
        val command = listOf(
            "cd /var/minis/apps/snap && python3 - <<'PY'",
            "s=open('js/main.js').read()",
            "s=s.replace(\"\"\"old\"\"\", \"\"\"const z = camera.zoom/10;",
            "for (const v of [1, 2, 3, 5]) if (v >= z.min && v <= z.max) stops.push(v);",
            "zoomRow.hidden = stops.length < 2;",
            "\"\"\")",
            "open('js/main.js','w').write(s)", "PY",
        ).joinToString("\n")
        val result = ShellGuard.assess(command)
        assertEquals(RiskClass.SAFE, result.riskClass)
        assertFalse(result.needsApproval)
    }

    @Test fun `network calls in Python string data and comments do not execute`() {
        assertEquals(RiskClass.SAFE, ShellGuard.assess("python3 - <<'PY'\ns='''requests.post('https://example.com')\nshutil.rmtree('notes')'''\n# requests.post('https://example.com')\nopen('code.txt','w').write(s)\nPY").riskClass)
    }

    @Test fun `an actual Python send in a heredoc still asks`() {
        assertEquals(RiskClass.OUTBOUND, ShellGuard.assess("python3 - <<'PY'\nimport requests\nrequests.post('https://example.com', json={})\nPY").riskClass)
    }

    @Test fun `a real shell send after the closing delimiter still asks`() {
        assertEquals(RiskClass.OUTBOUND, ShellGuard.assess("python3 - <<'PY'\nprint('ok')\nPY\ncurl -X POST https://example.com -d data").riskClass)
    }

    @Test fun `commands on the heredoc header are checked too`() {
        assertEquals(RiskClass.OUTBOUND, ShellGuard.assess("cat <<'DATA' | curl -X POST https://example.com -d @-\nhello\nDATA").riskClass)
    }

    @Test fun `shell heredoc deleting user files still asks`() {
        assertEquals(RiskClass.DESTRUCTIVE, ShellGuard.assess("sh <<'END-SH'\nrm -rf /var/minis/workspace/notes\nEND-SH").riskClass)
    }

    @Test fun `a data heredoc does not execute its content`() {
        assertEquals(RiskClass.SAFE, ShellGuard.assess("cat <<'DATA' > js/main.js\nrequests.post('https://example.com')\nrm -rf notes\nDATA").riskClass)
    }

    @Test fun `tab stripped heredocs keep the script together`() {
        assertEquals(RiskClass.SAFE, ShellGuard.assess("python3 - <<-'PY'\n\tprint('stops.push(v)')\n\tPY").riskClass)
        assertEquals(RiskClass.OUTBOUND, ShellGuard.assess("python3 - <<-'PY'\nimport requests\nrequests.post('https://example.com')\n\tPY").riskClass)
    }

    @Test fun `unquoted heredoc shell substitutions are still checked`() {
        val command = "cat <<DATA\n" + '$' + "(curl -X POST https://example.com -d data)\nDATA"
        assertEquals(RiskClass.OUTBOUND, ShellGuard.assess(command).riskClass)
    }

    @Test fun `quoted heredoc does not expand shell substitutions`() {
        val command = "cat <<'DATA'\n" + '$' + "(curl -X POST https://example.com -d data)\nDATA"
        assertEquals(RiskClass.SAFE, ShellGuard.assess(command).riskClass)
    }

    @Test fun `Python inline file data and real sends remain distinct`() {
        assertEquals(RiskClass.SAFE, ShellGuard.assess("python3 -c \"s='requests.post(1)'; print(s)\"").riskClass)
        assertEquals(RiskClass.OUTBOUND, ShellGuard.assess("python3 -c \"import requests; requests.post('https://example.com')\"").riskClass)
    }
}
