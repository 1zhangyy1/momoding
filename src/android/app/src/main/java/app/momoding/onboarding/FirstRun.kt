// Structure from nanoMuse (GPL-3.0): io/github/nanomuse/ui/onboarding/FirstRunSetup.kt — full pages,
// one primary button each, state in prefs. The pages follow Momoding's prototype instead of
// nanoMuse's account flow.
package app.momoding.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.Identity
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import app.momoding.ui.Design

/**
 * Momoding's first run, three pages as in the prototype: connect a model → "I can work on your
 * phone" → may I notify you. Then the main chat opens with "What can I do for you?" and a few
 * examples ([StarterExamples]). The phone permissions (accessibility, drawing over apps, calendar…)
 * are not asked here; each is asked the first time a task needs it.
 */
/** The first-run route (debug: `momoding://first-run` opens it on any install). */
const val ROUTE_FIRST_RUN = "momoding/first_run"

object FirstRun {
    private const val PREFS = "momoding"
    private const val KEY_DONE = "firstRun.done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** Whether launch should show this instead of the chat: a fresh install with nothing to talk to. */
    fun needed(context: Context, hasProvider: Boolean): Boolean = !hasProvider && !isDone(context)
}

private enum class Page { MODEL, MEET, NOTIFY }

@Composable
fun FirstRunScreen(
    providerRepository: ProviderRepository,
    onAddProvider: () -> Unit,
    onPickModels: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val config by providerRepository.config.collectAsState()
    val hasProvider = config.instances.isNotEmpty()
    val hasModels = config.modelGroups.isNotEmpty()
    // momoding: the chat model is picked for the person once the provider's models are in
    val hasEntries = config.modelEntries.isNotEmpty()
    androidx.compose.runtime.LaunchedEffect(config) {
        if (hasProvider && !hasModels) app.momoding.providers.AutoSetup.ensureDefaultGroup(providerRepository)
    }
    var meetSeen by rememberSaveable { mutableIntStateOf(0) }
    val page = when {
        !hasProvider || !hasModels -> Page.MODEL
        meetSeen == 0 -> Page.MEET
        else -> Page.NOTIFY
    }
    val finish = { FirstRun.markDone(context); onDone() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Dots(page.ordinal)
        Spacer(Modifier.height(36.dp))
        when (page) {
            Page.MODEL -> {
                Title(stringResource(R.string.mo_ob_model_title))
                Body(stringResource(R.string.mo_ob_model_body))
                Spacer(Modifier.height(12.dp))
                Body(stringResource(R.string.mo_ob_model_vision), small = true)
                Spacer(Modifier.weight(1f))
                when {
                    !hasProvider -> Primary(stringResource(R.string.mo_ob_model_connect), onAddProvider)
                    !hasEntries -> Body(stringResource(R.string.mo_ob_model_preparing)) // models still loading
                    else -> Primary(stringResource(R.string.mo_ob_model_pick), onPickModels) // nothing we know: pick by hand
                }
            }
            Page.MEET -> {
                val face = remember { Identity.face(context)?.asImageBitmap() }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (face != null) Image(face, null, Modifier.size(88.dp).clip(CircleShape))
                }
                Spacer(Modifier.height(20.dp))
                Title(stringResource(R.string.mo_ob_meet_title, Identity.name(context)))
                Body(stringResource(R.string.mo_ob_meet_body))
                Spacer(Modifier.height(20.dp))
                Point(stringResource(R.string.mo_ob_meet_stop))
                Point(stringResource(R.string.mo_ob_meet_ask))
                Point(stringResource(R.string.mo_ob_meet_routine))
                Spacer(Modifier.weight(1f))
                Primary(stringResource(R.string.mo_ob_meet_ok)) { meetSeen = 1 }
            }
            Page.NOTIFY -> {
                Title(stringResource(R.string.mo_ob_notify_title))
                Body(stringResource(R.string.mo_ob_notify_body))
                Spacer(Modifier.weight(1f))
                Primary(stringResource(R.string.mo_ob_notify_on)) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else finish()
                }
                TextButton(onClick = finish, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mo_ob_notify_later), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun Dots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Box(Modifier.width(if (i == current) 22.dp else 8.dp).height(8.dp).clip(CircleShape)
                .background(if (i <= current) Tones.action else Tones.hairline))
        }
    }
}

@Composable private fun Title(text: String) {
    Text(text, fontSize = Design.Size.Display, lineHeight = Design.Line.Display, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(12.dp))
}

@Composable private fun Body(text: String, small: Boolean = false) {
    Text(text, fontSize = if (small) Design.Size.Note else Design.Size.Body, lineHeight = if (small) Design.Line.Note else Design.Line.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun Point(text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Tones.action))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = Design.Size.Body, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable private fun Primary(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tones.action, contentColor = Color.White)) {
        Text(text, fontSize = Design.Size.Title, fontWeight = FontWeight.SemiBold)
    }
}

/** The empty main chat: "What can I do for you?" and examples; a tap sends one. */
@Composable
fun StarterExamples(onPick: (String) -> Unit) {
    val examples = listOf(
        stringResource(R.string.mo_ex_reminder), stringResource(R.string.mo_ex_routine),
        stringResource(R.string.mo_ex_train), stringResource(R.string.mo_ex_reply),
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.mo_ex_title), fontSize = Design.Size.Display, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Start)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Design.Space.Gap)) {
            examples.forEach { ex -> app.momoding.ui.chat.Pill(ex, app.momoding.ui.chat.PillKind.SUGGEST) { onPick(ex) } }
        }
    }
}
