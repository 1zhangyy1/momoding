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

    // what is said while the chat is open is seen
    override fun onResume() {
        super.onResume()
        BotStatus.showing(session())
    }

    override fun onPause() {
        super.onPause()
        BotStatus.hidden(session())
    }

    private fun session() = MoApps.get(this, intent.getStringExtra(EXTRA_NAME).orEmpty())?.botSession

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

    /**
     * Where a Bot lives: its app, with its chat raised over it when [raise] (something new, or it
     * waits); a Bot without an app (and the main Momoding) in its chat in Momoding's own window.
     */
    fun home(context: Context, session: String, app: MoApps.App?, raise: Boolean) {
        when {
            app?.hasApp == true && raise -> openWithApp(context, app)
            app?.hasApp == true -> context.startActivity(MoAppActivity.intent(context, app.name)
                .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("momoding://session/$session"))
                .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** The app with its Momoding over it: where anything that leads to that Momoding takes the person. */
    fun openWithApp(context: Context, app: MoApps.App) {
        context.startActivities(arrayOf(
            MoAppActivity.intent(context, app.name),
            Intent(context, AppChatActivity::class.java).putExtra(AppChatActivity.EXTRA_NAME, app.name),
        ))
    }
}
