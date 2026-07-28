package app.momoding.core.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityUiActionTest {
    @Test
    fun exactActionShapesAreAccepted() {
        assertEquals(
            AccessibilityUiActionKind.CLICK,
            AccessibilityUiActionRequest(SNAPSHOT, "$SNAPSHOT:n1", AccessibilityUiActionKind.CLICK)
                .action,
        )
        AccessibilityUiActionRequest(
            SNAPSHOT,
            "$SNAPSHOT:n2",
            AccessibilityUiActionKind.SCROLL,
            direction = AccessibilityScrollDirection.DOWN,
        )
        AccessibilityUiActionRequest(
            SNAPSHOT,
            "$SNAPSHOT:n3",
            AccessibilityUiActionKind.INPUT_DRAFT,
            text = "harmless draft",
        )
        AccessibilityUiActionRequest(SNAPSHOT, null, AccessibilityUiActionKind.BACK)
    }

    @Test
    fun ambiguousOrSubmittingShapesFailBeforeAndroidExecution() {
        val invalid = listOf(
            { AccessibilityUiActionRequest(SNAPSHOT, null, AccessibilityUiActionKind.CLICK) },
            {
                AccessibilityUiActionRequest(
                    SNAPSHOT,
                    "$SNAPSHOT:n1",
                    AccessibilityUiActionKind.SCROLL,
                )
            },
            {
                AccessibilityUiActionRequest(
                    SNAPSHOT,
                    "$SNAPSHOT:n1",
                    AccessibilityUiActionKind.INPUT_DRAFT,
                    text = "",
                )
            },
            {
                AccessibilityUiActionRequest(
                    SNAPSHOT,
                    "$SNAPSHOT:n1",
                    AccessibilityUiActionKind.BACK,
                )
            },
        )

        invalid.forEach { create -> assertTrue(runCatching(create).isFailure) }
    }

    private companion object {
        const val SNAPSHOT = "ui-11111111111111111111111111111111"
    }
}
