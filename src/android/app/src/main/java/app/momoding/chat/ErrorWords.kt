package app.momoding.chat

import android.content.Context
import com.openminis.app.R

/**
 * A failed turn in one plain sentence: what went wrong and where to fix it, instead of the
 * provider's raw text ("[404] No allowed providers are available for the selected model…").
 * The raw text is still there for whoever wants it: long-press copies it, and Show how the
 * work is done shows it as before.
 */
object ErrorWords {
    enum class Kind { KEY, BALANCE, RATE, MODEL, TIMEOUT, NETWORK, OTHER }

    internal fun classify(raw: String): Kind {
        val t = raw.lowercase()
        return when {
            Regex("""\b401\b|\b403\b|invalid.{0,12}(api.?key|token)|unauthori[sz]ed|incorrect api key|authentication""").containsMatchIn(t) -> Kind.KEY
            Regex("""\b402\b|insufficient|balance|quota|credit|payment required|欠费|余额""").containsMatchIn(t) -> Kind.BALANCE
            Regex("""\b429\b|rate.?limit|too many requests|overloaded|\b529\b""").containsMatchIn(t) -> Kind.RATE
            Regex("""\b404\b|model.{0,20}(not found|does not exist|not available)|no allowed providers|no endpoints""").containsMatchIn(t) -> Kind.MODEL
            Regex("""timed? ?out|timeout|deadline""").containsMatchIn(t) -> Kind.TIMEOUT
            Regex("""unknownhost|unable to resolve|failed to connect|connection (refused|reset)|network|ssl|socket""").containsMatchIn(t) -> Kind.NETWORK
            else -> Kind.OTHER
        }
    }

    fun plain(context: Context, raw: String): String = context.getString(
        when (classify(raw)) {
            Kind.KEY -> R.string.mo_error_key
            Kind.BALANCE -> R.string.mo_error_balance
            Kind.RATE -> R.string.mo_error_rate
            Kind.MODEL -> R.string.mo_error_model
            Kind.TIMEOUT -> R.string.mo_error_timeout
            Kind.NETWORK -> R.string.mo_error_network
            Kind.OTHER -> R.string.mo_error_other
        }
    )
}
