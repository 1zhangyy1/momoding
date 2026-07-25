package app.momoding.feature.share

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.momoding.app.MomodingApplication
import app.momoding.core.attachments.AttachmentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareSheetE2EInstrumentedTest {
    private lateinit var targetContext: Context
    private lateinit var sourceContext: Context
    private lateinit var device: UiDevice

    @Before
    fun setUp() = runBlocking {
        targetContext = ApplicationProvider.getApplicationContext()
        sourceContext = InstrumentationRegistry.getInstrumentation().context
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val container = (targetContext as MomodingApplication).container
        container.database.momodingDao().eraseHostScopedData()
        targetContext.getSharedPreferences("share-import-receipts-v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        Unit
    }

    @Test
    fun sharesheetCoversTextImagesTextFileDuplicateCancelAndBadUri() {
        val container = (targetContext as MomodingApplication).container
        val dao = container.database.momodingDao()

        shareThroughChooser(ShareSourceFixtureActivity.SCENARIO_TEXT, ShareSourceFixtureActivity.TEXT_VALUE)
        assertEquals(1, dao.drafts().count { it.text.contains(ShareSourceFixtureActivity.TEXT_VALUE) })
        assertTrue(dao.allTasks().isEmpty())

        shareThroughChooser(ShareSourceFixtureActivity.SCENARIO_TEXT, ShareSourceFixtureActivity.TEXT_VALUE)
        assertEquals(1, dao.drafts().count { it.text.contains(ShareSourceFixtureActivity.TEXT_VALUE) })
        assertTrue(dao.allTasks().isEmpty())

        shareThroughChooser(ShareSourceFixtureActivity.SCENARIO_SINGLE_IMAGE, "single.png")
        shareThroughChooser(ShareSourceFixtureActivity.SCENARIO_MULTI_IMAGE, "multi-one.png")
        shareThroughChooser(ShareSourceFixtureActivity.SCENARIO_TEXT_FILE, "shared-notes.md")
        val records = container.database.attachmentDao().allAttachments()
        assertEquals(4, records.size)
        assertEquals(3, records.count { it.kind == AttachmentKind.IMAGE.name })
        assertEquals(1, records.count { it.kind == AttachmentKind.TEXT_FILE.name })
        assertTrue(dao.allTasks().isEmpty())

        val draftsBeforeCancel = dao.drafts().size
        openChooser(ShareSourceFixtureActivity.SCENARIO_CANCEL)
        assertTrue(device.wait(Until.hasObject(By.text("Momoding")), 5_000))
        device.pressBack()
        device.waitForIdle()
        assertEquals(draftsBeforeCancel, dao.drafts().size)

        shareThroughChooser(
            ShareSourceFixtureActivity.SCENARIO_MISSING_IMAGE,
            "could not be read",
        )
        assertEquals(4, container.database.attachmentDao().allAttachments().size)
        assertTrue(dao.allTasks().isEmpty())

        val pdf = Intent(Intent.ACTION_SEND).setType("application/pdf")
        assertFalse(
            targetContext.packageManager.queryIntentActivities(pdf, 0).any { resolveInfo ->
                resolveInfo.activityInfo.packageName == targetContext.packageName
            },
        )
    }

    private fun shareThroughChooser(scenario: String, expectedText: String) {
        openChooser(scenario)
        val targetSelector = By.text("Momoding").pkg("com.android.intentresolver")
        var target: UiObject2? = device.wait(Until.findObject(targetSelector), 2_000)
        repeat(3) {
            if (target == null) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 4 / 5,
                    device.displayWidth / 2,
                    device.displayHeight * 2 / 5,
                    20,
                )
                target = device.wait(Until.findObject(targetSelector), 2_000)
            }
        }
        checkNotNull(target) { "Momoding was not visible in the Android Sharesheet" }.click()
        assertTrue(device.wait(Until.hasObject(By.textContains(expectedText)), 10_000))
        assertTrue(device.hasObject(By.textContains("Nothing has been sent")) ||
            expectedText.contains("could not be read"))
    }

    private fun openChooser(scenario: String) {
        sourceContext.startActivity(
            Intent(sourceContext, ShareSourceFixtureActivity::class.java)
                .putExtra(ShareSourceFixtureActivity.EXTRA_SCENARIO, scenario)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.waitForIdle()
    }
}
