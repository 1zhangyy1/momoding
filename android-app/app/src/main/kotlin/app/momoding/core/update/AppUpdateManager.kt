package app.momoding.core.update

import android.app.Application
import android.os.SystemClock
import app.momoding.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class AppUpdateManager internal constructor(
    private val scope: CoroutineScope,
    private val currentVersionName: String,
    private val source: AppUpdateSource,
    private val downloader: AppUpdateDownload,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()
    private var checkJob: Job? = null
    private var lastCheckStartedAt = Long.MIN_VALUE

    fun checkIfDue() {
        val now = elapsedRealtime()
        if (lastCheckStartedAt != Long.MIN_VALUE && now - lastCheckStartedAt < CHECK_INTERVAL_MILLIS) {
            return
        }
        checkForUpdates()
    }

    fun checkNow() = checkForUpdates()

    private fun checkForUpdates() {
        if (checkJob?.isActive == true) return
        lastCheckStartedAt = elapsedRealtime()
        checkJob = scope.launch {
            operationMutex.withLock {
                _state.value = AppUpdateUiState.Checking
                _state.value = runCatching {
                    val release = source.latestRelease()
                    val current = ReleaseVersion.parse(currentVersionName)
                        ?: error("Current app version is not update-compatible")
                    val available = release?.takeIf {
                        val candidate = ReleaseVersion.parse(it.versionName)
                            ?: return@takeIf false
                        candidate > current
                    }
                    available?.let(AppUpdateUiState::Available)
                        ?: AppUpdateUiState.UpToDate(currentVersionName)
                }.getOrElse { error ->
                    AppUpdateUiState.Failed(error.checkUserFacingMessage())
                }
            }
        }
    }

    suspend fun prepareUpdate(): Result<File> = operationMutex.withLock {
        val release = when (val current = _state.value) {
            is AppUpdateUiState.Available -> current.release
            is AppUpdateUiState.Failed -> current.release
            else -> return@withLock Result.failure(
                IllegalStateException("No Momoding update is ready to download"),
            )
        } ?: return@withLock Result.failure(
            IllegalStateException("No Momoding update is ready to download"),
        )
        _state.value = AppUpdateUiState.Downloading(release, progressPercent = 0)
        runCatching {
            downloader.download(release) { progress ->
                _state.value = AppUpdateUiState.Downloading(release, progress)
            }
        }.onSuccess {
            _state.value = AppUpdateUiState.Available(release)
        }.onFailure { error ->
            _state.value = AppUpdateUiState.Failed(error.downloadUserFacingMessage(), release)
        }
    }

    private fun Throwable.checkUserFacingMessage(): String = when (this) {
        is java.net.UnknownHostException -> "Couldn’t reach GitHub. Check your connection and try again."
        is java.net.SocketTimeoutException -> "The update check timed out. Try again."
        else -> message?.take(180)?.ifBlank { null } ?: "Couldn’t check for updates."
    }

    private fun Throwable.downloadUserFacingMessage(): String = when (this) {
        is java.net.SocketTimeoutException ->
            "Download timed out. Tap Retry; saved progress will be reused."
        is java.io.IOException ->
            "Download interrupted. Tap Retry; saved progress will be reused."
        else -> message?.take(180)?.ifBlank { null } ?:
            "Couldn’t download the update. Tap Retry to continue."
    }

    companion object {
        private const val CHECK_INTERVAL_MILLIS = 12L * 60L * 60L * 1_000L

        fun create(application: Application, scope: CoroutineScope): AppUpdateManager =
            AppUpdateManager(
                scope = scope,
                currentVersionName = BuildConfig.VERSION_NAME,
                source = GitHubReleaseUpdateSource(),
                downloader = AppUpdateDownloader(application),
            )
    }
}
