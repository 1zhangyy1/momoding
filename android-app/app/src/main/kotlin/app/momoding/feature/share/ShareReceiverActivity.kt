package app.momoding.feature.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.momoding.app.MomodingApplication
import app.momoding.app.MainActivity
import app.momoding.core.appearance.AppearanceMode
import app.momoding.ui.theme.MomodingTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    private val container by lazy { (application as MomodingApplication).container }
    private var screenState by mutableStateOf<ShareReceiverState>(ShareReceiverState.Importing)
    private var importJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MomodingTheme(
                appearance = AppearanceMode.SYSTEM,
                systemDark = isSystemInDarkTheme(),
            ) {
                ShareReceiverScreen(
                    state = screenState,
                    onRetry = ::startImport,
                    onCancel = {
                        importJob?.cancel()
                        finish()
                    },
                )
            }
        }
        startImport()
    }

    private fun startImport() {
        if (importJob?.isActive == true) return
        screenState = ShareReceiverState.Importing
        importJob = lifecycleScope.launch {
            try {
                val result = container.shareImportCoordinator.import(intent)
                startActivity(
                    Intent(this@ShareReceiverActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_SHARE_RECEIPT_ID, result.receiptId),
                )
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                screenState = ShareReceiverState.Error
            }
        }
    }
}

private sealed interface ShareReceiverState {
    data object Importing : ShareReceiverState
    data object Error : ShareReceiverState
}

@androidx.compose.runtime.Composable
private fun ShareReceiverScreen(
    state: ShareReceiverState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ShareReceiverState.Importing -> {
                CircularProgressIndicator()
                Text("Preparing your new task", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Shared content is being copied into Momoding private storage. Nothing will be sent automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ShareReceiverState.Error -> {
                Text("Shared content could not be prepared", style = MaterialTheme.typography.titleLarge)
                Text("Nothing was sent. You can retry or return to the sharing app.")
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

const val EXTRA_SHARE_RECEIPT_ID = "app.momoding.extra.SHARE_RECEIPT_ID"
