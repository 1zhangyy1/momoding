package app.momoding.feature.localtask

enum class PhoneLocalTaskPhase {
    COMPOSING,
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class PhoneLocalTaskUiState(
    val draft: String = "",
    val submittedPrompt: String? = null,
    val answer: String = "",
    val phase: PhoneLocalTaskPhase = PhoneLocalTaskPhase.COMPOSING,
    val error: String? = null,
) {
    val canSend: Boolean
        get() = phase == PhoneLocalTaskPhase.COMPOSING && draft.isNotBlank()
}

sealed interface PhoneLocalTaskAction {
    data class EditDraft(val value: String) : PhoneLocalTaskAction
    data class UseSuggestion(val value: String) : PhoneLocalTaskAction
    data object Send : PhoneLocalTaskAction
    data object Stop : PhoneLocalTaskAction
    data object StartAnother : PhoneLocalTaskAction
    data object Back : PhoneLocalTaskAction
}

sealed interface PhoneLocalTaskOneShot {
    data object Back : PhoneLocalTaskOneShot
}
