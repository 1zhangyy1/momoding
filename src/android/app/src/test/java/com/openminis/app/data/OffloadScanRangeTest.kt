package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-offload-scan-range] Port of iOS 5a9c5bfcd + d302de846: the
 * context offload scans only what is sent (after the compact-v2 anchor), and
 * ranks / accounts with ContextSizeMeter's calibrated estimate instead of the
 * vocab-less BPETokenizer (a flat chars/3).
 */
class OffloadScanRangeTest {

    private fun result(text: String) = AgentContentPart.ToolResult(id = "t", name = "shell_execute", content = text)

    @Test fun `CJK output is no longer under-ranked against English of fewer tokens`() {
        val cjk = result("日志内容".repeat(250))            // 1000 CJK chars ≈ 1000 tokens
        val english = result("abcd efgh ".repeat(200))    // 2000 chars ≈ 450 tokens
        // The old ranking (chars/3) put English first ...
        assertTrue(BPETokenizer.countTokens(english.content) > BPETokenizer.countTokens(cjk.content))
        // ... the meter's estimate puts the CJK part, the larger one, first.
        assertTrue(ContextSizeMeter.estimateTokens(cjk) > ContextSizeMeter.estimateTokens(english))
    }

    @Test fun `freed tokens are on the calibrated scale`() {
        val part = result("x".repeat(4500))
        val est = ContextSizeMeter.estimateTokens(part)
        assertTrue(ContextSizeMeter.calibrated(est, 2.0) >= est * 2)
    }
}
