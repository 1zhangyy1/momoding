package app.momoding.feature.tasks

import app.momoding.wire.WireErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalTaskCreationFailureTest {
    @Test
    fun `frozen session busy errors become actionable retryable creation failures`() {
        listOf(
            "PI_MOBILE_ANOTHER_TASK_IS_RUNNING",
            "PI_MOBILE_TASK_SESSION_BUSY",
        ).forEach { errorCode ->
            val failure = phoneLocalTaskCreationFailure(
                text = "Start a second task.",
                error = IllegalStateException(errorCode),
            )

            assertEquals(WireErrorCode.SESSION_BUSY, failure.code)
            assertTrue(failure.retryable)
            assertEquals(
                "Another on-device task is still running or waiting for you. Finish or stop it, then retry.",
                failure.safeMessage,
            )
        }
    }

    @Test
    fun `unknown creation failure remains generic and does not expose internal message`() {
        val failure = phoneLocalTaskCreationFailure(
            text = "Normal task.",
            error = IllegalStateException("private internal detail"),
        )

        assertEquals(WireErrorCode.RECOVERY_REQUIRED, failure.code)
        assertTrue(failure.retryable)
        assertNull(failure.safeMessage)
    }

    @Test
    fun `near match busy errors fail closed as unknown`() {
        listOf(
            IllegalStateException("prefix_PI_MOBILE_ANOTHER_TASK_IS_RUNNING"),
            IllegalStateException("PI_MOBILE_TASK_SESSION_BUSY_suffix"),
            RuntimeException("PI_MOBILE_ANOTHER_TASK_IS_RUNNING"),
        ).forEach { error ->
            val failure = phoneLocalTaskCreationFailure("Normal task.", error)
            assertEquals(WireErrorCode.RECOVERY_REQUIRED, failure.code)
            assertTrue(failure.retryable)
            assertNull(failure.safeMessage)
        }
    }
}
