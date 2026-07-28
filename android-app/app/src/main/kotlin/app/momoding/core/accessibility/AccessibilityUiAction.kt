package app.momoding.core.accessibility

enum class AccessibilityUiActionKind(val wireValue: String) {
    CLICK("click"),
    SCROLL("scroll"),
    INPUT_DRAFT("input_draft"),
    BACK("back"),
    ;

    companion object {
        fun fromWire(value: String): AccessibilityUiActionKind =
            entries.singleOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported interface action")
    }
}

enum class AccessibilityScrollDirection(val wireValue: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromWire(value: String): AccessibilityScrollDirection =
            entries.singleOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported scroll direction")
    }
}

data class AccessibilityUiActionRequest(
    val snapshotId: String,
    val nodeHandle: String?,
    val action: AccessibilityUiActionKind,
    val text: String? = null,
    val direction: AccessibilityScrollDirection? = null,
) {
    init {
        require(snapshotId.isNotBlank()) { "snapshotId is blank" }
        when (action) {
            AccessibilityUiActionKind.CLICK -> {
                require(!nodeHandle.isNullOrBlank()) { "click requires nodeHandle" }
                require(text == null && direction == null)
            }
            AccessibilityUiActionKind.SCROLL -> {
                require(!nodeHandle.isNullOrBlank()) { "scroll requires nodeHandle" }
                require(text == null && direction != null)
            }
            AccessibilityUiActionKind.INPUT_DRAFT -> {
                require(!nodeHandle.isNullOrBlank()) { "input_draft requires nodeHandle" }
                require(!text.isNullOrEmpty() && text.length <= MAX_UI_DRAFT_LENGTH)
                require(direction == null)
            }
            AccessibilityUiActionKind.BACK -> {
                require(nodeHandle == null && text == null && direction == null)
            }
        }
    }
}

data class AccessibilityUiActionDescription(
    val action: AccessibilityUiActionKind,
    val packageName: String,
    val node: AccessibilityNodeSnapshot?,
    val snapshotObservedAtMillis: Long,
)

sealed interface AccessibilityActionDescriptionResult {
    data class Ready(
        val description: AccessibilityUiActionDescription,
    ) : AccessibilityActionDescriptionResult

    data class Failed(
        val code: String,
        val safeMessage: String,
    ) : AccessibilityActionDescriptionResult
}

sealed interface AccessibilityRawActionResult {
    data object Performed : AccessibilityRawActionResult

    data class Failed(
        val code: String,
        val safeMessage: String,
    ) : AccessibilityRawActionResult
}

const val MAX_UI_DRAFT_LENGTH = 4_096
