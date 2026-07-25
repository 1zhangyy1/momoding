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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareReceiverTextE2EInstrumentedTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val application = context as MomodingApplication
        application.container.database.momodingDao().eraseHostScopedData()
        context.getSharedPreferences("share-import-receipts-v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        Unit
    }

    @Test
    fun coldAndDuplicateTextShareOpenOneEditableDraftWithoutStartingAgent() {
        val intent = Intent(context, ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, SHARED_TEXT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<ShareReceiverActivity>(intent).use {
            assertTrue(device.wait(Until.hasObject(By.textContains(SHARED_TEXT)), 10_000))
            assertTrue(device.hasObject(By.textContains("Nothing has been sent")))
        }
        val dao = (context as MomodingApplication).container.database.momodingDao()
        assertEquals(1, dao.drafts().count { it.taskId == null && it.text == SHARED_TEXT })
        assertTrue(dao.allTasks().isEmpty())

        ActivityScenario.launch<ShareReceiverActivity>(Intent(intent)).use {
            assertTrue(device.wait(Until.hasObject(By.textContains(SHARED_TEXT)), 10_000))
        }
        assertEquals(1, dao.drafts().count { it.taskId == null && it.text == SHARED_TEXT })
        assertTrue(dao.allTasks().isEmpty())
    }

    private companion object {
        const val SHARED_TEXT = "Share sheet editable shared text"
    }
}
