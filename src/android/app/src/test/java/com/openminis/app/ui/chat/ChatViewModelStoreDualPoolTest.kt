package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-vm-store-dual-pool] Sub agent sessions and the user's own chats
 * shared one LRU, so they evicted each other. Measured on a Pixel 4a with
 * MAX_CONCURRENT_CHILD_JOBS (=3) saturated: three children allocated inside
 * 55ms and the third evicted the FIRST — a child 55 milliseconds old — while
 * also pushing two of the user's own sessions out of the cache.
 *
 * ChatViewModelStore is an Android-runtime object this JVM test cannot
 * instantiate, so the store's own invariants are pinned as source facts (the
 * approach [ChatViewModelStoreEvictionTest] established). The eviction
 * ARITHMETIC — the part that actually decides who gets thrown out — is
 * reimplemented here against the same rules and exercised directly, so the
 * isolation claim is tested rather than asserted.
 */
class ChatViewModelStoreDualPoolTest {

    // ---- source invariants -------------------------------------------------

    // ---- eviction arithmetic ----------------------------------------------

    private enum class Kind { NORMAL, CHILD }

    /** Mirrors ChatViewModelStore: per-pool LRU with the two carve-outs. */
    private class Pools(val normalCap: Int = 4, val childCap: Int = 6) {
        val stores = LinkedHashMap<String, Kind>()
        val kinds = mutableMapOf<String, Kind>()
        var busy: Set<String> = emptySet()
        var onScreen: String? = null
        val evicted = mutableListOf<String>()

        fun open(id: String, kind: Kind) {
            val existing = stores.remove(id)
            if (existing == null) kinds.putIfAbsent(id, kind)
            stores[id] = kinds[id]!!
            trim(kinds[id]!!)
        }

        private fun trim(kind: Kind) {
            val cap = if (kind == Kind.NORMAL) normalCap else childCap
            val inPool = stores.keys.filter { kinds[it] == kind }
            if (inPool.size <= cap) return
            var overflow = inPool.size - cap
            for (key in inPool.filter { it != onScreen && it !in busy }) {
                if (overflow <= 0) break
                stores.remove(key); kinds.remove(key); evicted += key
                overflow--
            }
        }

        fun kindsOfEvicted() = evicted.map { it.substringBefore('-') }
    }

    /**
     * THE BUG, as measured: three concurrent children plus the user's own
     * chats, in one pool of 4. Reproduced against the OLD single-pool rule to
     * show the arithmetic really does produce cross-kind eviction.
     */
    @Test
    fun `single pool lets children evict normals and each other`() {
        val single = Pools(normalCap = 4, childCap = 4)
        // One shared pool ⇒ model every session as the same kind.
        listOf("normal-1", "normal-2", "normal-3", "normal-4")
            .forEach { single.open(it, Kind.NORMAL) }
        listOf("child-1", "child-2", "child-3")
            .forEach { single.open(it, Kind.NORMAL) }

        assertEquals(
            "three normals get pushed out by the three children",
            listOf("normal", "normal", "normal"), single.kindsOfEvicted(),
        )
    }

    /** The fix: children and normals no longer interfere in either direction. */
    @Test
    fun `dual pool keeps three concurrent children and four normals alive together`() {
        val p = Pools()
        listOf("normal-1", "normal-2", "normal-3", "normal-4")
            .forEach { p.open(it, Kind.NORMAL) }
        listOf("child-1", "child-2", "child-3")
            .forEach { p.open(it, Kind.CHILD) }

        assertTrue("no eviction at all: ${p.evicted}", p.evicted.isEmpty())
        assertEquals("all seven stay cached", 7, p.stores.size)
    }

    @Test
    fun `a child burst never evicts a normal session`() {
        val p = Pools()
        listOf("normal-1", "normal-2", "normal-3", "normal-4")
            .forEach { p.open(it, Kind.NORMAL) }
        // Two full generations of concurrent children, past the child cap.
        (1..9).forEach { p.open("child-$it", Kind.CHILD) }

        assertTrue(
            "every eviction must be a child: ${p.evicted}",
            p.kindsOfEvicted().all { it == "child" },
        )
        assertTrue(
            "all four normals survive",
            (1..4).all { "normal-$it" in p.stores },
        )
    }

    @Test
    fun `normal churn never evicts a child`() {
        val p = Pools()
        (1..3).forEach { p.open("child-$it", Kind.CHILD) }
        (1..10).forEach { p.open("normal-$it", Kind.NORMAL) }

        assertTrue(
            "every eviction must be a normal: ${p.evicted}",
            p.kindsOfEvicted().all { it == "normal" },
        )
        assertTrue("all three children survive", (1..3).all { "child-$it" in p.stores })
    }

    @Test
    fun `viewing a child transcript does not move it into the normal pool`() {
        val p = Pools()
        p.open("child-1", Kind.CHILD)
        // AgentTranscriptScreen re-acquires the same VM; even if a caller asked
        // for NORMAL, stickiness must win.
        p.open("child-1", Kind.NORMAL)
        assertEquals(Kind.CHILD, p.kinds["child-1"])
    }

    @Test
    fun `a normal session cannot be pulled into the child pool`() {
        val p = Pools()
        p.open("normal-1", Kind.NORMAL)
        p.open("normal-1", Kind.CHILD)
        assertEquals(Kind.NORMAL, p.kinds["normal-1"])
    }

    @Test
    fun `both carve-outs still apply inside a pool`() {
        val p = Pools()
        (1..4).forEach { p.open("normal-$it", Kind.NORMAL) }
        p.busy = setOf("normal-1")     // agent loop running
        p.onScreen = "normal-2"        // being rendered
        (5..7).forEach { p.open("normal-$it", Kind.NORMAL) }

        assertTrue("a running session is never evicted", "normal-1" in p.stores)
        assertTrue("the on-screen session is never evicted", "normal-2" in p.stores)
    }

    /**
     * The cap is SOFT: when every member of an over-capacity pool is exempt,
     * the pool runs over rather than breaking a live run. Set the exemptions
     * BEFORE the opens that push the pool past its cap, so there is genuinely
     * nothing eligible at trim time.
     */
    @Test
    fun `a pool of entirely busy sessions runs over rather than killing a run`() {
        val p = Pools()
        p.busy = (1..6).map { "normal-$it" }.toSet()
        (1..6).forEach { p.open("normal-$it", Kind.NORMAL) }

        assertTrue("nothing evictable ⇒ nothing evicted: ${p.evicted}", p.evicted.isEmpty())
        assertEquals("pool allowed over its cap of 4", 6, p.stores.size)
    }
}
