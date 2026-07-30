package app.momoding.core.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.runtime.local.PiNativeToolRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalMediaToolExecutorTest {
    @Test
    fun `favorite trash and delete require consent then verify live state`() = runTest {
        listOf(
            Triple("set_favorite", "favorite", true),
            Triple("set_trashed", "trashed", true),
            Triple("delete", null, null),
        ).forEachIndexed { index, (action, field, desired) ->
            val handles = MediaHandleRegistry()
            val handle = handles.bind(TASK_ID, MEDIA_ID)
            val gateway = FakeGateway()
            val consent = ApplyingConsent(gateway)
            val executor = executor(handles, gateway, consent)
            val request = request(
                id = "media-$index",
                action = action,
                handle = handle,
                desiredField = field,
                desired = desired,
            )
            val preparation = executor.prepareMutation(TASK_ID, request)
            assertTrue(preparation is MediaMutationPreparation.Ready)
            var dispatched = 0

            val result = executor.executeMutation(
                TASK_ID,
                request,
                (preparation as MediaMutationPreparation.Ready).plan,
            ) { dispatched += 1 }

            assertFalse(result.isError)
            assertEquals(1, dispatched)
            assertEquals(1, consent.requests)
            assertEquals(
                "verified",
                result.contentPayload.getValue("verification").jsonObject
                    .getValue("status").jsonPrimitive.content,
            )
            when (action) {
                "set_favorite" -> assertTrue(gateway.snapshot?.favorite == true)
                "set_trashed" -> assertTrue(gateway.snapshot?.trashed == true)
                "delete" -> assertEquals(null, gateway.snapshot)
            }
        }
    }

    @Test
    fun `no-op avoids system consent while stale and cross-task handles fail closed`() = runTest {
        val handles = MediaHandleRegistry()
        val handle = handles.bind(TASK_ID, MEDIA_ID)
        val gateway = FakeGateway(
            MediaItemSnapshot(MEDIA_ID, "image/jpeg", true, false, 100L, 200L),
        )
        val consent = ApplyingConsent(gateway)
        val executor = executor(handles, gateway, consent)
        val request = request(
            id = "favorite-noop",
            action = "set_favorite",
            handle = handle,
            desiredField = "favorite",
            desired = true,
        )
        val plan = (executor.prepareMutation(TASK_ID, request) as MediaMutationPreparation.Ready).plan
        var dispatched = 0

        val noOp = executor.executeMutation(TASK_ID, request, plan) { dispatched += 1 }
        val crossTask = executor.prepareMutation(OTHER_TASK_ID, request)

        assertFalse(noOp.isError)
        assertEquals(false, noOp.contentPayload.getValue("data").jsonObject
            .getValue("changed").jsonPrimitive.content.toBoolean())
        assertEquals(0, dispatched)
        assertEquals(0, consent.requests)
        assertTrue(crossTask is MediaMutationPreparation.Failed)
        assertEquals(
            "STALE_HANDLE",
            (crossTask as MediaMutationPreparation.Failed).result.contentPayload
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun `declined system consent does not mutate and returns stable error`() = runTest {
        val handles = MediaHandleRegistry()
        val handle = handles.bind(TASK_ID, MEDIA_ID)
        val gateway = FakeGateway()
        val executor = executor(
            handles,
            gateway,
            MediaSystemConsentRequester { _, _, _ -> MediaSystemConsentResult.DENIED },
        )
        val request = request(
            id = "trash-declined",
            action = "set_trashed",
            handle = handle,
            desiredField = "trashed",
            desired = true,
        )
        val plan = (executor.prepareMutation(TASK_ID, request) as MediaMutationPreparation.Ready).plan

        val result = executor.executeMutation(TASK_ID, request, plan) {}

        assertTrue(result.isError)
        assertFalse(gateway.snapshot!!.trashed)
        assertEquals(
            "MEDIA_SYSTEM_CONSENT_DECLINED",
            result.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun `parser rejects unknown fields before querying Android`() = runTest {
        val handles = MediaHandleRegistry()
        val handle = handles.bind(TASK_ID, MEDIA_ID)
        val gateway = FakeGateway()
        val executor = executor(
            handles,
            gateway,
            MediaSystemConsentRequester { _, _, _ -> MediaSystemConsentResult.APPROVED },
        )
        val request = PiNativeToolRequest(
            id = "invalid",
            kind = "android_media_tool",
            toolCallId = "pi-invalid",
            toolName = PhoneLocalMediaToolExecutor.TOOL_NAME,
            arguments = buildJsonObject {
                put("action", "delete")
                put("mediaHandle", handle)
                put("uri", "content://must-not-be-accepted")
            },
        )

        val result = executor.prepareMutation(TASK_ID, request)

        assertTrue(result is MediaMutationPreparation.Failed)
        assertEquals(0, gateway.getCount)
    }

    private fun executor(
        handles: MediaHandleRegistry,
        gateway: FakeGateway,
        consent: MediaSystemConsentRequester,
    ) = PhoneLocalMediaToolExecutor(
        scopeProvider = PhotoLibraryScopeProvider { PhotoLibraryScope.FULL },
        gateway = gateway,
        handles = handles,
        consentRequester = consent,
    )

    private fun request(
        id: String,
        action: String,
        handle: String,
        desiredField: String?,
        desired: Boolean?,
    ) = PiNativeToolRequest(
        id = id,
        kind = "android_media_tool",
        toolCallId = "pi-$id",
        toolName = PhoneLocalMediaToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", action)
            put("mediaHandle", handle)
            if (desiredField != null) put(desiredField, requireNotNull(desired))
        },
    )

    private class FakeGateway(
        var snapshot: MediaItemSnapshot? =
            MediaItemSnapshot(MEDIA_ID, "image/jpeg", false, false, 100L, 200L),
    ) : MediaGateway {
        var getCount = 0
        var requestedAction: MediaToolAction? = null
        var requestedDesired: Boolean? = null

        override suspend fun get(mediaId: Long): MediaItemSnapshot? {
            getCount += 1
            return snapshot?.takeIf { it.mediaId == mediaId }
        }

        override fun consentRequest(
            action: MediaToolAction,
            mediaId: Long,
            desired: Boolean?,
        ): PendingIntent {
            requestedAction = action
            requestedDesired = desired
            val context = ApplicationProvider.getApplicationContext<Context>()
            return PendingIntent.getActivity(
                context,
                action.ordinal + 1,
                Intent(context, MediaConsentActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private class ApplyingConsent(
        private val gateway: FakeGateway,
    ) : MediaSystemConsentRequester {
        var requests = 0

        override suspend fun request(
            taskId: String,
            action: MediaToolAction,
            request: PendingIntent,
        ): MediaSystemConsentResult {
            requests += 1
            gateway.snapshot = when (action) {
                MediaToolAction.SET_FAVORITE ->
                    gateway.snapshot?.copy(favorite = requireNotNull(gateway.requestedDesired))
                MediaToolAction.SET_TRASHED ->
                    gateway.snapshot?.copy(trashed = requireNotNull(gateway.requestedDesired))
                MediaToolAction.DELETE -> null
            }
            return MediaSystemConsentResult.APPROVED
        }
    }

    private companion object {
        const val TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val OTHER_TASK_ID = "33333333-3333-4333-8333-333333333333"
        const val MEDIA_ID = 42L
    }
}
