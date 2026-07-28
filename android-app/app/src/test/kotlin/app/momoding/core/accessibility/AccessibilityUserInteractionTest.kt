package app.momoding.core.accessibility

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityUserInteractionTest {
    @Test
    fun realUiInteractionEventAdvancesIndependentTakeoverGeneration() {
        val controller = AccessibilitySessionController()
        val service = MomodingAccessibilityService()
        controller.onServiceConnected(service)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "dev.fixture"
        }

        controller.onAccessibilityEvent(
            service = service,
            foregroundPackage = "dev.fixture",
            event = event,
        )

        assertEquals(1, controller.state.value.userInteractionGeneration)
        assertEquals("dev.fixture", controller.state.value.foregroundPackage)
    }
}
