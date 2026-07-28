package app.momoding.core.accessibility

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.UUID

data class AccessibilitySessionState(
    val connected: Boolean = false,
    val foregroundPackage: String? = null,
    val contentGeneration: Long = 0,
    val stopGeneration: Long = 0,
    val userInteractionGeneration: Long = 0,
)

data class AccessibilityInspectionRequest(
    val requestId: String,
    val targetPackage: String? = null,
    val maxNodes: Int = MAX_ACCESSIBILITY_NODES,
    val maxDepth: Int = MAX_ACCESSIBILITY_DEPTH,
) {
    init {
        require(requestId.isNotBlank()) { "requestId is blank" }
        require(targetPackage == null || targetPackage.isNotBlank()) {
            "targetPackage is blank"
        }
        require(maxNodes in 1..MAX_ACCESSIBILITY_NODES) {
            "maxNodes must be between 1 and $MAX_ACCESSIBILITY_NODES"
        }
        require(maxDepth in 0..MAX_ACCESSIBILITY_DEPTH) {
            "maxDepth must be between 0 and $MAX_ACCESSIBILITY_DEPTH"
        }
    }
}

data class AccessibilityUiSnapshot(
    val snapshotId: String,
    val observedAtMillis: Long,
    val packageName: String?,
    val windows: List<AccessibilityWindowSnapshot>,
    val nodes: List<AccessibilityNodeSnapshot>,
    val truncated: Boolean,
    val redactedNodeCount: Int,
)

data class AccessibilityWindowSnapshot(
    val windowId: Int,
    val layer: Int,
    val type: Int,
    val active: Boolean,
    val focused: Boolean,
    val rootHandle: String?,
)

data class AccessibilityNodeSnapshot(
    val handle: String,
    val parentHandle: String?,
    val windowId: Int,
    val depth: Int,
    val packageName: String?,
    val className: String?,
    val resourceId: String?,
    val bounds: AccessibilityBounds,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val redacted: Boolean,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
)

data class AccessibilityBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

sealed interface AccessibilityInspectionResult {
    data class Ready(val snapshot: AccessibilityUiSnapshot) : AccessibilityInspectionResult
    data object NotConnected : AccessibilityInspectionResult
    data object Stopped : AccessibilityInspectionResult
    data object Stale : AccessibilityInspectionResult
    data object TargetChanged : AccessibilityInspectionResult
    data class Failed(val safeMessage: String) : AccessibilityInspectionResult
}

internal data class AccessibilityInspectionToken(
    val serviceIdentity: Int,
    val contentGeneration: Long,
    val stopGeneration: Long,
    val foregroundPackage: String?,
)

internal class AccessibilityTreeInspector(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val snapshotId: () -> String = {
        "ui-${UUID.randomUUID().toString().replace("-", "")}"
    },
) {
    fun inspect(
        windows: List<AccessibilityWindowInfo>,
        fallbackRoot: AccessibilityNodeInfo?,
        request: AccessibilityInspectionRequest,
    ): AccessibilityUiSnapshot = inspectArtifact(windows, fallbackRoot, request).snapshot

    internal fun inspectArtifact(
        windows: List<AccessibilityWindowInfo>,
        fallbackRoot: AccessibilityNodeInfo?,
        request: AccessibilityInspectionRequest,
    ): AccessibilityInspectionArtifact {
        val id = snapshotId()
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        val locators = linkedMapOf<String, AccessibilityNodeLocator>()
        val windowSnapshots = mutableListOf<AccessibilityWindowSnapshot>()
        var truncated = false
        val sensitiveWindows = mutableSetOf<Int>()

        fun appendNode(
            node: AccessibilityNodeInfo,
            windowId: Int,
            parentHandle: String?,
            depth: Int,
            childPath: List<Int>,
            redactFromParent: Boolean = false,
        ): String? {
            if (depth > request.maxDepth || nodes.size >= request.maxNodes) {
                truncated = true
                return null
            }
            val handle = "$id:n${nodes.size}"
            val sensitivity = AccessibilityNodeSanitizer.classify(node)
            if (sensitivity.redactWindow) sensitiveWindows += windowId
            val sanitized = AccessibilityNodeSanitizer.sanitize(
                node = node,
                handle = handle,
                parentHandle = parentHandle,
                windowId = windowId,
                depth = depth,
                forceRedaction = redactFromParent,
            )
            nodes += sanitized
            locators[handle] = AccessibilityNodeLocator(
                windowId = windowId,
                childPath = childPath,
                expected = sanitized,
            )
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                appendNode(
                    node = child,
                    windowId = windowId,
                    parentHandle = handle,
                    depth = depth + 1,
                    childPath = childPath + index,
                    redactFromParent = redactFromParent ||
                        sensitivity.redactNode ||
                        sensitivity.redactDescendants,
                )
                if (nodes.size >= request.maxNodes) {
                    if (index < node.childCount - 1) truncated = true
                    break
                }
            }
            return handle
        }

        val orderedWindows = windows.sortedWith(
            compareByDescending<AccessibilityWindowInfo> { it.isActive }
                .thenByDescending { it.isFocused }
                .thenByDescending { it.layer },
        )
        orderedWindows.forEach { window ->
            if (nodes.size >= request.maxNodes) {
                truncated = true
                return@forEach
            }
            val root = runCatching { window.root }.getOrNull()
            if (
                request.targetPackage != null &&
                root?.packageName?.toString() != request.targetPackage
            ) {
                return@forEach
            }
            val rootHandle = root?.let {
                appendNode(
                    node = it,
                    windowId = window.id,
                    parentHandle = null,
                    depth = 0,
                    childPath = emptyList(),
                )
            }
            windowSnapshots += AccessibilityWindowSnapshot(
                windowId = window.id,
                layer = window.layer,
                type = window.type,
                active = window.isActive,
                focused = window.isFocused,
                rootHandle = rootHandle,
            )
        }
        if (windowSnapshots.isEmpty() && fallbackRoot != null) {
            val rootHandle = appendNode(
                node = fallbackRoot,
                windowId = fallbackRoot.windowId,
                parentHandle = null,
                depth = 0,
                childPath = emptyList(),
            )
            windowSnapshots += AccessibilityWindowSnapshot(
                windowId = fallbackRoot.windowId,
                layer = 0,
                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                active = true,
                focused = true,
                rootHandle = rootHandle,
            )
        }
        nodes.indices.forEach { index ->
            if (nodes[index].windowId in sensitiveWindows) {
                nodes[index] = nodes[index].redactedCopy()
                val handle = nodes[index].handle
                locators[handle] = requireNotNull(locators[handle]).copy(expected = nodes[index])
            }
        }
        return AccessibilityInspectionArtifact(
            snapshot = AccessibilityUiSnapshot(
                snapshotId = id,
                observedAtMillis = nowMillis(),
                packageName = nodes.firstOrNull { it.packageName != null }?.packageName,
                windows = windowSnapshots,
                nodes = nodes,
                truncated = truncated,
                redactedNodeCount = nodes.count(AccessibilityNodeSnapshot::redacted),
            ),
            locators = locators,
        )
    }
}

internal data class AccessibilityInspectionArtifact(
    val snapshot: AccessibilityUiSnapshot,
    val locators: Map<String, AccessibilityNodeLocator>,
)

/**
 * A locator contains only an ephemeral tree path and a sanitized fingerprint. It never retains
 * an [AccessibilityNodeInfo] across inspection and action boundaries.
 */
internal data class AccessibilityNodeLocator(
    val windowId: Int,
    val childPath: List<Int>,
    val expected: AccessibilityNodeSnapshot,
)

internal object AccessibilityNodeSanitizer {
    private val secretWords = listOf(
        "password",
        "passcode",
        "pin code",
        "one-time",
        "otp",
        "verification code",
        "security code",
        "cvv",
        "cvc",
        "payment",
        "bank card",
        "credit card",
        "密码",
        "口令",
        "验证码",
        "安全码",
        "登录验证",
        "双重验证",
    )
    private val sensitiveWindowWords = listOf(
        "payment",
        "card details",
        "card number",
        "bank card",
        "debit card",
        "credit card",
        "bank account",
        "account security",
        "two-factor",
        "2fa",
        "transfer",
        "支付",
        "付款",
        "转账",
        "银行卡",
        "银行账号",
        "账户安全",
    )
    private val shortSecret = Regex("""^\s*\d{4,8}\s*$""")
    private val longAccountNumber = Regex("""^\s*(?:\d[\s-]*){12,24}\s*$""")
    private val expiry = Regex("""^\s*(?:0[1-9]|1[0-2])\s*[/.-]\s*\d{2,4}\s*$""")
    private val pinToken = Regex("""(^|[^a-z])pin([^a-z]|$)""")

    internal data class Sensitivity(
        val redactNode: Boolean,
        val redactDescendants: Boolean,
        val redactWindow: Boolean,
    )

    fun classify(node: AccessibilityNodeInfo): Sensitivity {
        val rawText = node.text?.toString()
        val searchable = searchable(node)
        val secretContainer = secretWords.any(searchable::contains) ||
            pinToken.containsMatchIn(searchable)
        val sensitiveWindow = sensitiveWindowWords.any(searchable::contains)
        val numericSecret = rawText?.let { value ->
            value.matches(shortSecret) ||
                value.matches(longAccountNumber) ||
                value.matches(expiry)
        } == true
        val secretInput = node.isPassword || isPasswordInputType(node.inputType)
        return Sensitivity(
            redactNode = secretInput || secretContainer || sensitiveWindow || numericSecret,
            redactDescendants = secretInput || secretContainer || sensitiveWindow,
            redactWindow = sensitiveWindow,
        )
    }

    fun sanitize(
        node: AccessibilityNodeInfo,
        handle: String,
        parentHandle: String?,
        windowId: Int,
        depth: Int,
        forceRedaction: Boolean = false,
    ): AccessibilityNodeSnapshot {
        val rawText = node.text?.toString()
        val rawDescription = node.contentDescription?.toString()
        val rawHint = node.hintText?.toString()
        val rawResourceId = node.viewIdResourceName
        val sensitive = forceRedaction || classify(node).redactNode
        val bounds = Rect().also(node::getBoundsInScreen)
        return AccessibilityNodeSnapshot(
            handle = handle,
            parentHandle = parentHandle,
            windowId = windowId,
            depth = depth,
            packageName = bounded(node.packageName?.toString()),
            className = bounded(node.className?.toString()),
            resourceId = if (sensitive) null else bounded(rawResourceId),
            bounds = AccessibilityBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            ),
            text = if (sensitive && rawText != null) REDACTED else bounded(rawText),
            contentDescription = if (sensitive && rawDescription != null) {
                REDACTED
            } else {
                bounded(rawDescription)
            },
            hint = if (sensitive && rawHint != null) REDACTED else bounded(rawHint),
            redacted = sensitive,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
        )
    }

    internal fun isPasswordInputType(inputType: Int): Boolean {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun bounded(value: String?): String? = value
        ?.takeIf(String::isNotBlank)
        ?.take(MAX_ACCESSIBILITY_STRING_LENGTH)

    private fun searchable(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.text?.toString(),
        node.contentDescription?.toString(),
        node.hintText?.toString(),
        node.viewIdResourceName,
        node.className?.toString(),
    ).joinToString(" ").lowercase()

    private const val REDACTED = "[redacted]"
}

private fun AccessibilityNodeSnapshot.redactedCopy(): AccessibilityNodeSnapshot = copy(
    resourceId = null,
    text = text?.let { "[redacted]" },
    contentDescription = contentDescription?.let { "[redacted]" },
    hint = hint?.let { "[redacted]" },
    redacted = true,
)

const val MAX_ACCESSIBILITY_NODES = 250
const val MAX_ACCESSIBILITY_DEPTH = 12
const val MAX_ACCESSIBILITY_STRING_LENGTH = 512
