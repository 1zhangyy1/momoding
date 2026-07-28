package app.momoding.core.capabilities

import android.Manifest
import android.os.Build
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCapabilityRegistryTest {
    @Test
    fun accessibilityIsReadyOnlyWhenTheDeclaredServiceIsActuallyConnected() {
        assertEquals(
            CapabilityAvailability.UNSUPPORTED,
            accessibilityAvailability(
                serviceInstalled = false,
                settingEnabled = true,
                serviceConnected = true,
            ),
        )
        assertEquals(
            CapabilityAvailability.NOT_GRANTED,
            accessibilityAvailability(
                serviceInstalled = true,
                settingEnabled = false,
                serviceConnected = false,
            ),
        )
        assertEquals(
            CapabilityAvailability.SESSION_REQUIRED,
            accessibilityAvailability(
                serviceInstalled = true,
                settingEnabled = true,
                serviceConnected = false,
            ),
        )
        assertEquals(
            CapabilityAvailability.READY,
            accessibilityAvailability(
                serviceInstalled = true,
                settingEnabled = true,
                serviceConnected = true,
            ),
        )
        assertEquals(
            CapabilityAvailability.NOT_GRANTED,
            accessibilityAvailability(
                serviceInstalled = true,
                settingEnabled = false,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun photoLibraryContractDistinguishesFullPartialDeniedAndLegacyRequests() {
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            photoLibraryPermissionRequest(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            photoLibraryPermissionRequest(Build.VERSION_CODES.S_V2),
        )
        assertEquals(
            CapabilityAvailability.READY,
            photoLibraryAvailability(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                it == Manifest.permission.READ_MEDIA_IMAGES
            },
        )
        assertEquals(
            CapabilityAvailability.PARTIAL,
            photoLibraryAvailability(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            },
        )
        assertEquals(
            CapabilityAvailability.NOT_GRANTED,
            photoLibraryAvailability(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { false },
        )
    }

    @Test
    fun refreshPublishesEveryProbeInStableCapabilityOrder() = runTest {
        val registry = AndroidCapabilityRegistry(
            probes = AndroidCapabilityId.entries.associateWith { id ->
                AndroidCapabilityProbe { checkedAt ->
                    AndroidCapabilityState(
                        id = id,
                        availability = if (id == AndroidCapabilityId.SAF_FOLDERS) {
                            CapabilityAvailability.READY
                        } else {
                            CapabilityAvailability.UNSUPPORTED
                        },
                        source = "test source",
                        checkedAtMillis = checkedAt,
                        safeMessage = "test message",
                    )
                }
            },
            scope = backgroundScope,
            nowMillis = { 42L },
        )

        val states = registry.refreshNow()

        assertEquals(AndroidCapabilityId.entries, states.map(AndroidCapabilityState::id))
        assertEquals(states, registry.states.value)
        assertEquals(42L, states.first().checkedAtMillis)
        assertEquals(CapabilityAvailability.READY, states.first().availability)
    }

    @Test
    fun failedProbeIsFailClosedAndDoesNotExposeExceptionText() = runTest {
        val secret = "secret-provider-uri"
        val probes = AndroidCapabilityId.entries.associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                if (id == AndroidCapabilityId.ACCESSIBILITY_CONTROL) error(secret)
                AndroidCapabilityState(
                    id = id,
                    availability = CapabilityAvailability.NOT_GRANTED,
                    source = "test source",
                    checkedAtMillis = checkedAt,
                    safeMessage = "not granted",
                )
            }
        }
        val registry = AndroidCapabilityRegistry(
            probes = probes,
            scope = backgroundScope,
            nowMillis = { 7L },
        )

        val state = registry.refreshNow().single {
            it.id == AndroidCapabilityId.ACCESSIBILITY_CONTROL
        }

        assertEquals(CapabilityAvailability.ERROR, state.availability)
        assertEquals("Android access could not be checked. Try again.", state.safeMessage)
        assertFalse(state.safeMessage.contains(secret))
        assertTrue(registry.states.value.all { it.availability != CapabilityAvailability.READY })
    }

    @Test
    fun registryRejectsMissingProbeBeforeItCanClaimACompleteDeviceState() = runTest {
        val probes = AndroidCapabilityId.entries.dropLast(1).associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                AndroidCapabilityState(
                    id,
                    CapabilityAvailability.NOT_GRANTED,
                    "test source",
                    checkedAt,
                    "not granted",
                )
            }
        }

        val failure = runCatching {
            AndroidCapabilityRegistry(probes, backgroundScope)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun malformedRefreshReplacesAPreviouslyReadyCapabilityWithSafeError() = runTest {
        var malformed = false
        val probes = AndroidCapabilityId.entries.associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                AndroidCapabilityState(
                    id = id,
                    availability = CapabilityAvailability.READY,
                    source = "test source",
                    checkedAtMillis = if (malformed && id == AndroidCapabilityId.SAF_FOLDERS) {
                        checkedAt - 1
                    } else {
                        checkedAt
                    },
                    safeMessage = "ready",
                )
            }
        }
        val registry = AndroidCapabilityRegistry(
            probes = probes,
            scope = backgroundScope,
            nowMillis = { 99L },
        )
        registry.refreshNow()
        assertEquals(
            CapabilityAvailability.READY,
            registry.states.value.first().availability,
        )

        malformed = true
        registry.refreshNow()

        val saf = registry.states.value.first()
        assertEquals(CapabilityAvailability.ERROR, saf.availability)
        assertEquals("Android access could not be checked. Try again.", saf.safeMessage)
    }
}
