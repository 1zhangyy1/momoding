package app.momoding.core.media

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class AndroidMediaConsentCoordinator(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : MediaSystemConsentRequester {
    private val appContext = context.applicationContext
    private val requestMutex = Mutex()

    @Volatile
    private var pendingId: String? = null

    @Volatile
    private var pendingResult: CompletableDeferred<MediaSystemConsentResult>? = null

    override suspend fun request(
        taskId: String,
        action: MediaToolAction,
        request: PendingIntent,
    ): MediaSystemConsentResult {
        require(taskId.isNotBlank())
        return requestMutex.withLock {
            val requestId = requestIdFactory()
            val result = CompletableDeferred<MediaSystemConsentResult>()
            pendingId = requestId
            pendingResult = result
            val launched = runCatching {
                appContext.startActivity(
                    MediaConsentActivity.intent(
                        appContext,
                        requestId,
                        action,
                        request,
                    ),
                )
            }.isSuccess
            if (!launched) {
                clear(requestId, result)
                return@withLock MediaSystemConsentResult.UNAVAILABLE
            }
            try {
                withTimeoutOrNull(timeoutMillis) { result.await() }
                    ?: MediaSystemConsentResult.TIMEOUT
            } finally {
                clear(requestId, result)
            }
        }
    }

    fun respond(requestId: String, result: MediaSystemConsentResult): Boolean {
        if (pendingId != requestId) return false
        return pendingResult?.complete(result) == true
    }

    private fun clear(
        requestId: String,
        result: CompletableDeferred<MediaSystemConsentResult>,
    ) {
        if (pendingId == requestId) pendingId = null
        if (pendingResult === result) pendingResult = null
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60_000L
    }
}

class MediaConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        respond(
            if (result.resultCode == Activity.RESULT_OK) {
                MediaSystemConsentResult.APPROVED
            } else {
                MediaSystemConsentResult.DENIED
            },
        )
    }

    private lateinit var requestId: String
    private var responded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val pendingIntent = intent.pendingIntentExtra()
        if (requestId.isBlank() || pendingIntent == null) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            runCatching {
                launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }.onFailure {
                respond(MediaSystemConsentResult.UNAVAILABLE)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && !responded && ::requestId.isInitialized) {
            respond(MediaSystemConsentResult.DENIED)
        }
        super.onDestroy()
    }

    private fun respond(result: MediaSystemConsentResult) {
        if (responded) return
        responded = true
        (application as app.momoding.app.MomodingApplication)
            .container
            .androidMediaConsentCoordinator
            .respond(requestId, result)
        finish()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "media_consent_request_id"
        private const val EXTRA_ACTION = "media_consent_action"
        private const val EXTRA_PENDING_INTENT = "media_consent_pending_intent"

        fun intent(
            context: Context,
            requestId: String,
            action: MediaToolAction,
            request: PendingIntent,
        ): Intent = Intent(context, MediaConsentActivity::class.java)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_ACTION, action.wireValue)
            .putExtra(EXTRA_PENDING_INTENT, request)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Suppress("DEPRECATION")
    private fun Intent.pendingIntentExtra(): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PENDING_INTENT, PendingIntent::class.java)
        } else {
            getParcelableExtra(EXTRA_PENDING_INTENT)
        }
}
