package app.momoding.core.media

import android.app.PendingIntent

interface MediaGateway {
    suspend fun get(mediaId: Long): MediaItemSnapshot?

    fun consentRequest(
        action: MediaToolAction,
        mediaId: Long,
        desired: Boolean?,
    ): PendingIntent
}

enum class MediaSystemConsentResult {
    APPROVED,
    DENIED,
    UNAVAILABLE,
    TIMEOUT,
}

fun interface MediaSystemConsentRequester {
    suspend fun request(
        taskId: String,
        action: MediaToolAction,
        request: PendingIntent,
    ): MediaSystemConsentResult
}
