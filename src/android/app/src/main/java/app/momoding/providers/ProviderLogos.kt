// Logos from lobe-icons (MIT): https://github.com/lobehub/lobe-icons, @lobehub/icons-static-png 1.97.1
package app.momoding.providers

import com.openminis.app.R
import com.openminis.app.data.model.ProviderType

/** Each service's own logo, so the list reads like the services people already know. */
object ProviderLogos {
    fun forTemplate(id: String): Int? = when (id) {
        "bailian" -> R.drawable.mo_logo_qwen
        "deepseek" -> R.drawable.mo_logo_deepseek
        "moonshot" -> R.drawable.mo_logo_kimi
        "zhipu" -> R.drawable.mo_logo_zhipu
        "ark" -> R.drawable.mo_logo_doubao
        "siliconflow" -> R.drawable.mo_logo_siliconflow
        else -> null
    }

    /** Voice services; Deepgram has none in the set and keeps the sound-wave icon. */
    fun forVoice(id: String): Int? = when (id) {
        "elevenlabs" -> R.drawable.mo_logo_elevenlabs
        "azure-tts" -> R.drawable.mo_logo_azure
        "minimax" -> R.drawable.mo_logo_minimax
        "alibaba" -> R.drawable.mo_logo_bailian
        "doubao" -> R.drawable.mo_logo_doubao
        "xunfei" -> R.drawable.mo_logo_iflytek
        "mimo" -> R.drawable.mo_logo_xiaomimimo
        else -> null
    }

    fun forType(type: ProviderType): Int? = when (type) {
        ProviderType.openAI, ProviderType.openAIResponses -> R.drawable.mo_logo_openai
        ProviderType.anthropic -> R.drawable.mo_logo_claude
        ProviderType.gemini -> R.drawable.mo_logo_gemini
        ProviderType.openRouter -> R.drawable.mo_logo_openrouter
        ProviderType.xAI -> R.drawable.mo_logo_grok
        ProviderType.kimiCode -> R.drawable.mo_logo_kimi
        ProviderType.githubCopilot -> R.drawable.mo_logo_githubcopilot
        else -> null
    }
}
