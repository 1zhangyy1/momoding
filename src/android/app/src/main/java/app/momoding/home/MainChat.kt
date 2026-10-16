package app.momoding.home

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import java.util.UUID

/**
 * The one conversation that is Momoding: the app always opens here.
 *
 * The id lives in shared preferences. Until anything is persisted (a fresh install, or every
 * chat deleted) a process-lifetime draft id stands in; the moment that draft gets its database
 * row, [onPromoted] pins the real id. Ported from nanoMuse (`io.github.nanomuse.home.MainChat`),
 * without its sync and feed exclusions.
 */
object MainChat {
    private const val PREFS = "momoding"
    private const val KEY_SESSION = "main_chat.session"

    @Volatile private var draft: String? = null

    /** The persisted main chat if it still exists, else the newest chat, else a draft. */
    suspend fun resolve(context: Context, chatRepository: ChatRepository): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SESSION, null)?.let { id ->
            if (chatRepository.getSession(id) != null) return id
        }
        chatRepository.dao.listSessions().firstOrNull { !it.isChild }?.let { latest ->
            prefs.edit().putString(KEY_SESSION, latest.id).apply()
            return latest.id
        }
        return draft ?: "__new__${UUID.randomUUID()}".also { draft = it }
    }

    /** Called by ChatViewModel.ensureSession when a draft gets its database row. */
    fun onPromoted(context: Context, draftId: String, realId: String) {
        if (draftId != draft) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SESSION, realId).apply()
        draft = null
    }
}
