package app.momoding.core.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuContractsTest {
    @Test
    fun lifecycleRequiresEveryShellOnlyPrerequisite() {
        val cases = listOf(
            facts(managerInstalled = false) to ShizukuLifecycleStage.NOT_INSTALLED,
            facts(binderAlive = false) to ShizukuLifecycleStage.NOT_RUNNING,
            facts(preV11 = true) to ShizukuLifecycleStage.UNSUPPORTED_VERSION,
            facts(serverApiVersion = null) to ShizukuLifecycleStage.UNSUPPORTED_VERSION,
            facts(serverApiVersion = 12) to ShizukuLifecycleStage.UNSUPPORTED_VERSION,
            facts(serverUid = 0) to ShizukuLifecycleStage.ROOT_REJECTED,
            facts(serverUid = null) to ShizukuLifecycleStage.ROOT_REJECTED,
            facts(permissionGranted = false) to ShizukuLifecycleStage.PERMISSION_REQUIRED,
            facts(serviceConnected = false, serviceUid = null) to ShizukuLifecycleStage.CONNECTING,
            facts(serviceUid = 0) to ShizukuLifecycleStage.ROOT_REJECTED,
            facts(serviceUid = null) to ShizukuLifecycleStage.ROOT_REJECTED,
            facts() to ShizukuLifecycleStage.READY,
            facts(failed = true) to ShizukuLifecycleStage.ERROR,
        )

        cases.forEach { (input, expected) ->
            assertEquals(expected, shizukuSnapshot(input).stage)
        }
    }

    @Test
    fun readySnapshotContainsOnlyBoundedServiceFacts() {
        val snapshot = shizukuSnapshot(facts())

        assertEquals(ANDROID_SHELL_UID, snapshot.serverUid)
        assertEquals(ANDROID_SHELL_UID, snapshot.serviceUid)
        assertEquals(13, snapshot.serverApiVersion)
        assertEquals(13, snapshot.clientApiVersion)
        assertEquals(1234, snapshot.servicePid)
        assertFalse(snapshot.safeMessage.isBlank())
    }

    @Test
    fun packageQueriesRejectUnboundedOrCommandLikeInputs() {
        assertTrue(validShizukuPackagePage(offset = 0, limit = 1))
        assertTrue(validShizukuPackagePage(offset = 10_000, limit = 100))
        assertFalse(validShizukuPackagePage(offset = -1, limit = 1))
        assertFalse(validShizukuPackagePage(offset = 0, limit = 0))
        assertFalse(validShizukuPackagePage(offset = 0, limit = 101))
        assertFalse(validShizukuPackagePage(offset = 10_001, limit = 1))

        assertTrue(validShizukuPackageName("com.android.settings"))
        assertTrue(validShizukuPackageName("app.momoding"))
        assertFalse(validShizukuPackageName("settings"))
        assertFalse(validShizukuPackageName("../data"))
        assertFalse(validShizukuPackageName("com.android.settings;id"))
        assertFalse(validShizukuPackageName("com.android.settings --user 0"))
        assertFalse(validShizukuPackageName("a.".padEnd(260, 'b')))
    }

    private fun facts(
        managerInstalled: Boolean = true,
        binderAlive: Boolean = true,
        preV11: Boolean = false,
        permissionGranted: Boolean = true,
        serverUid: Int? = ANDROID_SHELL_UID,
        serverApiVersion: Int? = MIN_SHIZUKU_SERVER_API,
        serviceConnected: Boolean = true,
        serviceUid: Int? = ANDROID_SHELL_UID,
        failed: Boolean = false,
    ) = ShizukuPrerequisites(
        managerInstalled = managerInstalled,
        binderAlive = binderAlive,
        preV11 = preV11,
        permissionGranted = permissionGranted,
        serverUid = serverUid,
        serverApiVersion = serverApiVersion,
        clientApiVersion = 13,
        serviceUid = serviceUid,
        servicePid = 1234,
        serviceConnected = serviceConnected,
        failed = failed,
    )
}
