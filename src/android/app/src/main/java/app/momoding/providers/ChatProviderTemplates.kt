package app.momoding.providers

/**
 * The chat-model services most people in China already have a key for, as one-tap rows on the
 * Add Provider page. All of them speak the OpenAI-compatible API, so a template is only the
 * vendor's name and address on top of upstream's OpenAI provider; models are then fetched from
 * the vendor's `/models` the way upstream already does after saving (models.dev as fallback).
 * Same shape as upstream's VoiceProviderTemplate rows, which this reuses in the flow.
 */
data class ChatProviderTemplate(
    val id: String,
    val name: String,
    val baseURL: String,
    /** Whether the app appends `/v1` (false when the vendor's path already ends in a version). */
    val appendV1: Boolean,
)

object ChatProviderTemplates {
    val all: List<ChatProviderTemplate> = listOf(
        ChatProviderTemplate("bailian", "阿里云百炼 (Qwen)", "https://dashscope.aliyuncs.com/compatible-mode", appendV1 = true),
        ChatProviderTemplate("deepseek", "DeepSeek", "https://api.deepseek.com", appendV1 = true),
        ChatProviderTemplate("moonshot", "月之暗面 (Kimi)", "https://api.moonshot.cn", appendV1 = true),
        ChatProviderTemplate("zhipu", "智谱 (GLM)", "https://open.bigmodel.cn/api/paas/v4", appendV1 = false),
        ChatProviderTemplate("ark", "火山方舟 (豆包)", "https://ark.cn-beijing.volces.com/api/v3", appendV1 = false),
        ChatProviderTemplate("siliconflow", "硅基流动", "https://api.siliconflow.cn", appendV1 = true),
    )
}
