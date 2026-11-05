package app.momoding.task

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.apps.AppBridge
import app.momoding.apps.AppPage
import app.momoding.apps.AppPermissions
import app.momoding.apps.MoAppActivity
import app.momoding.apps.MoApps
import app.momoding.guard.Grants
import app.momoding.guard.RiskClass
import app.momoding.guard.RiskDecision
import app.momoding.guard.RiskGate
import com.openminis.app.MinisApp
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.NativeOffloadServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Real AppPage shim, JS promises, RiskGate, native dispatch and Room. The calendar executor
 * is replaced only for this owned Bot/token by a private counter; no calendar data is touched. */
class AppBridgeRouteTest {
    @get:Rule val name = TestName()
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private val dao get() = application.database.taskDao()
    private lateinit var owner: String
    private lateinit var hand: String
    private lateinit var work: TaskEntity
    private lateinit var fixture: MoApps.App
    private lateinit var web: WebView
    private lateinit var bridge: AppBridge
    private lateinit var originalHandler: NativeOffloadHandler
    private var originalGuard: ((NativeOffloadRequest) -> String?)? = null
    private lateinit var token: String
    private val counter = AtomicInteger()
    private val reads = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val requests = ConcurrentLinkedQueue<NativeOffloadRequest>()
    private val atDispatch = CountDownLatch(1)
    private val enterExecutor = CountDownLatch(1)
    private val passDispatch = CountDownLatch(1)
    private val returnExecutor = CountDownLatch(1)
    @Volatile private var holdDispatch = false
    @Volatile private var holdExecutor = false
    private var passed = false
    private val command get() = "android-calendar delete --id $token"

    @Before fun setup(): Unit = runBlocking {
        val groupId = InstrumentationRegistry.getArguments().getString("m41_bridge_group"); assumeTrue(!groupId.isNullOrBlank())
        withTimeout(15_000) { while (!application.subsystemsReady()) delay(25); application.providerRepository.awaitConfigLoaded() }; TaskEngine.awaitRecovery()
        val config = application.providerRepository.config.value
        val group = config.modelGroups.single { it.id == groupId }; require(group.memberEntryIds.isNotEmpty())
        group.memberEntryIds.forEach { id ->
            val entry = config.modelEntries.single { it.id == id }
            require(config.instances.single { it.id == entry.providerInstanceId }.customBaseURL?.startsWith("http://127.0.0.1:") == true)
        }
        owner = application.chatRepository.createSession("m41-appbridge", "M41 bridge owner").id
        hand = application.chatRepository.createSession("m41-appbridge", "M41 bridge Bot").id
        for (sid in listOf(owner, hand)) application.chatRepository.updateSessionBinding(sid,
            JSONObject().put("type", "group").put("groupId", groupId).toString(), "m41-appbridge")
        token = "m41-bridge-" + UUID.randomUUID()
        fixture = MoApps.create(application, "m41-bridge-" + owner.take(8), "M41 owned bridge").also { it.setBot(hand) }
        AppPermissions.set(fixture, "android-calendar", true)
        val source = application.chatRepository.appendMessage(owner, "user", """[{"type":"text","value":"M41 owned bridge work"}]""")
        work = TaskEntity(UUID.randomUUID().toString(), owner, source.id, "M41 bridge original", hand, "RUNNING", createdAt = 1, updatedAt = 1)
        dao.insertTask(work)
        // Save/restore the existing executor; other sessions still use it. The guard is never bypassed.
        val field = NativeOffloadServer::class.java.getDeclaredField("handlers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val handlers = field.get(null) as Map<String, NativeOffloadHandler>
        originalHandler = requireNotNull(handlers["android-calendar"])
        originalGuard = requireNotNull(NativeOffloadServer.taskGuard)
        NativeOffloadServer.register("android-calendar", NativeOffloadHandler { request ->
            if (request.sessionId != hand) originalHandler.handle(request) else {
                require(request.argv == listOf("android-calendar", request.argv[1], "--id", token) && request.argv[1] in setOf("delete", "get"))
                inFlight.incrementAndGet()
                try {
                    requests.add(request)
                    if (request.argv[1] == "delete") File(fixture.platform, "owned-counter").writeText(counter.incrementAndGet().toString()) else reads.incrementAndGet()
                    enterExecutor.countDown()
                    check(!holdExecutor || returnExecutor.await(15, TimeUnit.SECONDS))
                    NativeOffloadResult(0, JSONObject().put("counter", counter.get()).put("reads", reads.get()).put("owned", token).toString())
                } finally { inFlight.decrementAndGet() }
            }
        })
        NativeOffloadServer.taskGuard = { request ->
            if (request.sessionId == hand && holdDispatch) { atDispatch.countDown(); check(passDispatch.await(15, TimeUnit.SECONDS)) }
            originalGuard?.invoke(request)
        }
        fixture.index.writeText("""<!doctype html><html><head><title>M41 bridge</title></head><body><script>
          window.m41 = {phase:'ready'};
          window.runOwned = async (command = ${JSONObject.quote(command)}) => {
            window.m41 = {phase:'pending'};
            try { const value = await momoding.run(command); window.m41 = {phase:'done',value}; }
            catch(e) { window.m41 = {phase:'failed',error:String(e.message||e)}; }
          };
        </script></body></html>""")
        withContext(Dispatchers.Main) {
            val page = AppPage.unattended(application, fixture); web = page.first; bridge = page.second
            web.loadUrl(MoAppActivity.ORIGIN + "index.html")
        }
        await { js("typeof runOwned === 'function' && typeof momoding.run === 'function'") == "true" }
    }

    @After fun close(): Unit = runBlocking {
        if (!::owner.isInitialized) return@runBlocking
        passDispatch.countDown(); returnExecutor.countDown()
        if (::bridge.isInitialized) bridge.close()
        await { inFlight.get() == 0 }
        if (::web.isInitialized) withContext(Dispatchers.Main) { web.destroy() }
        val proof = File(application.filesDir, "m41-appbridge-proof.json")
        val all = if (proof.isFile) JSONArray(proof.readText()) else JSONArray()
        val tasks = TaskEngine.dump(application, 100).getJSONArray("tasks")
        val own = JSONArray(); for (i in 0 until tasks.length()) tasks.getJSONObject(i).takeIf { it.optString("conversation") in listOf(owner, hand) }?.let(own::put)
        all.put(JSONObject().put("test", name.methodName).put("passed", passed).put("owner", owner).put("hand", hand)
            .put("work", if (::work.isInitialized) work.id else "").put("counter", counter.get()).put("reads", reads.get()).put("tasks", own)
            .put("requests", JSONArray(requests.map { JSONObject().put("argv", JSONArray(it.argv)).put("session", it.sessionId).put("process", it.env["MINIS_EXECUTION_ID"]) })))
        proof.writeText(all.toString(2))
        if (::originalHandler.isInitialized) NativeOffloadServer.register("android-calendar", originalHandler)
        originalGuard?.let { NativeOffloadServer.taskGuard = it }
        Grants.clearSession(hand)
        SessionDeleter.deleteTree(application, application.chatRepository, owner, "owned bridge fixture cleanup")
        if (::hand.isInitialized) SessionDeleter.deleteTree(application, application.chatRepository, hand, "owned bridge fixture cleanup")
        if (::fixture.isInitialized) MoApps.remove(fixture)
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) { suspendCancellableCoroutine { c ->
        web.evaluateJavascript(script) { if (c.isActive) c.resume(it) }
    } }
    private suspend fun state(): JSONObject = (runCatching { JSONTokener(js("JSON.stringify(window.m41)")).nextValue() as? String }.getOrNull())?.let(::JSONObject) ?: JSONObject()
    private suspend fun await(check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(25) }
    private suspend fun start(command: String = this.command) { js("runOwned(${JSONObject.quote(command)}); 'started'") }
    private suspend fun ended(): JSONObject { await { state().optString("phase") in setOf("done", "failed") }; return state() }
    private fun grant() = Grants.grantSession(RiskClass.DESTRUCTIVE, hand, "M41 owned calendar executor", "calendar")
    private suspend fun nextTask(): TaskEntity {
        val source = application.chatRepository.appendMessage(owner, "user", """[{"type":"text","value":"M41 unrelated new bridge work"}]""")
        val now = System.currentTimeMillis()
        return work.copy(id = UUID.randomUUID().toString(), sourceMessageId = source.id, title = "M41 bridge next", createdAt = now, updatedAt = now).also { dao.insertTask(it) }
    }
    private suspend fun screenPermission() {
        assertTrue(File(fixture.vault, fixture.name + ".permissions.json").delete())
        js("window.runOwned = undefined")
        withContext(Dispatchers.Main) {
            bridge.close(); web.removeJavascriptInterface(AppBridge.JS_NAME)
            bridge = AppBridge(application, web, AppBridge.Mode.SCREEN)
            web.addJavascriptInterface(bridge, AppBridge.JS_NAME); web.reload()
        }
        await { js("typeof runOwned === 'function'") == "true" }
    }
    private suspend fun pendingPermission(): AppPermissions.Request {
        await { AppPermissions.pending.value?.app?.name == fixture.name }
        return requireNotNull(AppPermissions.pending.value)
    }
    private suspend fun history() = dao.log(work.id)
    private suspend fun assertKnown(success: Boolean) {
        val log = history(); val about = log.single { it.kind == "ABOUT_TO" }; val result = JSONObject(log.single { it.kind == "RESULT" }.body)
        assertEquals(JSONObject(about.body).getString("id"), result.getString("id")); assertFalse(result.optBoolean("unknown")); assertEquals(success, result.getBoolean("success"))
        assertEquals(1, log.count { it.kind == "COMMAND_RESULT" })
    }

    @Test fun rememberedPermissionRunsOnceAndResolvesTheRealJsPromise(): Unit = runBlocking {
        grant(); start(); val result = ended(); assertEquals("done", result.getString("phase")); assertEquals(0, result.getJSONObject("value").getInt("code"))
        assertEquals(1, counter.get()); assertEquals(hand, requests.single().sessionId); assertTrue(requests.single().env["MINIS_EXECUTION_ID"].orEmpty().isNotBlank()); assertKnown(true); passed = true
    }
    @Test fun anAppPermissionDenialRunsNothing(): Unit = runBlocking {
        AppPermissions.set(fixture, "android-calendar", false); grant(); start(); assertEquals("failed", ended().getString("phase"))
        assertEquals(0, counter.get()); assertTrue(history().isEmpty()); passed = true
    }
    @Test fun aHoldBlocksEvenARememberedPermission(): Unit = runBlocking {
        dao.updateTask(work.copy(hold = "wait for the person")); grant(); start(); assertEquals("failed", ended().getString("phase"))
        assertEquals(0, counter.get()); assertTrue(history().isEmpty()); passed = true
    }
    @Test fun aQuestionBlocksEvenARememberedPermission(): Unit = runBlocking {
        dao.updateTask(work.copy(state = "WAITING", ask = """{"question":"which one?"}""")); grant(); start(); assertEquals("failed", ended().getString("phase"))
        assertEquals(0, counter.get()); assertTrue(history().isEmpty()); passed = true
    }
    @Test fun thePageStaysUsableWhileItsExactApprovalWaits(): Unit = runBlocking {
        start(); await { Approval.pending(dao.task(work.id)?.ask).isNotEmpty() }
        val spec = Approval.pending(dao.task(work.id)?.ask).single(); assertEquals(command, spec.request.preview); assertEquals(work.id, spec.request.taskId)
        assertEquals("pending", state().getString("phase")); assertEquals(0, counter.get())
        assertEquals("true", js("momoding.store.set('whileWaiting', true); momoding.store.get('whileWaiting')"))
        RiskGate.decide(spec.request.id, RiskDecision.ALLOW_ONCE).join()
        assertEquals("done", ended().getString("phase")); assertEquals(1, counter.get()); assertKnown(true)
        assertEquals(1, history().count { it.kind == "APPROVAL_USED" }); passed = true
    }
    @Test fun stoppingAnApprovalRejectsItsOriginalPromise(): Unit = runBlocking {
        start(); await { Approval.pending(dao.task(work.id)?.ask).isNotEmpty() }; TaskEngine.stop(application, work.id)
        assertEquals("failed", ended().getString("phase")); assertEquals(0, counter.get()); assertTrue(history().none { it.kind == "ABOUT_TO" }); passed = true
    }
    @Test fun anOldDispatchCannotBorrowTheNextTaskOnTheSameBot(): Unit = runBlocking {
        grant(); holdDispatch = true; start(); await { atDispatch.count == 0L }; TaskEngine.stop(application, work.id); val next = nextTask(); passDispatch.countDown()
        assertEquals(125, ended().getJSONObject("value").getInt("code")); assertEquals(0, counter.get()); assertKnown(false)
        assertTrue(dao.log(next.id).isEmpty()); assertEquals("STOPPED", dao.task(work.id)?.outcome); passed = true
    }
    @Test fun aLateKnownReturnStaysOnItsStoppedOriginalTask(): Unit = runBlocking {
        grant(); holdExecutor = true; start(); await { enterExecutor.count == 0L }; TaskEngine.stop(application, work.id); val next = nextTask(); returnExecutor.countDown()
        assertEquals(0, ended().getJSONObject("value").getInt("code")); assertEquals(1, counter.get()); assertKnown(true)
        assertEquals("STOPPED", dao.task(work.id)?.outcome); assertEquals(1, history().count { it.kind == "END" })
        assertEquals(1, history().count { it.kind == "REPORT" && it.body.contains("A late command return") }); assertTrue(dao.log(next.id).isEmpty()); passed = true
    }
    @Test fun closingThePageDoesNotLoseAKnownNativeReturn(): Unit = runBlocking {
        grant(); holdExecutor = true; start(); await { enterExecutor.count == 0L }; bridge.close(); returnExecutor.countDown()
        await { history().any { it.kind == "RESULT" } }; assertKnown(true); assertEquals(1, counter.get()); passed = true
    }
    @Test fun evenAReadKeepsTheTaskThatWasCheckedBeforeDispatch(): Unit = runBlocking {
        holdDispatch = true; start("android-calendar get --id $token"); await { atDispatch.count == 0L }
        TaskEngine.stop(application, work.id); val next = nextTask(); passDispatch.countDown()
        assertEquals(125, ended().getJSONObject("value").getInt("code")); assertEquals(0, reads.get()); assertTrue(dao.log(next.id).isEmpty()); passed = true
    }
    @Test fun anAppPermissionWaitDoesNotTransferTheOldActionToTheNextTask(): Unit = runBlocking {
        grant(); screenPermission(); start(); val request = pendingPermission()
        TaskEngine.stop(application, work.id); val next = nextTask(); request.answer(true)
        assertEquals("failed", ended().getString("phase")); assertEquals(0, counter.get()); assertTrue(dao.log(next.id).isEmpty()); passed = true
    }
    @Test fun anInitiallyUnboundPageActionDoesNotBorrowAFutureBotTask(): Unit = runBlocking {
        TaskEngine.stop(application, work.id); grant(); screenPermission(); start(); val request = pendingPermission()
        val next = nextTask(); request.answer(true)
        assertEquals(0, ended().getJSONObject("value").getInt("code")); assertEquals(1, counter.get()); assertTrue(dao.log(next.id).isEmpty())
        val own = dao.relatedTo(listOf(hand)).filter { it.conversation == hand }; assertEquals(1, own.size)
        assertEquals("DONE", own.single().outcome); assertEquals(1, dao.log(own.single().id).count { it.kind == "RESULT" }); passed = true
    }
}
