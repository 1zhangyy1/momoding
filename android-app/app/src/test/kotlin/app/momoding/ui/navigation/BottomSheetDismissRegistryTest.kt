package app.momoding.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomSheetDismissRegistryTest {
    @Test
    fun `locked and missing owners cannot dismiss`() {
        val registry = MomodingBottomSheetDismissRegistry()
        var requests = 0

        assertFalse(registry.canDismiss("attention"))
        assertFalse(registry.requestDismiss("attention"))

        registry.register(
            key = "attention",
            routeIdentity = "route",
            owner = this,
            enabled = false,
            request = { requests += 1 },
        )

        assertFalse(registry.canDismiss("attention"))
        assertFalse(registry.requestDismiss("attention"))
        assertEquals(0, requests)
    }

    @Test
    fun `only the exact current owner can request or unregister`() {
        val registry = MomodingBottomSheetDismissRegistry()
        val staleOwner = Any()
        val currentOwner = Any()
        val requests = mutableListOf<String>()

        registry.register("attention", "route", staleOwner, enabled = true) {
            requests += "stale"
        }
        registry.register("attention", "route", currentOwner, enabled = true) {
            requests += "current"
        }

        registry.unregister("attention", staleOwner)
        assertTrue(registry.canDismiss("attention"))
        assertTrue(registry.requestDismiss("attention"))
        assertEquals(listOf("current"), requests)

        registry.unregister("attention", currentOwner)
        assertFalse(registry.canDismiss("attention"))
        assertFalse(registry.requestDismiss("attention"))
        assertEquals(listOf("current"), requests)
    }

    @Test
    fun `route replacement cannot invoke another route owner`() {
        val registry = MomodingBottomSheetDismissRegistry()
        val requests = mutableListOf<String>()

        registry.register("content-1", "call-1", Any(), enabled = true) { requests += "call-1" }
        registry.register("content-2", "call-2", Any(), enabled = true) { requests += "call-2" }

        assertFalse(registry.requestDismiss("content-1"))
        assertFalse(registry.requestDismissForRoute("call-1"))
        assertTrue(registry.requestDismissForRoute("call-2"))
        assertEquals(listOf("call-2"), requests)
    }

    @Test
    fun `same route replacement with locked owner rejects old key and system back`() {
        val registry = MomodingBottomSheetDismissRegistry()
        val staleOwner = Any()
        val currentOwner = Any()
        val requests = mutableListOf<String>()

        registry.register("content-1", "call", staleOwner, enabled = true) {
            requests += "stale"
        }
        registry.register("content-2", "call", currentOwner, enabled = false) {
            requests += "current"
        }
        assertFalse(registry.canDismiss("content-1"))
        assertFalse(registry.canDismiss("content-2"))
        assertFalse(registry.requestDismiss("content-1"))
        assertFalse(registry.requestDismissForRoute("call"))

        registry.unregister("content-1", staleOwner)
        assertFalse(registry.requestDismiss("content-1"))
        assertFalse(registry.requestDismissForRoute("call"))
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `same route enabled replacement invokes only current key and never falls back`() {
        val registry = MomodingBottomSheetDismissRegistry()
        val staleOwner = Any()
        val currentOwner = Any()
        val requests = mutableListOf<String>()

        registry.register("content-1", "call", staleOwner, enabled = true) {
            requests += "stale"
        }
        registry.register("content-2", "call", currentOwner, enabled = true) {
            requests += "current"
        }

        assertFalse(registry.canDismiss("content-1"))
        assertTrue(registry.canDismiss("content-2"))
        assertFalse(registry.requestDismiss("content-1"))
        assertTrue(registry.requestDismiss("content-2"))
        assertTrue(registry.requestDismissForRoute("call"))
        assertEquals(listOf("current", "current"), requests)

        registry.unregister("content-1", staleOwner)
        assertTrue(registry.requestDismiss("content-2"))
        registry.unregister("content-2", currentOwner)
        assertFalse(registry.requestDismiss("content-1"))
        assertFalse(registry.requestDismissForRoute("call"))
        assertEquals(listOf("current", "current", "current"), requests)
    }
}
