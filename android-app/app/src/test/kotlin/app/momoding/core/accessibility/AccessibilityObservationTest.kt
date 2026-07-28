package app.momoding.core.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityObservationTest {
    @Test
    fun passwordNodesNeverExposeValueHintDescriptionOrResourceId() {
        val node = AccessibilityNodeInfo.obtain().apply {
            text = "top-secret-value"
            hintText = "Password"
            contentDescription = "Current password"
            viewIdResourceName = "com.example:id/password"
            isPassword = true
            isEditable = true
        }

        val sanitized = AccessibilityNodeSanitizer.sanitize(
            node = node,
            handle = "ui-test:n0",
            parentHandle = null,
            windowId = 1,
            depth = 0,
        )

        assertTrue(sanitized.redacted)
        assertEquals("[redacted]", sanitized.text)
        assertEquals("[redacted]", sanitized.hint)
        assertEquals("[redacted]", sanitized.contentDescription)
        assertNull(sanitized.resourceId)
        assertFalse(sanitized.toString().contains("top-secret-value"))
    }

    @Test
    fun inputTypeOtpCardNumberAndExpiryAreTreatedAsSensitive() {
        assertTrue(
            AccessibilityNodeSanitizer.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ),
        )
        assertTrue(
            AccessibilityNodeSanitizer.isPasswordInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertFalse(
            AccessibilityNodeSanitizer.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            ),
        )

        val code = AccessibilityNodeInfo.obtain().apply {
            text = "123456"
        }
        val sanitized = AccessibilityNodeSanitizer.sanitize(
            code,
            "ui-test:n1",
            null,
            1,
            0,
        )
        assertTrue(sanitized.redacted)
        assertEquals("[redacted]", sanitized.text)

        listOf("4111 1111 1111 1111", "09/29").forEachIndexed { index, value ->
            val financialValue = AccessibilityNodeInfo.obtain().apply { text = value }
            val result = AccessibilityNodeSanitizer.sanitize(
                financialValue,
                "ui-test:financial-$index",
                null,
                1,
                0,
            )
            assertTrue(result.redacted)
            assertEquals("[redacted]", result.text)
        }
    }

    @Test
    fun sensitiveContainerCanRedactAChildAndStringsAreAlwaysBounded() {
        val child = AccessibilityNodeInfo.obtain().apply {
            text = "child-value"
            contentDescription = "child-description"
            viewIdResourceName = "com.example:id/child"
        }

        val redacted = AccessibilityNodeSanitizer.sanitize(
            child,
            "ui-test:n2",
            "ui-test:n1",
            1,
            1,
            forceRedaction = true,
        )

        assertTrue(redacted.redacted)
        assertEquals("[redacted]", redacted.text)
        assertEquals("[redacted]", redacted.contentDescription)
        assertNull(redacted.resourceId)

        val longText = AccessibilityNodeInfo.obtain().apply {
            text = "x".repeat(MAX_ACCESSIBILITY_STRING_LENGTH + 100)
        }
        val bounded = AccessibilityNodeSanitizer.sanitize(
            longText,
            "ui-test:n3",
            null,
            1,
            0,
        )
        assertEquals(MAX_ACCESSIBILITY_STRING_LENGTH, bounded.text?.length)
    }

    @Test
    fun requestBoundsEnforceTheProductObservationBudget() {
        AccessibilityInspectionRequest(
            requestId = "within-budget",
            maxNodes = MAX_ACCESSIBILITY_NODES,
            maxDepth = MAX_ACCESSIBILITY_DEPTH,
        )

        assertTrue(
            runCatching {
                AccessibilityInspectionRequest(
                    requestId = "too-many",
                    maxNodes = MAX_ACCESSIBILITY_NODES + 1,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                AccessibilityInspectionRequest(
                    requestId = "too-deep",
                    maxDepth = MAX_ACCESSIBILITY_DEPTH + 1,
                )
            }.isFailure,
        )
    }
}
