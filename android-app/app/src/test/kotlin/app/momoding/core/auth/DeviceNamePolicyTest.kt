package app.momoding.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceNamePolicyTest {
    @Test
    fun matchesHostTrimScalarAndControlContract() {
        assertEquals("Phone", DeviceNamePolicy.canonicalize("\ufeff\u00a0Phone\u3000"))
        assertEquals("\ud83d\ude80".repeat(80), DeviceNamePolicy.canonicalize("\ud83d\ude80".repeat(80)))

        listOf(
            " ",
            "x".repeat(81),
            "\ud83d\ude80".repeat(81),
            "valid\u0000invalid",
            "valid\u0085invalid",
            "bad\ud800name",
            "bad\udc00name",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                DeviceNamePolicy.canonicalize(invalid)
            }
        }
    }

    @Test
    fun durableValuesMustAlreadyBeCanonical() {
        assertEquals("Phone", DeviceNamePolicy.requireCanonical("Phone"))
        assertThrows(IllegalArgumentException::class.java) {
            DeviceNamePolicy.requireCanonical(" Phone ")
        }
    }
}
