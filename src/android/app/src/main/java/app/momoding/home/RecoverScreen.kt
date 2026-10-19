package app.momoding.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.Identity
import app.momoding.ui.chat.PillButton
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import app.momoding.ui.Design

const val ROUTE_RECOVER = "momoding/recover"

/**
 * Where the app lands after it crashed or hung several times in a row (upstream's safety landing,
 * which opened its session list). Momoding's own page instead: nothing is lost, go back to the
 * conversation, or — if that is what keeps failing — start a fresh one.
 */
@Composable
fun RecoverScreen(onBackToChat: () -> Unit, onFreshChat: () -> Unit) {
    val context = LocalContext.current
    val face = remember { Identity.face(context)?.asImageBitmap() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (face != null) Image(face, contentDescription = null, modifier = Modifier.size(72.dp).clip(CircleShape))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.mo_recover_title), fontSize = Design.Size.Display, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.mo_recover_body), fontSize = Design.Size.Body, lineHeight = Design.Line.Body, color = ChatColors.secondaryText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PillButton(stringResource(R.string.mo_recover_back), primary = true, onClick = onBackToChat)
        Spacer(Modifier.height(10.dp))
        PillButton(stringResource(R.string.mo_recover_fresh), primary = false, onClick = onFreshChat)
    }
}
