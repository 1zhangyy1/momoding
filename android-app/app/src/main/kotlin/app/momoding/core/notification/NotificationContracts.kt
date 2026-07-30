package app.momoding.core.notification

enum class NotificationToolAction(
    val wireValue: String,
    val isMutation: Boolean,
) {
    STATUS("status", false),
    POST("post", true),
    LIST_ACTIVE("list_active", false),
    UPDATE("update", true),
    CANCEL("cancel", true),
    OPEN_SETTINGS("open_settings", false),
    ;

    companion object {
        fun fromWireValue(value: String): NotificationToolAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface NotificationToolRequest {
    val action: NotificationToolAction

    data object Status : NotificationToolRequest {
        override val action = NotificationToolAction.STATUS
    }

    data class Post(
        val title: String,
        val message: String,
    ) : NotificationToolRequest {
        override val action = NotificationToolAction.POST
    }

    data class ListActive(
        val limit: Int,
    ) : NotificationToolRequest {
        override val action = NotificationToolAction.LIST_ACTIVE
    }

    data class Update(
        val notificationHandle: String,
        val title: String,
        val message: String,
    ) : NotificationToolRequest {
        override val action = NotificationToolAction.UPDATE
    }

    data class Cancel(
        val notificationHandle: String,
    ) : NotificationToolRequest {
        override val action = NotificationToolAction.CANCEL
    }

    data object OpenSettings : NotificationToolRequest {
        override val action = NotificationToolAction.OPEN_SETTINGS
    }
}

enum class NotificationChannelState(val wireValue: String) {
    NOT_CREATED("not_created"),
    ENABLED("enabled"),
    BLOCKED("blocked"),
}

data class NotificationCapabilitySnapshot(
    val declared: Boolean,
    val runtimePermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelState: NotificationChannelState,
) {
    val readyToPost: Boolean
        get() = declared &&
            runtimePermissionGranted &&
            appNotificationsEnabled &&
            channelState != NotificationChannelState.BLOCKED
}

data class NotificationIdentity(
    val tag: String,
    val id: Int,
)

data class ActiveAgentNotification(
    val identity: NotificationIdentity,
    val title: String,
    val message: String,
    val postedAtMillis: Long,
)

internal const val NOTIFICATION_TOOL_NAME = "device_notification"
internal const val AGENT_NOTIFICATION_CHANNEL_ID = "momoding_agent_notifications_v1"
internal const val MAX_NOTIFICATION_TITLE_LENGTH = 80
internal const val MAX_NOTIFICATION_MESSAGE_LENGTH = 240
internal const val DEFAULT_NOTIFICATION_LIST_LIMIT = 10
internal const val MAX_NOTIFICATION_LIST_LIMIT = 20
