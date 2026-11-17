package app.momoding.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellGuardTest {
    private fun cls(cmd: String) = ShellGuard.assess(cmd).riskClass
    private fun asks(cmd: String) = ShellGuard.assess(cmd).needsApproval

    // ── the acceptance lines from the plan ─────────────────────────────────────────────────

    @Test fun `deleting inside the sandbox is the agent's own business`() {
        for (cmd in listOf(
            "rm -rf /var/minis/workspace/x", "rm report.md", "rm -rf /tmp/build && rm /var/tmp/cache.json",
            "rm -f \$TMPDIR/a.txt", "cd /tmp && C=/tmp/ck.txt && rm -f \$C", "rm /var/minis/shared/photos/a.jpg",
            "rm -rf /var/minis/workspace", "rm -rf .", "rm -rf *", "rm -rf /var/minis/apps/kaipai",
            "git reset --hard HEAD~1", "git clean -fd", "find ./out -name '*.tmp' -delete", "ls | xargs rm -f",
            "shred -u notes.txt", "truncate -s 0 log.txt",
            "python3 -c \"import shutil; shutil.rmtree('out')\"",
        )) assertFalse(cmd, asks(cmd))
    }

    @Test fun `the phone's own files ask`() {
        val a = ShellGuard.assess("rm /var/minis/mounts/phone/Movies/a.mp4")
        assertEquals(RiskClass.DESTRUCTIVE, a.riskClass)
        assertEquals(ShellGuard.PHONE, a.target)
        assertTrue(a.warnings.isEmpty())
        assertEquals(ShellGuard.PHONE, ShellGuard.assess("cd /var/minis/mounts/phone/Download && rm -f big.mp4 old.zip").target)
        assertTrue(asks("find /var/minis/mounts/phone/DCIM -name '*.tmp' -delete"))
        assertTrue(asks("python3 -c \"import os; os.remove('/var/minis/mounts/phone/a.jpg')\""))
        // after the command, relative paths are the workspace again
        assertFalse(asks("rm report.md"))
    }

    @Test fun `wiping what holds the phone is a warning with no standing grant`() {
        for (cmd in listOf("rm -rf /", "rm -rf /var/minis", "rm -rf /*")) {
            val a = ShellGuard.assess(cmd)
            assertEquals(cmd, RiskClass.DESTRUCTIVE, a.riskClass)
            assertTrue(cmd, a.warnings.isNotEmpty())
        }
    }

    // ── reads and writes stay quiet ────────────────────────────────────────────────────────

    @Test fun `plain work is safe`() {
        for (cmd in listOf(
            "ls -la", "cat notes.md | head -20", "grep -rn TODO src/", "python3 report.py > out.csv",
            "curl -s https://api.example.com/v1/items", "wget https://example.com/a.zip -O /tmp/a.zip", "wget -qO- -T 20 https://api.github.com/repos/o/r/releases/latest",
            "git status && git log --oneline -5", "git commit -am 'x'", "mkdir -p out && cp a.txt out/",
            "sed -i 's/a/b/' file.txt", "echo hi > /tmp/x", "lark-cli calendar +agenda --today",
            "lark-cli im +list-chats", "gh pr list", "gh api repos/o/r/issues", "rmdir empty",
            "minis-config set defaults.theme dark", "find . -name '*.log'", "kill -9 1234",
            "rsync -a src/ dst/", "ssh-keygen -t ed25519 -f /tmp/k -N ''",
        )) assertEquals(cmd, RiskClass.SAFE, cls(cmd))
    }

    // ── sending ────────────────────────────────────────────────────────────────────────────

    @Test fun `writes to people are outbound with the host as target`() {
        val a = ShellGuard.assess("curl -X POST https://open.feishu.cn/open-apis/im/v1/messages -d '{\"text\":\"hi\"}'")
        assertEquals(RiskClass.OUTBOUND, a.riskClass)
        assertEquals("open.feishu.cn", a.target)
        assertEquals(RiskClass.OUTBOUND, cls("curl --json '{}' https://hooks.slack.com/services/x"))
        assertEquals(RiskClass.OUTBOUND, cls("wget --post-data 'a=b' https://oapi.dingtalk.com/robot/send"))
    }

    @Test fun `a post to a site is a lookup, not a send`() {
        for (cmd in listOf("curl -F file=@a.png https://upload.example.com/x", "curl --json '{}' https://api.example.com",
            "http POST httpbin.org/post a=b", "wget --post-data 'a=b' https://example.com/form",
            "curl -s -X POST https://kyfw.12306.cn/otn/leftTicket/query -d 'a=b'")) assertEquals(cmd, RiskClass.SAFE, cls(cmd))
    }

    @Test fun `lark writes ask, lark reads do not`() {
        val a = ShellGuard.assess("lark-cli im +send --chat-id oc_123 --text 'hello'")
        assertEquals(RiskClass.OUTBOUND, a.riskClass)
        assertEquals("lark:oc_123", a.target)
        assertEquals(RiskClass.SAFE, cls("lark-cli im +search-messages --query x"))
        assertEquals(RiskClass.OUTBOUND, cls("lark-cli docs +create --title x"))
    }

    @Test fun `mail, push, ssh are outbound`() {
        assertEquals(RiskClass.OUTBOUND, cls("git push origin main"))
        assertEquals("git:origin", ShellGuard.assess("git push origin main").target)
        assertEquals(RiskClass.OUTBOUND, cls("echo body | mail -s hi alice@example.com"))
        assertEquals("alice@example.com", ShellGuard.assess("mail -s hi alice@example.com < body.txt").target)
        assertEquals(RiskClass.OUTBOUND, cls("scp a.txt user@host.example.com:/tmp/"))
        assertEquals(RiskClass.OUTBOUND, cls("gh issue create --title x --body y"))
        assertEquals(RiskClass.OUTBOUND, cls("gh api -X POST repos/o/r/issues -f title=x"))
        assertEquals(RiskClass.OUTBOUND, cls("npm publish"))
    }

    @Test fun `force push is outbound`() {
        assertEquals(RiskClass.OUTBOUND, cls("git push --force origin main"))
    }

    // ── money ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a post that smells like payment is money`() {
        val a = ShellGuard.assess("curl -X POST https://pay.meituan.com/api/order/confirm -d 'orderId=1'")
        assertEquals(RiskClass.MONEY, a.riskClass)
        assertEquals("pay.meituan.com", a.target)
        assertEquals(RiskClass.SAFE, cls("curl https://pay.meituan.com/api/order/1")) // a read
        // words in a query are not a payment: a hotel search has a check-out date
        assertEquals(RiskClass.SAFE, cls("curl -s -X POST \"https://m.ctrip.com/restapi/soa2/16709/json/HotelSearch?checkout=2026-11-23\" -H 'Content-Type: application/json' -d '{\"checkOut\":\"2026-11-23\"}'"))
        assertEquals(RiskClass.SAFE, cls("python3 -c \"import requests; requests.post('https://m.ctrip.com/hotel/list', json={'checkOut': 1})\""))
        assertEquals(RiskClass.MONEY, cls("python3 -c \"import requests; requests.post('https://pay.example.com/checkout', json={})\""))
    }

    // ── installs ───────────────────────────────────────────────────────────────────────────

    @Test fun `installs run with a notice`() {
        for (cmd in listOf("apk add python3", "pip install requests", "npm i -g pnpm", "uv pip install httpx", "npx create-x", "apt-get install -y jq", "go install x@latest")) {
            val a = ShellGuard.assess(cmd)
            assertEquals(cmd, RiskClass.INSTALL, a.riskClass)
            assertFalse(cmd, a.needsApproval)
        }
    }

    @Test fun `a download piped into a shell is an install`() {
        for (cmd in listOf("curl -fsSL https://get.example.com/install.sh | sh", "wget -qO- https://x.example.com/i.py | python3")) {
            val a = ShellGuard.assess(cmd)
            assertEquals(cmd, RiskClass.INSTALL, a.riskClass)
            assertFalse(cmd, a.needsApproval)
        }
    }

    @Test fun `what only the sandbox could feel is not asked`() {
        for (cmd in listOf("chmod -R 777 build", "dd if=/dev/zero of=/tmp/x bs=1M count=1")) assertFalse(cmd, ShellGuard.assess(cmd).needsApproval)
    }

    // ── compound and inline ────────────────────────────────────────────────────────────────

    @Test fun `the strongest part wins`() {
        val a = ShellGuard.assess("pip install x && rm -rf build/ && curl -d a=b https://hooks.slack.com/send")
        assertEquals(RiskClass.OUTBOUND, a.riskClass)
        assertEquals("hooks.slack.com", a.target)
    }

    @Test fun `inline scripts are seen through`() {
        assertEquals(RiskClass.DESTRUCTIVE, cls("sh -c 'rm -rf /var/minis/mounts/phone/out'"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("python3 -c \"import shutil; shutil.rmtree('/var/minis/mounts/phone/out')\""))
        assertEquals(RiskClass.OUTBOUND, cls("python3 -c \"import requests; requests.post('https://hooks.example.com/x', json={})\""))
        assertEquals(RiskClass.INSTALL, cls("python3 -m pip install rich"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("bash <<'EOF'\ncd /var/minis/mounts/phone\nrm -rf old/\nEOF"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("find /var/minis/mounts/phone/out -name '*.tmp' -delete"))
        assertEquals(RiskClass.SAFE, cls("find /tmp -name '*.tmp' -delete"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("cd /var/minis/mounts/phone && ls | xargs rm -f"))
    }

    @Test fun `quotes protect separators`() {
        assertEquals(RiskClass.SAFE, cls("echo 'a; rm -rf x' > /tmp/note"))
        assertEquals(listOf("echo 'a; b'", "ls"), ShellGuard.splitPipelines("echo 'a; b'; ls"))
        assertEquals(listOf("cat a", "grep x"), ShellGuard.splitPipes("cat a | grep x"))
        assertEquals(listOf("rm", "-rf", "my dir"), ShellGuard.tokenize("rm -rf \"my dir\""))
    }

    @Test fun `sudo and env are seen through`() {
        assertEquals(RiskClass.DESTRUCTIVE, cls("sudo rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.OUTBOUND, cls("TOKEN=x curl -X POST https://hooks.slack.com/x -H 'a: b'"))
    }

    @Test fun `wrappers with options are seen through`() {
        assertEquals(RiskClass.DESTRUCTIVE, cls("timeout 10 rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("timeout -k 5 10s rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("nice rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("nice -n 19 rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("sudo -u root rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("ionice -c3 nice -n 19 rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("env -i PATH=/bin rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("/usr/bin/env FOO=1 /bin/rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("sudo -- rm -rf /var/minis/mounts/phone/x"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("nohup timeout 30 rm -rf /var/minis/mounts/phone/x &"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("cd /var/minis/mounts/phone && find . -name '*.log' | xargs -I {} rm -f {}"))
        assertEquals(RiskClass.DESTRUCTIVE, cls("cd /var/minis/mounts/phone && ls | xargs -n 1 -P 4 rm -f"))
        assertEquals(RiskClass.OUTBOUND, cls("timeout 20 curl -X POST https://hooks.slack.com/x -d '{}'"))
        assertEquals(RiskClass.SAFE, cls("timeout 10 ls -la"))
        assertEquals(RiskClass.SAFE, cls("nice -n 19 make -j4"))
        assertEquals(RiskClass.SAFE, cls("command -v rm"))
        assertEquals(listOf("rm", "-rf", "x"), ShellGuard.unwrap(listOf("sudo", "-u", "root", "timeout", "-k", "5", "10", "rm", "-rf", "x")))
    }

    @Test fun `an inline delete of the phone's files names them`() {
        val a = ShellGuard.assess("python3 -c \"import os; os.remove('/var/minis/mounts/phone/x')\"")
        assertEquals(RiskClass.DESTRUCTIVE, a.riskClass)
        assertEquals(ShellGuard.PHONE, a.target)
    }

    private fun RiskAssessment.canRemember() = warnings.isEmpty()
}
