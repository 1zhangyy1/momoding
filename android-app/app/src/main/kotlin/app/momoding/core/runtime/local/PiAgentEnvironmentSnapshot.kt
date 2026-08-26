package app.momoding.core.runtime.local

import kotlinx.serialization.Serializable
import java.time.ZonedDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bounded, non-sensitive facts about the current Task environment.
 *
 * Live Android permissions, grants, approval outcomes, paths, credentials, and user data never
 * belong here; their authoritative source remains the specific Android Tool result.
 */
@Serializable
data class PiAgentEnvironmentSnapshot(
    val version: Int,
    val workspaceKind: String,
    val webSearchEnabled: Boolean,
    val webFetchEnabled: Boolean,
    val imageGenerationEnabled: Boolean,
    val currentDateTime: String,
    val timeZone: String,
) {
    init {
        require(version == SCHEMA_VERSION) { "PI_MOBILE_TASK_ENVIRONMENT_VERSION_UNSUPPORTED" }
        require(workspaceKind in WORKSPACE_KINDS) {
            "PI_MOBILE_TASK_ENVIRONMENT_WORKSPACE_INVALID"
        }
        require(currentDateTime.length in 1..MAX_CLOCK_FIELD_LENGTH) {
            "PI_MOBILE_TASK_ENVIRONMENT_CLOCK_INVALID"
        }
        require(runCatching { OffsetDateTime.parse(currentDateTime) }.isSuccess) {
            "PI_MOBILE_TASK_ENVIRONMENT_CLOCK_INVALID"
        }
        require(
            timeZone.length in 1..MAX_CLOCK_FIELD_LENGTH &&
                timeZone.none { it.isWhitespace() || it.isISOControl() } &&
                runCatching { ZoneId.of(timeZone) }.isSuccess
        ) {
            "PI_MOBILE_TASK_ENVIRONMENT_CLOCK_INVALID"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val PRIVATE_SCRATCH = "private_scratch"
        const val AUTHORIZED_PROJECT = "authorized_project"
        private const val MAX_CLOCK_FIELD_LENGTH = 64
        private val WORKSPACE_KINDS = setOf(PRIVATE_SCRATCH, AUTHORIZED_PROJECT)

        fun create(
            workspaceMode: PhoneLocalWorkspaceMode,
            webSearchEnabled: Boolean,
            webFetchEnabled: Boolean,
            imageGenerationEnabled: Boolean,
            now: ZonedDateTime = ZonedDateTime.now(),
        ) = PiAgentEnvironmentSnapshot(
            version = SCHEMA_VERSION,
            workspaceKind = when (workspaceMode) {
                PhoneLocalWorkspaceMode.PRIVATE_SCRATCH -> PRIVATE_SCRATCH
                PhoneLocalWorkspaceMode.AUTHORIZED_PROJECT -> AUTHORIZED_PROJECT
            },
            webSearchEnabled = webSearchEnabled,
            webFetchEnabled = webFetchEnabled,
            imageGenerationEnabled = imageGenerationEnabled,
            currentDateTime = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            timeZone = now.zone.id,
        )
    }
}
