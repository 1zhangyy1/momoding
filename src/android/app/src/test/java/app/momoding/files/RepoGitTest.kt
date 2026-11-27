package app.momoding.files

import app.momoding.files.RepoGit.Kind
import app.momoding.files.RepoGit.LineKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** momoding: reading git log / show output, and who each change came from. */
class RepoGitTest {
    @Test fun `log records carry time, subject and per-file counts`() {
        val out = "\u001eaaa\u001f1795778948\u001fchat s2: 我对花生过敏\n\n1\t0\tpeople/妈妈.md\n4\t1\t健康.md\n" +
            "\u001ebbb\u001f1795778000\u001freflect: what the person said in 2 chats\n\n-\t-\tpic.png\n"
        val log = RepoGit.parseLog(out)
        assertEquals(2, log.size)
        assertEquals(1795778948000L, log[0].at)
        assertEquals(listOf(RepoGit.FileChange("people/妈妈.md", 1, 0), RepoGit.FileChange("健康.md", 4, 1)), log[0].files)
        assertEquals(RepoGit.Label(Kind.SAID, "我对花生过敏"), log[0].label)
        assertEquals(Kind.SELF, log[1].label.kind)
        assertEquals(0, log[1].files[0].added)
    }

    @Test fun `labels`() {
        assertEquals(Kind.SELF, RepoGit.label("chat abc").kind)
        assertEquals(RepoGit.Label(Kind.YOU, "edit 健康.md"), RepoGit.label("you: edit 健康.md"))
        assertEquals(RepoGit.Label(Kind.OTHER, "add monthly totals"), RepoGit.label("add monthly totals"))
    }

    @Test fun `show splits into files and lines, skipping headers`() {
        val out = """
            diff --git a/people/妈妈.md b/people/妈妈.md
            new file mode 100644
            --- /dev/null
            +++ b/people/妈妈.md
            @@ -0,0 +1 @@
            +- 妈妈
            diff --git a/健康.md b/健康.md
            --- a/健康.md
            +++ b/健康.md
            @@ -1,3 +1,3 @@
             # 健康
            -- 每天走路
            +- 每天走 8000 步
            @@ -9,1 +9,1 @@
            -旧
            +新
        """.trimIndent()
        val d = RepoGit.parseDiff(out)
        assertEquals(listOf("people/妈妈.md", "健康.md"), d.map { it.path })
        assertEquals(listOf(RepoGit.Line(LineKind.ADD, "- 妈妈")), d[0].lines)
        assertEquals(listOf(LineKind.CTX, LineKind.DEL, LineKind.ADD, LineKind.GAP, LineKind.DEL, LineKind.ADD), d[1].lines.map { it.kind })
        assertEquals("- 每天走路", d[1].lines[1].text)
    }
}
