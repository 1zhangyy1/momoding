package app.momoding.core.clipboard

enum class ClipboardToolAction(val wireValue: String) {
    GET("get"),
    SET("set"),
    CLEAR("clear"),
    ;

    val isMutation: Boolean
        get() = this != GET

    companion object {
        fun fromWireValue(value: String): ClipboardToolAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface ClipboardToolRequest {
    val action: ClipboardToolAction
    val purpose: String

    data class Get(
        override val purpose: String,
    ) : ClipboardToolRequest {
        override val action = ClipboardToolAction.GET
    }

    data class Set(
        val text: String,
        override val purpose: String,
    ) : ClipboardToolRequest {
        override val action = ClipboardToolAction.SET
    }

    data class Clear(
        override val purpose: String,
    ) : ClipboardToolRequest {
        override val action = ClipboardToolAction.CLEAR
    }
}

sealed interface ClipboardSnapshot {
    data object Empty : ClipboardSnapshot
    data object Unsupported : ClipboardSnapshot
    data class Text(
        val value: String,
        val sourceMarkedSensitive: Boolean,
    ) : ClipboardSnapshot
}

fun interface ClipboardForegroundGate {
    fun isForeground(): Boolean
}

class ClipboardToolArgumentsException :
    IllegalArgumentException("Clipboard arguments are invalid")
