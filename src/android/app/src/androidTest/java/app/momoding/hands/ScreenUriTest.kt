package app.momoding.hands

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.task.TaskEngine
import com.openminis.app.MinisApp
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.ui.MinisImageFetcher
import com.openminis.app.ui.chat.ChatLinkAction
import com.openminis.app.ui.chat.ChatLinkResolver
import com.openminis.app.ui.chat.resolveMdMediaFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Same relative image name in two real session directories; only owned pixels are read. */
class ScreenUriTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private val roots = mutableListOf<String>()
    private lateinit var receiver: String
    private lateinit var owner: String
    private lateinit var child: String
    private lateinit var ownerImage: File
    private val path = "attachments/hands/owned-uri/step-1.jpg"
    private val uri get() = "minis://$path?session=$owner"

    @Before fun setup(): Unit = runBlocking {
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }; app.providerRepository.awaitConfigLoaded()
        receiver = app.chatRepository.createSession("fixture", "M41 URI receiver").id.also(roots::add)
        owner = app.chatRepository.createSession("fixture", "M41 URI owner").id.also(roots::add)
        child = app.chatRepository.createSession("fixture", "M41 URI helper", parentSessionId = owner).id
        ownerImage = image(owner, path, Color.BLUE); image(receiver, path, Color.RED)
    }
    @After fun close(): Unit = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("m41_uri_keep") != "true")
            for (sid in roots) SessionDeleter.deleteTree(app, app.chatRepository, sid, "owned URI fixture cleanup")
    }
    private fun file(sid: String, path: String) = File(app.filesDir, "minis-sessions/$sid/$path")
    private fun image(sid: String, path: String, color: Int): File = file(sid, path).apply {
        parentFile!!.mkdirs(); val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color); outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
    }
    private fun shown(url: String) = MinisImageFetcher.resolve(app, url, MinisImageFetcher.sessionOf(url, receiver))
    private fun read(url: String) = ReadImageTool.execute(JSONObject().put("path", url).put("tool_title", "owned screenshot").toString(), receiver, app)

    @Test fun inlineImageUsesTheUriOwnerDespiteTheReceivingConversation() {
        assertEquals(ownerImage.canonicalFile, shown(uri)?.canonicalFile)
        assertEquals(ownerImage.canonicalFile, resolveMdMediaFile(app, uri, receiver)?.canonicalFile)
    }
    @Test fun readImageUsesTheSameOwnerAsTheInlineImage() {
        val result = read(uri); assertTrue(result.output, result.success); assertEquals(ownerImage.absolutePath, result.imageFilePath)
    }
    @Test fun openingTheScreenshotUsesTheSameFile() {
        val result = ChatLinkResolver.resolve(uri, receiver, app) as? ChatLinkAction.SandboxFile
        assertEquals(ownerImage.canonicalFile, result?.item?.file?.canonicalFile)
    }
    @Test fun aMissingExplicitOwnerNeverFallsBackToAnotherConversationsImage() {
        assertTrue(ownerImage.delete()); assertNull(shown(uri))
        assertNull(resolveMdMediaFile(app, uri, receiver))
        assertFalse(read(uri).success); assertFalse(ChatLinkResolver.resolve(uri, receiver, app) is ChatLinkAction.SandboxFile)
    }
    @Test fun anInvalidExplicitOwnerDoesNotBecomeTheReceivingConversationsImage() {
        val bad = "minis://$path?session=.."; assertNull(shown(bad)); assertFalse(read(bad).success)
        assertNull(resolveMdMediaFile(app, bad, receiver))
        assertFalse(ChatLinkResolver.resolve(bad, receiver, app) is ChatLinkAction.SandboxFile)
    }
    @Test fun anOldUnqualifiedImageStillUsesItsReceivingConversation() {
        assertEquals(file(receiver, path).canonicalFile, shown("minis://$path")?.canonicalFile)
        assertEquals(file(receiver, path).absolutePath, read("minis://$path").imageFilePath)
    }

    /** Explicit local-model opt-in: exercise the real screenshot producer, including a helper's parent directory. */
    @Test fun aRealHandsRunKeepsTheImageOwnerWhenItsResultTravelsToAnotherChat(): Unit = runBlocking {
        val entry = InstrumentationRegistry.getArguments().getString("m41_uri_model"); assumeTrue(!entry.isNullOrBlank())
        val model = app.providerRepository.config.value.modelEntries.single { it.id == entry }
        val provider = app.providerRepository.config.value.instances.single { it.id == model.providerInstanceId }
        require(provider.customBaseURL?.startsWith("http://127.0.0.1:") == true)
        File(app.filesDir, "m41-screen-uri-ready").writeText("owned screenshot fixture waiting for its accessibility service")
        withTimeout(15_000) { while (com.openminis.app.accessibility.MinisAccessibilityService.getInstance() == null) delay(50) }
        val report = JSONArray()
        for (actor in listOf(owner, child)) {
            app.startActivity(Intent().setComponent(ComponentName(app.packageName + ".test", "app.momoding.test.ScreenFixtureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(500)
            assertTrue(TaskEngine.claimDiagnosticScreen(app))
            val result = try { HandsOperator(app).run(HandsOperator.Options("Observe the owned fixture and finish without any action.",
                sessionId = actor, mode = HandsPrompt.Mode.VISION, background = false, maxSteps = 1, modelEntryId = entry)) }
                finally { Hands.setActive(false) }
            assertTrue(result.outcome.toString(), result.outcome is HandsOperator.Outcome.Done)
            val last = requireNotNull(result.lastScreen); val rel = "attachments/hands/${result.runId}/step-1.jpg"
            val actual = file(owner, rel); assertTrue(actual.isFile); image(receiver, rel, Color.RED)
            val selected = shown(last)
            report.put(JSONObject().put("actor", actor).put("owner", owner).put("uri", last).put("actual", actual.absolutePath)
                .put("selected", selected?.absolutePath).put("correct", actual.canonicalFile == selected?.canonicalFile))
        }
        app.chatRepository.appendMessage(receiver, "assistant", JSONArray().put(JSONObject().put("type", "text").put("value",
            "M41 captured screen ${receiver.take(8)}\n\n![M41 captured screen ${receiver.take(8)}](${report.getJSONObject(report.length() - 1).getString("uri")})" +
                "\n\n[Open captured screen](${report.getJSONObject(report.length() - 1).getString("uri")})")).toString())
        File(app.filesDir, "m41-screen-uri-proof.json").writeText(JSONObject().put("receiver", receiver).put("owner", owner).put("child", child)
            .put("cases", report).toString(2))
        assertTrue(report.toString(), (0 until report.length()).all { report.getJSONObject(it).getBoolean("correct") })
    }
}
