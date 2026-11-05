package com.openminis.app.browser

import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.task.Helpers
import app.momoding.task.TaskCompletion
import app.momoding.task.TaskEngine
import app.momoding.task.TaskEntity
import com.openminis.app.MinisApp
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File
import java.util.UUID

/** Real idle VMs, native browser CLI, WebViews and task closeout. Only local model callbacks. */
class HelperBrowserLifecycleTest {
    @get:Rule val name = TestName()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private val dao get() = app.database.taskDao()
    private lateinit var parent: String
    private lateinit var group: String
    private val pools = mutableListOf<BrowserTabPool>()
    private val checks = JSONObject()
    private var passed = false

    @Before fun setup(): Unit = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("m41_browser_group"); assumeTrue(!id.isNullOrBlank())
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        group = requireNotNull(id)
        val config = app.providerRepository.config.value
        val members = config.modelGroups.single { it.id == group }.memberEntryIds; require(members.isNotEmpty())
        members.forEach { entry ->
            val provider = config.modelEntries.single { it.id == entry }.providerInstanceId
            require(config.instances.single { it.id == provider }.customBaseURL?.startsWith("http://127.0.0.1:") == true)
        }
        parent = session("M41 browser lifecycle owner")
    }

    private suspend fun session(title: String, owner: String? = null): String {
        val sid = app.chatRepository.createSession("m41-browser-lifecycle", title, parentSessionId = owner).id
        app.chatRepository.updateSessionBinding(sid, JSONObject().put("type", "group").put("groupId", group).toString(), "m41-browser-lifecycle")
        return sid
    }
    private suspend fun parentVm() = withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(app, parent) }
    private suspend fun pool(vm: ChatViewModel) = withContext(Dispatchers.Main) { vm.browserTabPool.also { if (it !in pools) pools.add(it) } }
    private suspend fun helper(state: String = "WAITING"): Pair<TaskEntity, ChatViewModel> {
        val hand = session("M41 browser lifecycle helper", parent)
        val source = app.chatRepository.appendMessage(parent, "user", """[{"type":"text","value":"M41 owned browser work"}]""")
        val task = TaskEntity(UUID.randomUUID().toString(), parent, source.id, "M41 owned browser", hand, state,
            ask = if (state == "WAITING") """{"question":"M41 waiting for the person"}""" else null, createdAt = 1, updatedAt = 1)
        dao.insertTask(task)
        return task to Helpers.ready(app, hand, task).also { assertFalse(it.turnIsRunning) }
    }
    private fun tab(sid: String): Int {
        val result = BrowserUseOffloadHandler(app).handle(NativeOffloadRequest(0,
            listOf("minis-browser-use", "--compact", "new_tab"), emptyMap(), "/", sid))
        assertEquals(result.output, 0, result.exitCode)
        return Regex("Opened new tab (\\d+)").find(JSONObject(result.output).getJSONObject("data").getString("text"))!!.groupValues[1].toInt()
    }
    private fun ids(pool: BrowserTabPool) = pool.tabs.value.map { it.id }.toSet()
    private fun snapshot(key: String, pool: BrowserTabPool) { checks.put(key, JSONArray(ids(pool).sorted())) }
    private suspend fun finish(task: TaskEntity) = TaskEngine.turnFinished(app, task.hand, task.id, TaskCompletion(true, "M41 owned browser done"), false)

    @After fun close(): Unit = runBlocking {
        if (!::parent.isInitialized) return@runBlocking
        val proof = File(app.filesDir, "m41-helper-browser-proof.json")
        val all = if (proof.isFile) JSONArray(proof.readText()) else JSONArray()
        all.put(JSONObject().put("test", name.methodName).put("passed", passed).put("checks", checks)
            .put("tasks", TaskEngine.dump(app, 100).getJSONArray("tasks")))
        proof.writeText(all.toString(2))
        withContext(Dispatchers.Main) { pools.forEach { it.destroyAllTabs() } }
        SessionDeleter.deleteTree(app, app.chatRepository, parent, "owned browser lifecycle cleanup")
    }

    @Test fun stoppingAnIdleWaitingHelperReleasesOnlyItsSharedTabs(): Unit = runBlocking {
        val pool = pool(parentVm()); val parentTab = tab(parent)
        val (task, vm) = helper(); val firstTab = tab(task.hand)
        val (sibling, _) = helper(); val secondTab = tab(sibling.hand)
        assertSame(pool, vm.browserTabPool); checks.put("parent", parentTab).put("first", firstTab).put("second", secondTab)
        TaskEngine.stop(app, task.id); snapshot("afterStop", pool)
        assertEquals("STOPPED", dao.task(task.id)?.outcome); assertFalse(vm.turnIsRunning)
        assertEquals(setOf(parentTab, secondTab), ids(pool)); assertEquals("WAITING", dao.task(sibling.id)?.state)
        TaskEngine.stop(app, task.id); Helpers.ended(app, task.hand, parent)
        assertEquals(setOf(parentTab, secondTab), ids(pool)); passed = true
    }

    @Test fun stoppingAPrivateHelperPoolDoesNotOpenTheAbsentParentBrowser(): Unit = runBlocking {
        assertNull(ChatViewModelStore.existing(parent)); val (task, vm) = helper(); val pool = pool(vm); tab(task.hand)
        TaskEngine.stop(app, task.id); snapshot("afterStop", pool)
        assertEquals("STOPPED", dao.task(task.id)?.outcome); assertTrue(ids(pool).isEmpty()); passed = true
    }

    @Test fun completingAHelperClosesItsPrivatePool(): Unit = runBlocking {
        val (task, vm) = helper("RUNNING"); val pool = pool(vm); tab(task.hand)
        finish(task); snapshot("afterDone", pool)
        assertEquals("DONE", dao.task(task.id)?.outcome); assertTrue(ids(pool).isEmpty()); passed = true
    }

    @Test fun completingAHelperReleasesTheOriginalPoolAfterTheParentVmChanges(): Unit = runBlocking {
        val old = pool(parentVm()); val parentTab = tab(parent)
        val (task, _) = helper("RUNNING"); val helperTab = tab(task.hand)
        withContext(Dispatchers.Main) { ChatViewModelStore.release(parent) }
        val fresh = pool(parentVm()); assertNotSame(old, fresh); val freshTab = tab(parent)
        checks.put("parent", parentTab).put("helper", helperTab).put("fresh", freshTab)
        finish(task); snapshot("oldAfterDone", old); snapshot("freshAfterDone", fresh)
        assertEquals(setOf(parentTab), ids(old)); assertEquals(setOf(freshTab), ids(fresh)); passed = true
    }

    @Test fun endingANonBrowsingHelperDoesNotCreateABrowserPool(): Unit = runBlocking {
        val (task, vm) = helper(); val parentVm = parentVm()
        val field = ChatViewModel::class.java.getDeclaredField("_browserTabPoolRef").apply { isAccessible = true }
        assertNull(field.get(parentVm)); assertNull(field.get(vm))
        Helpers.ended(app, task.hand, parent)
        assertNull(field.get(parentVm)); assertNull(field.get(vm)); passed = true
    }

    @Test fun anEvictedHelperStillReturnsItsTabsToTheExistingParentPool(): Unit = runBlocking {
        val pool = pool(parentVm()); val parentTab = tab(parent)
        val (task, _) = helper("RUNNING"); tab(task.hand)
        withContext(Dispatchers.Main) { ChatViewModelStore.release(task.hand) }
        finish(task); snapshot("afterDone", pool)
        assertEquals(setOf(parentTab), ids(pool)); passed = true
    }

    @Test fun evictingAPrivateHelperVmReturnsItsBrowserTabs(): Unit = runBlocking {
        val (task, vm) = helper(); val pool = pool(vm); tab(task.hand)
        withContext(Dispatchers.Main) { ChatViewModelStore.release(task.hand) }
        // Eviction loses the only VM reference to this pool; a later stop cannot find it.
        withTimeout(3_000) { while (ids(pool).isNotEmpty()) delay(25) }
        snapshot("afterEviction", pool); TaskEngine.stop(app, task.id)
        assertEquals("STOPPED", dao.task(task.id)?.outcome); assertTrue(ids(pool).isEmpty()); passed = true
    }

    @Test fun stoppingANonHelperKeepsItsOwnBrowser(): Unit = runBlocking {
        val pool = pool(parentVm()); val parentTab = tab(parent)
        val task = TaskEntity(UUID.randomUUID().toString(), parent, title = "M41 chat work", hand = parent,
            state = "WAITING", ask = """{"question":"M41 wait"}""", createdAt = 1, updatedAt = 1)
        dao.insertTask(task); TaskEngine.stop(app, task.id); snapshot("afterStop", pool)
        assertEquals("STOPPED", dao.task(task.id)?.outcome); assertEquals(setOf(parentTab), ids(pool)); passed = true
    }
}
