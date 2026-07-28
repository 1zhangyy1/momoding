package app.momoding.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class MomodingAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        MomodingAccessibilityRuntime.controller.onServiceConnected(this)
        MomodingAccessibilityRuntime.notifyCapabilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        MomodingAccessibilityRuntime.controller.onAccessibilityEvent(
            service = this,
            foregroundPackage = runCatching {
                rootInActiveWindow?.packageName?.toString()
            }.getOrNull(),
            event = event,
        )
    }

    override fun onInterrupt() {
        MomodingAccessibilityRuntime.controller.stop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        MomodingAccessibilityRuntime.controller.onServiceDisconnected(this)
        MomodingAccessibilityRuntime.notifyCapabilityChanged()
    }
}
