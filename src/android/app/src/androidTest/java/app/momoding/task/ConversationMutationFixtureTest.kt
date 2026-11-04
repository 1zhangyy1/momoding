package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.openminis.app.ui.chat.QueuedPromptOrigin
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.addAttachment
import android.net.Uri
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public ViewModel fixture, driven by an owned session and a local model. */
class ConversationMutationFixtureTest {
    @Test fun mutateTheOwnedConversation(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val session = args.getString("m41_conversation_session")
        assumeTrue(!session.isNullOrBlank())
        val mode = args.getString("m41_conversation_mode")
        require(mode in listOf("edit", "edit_queued", "attachment", "headless", "capture", "inspect", "delete"))
        val source = requireNotNull(args.getString("m41_conversation_source"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        val vm = withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(context, requireNotNull(session)) }
        withTimeout(15_000) { while (vm.messages.value.none { it.id == source }) delay(30) }
        if (mode == "inspect") {
            val corrected = withTimeout(10_000) {
                while (vm.messages.value.none { it.correction != null }) delay(30)
                vm.messages.value.last { it.correction != null }
            }
            val correction = corrected.correction!!
            assertEquals("M41_EDIT_REPLACEMENT", correction.replacement)
            assertNotNull(app.database.chatDao().message(correction.source.substringBefore('#')))
            assertEquals(correction.replacement, withContext(Dispatchers.Main) { vm.editMessage(corrected.id) })
            assertEquals(corrected.imageUris.size + corrected.attachmentUris.size, vm.attachments.value.size)
            withContext(Dispatchers.Main) { vm.cancelEdit() }
            assertTrue(vm.attachments.value.isEmpty())
            return@runBlocking
        }
        var editedSource = source
        if (mode == "attachment") {
            val file = File(context.filesDir, "m41-conversation-owned-image.png")
            try {
                val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                withContext(Dispatchers.Main) {
                    vm.addAttachment(InputAttachment(fileName = "fixture.png", uri = Uri.fromFile(file), mimeType = "image/png", kind = InputAttachment.Kind.IMAGE))
                    vm.sendMessage("M41_IMAGE_ORIGINAL")
                }
                withTimeout(15_000) {
                    while (vm.messages.value.none { it.content == "M41_IMAGE_ORIGINAL" }) delay(30)
                    while (vm.isStreaming.value) delay(30)
                }
                editedSource = vm.messages.value.single { it.content == "M41_IMAGE_ORIGINAL" }.id
                assertEquals(1, vm.messages.value.single { it.id == editedSource }.imageUris.size)
            } finally { file.delete() }
        }
        val before = app.database.chatDao().loadMessages(requireNotNull(session))
        require(before.any { it.id == editedSource })
        if (mode == "capture") {
            val second = vm.messages.value.single { it.content == "M41_LATER_MESSAGE" }.id
            val third = vm.messages.value.single { it.content == "M41_THIRD_SOURCE" }.id
            withContext(Dispatchers.Main) {
                vm.editMessage(source); vm.sendMessage("M41_CAPTURE_FIRST")
                vm.editMessage(second); vm.sendMessage("M41_EDIT_REPLACEMENT")
                vm.editMessage(third); vm.setInputText("M41_UNSENT_THIRD_DRAFT")
            }
            withTimeout(15_000) {
                while (vm.messages.value.none { it.correction?.replacement == "M41_EDIT_REPLACEMENT" }) delay(30)
            }
            val correction = vm.messages.value.last { it.correction?.replacement == "M41_EDIT_REPLACEMENT" }.correction!!
            assertEquals("The queued send must keep the source selected at its own click", second, correction.source)
            assertEquals(third, vm.editingMessageId.value)
            assertEquals("M41_UNSENT_THIRD_DRAFT", vm.inputText.value)
            return@runBlocking
        }
        if (mode != "delete") {
            if (mode == "edit_queued") withTimeout(15_000) { while (!vm.isStreaming.value) delay(30) }
            val prefill = withContext(Dispatchers.Main) { vm.editMessage(editedSource) }
            assertEquals(if (mode == "attachment") "M41_IMAGE_ORIGINAL" else "M41_EDIT_ORIGINAL", prefill)
            if (mode == "attachment") assertEquals(1, vm.attachments.value.size)
            if (mode == "headless") {
                withContext(Dispatchers.Main) { vm.submitPrompt("M41_AUTO_WHILE_EDIT", origin = QueuedPromptOrigin.PROGRAMMATIC) }
                withTimeout(15_000) { while (vm.isStreaming.value) delay(30) }
                assertEquals(editedSource, vm.editingMessageId.value)
                assertTrue(app.database.chatDao().loadMessages(session).map { it.id }.containsAll(before.map { it.id }))
            }
            withContext(Dispatchers.Main) { vm.sendMessage("M41_EDIT_REPLACEMENT") }
            withTimeout(15_000) {
                while (app.database.chatDao().loadMessages(session).none { it.partsJson.contains("M41_EDIT_REPLACEMENT") } &&
                    app.database.taskDao().inboxFor(session).none { it.text.contains("M41_EDIT_REPLACEMENT") }) delay(30)
                while (vm.messages.value.none { it.correction?.source == editedSource && it.correction?.replacement == "M41_EDIT_REPLACEMENT" }) delay(30)
                if (mode != "edit_queued") {
                    while (app.database.chatDao().loadMessages(session).none { it.role == "assistant" && it.partsJson.contains("M41_CORRECTION_ACK") }) delay(30)
                    while (vm.isStreaming.value) delay(30)
                }
            }
            val after = app.database.chatDao().loadMessages(session)
            assertTrue("Editing must keep the original message and every later row", after.map { it.id }.containsAll(before.map { it.id }))
            val oldIds = before.map { it.id }.toSet()
            assertEquals(before.map { it.id to it.partsJson }, after.filter { it.id in oldIds }.map { it.id to it.partsJson })
            if (mode != "edit_queued") {
                val corrected = vm.messages.value.last { it.correction?.source == editedSource }
                assertEquals(editedSource, corrected.correction!!.source)
                if (mode == "attachment") assertEquals(1, corrected.imageUris.size)
            }
        } else {
            val task = requireNotNull(args.getString("m41_conversation_task"))
            withContext(Dispatchers.Main) {
                vm.editMessage(source); vm.setInputText("M41_PENDING_EDIT")
                vm.deleteFromMessage(source)
            }
            withTimeout(10_000) { while (app.database.chatDao().message(source) != null) delay(30) }
            assertEquals("STOPPED", app.database.taskDao().task(task)!!.outcome)
            withTimeout(10_000) { while (vm.editingMessageId.value != null) delay(30) }
            assertTrue(vm.inputText.value.isEmpty()); assertTrue(vm.attachments.value.isEmpty())
        }
    }
}
