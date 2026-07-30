package app.momoding.core.notification

interface NotificationGateway {
    suspend fun capability(): NotificationCapabilitySnapshot
    suspend fun listActive(namespacePrefix: String): List<ActiveAgentNotification>
    suspend fun post(
        identity: NotificationIdentity,
        title: String,
        message: String,
    )
    suspend fun cancel(identity: NotificationIdentity)
    suspend fun openAppNotificationSettings()
}
