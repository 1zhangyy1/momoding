package app.momoding.feature.localtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.runtime.local.PhoneLocalPiOpenRouterRuntime
import app.momoding.core.runtime.local.PiNativeOpenRouterScenarioStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class PhoneLocalTaskViewModel internal constructor(
    private val runPrompt: suspend (
        String,
        (PiNativeOpenRouterScenarioStatus) -> Unit,
    ) -> PiNativeOpenRouterScenarioStatus,
    private val stopPrompt: suspend () -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhoneLocalTaskUiState())
    val state: StateFlow<PhoneLocalTaskUiState> = mutableState.asStateFlow()
    private val oneShotChannel = Channel<PhoneLocalTaskOneShot>(Channel.BUFFERED)
    val oneShots: Flow<PhoneLocalTaskOneShot> = oneShotChannel.receiveAsFlow()

    fun dispatch(action: PhoneLocalTaskAction) {
        when (action) {
            is PhoneLocalTaskAction.EditDraft -> if (
                mutableState.value.phase == PhoneLocalTaskPhase.COMPOSING
            ) {
                mutableState.value = mutableState.value.copy(
                    draft = action.value,
                    error = null,
                )
            }
            is PhoneLocalTaskAction.UseSuggestion -> if (
                mutableState.value.phase == PhoneLocalTaskPhase.COMPOSING
            ) {
                mutableState.value = mutableState.value.copy(
                    draft = action.value,
                    error = null,
                )
            }
            PhoneLocalTaskAction.Send -> send()
            PhoneLocalTaskAction.Stop -> viewModelScope.launch {
                runCatching { stopPrompt() }
            }
            PhoneLocalTaskAction.StartAnother -> if (
                mutableState.value.phase != PhoneLocalTaskPhase.RUNNING
            ) {
                mutableState.value = PhoneLocalTaskUiState()
            }
            PhoneLocalTaskAction.Back -> viewModelScope.launch {
                if (mutableState.value.phase == PhoneLocalTaskPhase.RUNNING) {
                    runCatching { stopPrompt() }
                }
                oneShotChannel.send(PhoneLocalTaskOneShot.Back)
            }
        }
    }

    private fun send() {
        val prompt = mutableState.value.draft.trim()
        if (!mutableState.value.canSend || prompt.isEmpty()) return
        mutableState.value = mutableState.value.copy(
            submittedPrompt = prompt,
            answer = "",
            phase = PhoneLocalTaskPhase.RUNNING,
            error = null,
        )
        viewModelScope.launch {
            try {
                val terminal = runPrompt(prompt) { status ->
                    val partial = status.latestAssistantText()
                    mutableState.update { current ->
                        if (current.phase != PhoneLocalTaskPhase.RUNNING) {
                            current
                        } else {
                            current.copy(
                                answer = partial.ifEmpty { current.answer },
                                error = status.providerError,
                            )
                        }
                    }
                }
                mutableState.update { current ->
                    current.copy(
                        answer = terminal.finalText
                            ?.takeIf(String::isNotEmpty)
                            ?: current.answer,
                        phase = when {
                            terminal.stopRequested || terminal.hasAbort ->
                                PhoneLocalTaskPhase.STOPPED
                            terminal.providerError != null || terminal.promptError != null ->
                                PhoneLocalTaskPhase.FAILED
                            else -> PhoneLocalTaskPhase.COMPLETED
                        },
                        error = terminal.providerError ?: terminal.promptError,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        phase = PhoneLocalTaskPhase.FAILED,
                        error = "The phone-local Pi task could not be started safely.",
                    )
                }
            }
        }
    }

    class Factory(
        runtime: PhoneLocalPiOpenRouterRuntime,
    ) : ViewModelProvider.Factory {
        private val runPrompt: suspend (
            String,
            (PiNativeOpenRouterScenarioStatus) -> Unit,
        ) -> PiNativeOpenRouterScenarioStatus = runtime::runPrompt
        private val stopPrompt: suspend () -> Unit = {
            runtime.stop()
            Unit
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PhoneLocalTaskViewModel::class.java))
            return PhoneLocalTaskViewModel(runPrompt, stopPrompt) as T
        }
    }
}

internal fun PiNativeOpenRouterScenarioStatus.latestAssistantText(): String {
    events.asReversed().forEach { event ->
        if (event["type"]?.jsonPrimitive?.contentOrNull != "message_update") {
            return@forEach
        }
        val message = event["message"] as? JsonObject
            ?: (event["assistantMessageEvent"] as? JsonObject)
                ?.get("partial") as? JsonObject
            ?: return@forEach
        val content = message["content"] as? JsonArray ?: return@forEach
        val text = content.mapNotNull { block ->
            (block as? JsonObject)
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.joinToString("")
        if (text.isNotEmpty()) return text
    }
    return finalText.orEmpty()
}
