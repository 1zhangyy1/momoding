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
    /** What a person gets, in a few words (the row's subtitle; the address is filled in for them). */
    val note: String = "",
)

object ChatProviderTemplates {
    val all: List<ChatProviderTemplate> = listOf(
        ChatProviderTemplate("bailian", "阿里云百炼 (Qwen)", "https://dashscope.aliyuncs.com/compatible-mode", appendV1 = true, note = "通义千问，能看图"),
        ChatProviderTemplate("deepseek", "DeepSeek", "https://api.deepseek.com", appendV1 = true, note = "DeepSeek，好用不贵"),
        ChatProviderTemplate("moonshot", "月之暗面 (Kimi)", "https://api.moonshot.cn", appendV1 = true, note = "Kimi，长文本"),
        ChatProviderTemplate("zhipu", "智谱 (GLM)", "https://open.bigmodel.cn/api/paas/v4", appendV1 = false, note = "GLM，能看图"),
        ChatProviderTemplate("ark", "火山方舟 (豆包)", "https://ark.cn-beijing.volces.com/api/v3", appendV1 = false, note = "豆包，能看图"),
        ChatProviderTemplate("siliconflow", "硅基流动", "https://api.siliconflow.cn", appendV1 = true, note = "一个 Key 用多家开源模型"),
    )
}
