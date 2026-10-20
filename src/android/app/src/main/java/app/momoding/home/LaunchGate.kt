package app.momoding.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the cold-start dispatcher has decided where the app opens. Until it has, the session
 * list (the NavHost's start destination) draws nothing, so upstream's "add a provider" landing
 * does not flash before the main chat, as it did after a language switch recreated the Activity.
 */
object LaunchGate {
    private val _decided = MutableStateFlow(false)
    val decided: StateFlow<Boolean> = _decided

    /** A new Activity is composing its navigation: the next launch decision is still to come. */
    fun reset() { _decided.value = false }

    fun decide() { _decided.value = true }
}
