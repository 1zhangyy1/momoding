package app.momoding.feature.share

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.momoding.app.MomodingApplication
import app.momoding.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareDraftRebuildInstrumentedTest {
    @Test
    fun completedShareReceiptRestoresSameEditableDraftAfterActivityRebuild() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context as MomodingApplication).container
        container.database.momodingDao().eraseHostScopedData()
        context.getSharedPreferences("share-import-receipts-v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val result = container.shareImportCoordinator.import(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, SHARED_TEXT),
        )
        val launch = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHARE_RECEIPT_ID, result.receiptId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.textContains(SHARED_TEXT)), 10_000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.textContains(SHARED_TEXT)), 10_000))
            assertTrue(device.hasObject(By.textContains("Nothing has been sent")))
        }
        assertTrue(container.database.momodingDao().allTasks().isEmpty())
    }

    private companion object {
        const val SHARED_TEXT = "Share sheet survives Activity rebuild"
    }
}
