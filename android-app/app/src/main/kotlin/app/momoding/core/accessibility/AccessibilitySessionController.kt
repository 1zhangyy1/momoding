package app.momoding.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AccessibilitySessionController internal constructor(
    private val inspector: AccessibilityTreeInspector = AccessibilityTreeInspector(),
) {
    private val lock = Any()
    private var serviceReference = WeakReference<MomodingAccessibilityService>(null)
    private val snapshots = LinkedHashMap<String, StoredAccessibilitySnapshot>()
    private var expectedAutomationEvent: ExpectedAutomationEvent? = null
    private val _state = MutableStateFlow(AccessibilitySessionState())
    val state: StateFlow<AccessibilitySessionState> = _state.asStateFlow()

    internal fun onServiceConnected(service: MomodingAccessibilityService) {
        synchronized(lock) {
            serviceReference = WeakReference(service)
            _state.value = _state.value.copy(
                connected = true,
                foregroundPackage = runCatching {
                    service.rootInActiveWindow?.packageName?.toString()
                }.getOrNull(),
                contentGeneration = _state.value.contentGeneration + 1,
            )
        }
    }

    internal fun onContentChanged(
        service: MomodingAccessibilityService,
        foregroundPackage: String?,
        userInteraction: Boolean = false,
    ) {
        synchronized(lock) {
            if (serviceReference.get() !== service) return
            _state.value = _state.value.copy(
                foregroundPackage = foregroundPackage ?: _state.value.foregroundPackage,
                contentGeneration = _state.value.contentGeneration + 1,
                userInteractionGeneration = _state.value.userInteractionGeneration +
                    if (userInteraction) 1 else 0,
            )
        }
    }

    internal fun onAccessibilityEvent(
        service: MomodingAccessibilityService,
        foregroundPackage: String?,
        event: AccessibilityEvent?,
    ) {
        val eventType = event?.eventType
        val eventPackage = event?.packageName?.toString()
        val userInteraction = synchronized(lock) {
            val expected = expectedAutomationEvent
            val now = SystemClock.elapsedRealtime()
            if (expected != null && expected.expiresAtElapsedMillis < now) {
                expectedAutomationEvent = null
            }
            when {
                eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> true
                eventType !in USER_INTERACTION_EVENT_TYPES -> false
                expected != null &&
                    expected.expiresAtElapsedMillis >= now &&
                    expected.eventType == eventType &&
                    expected.packageName == eventPackage -> {
                    expectedAutomationEvent = null
                    false
                }
                else -> true
            }
        }
        onContentChanged(service, foregroundPackage, userInteraction)
    }

    internal fun onServiceDisconnected(service: MomodingAccessibilityService) {
        synchronized(lock) {
            if (serviceReference.get() !== service) return
            serviceReference.clear()
            snapshots.clear()
            expectedAutomationEvent = null
            _state.value = _state.value.copy(
                connected = false,
                foregroundPackage = null,
                contentGeneration = _state.value.contentGeneration + 1,
            )
        }
    }

    /**
     * Stops the current observation attempt and invalidates every snapshot handle.
     * It intentionally does not disable the Android accessibility service.
     */
    fun stop() {
        synchronized(lock) {
            _state.value = _state.value.copy(
                foregroundPackage = null,
                contentGeneration = _state.value.contentGeneration + 1,
                stopGeneration = _state.value.stopGeneration + 1,
            )
            snapshots.clear()
            expectedAutomationEvent = null
        }
    }

    fun inspect(request: AccessibilityInspectionRequest): AccessibilityInspectionResult {
        val (service, token) = synchronized(lock) {
            val activeService = serviceReference.get()
                ?: return AccessibilityInspectionResult.NotConnected
            activeService to token(activeService)
        }
        if (
            request.targetPackage != null &&
            request.targetPackage != token.foregroundPackage
        ) {
            return AccessibilityInspectionResult.TargetChanged
        }
        val artifact = runCatching {
            inspector.inspectArtifact(
                windows = service.windows.orEmpty(),
                fallbackRoot = service.rootInActiveWindow,
                request = request,
            )
        }.getOrElse {
            return AccessibilityInspectionResult.Failed(
                "The visible interface could not be inspected.",
            )
        }
        if (artifact.snapshot.nodes.isEmpty()) {
            return AccessibilityInspectionResult.Failed(
                "No inspectable interface is currently visible.",
            )
        }
        val completed = complete(token, request, artifact.snapshot)
        if (completed is AccessibilityInspectionResult.Ready) {
            synchronized(lock) {
                snapshots[artifact.snapshot.snapshotId] = StoredAccessibilitySnapshot(
                    artifact = artifact,
                    serviceIdentity = token.serviceIdentity,
                    stopGeneration = token.stopGeneration,
                )
                while (snapshots.size > MAX_STORED_UI_SNAPSHOTS) {
                    snapshots.remove(snapshots.keys.first())
                }
            }
        }
        return completed
    }

    fun describeAction(
        request: AccessibilityUiActionRequest,
    ): AccessibilityActionDescriptionResult {
        val resolved = resolveActionTarget(request)
        return when (resolved) {
            is ResolvedActionTarget.Failed -> resolved.failure
            is ResolvedActionTarget.Ready -> AccessibilityActionDescriptionResult.Ready(
                AccessibilityUiActionDescription(
                    action = request.action,
                    packageName = resolved.packageName,
                    node = resolved.nodeSnapshot,
                    snapshotObservedAtMillis = resolved.snapshotObservedAtMillis,
                ),
            )
        }
    }

    fun snapshot(snapshotId: String): AccessibilityUiSnapshot? = synchronized(lock) {
        snapshots[snapshotId]?.artifact?.snapshot
    }

    fun performAction(request: AccessibilityUiActionRequest): AccessibilityRawActionResult {
        val resolved = resolveActionTarget(request)
        if (resolved is ResolvedActionTarget.Failed) {
            return AccessibilityRawActionResult.Failed(
                resolved.failure.code,
                resolved.failure.safeMessage,
            )
        }
        resolved as ResolvedActionTarget.Ready
        val expectedEventType = when (request.action) {
            AccessibilityUiActionKind.CLICK -> AccessibilityEvent.TYPE_VIEW_CLICKED
            AccessibilityUiActionKind.SCROLL -> AccessibilityEvent.TYPE_VIEW_SCROLLED
            AccessibilityUiActionKind.INPUT_DRAFT -> AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            AccessibilityUiActionKind.BACK -> null
        }
        expectedEventType?.let { eventType ->
            synchronized(lock) {
                expectedAutomationEvent = ExpectedAutomationEvent(
                    packageName = resolved.packageName,
                    eventType = eventType,
                    expiresAtElapsedMillis = SystemClock.elapsedRealtime() +
                        AUTOMATION_EVENT_TTL_MILLIS,
                )
            }
        }
        val performed = runCatching {
            when (request.action) {
                AccessibilityUiActionKind.CLICK -> requireNotNull(resolved.node)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AccessibilityUiActionKind.SCROLL -> {
                    val node = requireNotNull(resolved.node)
                    node.performAction(scrollAction(node, requireNotNull(request.direction)))
                }
                AccessibilityUiActionKind.INPUT_DRAFT -> {
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            requireNotNull(request.text),
                        )
                    }
                    requireNotNull(resolved.node).performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments,
                    )
                }
                AccessibilityUiActionKind.BACK -> resolved.service.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                )
            }
        }.getOrDefault(false)
        if (!performed && expectedEventType != null) {
            synchronized(lock) { expectedAutomationEvent = null }
        }
        return if (performed) {
            AccessibilityRawActionResult.Performed
        } else {
            AccessibilityRawActionResult.Failed(
                "UI_ACTION_REJECTED",
                "Android rejected the requested interface action.",
            )
        }
    }

    private data class ExpectedAutomationEvent(
        val packageName: String,
        val eventType: Int,
        val expiresAtElapsedMillis: Long,
    )

    internal suspend fun captureScreenshot(targetPackage: String?): RawScreenFrameResult {
        val (service, token) = synchronized(lock) {
            val activeService = serviceReference.get()
                ?: return RawScreenFrameResult.Failed(
                    "SCREEN_CAPTURE_SESSION_REQUIRED",
                    "Enable Accessibility control or start a screen-capture session.",
                )
            activeService to token(activeService)
        }
        if (targetPackage != null && targetPackage != token.foregroundPackage) {
            return RawScreenFrameResult.Failed(
                "SCREEN_CAPTURE_TARGET_CHANGED",
                "The visible app no longer matches the requested target.",
            )
        }
        val activeWindowId = runCatching {
            service.windows.orEmpty()
                .firstOrNull { it.isActive || it.isFocused }
                ?.id
                ?: service.rootInActiveWindow?.windowId
        }.getOrNull()
        val captured = takeScreenshot(service, activeWindowId)
        if (captured is RawScreenFrameResult.Failed) return captured
        val bitmap = (captured as RawScreenFrameResult.Ready).frame.bitmap
        return synchronized(lock) {
            val currentService = serviceReference.get()
            val current = _state.value
            when {
                currentService == null ||
                    System.identityHashCode(currentService) != token.serviceIdentity -> {
                    bitmap.recycle()
                    RawScreenFrameResult.Failed(
                        "SCREEN_CAPTURE_ACCESS_REVOKED",
                        "Accessibility access ended before the screen image was ready.",
                    )
                }
                current.stopGeneration != token.stopGeneration -> {
                    bitmap.recycle()
                    RawScreenFrameResult.Failed(
                        "SCREEN_CAPTURE_STOPPED",
                        "Screen capture was stopped.",
                    )
                }
                current.contentGeneration != token.contentGeneration -> {
                    bitmap.recycle()
                    RawScreenFrameResult.Failed(
                        "SCREEN_CAPTURE_STALE",
                        "The visible screen changed while it was being captured.",
                    )
                }
                targetPackage != null && targetPackage != current.foregroundPackage -> {
                    bitmap.recycle()
                    RawScreenFrameResult.Failed(
                        "SCREEN_CAPTURE_TARGET_CHANGED",
                        "The visible app no longer matches the requested target.",
                    )
                }
                else -> RawScreenFrameResult.Ready(
                    RawScreenFrame(
                        bitmap = bitmap,
                        source = ScreenCaptureSource.ACCESSIBILITY,
                        foregroundPackage = current.foregroundPackage,
                    ),
                )
            }
        }
    }

    internal fun currentTokenForTest(): AccessibilityInspectionToken? = synchronized(lock) {
        val service = serviceReference.get() ?: return null
        token(service)
    }

    internal fun completeForTest(
        token: AccessibilityInspectionToken,
        request: AccessibilityInspectionRequest,
        snapshot: AccessibilityUiSnapshot,
    ): AccessibilityInspectionResult = complete(token, request, snapshot)

    private fun complete(
        token: AccessibilityInspectionToken,
        request: AccessibilityInspectionRequest,
        snapshot: AccessibilityUiSnapshot,
    ): AccessibilityInspectionResult = synchronized(lock) {
        val currentService = serviceReference.get()
            ?: return AccessibilityInspectionResult.NotConnected
        val current = _state.value
        when {
            System.identityHashCode(currentService) != token.serviceIdentity ->
                AccessibilityInspectionResult.NotConnected
            current.stopGeneration != token.stopGeneration ->
                AccessibilityInspectionResult.Stopped
            current.contentGeneration != token.contentGeneration ->
                AccessibilityInspectionResult.Stale
            request.targetPackage != null &&
                request.targetPackage != current.foregroundPackage ->
                AccessibilityInspectionResult.TargetChanged
            snapshot.packageName != null &&
                current.foregroundPackage != null &&
                snapshot.packageName != current.foregroundPackage ->
                AccessibilityInspectionResult.Stale
            else -> AccessibilityInspectionResult.Ready(snapshot)
        }
    }

    private fun token(service: AccessibilityService): AccessibilityInspectionToken {
        val current = _state.value
        return AccessibilityInspectionToken(
            serviceIdentity = System.identityHashCode(service),
            contentGeneration = current.contentGeneration,
            stopGeneration = current.stopGeneration,
            foregroundPackage = current.foregroundPackage,
        )
    }

    private fun resolveActionTarget(
        request: AccessibilityUiActionRequest,
    ): ResolvedActionTarget {
        val (service, stored, foregroundPackage) = synchronized(lock) {
            val activeService = serviceReference.get()
                ?: return ResolvedActionTarget.Failed(
                    AccessibilityActionDescriptionResult.Failed(
                        "UI_CONTROL_SESSION_REQUIRED",
                        "Enable Accessibility control before using interface actions.",
                    ),
                )
            val artifact = snapshots[request.snapshotId]
                ?: return ResolvedActionTarget.Failed(
                    AccessibilityActionDescriptionResult.Failed(
                        "UI_SNAPSHOT_NOT_FOUND",
                        "Inspect the current interface again before acting.",
                    ),
                )
            val current = _state.value
            if (artifact.serviceIdentity != System.identityHashCode(activeService)) {
                return ResolvedActionTarget.Failed(
                    AccessibilityActionDescriptionResult.Failed(
                        "UI_SNAPSHOT_STALE",
                        "Accessibility reconnected; inspect the interface again.",
                    ),
                )
            }
            if (artifact.stopGeneration != current.stopGeneration) {
                return ResolvedActionTarget.Failed(
                    AccessibilityActionDescriptionResult.Failed(
                        "UI_ACTION_STOPPED",
                        "The interface action was stopped.",
                    ),
                )
            }
            Triple(activeService, artifact, current.foregroundPackage)
        }
        val snapshot = stored.artifact.snapshot
        val expectedPackage = snapshot.packageName
        if (expectedPackage == null || foregroundPackage != expectedPackage) {
            return ResolvedActionTarget.Failed(
                AccessibilityActionDescriptionResult.Failed(
                    "UI_ACTION_TARGET_CHANGED",
                    "The visible app changed; inspect the new interface before acting.",
                ),
            )
        }
        if (request.action == AccessibilityUiActionKind.BACK) {
            return ResolvedActionTarget.Ready(
                service = service,
                packageName = expectedPackage,
                node = null,
                nodeSnapshot = null,
                snapshotObservedAtMillis = snapshot.observedAtMillis,
            )
        }
        val handle = requireNotNull(request.nodeHandle)
        val locator = stored.artifact.locators[handle]
            ?: return ResolvedActionTarget.Failed(
                AccessibilityActionDescriptionResult.Failed(
                    "UI_NODE_NOT_FOUND",
                    "The selected interface element is no longer available.",
                ),
            )
        val root = runCatching {
            service.windows.orEmpty().firstOrNull { it.id == locator.windowId }?.root
                ?: service.rootInActiveWindow?.takeIf { it.windowId == locator.windowId }
        }.getOrNull()
            ?: return ResolvedActionTarget.Failed(
                AccessibilityActionDescriptionResult.Failed(
                    "UI_NODE_STALE",
                    "The selected interface window is no longer available.",
                ),
            )
        val node = locator.childPath.fold(root as AccessibilityNodeInfo?) { current, index ->
            current?.let { runCatching { it.getChild(index) }.getOrNull() }
        } ?: return ResolvedActionTarget.Failed(
            AccessibilityActionDescriptionResult.Failed(
                "UI_NODE_STALE",
                "The selected interface element moved; inspect the interface again.",
            ),
        )
        val fresh = AccessibilityNodeSanitizer.sanitize(
            node = node,
            handle = locator.expected.handle,
            parentHandle = locator.expected.parentHandle,
            windowId = locator.windowId,
            depth = locator.expected.depth,
            forceRedaction = locator.expected.redacted,
        )
        if (!fresh.matches(locator.expected)) {
            return ResolvedActionTarget.Failed(
                AccessibilityActionDescriptionResult.Failed(
                    "UI_NODE_STALE",
                    "The selected interface element changed; inspect the interface again.",
                ),
            )
        }
        val unsupported = when (request.action) {
            AccessibilityUiActionKind.CLICK -> !fresh.clickable
            AccessibilityUiActionKind.SCROLL -> !fresh.scrollable
            AccessibilityUiActionKind.INPUT_DRAFT -> !fresh.editable
            AccessibilityUiActionKind.BACK -> false
        }
        if (unsupported || !fresh.enabled || !fresh.visibleToUser) {
            return ResolvedActionTarget.Failed(
                AccessibilityActionDescriptionResult.Failed(
                    "UI_ACTION_UNSUPPORTED",
                    "The selected interface element does not support this action.",
                ),
            )
        }
        return ResolvedActionTarget.Ready(
            service = service,
            packageName = expectedPackage,
            node = node,
            nodeSnapshot = fresh,
            snapshotObservedAtMillis = snapshot.observedAtMillis,
        )
    }

    private fun scrollAction(
        node: AccessibilityNodeInfo,
        direction: AccessibilityScrollDirection,
    ): Int {
        val directional = when (direction) {
            AccessibilityScrollDirection.UP ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            AccessibilityScrollDirection.DOWN ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            AccessibilityScrollDirection.LEFT ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            AccessibilityScrollDirection.RIGHT ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        return if (node.actionList.any { it.id == directional.id }) {
            directional.id
        } else if (
            direction == AccessibilityScrollDirection.DOWN ||
            direction == AccessibilityScrollDirection.RIGHT
        ) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
    }

    private suspend fun takeScreenshot(
        service: MomodingAccessibilityService,
        activeWindowId: Int?,
    ): RawScreenFrameResult = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        fun deliver(result: RawScreenFrameResult) {
            if (completed.compareAndSet(false, true)) {
                continuation.resume(result)
            } else if (result is RawScreenFrameResult.Ready) {
                result.frame.bitmap.recycle()
            }
        }
        continuation.invokeOnCancellation { completed.compareAndSet(false, true) }
        runCatching {
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(
                    screenshot: AccessibilityService.ScreenshotResult,
                ) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val softwareBitmap = try {
                        Bitmap.wrapHardwareBuffer(
                            hardwareBuffer,
                            screenshot.colorSpace,
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBuffer.close()
                    }
                    deliver(
                        softwareBitmap?.let { bitmap ->
                            RawScreenFrameResult.Ready(
                                RawScreenFrame(
                                    bitmap = bitmap,
                                    source = ScreenCaptureSource.ACCESSIBILITY,
                                    foregroundPackage = null,
                                ),
                            )
                        } ?: RawScreenFrameResult.Failed(
                            "SCREEN_CAPTURE_COPY_FAILED",
                            "Android returned a screen image that could not be read.",
                        ),
                    )
                }

                override fun onFailure(errorCode: Int) {
                    deliver(accessibilityScreenshotFailure(errorCode))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                activeWindowId != null
            ) {
                service.takeScreenshotOfWindow(
                    activeWindowId,
                    SCREENSHOT_EXECUTOR,
                    callback,
                )
            } else {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    SCREENSHOT_EXECUTOR,
                    callback,
                )
            }
        }.onFailure {
            deliver(
                RawScreenFrameResult.Failed(
                    "SCREEN_CAPTURE_FAILED",
                    "Android could not capture the current screen.",
                ),
            )
        }
    }

    private companion object {
        const val MAX_STORED_UI_SNAPSHOTS = 16
        const val AUTOMATION_EVENT_TTL_MILLIS = 1_000L
        val USER_INTERACTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
        )
        val SCREENSHOT_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "momoding-accessibility-screenshot").apply { isDaemon = true }
        }
    }
}

private data class StoredAccessibilitySnapshot(
    val artifact: AccessibilityInspectionArtifact,
    val serviceIdentity: Int,
    val stopGeneration: Long,
)

private sealed interface ResolvedActionTarget {
    data class Ready(
        val service: MomodingAccessibilityService,
        val packageName: String,
        val node: AccessibilityNodeInfo?,
        val nodeSnapshot: AccessibilityNodeSnapshot?,
        val snapshotObservedAtMillis: Long,
    ) : ResolvedActionTarget

    data class Failed(
        val failure: AccessibilityActionDescriptionResult.Failed,
    ) : ResolvedActionTarget
}

private fun AccessibilityNodeSnapshot.matches(expected: AccessibilityNodeSnapshot): Boolean =
    copy(handle = expected.handle, parentHandle = expected.parentHandle) == expected

internal fun accessibilityScreenshotFailure(errorCode: Int): RawScreenFrameResult.Failed = when (
    errorCode
) {
    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
        RawScreenFrameResult.Failed(
            "SCREEN_CAPTURE_ACCESS_REVOKED",
            "Accessibility screen access is no longer available.",
        )
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
        RawScreenFrameResult.Failed(
            "SCREEN_CAPTURE_RATE_LIMITED",
            "Android requires a short pause between screen captures.",
        )
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
        RawScreenFrameResult.Failed(
            "SCREEN_CAPTURE_DISPLAY_UNAVAILABLE",
            "The requested Android display is no longer available.",
        )
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
        RawScreenFrameResult.Failed(
            "SCREEN_CAPTURE_WINDOW_UNAVAILABLE",
            "The requested Android window is no longer available.",
        )
    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
        RawScreenFrameResult.Failed(
            "SCREEN_CAPTURE_SECURE_WINDOW",
            "Android protected the visible window from screen capture.",
        )
    else -> RawScreenFrameResult.Failed(
        "SCREEN_CAPTURE_FAILED",
        "Android could not capture the current screen.",
    )
}

object MomodingAccessibilityRuntime {
    val controller = AccessibilitySessionController()

    @Volatile
    private var capabilityChanged: (() -> Unit)? = null

    fun setCapabilityChangedListener(listener: () -> Unit) {
        capabilityChanged = listener
    }

    internal fun notifyCapabilityChanged() {
        capabilityChanged?.invoke()
    }
}
