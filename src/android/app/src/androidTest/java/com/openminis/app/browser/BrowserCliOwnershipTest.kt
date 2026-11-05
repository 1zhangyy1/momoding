package com.openminis.app.browser

import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.task.Helpers
import app.momoding.task.TaskEngine
import app.momoding.task.TaskEntity
import com.openminis.app.MinisApp
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real WebViews and the CLI's native handler; only owned blank pages, no model or network. */
class BrowserCliOwnershipTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private lateinit var parent: String
    private lateinit var first: String
    private lateinit var second: String
    private lateinit var pool: BrowserTabPool
    private var parentTab = -1
    private var firstTab = -1
    private var secondTab = -1

    @Before fun setup(): Unit = runBlocking {
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }
        TaskEngine.awaitRecovery()
        parent = app.chatRepository.createSession("fixture", "M41 browser owner").id
        first = app.chatRepository.createSession("fixture", "M41 browser first", parentSessionId = parent).id
        second = app.chatRepository.createSession("fixture", "M41 browser second", parentSessionId = parent).id
        pool = withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(app, parent).browserTabPool }
        for (hand in listOf(first, second)) {
            val vm = Helpers.ready(app, hand, TaskEntity("fixture-$hand", parent, title = "owned browser", hand = hand,
                state = "RUNNING", createdAt = 1, updatedAt = 1))
            assertSame(pool, vm.browserTabPool)
        }
        parentTab = newTab(parent); js(parent, "window.m41Owner = 'parent'; return window.m41Owner;")
        firstTab = newTab(first); js(first, "window.m41Owner = 'first'; return window.m41Owner;")
        secondTab = newTab(second); js(second, "window.m41Owner = 'second'; return window.m41Owner;")
    }

    @After fun close(): Unit = runBlocking {
        if (::pool.isInitialized) withContext(Dispatchers.Main) { pool.destroyAllTabs() }
        if (::parent.isInitialized) SessionDeleter.deleteTree(app, app.chatRepository, parent, "owned browser test cleanup")
    }

    private fun run(sid: String, vararg args: String) = BrowserUseOffloadHandler(app)
        .handle(NativeOffloadRequest(0, listOf("minis-browser-use", "--compact", *args), emptyMap(), "/", sid))
    private fun text(sid: String, vararg args: String): String {
        val r = run(sid, *args); assertEquals(r.output, 0, r.exitCode)
        return JSONObject(r.output).getJSONObject("data").getString("text")
    }
    private fun newTab(sid: String) = Regex("Opened new tab (\\d+)").find(text(sid, "new_tab"))!!.groupValues[1].toInt()
    private fun pageText(text: String) = text.substringBefore("\n  tab_id:")
    private fun js(sid: String, code: String) = pageText(text(sid, "execute_js", "--script", code))
    private fun ids(sid: String) = Regex("Tab (\\d+):").findAll(text(sid, "list_tabs")).map { it.groupValues[1].toInt() }.toSet()

    @Test fun implicitReadsStayOnEachHelpersOwnPage() {
        assertEquals("first", js(first, "return window.m41Owner;"))
        assertEquals("second", js(second, "return window.m41Owner;"))
        assertEquals("parent", js(parent, "return window.m41Owner;"))
    }

    @Test fun explicitSiblingAndMainTabsCannotBeReadChangedOrClosed() {
        for (tab in listOf(parentTab, secondTab)) for (args in listOf(
            listOf("get_text"), listOf("execute_js", "--script", "window.m41Owner = 'wrong'; return 'wrong';"), listOf("close_tab"))) {
            val r = run(first, *args.toTypedArray(), "--tab-id", tab.toString())
            assertEquals(r.output, 1, r.exitCode); assertTrue(r.output.contains("belongs to another"))
        }
        assertEquals("parent", js(parent, "return window.m41Owner;"))
        assertEquals("second", js(second, "return window.m41Owner;"))
    }

    @Test fun listingAndImplicitCloseOnlyUseTheCallingHelpersTabs() {
        assertEquals(setOf(firstTab), ids(first)); assertEquals(setOf(secondTab), ids(second))
        text(first, "close_tab")
        assertTrue(ids(first).isEmpty()); assertEquals(setOf(parentTab, secondTab), ids(parent))
    }

    @Test fun endingAHelperReleasesOnlyItsCliTabs(): Unit = runBlocking {
        Helpers.ended(app, first, parent)
        assertEquals(setOf(parentTab, secondTab), pool.tabs.value.map { it.id }.toSet())
        assertEquals("second", js(second, "return window.m41Owner;"))
        assertTrue(ids(first).isEmpty())
    }

    @Test fun theMainConversationRetainsAccessToAllItsTabs() {
        assertEquals(setOf(parentTab, firstTab, secondTab), ids(parent))
        assertEquals("second", pageText(text(parent, "execute_js", "--tab-id", secondTab.toString(), "--script", "return window.m41Owner;")))
    }
}
