package app.momoding.core.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateManagerTest {
    @Test
    fun `exposes a newer release and downloads it on demand`() = runTest {
        val release = release("0.1.0-alpha.2")
        val downloaded = File("momoding-test.apk")
        val manager = AppUpdateManager(
            scope = this,
            currentVersionName = "0.1.0-alpha.1",
            source = AppUpdateSource { release },
            downloader = AppUpdateDownload { selected, onProgress ->
                assertEquals(release, selected)
                onProgress(52)
                downloaded
            },
            elapsedRealtime = { testScheduler.currentTime },
        )

        manager.checkNow()
        advanceUntilIdle()
        assertEquals(AppUpdateUiState.Available(release), manager.state.value)

        assertEquals(downloaded, manager.prepareUpdate().getOrThrow())
        assertEquals(AppUpdateUiState.Available(release), manager.state.value)
    }

    @Test
    fun `reports current release as up to date`() = runTest {
        val manager = AppUpdateManager(
            scope = this,
            currentVersionName = "0.1.0-alpha.2",
            source = AppUpdateSource { release("0.1.0-alpha.2") },
            downloader = AppUpdateDownload { _, _ -> error("not used") },
            elapsedRealtime = { testScheduler.currentTime },
        )

        manager.checkNow()
        advanceUntilIdle()

        assertTrue(manager.state.value is AppUpdateUiState.UpToDate)
    }

    private fun release(version: String) = AppRelease(
        versionName = version,
        tagName = "v$version",
        title = "Momoding $version",
        notes = "Notes",
        publishedAt = null,
        apk = AppReleaseAsset("momoding-$version.apk", "https://github.com/apk", 1024),
        checksum = AppReleaseAsset(
            "momoding-$version.apk.sha256",
            "https://github.com/checksum",
            93,
        ),
    )
}
