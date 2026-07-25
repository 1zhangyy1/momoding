package app.momoding.core.policy

/** Task-local approval policy. It never grants or removes an Android capability. */
enum class TaskApprovalMode(
    val persistedValue: String,
    internal val automationRank: Int,
) {
    REQUEST_APPROVAL("REQUEST_APPROVAL", 0),
    AUTO_APPROVE("AUTO_APPROVE", 1),
    FULL_ACCESS("FULL_ACCESS", 2),
    ;

    companion object {
        /** Strict decoder for validation and diagnostics. */
        fun requirePersisted(value: String): TaskApprovalMode = when (value) {
            REQUEST_APPROVAL.persistedValue -> REQUEST_APPROVAL
            AUTO_APPROVE.persistedValue -> AUTO_APPROVE
            FULL_ACCESS.persistedValue -> FULL_ACCESS
            else -> throw IllegalArgumentException("Unknown task approval mode")
        }

        /** Durable reads fail closed instead of widening automation for a corrupt future value. */
        fun fromPersistedFailClosed(value: String): TaskApprovalMode = when (value) {
            REQUEST_APPROVAL.persistedValue -> REQUEST_APPROVAL
            AUTO_APPROVE.persistedValue -> AUTO_APPROVE
            FULL_ACCESS.persistedValue -> FULL_ACCESS
            else -> REQUEST_APPROVAL
        }
    }
}

data class TaskApprovalModeTransition(
    val previous: TaskApprovalMode,
    val next: TaskApprovalMode,
    val invalidateUnusedAutoApprovalReceipts: Boolean,
)

fun transitionTaskApprovalMode(
    previous: TaskApprovalMode,
    next: TaskApprovalMode,
): TaskApprovalModeTransition = TaskApprovalModeTransition(
    previous = previous,
    next = next,
    invalidateUnusedAutoApprovalReceipts = next.automationRank < previous.automationRank,
)

/** A child may choose a stricter mode but can never exceed its parent Task. */
fun effectiveChildApprovalMode(
    parent: TaskApprovalMode,
    requested: TaskApprovalMode = parent,
): TaskApprovalMode = if (requested.automationRank <= parent.automationRank) requested else parent
