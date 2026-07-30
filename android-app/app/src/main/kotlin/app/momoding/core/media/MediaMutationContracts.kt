package app.momoding.core.media

import app.momoding.core.runtime.local.PiNativeAndroidToolResult

enum class MediaToolAction(val wireValue: String) {
    SET_FAVORITE("set_favorite"),
    SET_TRASHED("set_trashed"),
    DELETE("delete"),
    ;

    companion object {
        fun fromWireValue(value: String): MediaToolAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface MediaToolRequest {
    val action: MediaToolAction
    val mediaHandle: String

    data class SetFavorite(
        override val mediaHandle: String,
        val favorite: Boolean,
    ) : MediaToolRequest {
        override val action = MediaToolAction.SET_FAVORITE
    }

    data class SetTrashed(
        override val mediaHandle: String,
        val trashed: Boolean,
    ) : MediaToolRequest {
        override val action = MediaToolAction.SET_TRASHED
    }

    data class Delete(
        override val mediaHandle: String,
    ) : MediaToolRequest {
        override val action = MediaToolAction.DELETE
    }
}

data class MediaItemSnapshot(
    val mediaId: Long,
    val mimeType: String,
    val favorite: Boolean,
    val trashed: Boolean,
    val byteCount: Long?,
    val capturedAtMillis: Long?,
)

data class MediaMutationPlan(
    val taskId: String,
    val piToolCallId: String,
    val action: MediaToolAction,
    val mediaHandle: String,
    val mediaId: Long,
    val desired: Boolean?,
    val snapshotDigest: String,
    val requestDigest: String,
    val planDigest: String,
    val summary: String,
    val details: String,
)

sealed interface MediaMutationPreparation {
    data class Ready(val plan: MediaMutationPlan) : MediaMutationPreparation
    data class Failed(val result: PiNativeAndroidToolResult) : MediaMutationPreparation
}

internal const val MEDIA_TOOL_NAME = "device_media"
internal const val MEDIA_HANDLE_LENGTH = 30
internal val MEDIA_HANDLE_PATTERN = Regex("^media-[0-9a-f]{24}$")
