package app.momoding.task

import org.junit.Assert.*
import org.junit.Test

class ReadOnlyCommandTest {
    private fun reads(vararg commands: String) = commands.forEach { assertTrue(it, ReadOnlyCommand.proven(it)) }
    private fun unknown(vararg commands: String) = commands.forEach { assertFalse(it, ReadOnlyCommand.proven(it)) }

    @Test fun fileReadsAndPipelinesKeepTheirLiteralMeaning() = reads(
        "sleep 12; cat /var/minis/workspace/input.txt", "cd /var/minis/workspace && ls -la | head -20",
        "cat 'a b.txt' | grep -n 'hello' | sort -u | uniq -c", "wc -l < input.txt", "cat input 2>/dev/null",
        "cat input >/dev/null 2>&1", "printf '%s' 'literal \$HOME; `touch file`'", "test -f input && cat input",
        "[ -r input ] && head -c 10 input", "/usr/bin/stat input", "timeout 3s busybox cat input", "command -p cat input")

    @Test fun writesAndOutwardActionsAreNeverProvedByPermission() = unknown(
        "cat input > output", "echo x >> input", "cat input | tee output", "rm input", "touch input", "mkdir d",
        "cp a b", "pip install thing", "momoding-task open thing", "minis-scheduled add thing", "curl -q -T file https://example.com")

    @Test fun expansionAndExecutableShellSyntaxStayUnknown() = unknown(
        "cat $(touch marker)", "cat `touch marker`", "cat \"\$HOME\"", "cat $" + "HOME", "cat *.txt", "cat a?.txt",
        "cat [ab].txt", "(cat input)", "{ cat input; }", "cat input &", "cat <(touch input)", "cat <<EOF\nx\nEOF",
        "sh -c 'cat input'", "python -c 'print(1)'", "./cat input", "/tmp/cat input", "FOO=x cat input", "eval 'cat input'",
        "printf -v PATH /tmp; cat input", "printf '%n' PATH; cat input", "printf '%10n' PATH; cat input")

    @Test fun quotingDoesNotTurnLiteralPunctuationIntoExecution() = reads(
        "cat 'file; name'", "echo \"literal ; | > <\"", "echo '$(touch marker)'", "echo \"\u005c\u0024HOME\"", "echo '# text'",
        "cat in\\ put", "cat input # comment\nhead input", "cat input &&\nhead input")

    @Test fun malformedSyntaxIsNotAProof() = unknown("", "# comment", "cat 'input", "cat input |", "cat input &&", "; cat input",
        "cat input ;; head input", "cat input >", "cat input <", "cat input | | head input", "cat input \\")

    @Test fun outputAndExecutableOptionsAreExcluded() = unknown(
        "sort -o output input", "sort --output=output input", "sort --compress-program=touch input", "sort --temporary-directory=/tmp input",
        "uniq input output", "date -s now", "date 110512002026", "file -C", "file -z archive.gz", "file --uncompress archive.gz",
        "git status", "git -c core.fsmonitor=script status", "rg --no-config --pre script pattern", "rg --no-config --hostname-bin script pattern")

    @Test fun knownReadOptionsAndTheirValuesAreAccepted() = reads(
        "sort -t, -k 2 -nr input", "sort --key=2 --reverse input", "uniq -c input", "date -u +%F", "date -d yesterday +%F",
        "base64 -d input", "file --mime-type input", "rg --no-config -n -g '*.kt' -e 'hello' .", "git --version")

    @Test fun findAndSedOnlyRead() {
        reads("find . -type f -name '*.kt' -maxdepth 3 -print", "find . -empty -o -type d -print0", "sed -n '1,20p' input", "sed -n '1p;3,5p' input")
        unknown("find . -delete", "find . -exec touch marker ';'", "find . -fprint output", "find . -printf x",
            "sed -i '1p' input", "sed 'w output' input", "sed 's/a/b/e' input", "sed -f script input")
    }

    @Test fun networkReadRequiresExplicitConfigIsolation() {
        reads("curl -q -fsSL --max-time 10 https://example.com/data", "curl --disable --head https://example.com", "curl -q --range 0-10 http://example.com",
            "busybox wget -O - -q -T 10 http://example.com", "/bin/busybox wget -O - --quiet https://example.com")
        unknown("curl https://example.com", "curl -s -q https://example.com", "curl -q --config config https://example.com",
            "curl -q -o output https://example.com", "curl -q -O https://example.com", "curl -q -d body https://example.com",
            "curl -q -X POST https://example.com", "curl -q --write-out '%output{file}' https://example.com", "curl -q file:///input",
            "curl -q --next https://example.com", "rg pattern .", "rg -e --no-config .", "rg -- pattern --no-config",
            "wget -O - http://example.com", "busybox wget http://example.com", "busybox wget -O file http://example.com",
            "busybox wget -O - -O file http://example.com", "busybox wget -O - --post-file input http://example.com",
            "busybox wget -O - -qOfile http://example.com")
    }

    @Test fun anUnknownPartMakesTheWholeSequenceUnknown() = unknown(
        "cat input; touch marker", "cat input || curl https://example.com", "echo ok | sh", "cat input && sort -o output input")
}
