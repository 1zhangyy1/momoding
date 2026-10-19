package app.momoding.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/** momoding: the first-run chat model pick. */
class AutoSetupTest {
    @Test fun openrouter() = assertEquals(listOf("deepseek/deepseek-v4.1-flash", "z-ai/glm-5.3"),
        AutoSetup.pick(listOf("openai/gpt-4o", "z-ai/glm-5.3:batch", "z-ai/glm-5.3-prime", "deepseek/deepseek-v4.1-flash", "z-ai/glm-5.3", "qwen/qwen3-vl-8b-instruct")))
    @Test fun bailian() = assertEquals(listOf("deepseek-v4.1-flash", "qwen3.8-max"),
        AutoSetup.pick(listOf("text-embedding-v4", "qwen3.8-27b", "qwen3.8-max", "deepseek-v4.1-flash")))
    @Test fun `dated variants collapse and the fallback is another family`() = assertEquals(listOf("deepseek/deepseek-v4.1-flash", "z-ai/glm-5.3"),
        AutoSetup.pick(listOf("deepseek/deepseek-v4.1-flash-1017", "deepseek/deepseek-v4.1-flash", "deepseek/deepseek-v4-flash", "z-ai/glm-5.3")))
    @Test fun unknownVendorTakesTheFirstUsable() = assertEquals(listOf("acme-chat"), AutoSetup.pick(listOf("acme-embed", "acme-chat")))
}
