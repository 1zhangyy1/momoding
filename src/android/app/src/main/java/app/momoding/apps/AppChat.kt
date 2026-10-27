package app.momoding.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import com.openminis.app.MinisApp
import com.openminis.app.ui.chat.ChatScreen
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.theme.MinisTheme

/**
 * An app's own Momoding, over the app: the ordinary chat screen for the app's Bot session in the
 * lower part of the window, so the app above it changes while the person watches.
 */
class AppChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = intent.getStringExtra(EXTRA_NAME)?.let { MoApps.get(this, it) }
        val sid = app?.botSession
        val minis = application as MinisApp
        if (sid == null || minis.chatRepositoryOrNull == null) { finish(); return }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7f).toInt())
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.25f)
        // a tap on the app above puts the chat away, as with any sheet
        setFinishOnTouchOutside(true)
        setContent {
            MinisTheme(darkTheme = isSystemInDarkTheme()) {
                ChatScreen(
                    sessionId = sid,
                    chatRepository = minis.chatRepository,
                    providerRepository = minis.providerRepository,
                    memoryRepository = minis.memoryRepository,
                    skillRepository = minis.skillRepository,
                    mcpRepository = minis.mcpRepository,
                    onBack = { finish() },
                    momodingOver = true,
                )
            }
        }
        lifecycleScope.launch { runCatching { AppBots.chatSeen(this@AppChatActivity, app) } }
        // text the app handed over goes into the composer once the chat screen has its view model
        intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
            lifecycleScope.launch {
                repeat(30) {
                    ChatViewModelStore.existing(sid)?.let { it.appendToInputText(text); return@launch }
                    delay(100)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // what was said while the chat was open has been seen
        MoApps.get(this, intent.getStringExtra(EXTRA_NAME).orEmpty())?.let { app -> lifecycleScope.launch { runCatching { AppBots.chatSeen(this@AppChatActivity, app) } } }
    }

    companion object {
        const val EXTRA_NAME = "momoding_app"
        const val EXTRA_TEXT = "momoding_app_text"
    }
}

object AppChat {
    /** The app's Momoding over the app, [text] in its composer when there is any. */
    fun open(context: Context, app: MoApps.App, text: String) {
        context.startActivity(Intent(context, AppChatActivity::class.java)
            .putExtra(AppChatActivity.EXTRA_NAME, app.name)
            .putExtra(AppChatActivity.EXTRA_TEXT, text)
            .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
