package app.momoding.prompt

import android.content.Context
import com.openminis.app.BuildConfig
import java.io.File

/**
 * Debug builds only: the last system prompt as sent, at files/debug/system_prompt.md, so prompt
 * work can be read and measured on the device (`adb shell run-as … cat`).
 */
object PromptDump {
    fun write(context: Context, prompt: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val f = File(File(context.filesDir, "debug").apply { mkdirs() }, "system_prompt.md")
            if (!f.exists() || f.readText() != prompt) f.writeText(prompt)
        }
    }
}
