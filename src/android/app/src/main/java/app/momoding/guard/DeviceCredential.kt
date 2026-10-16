// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/guard/DeviceCredential.kt
package app.momoding.guard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * The phone's own screen lock — fingerprint, face, PIN, pattern or password — as the
 * confirmation for the highest tier of remembered approvals ("remember and run next time"
 * for a payment). The point is not to keep the person out of their own phone; it is to make
 * that choice a deliberate one, taken by them and not by a hand on a bumped screen or by the
 * agent itself.
 */
object DeviceCredential {

    /** True when there is a lock to confirm with. Without one the choice stands on its own. */
    fun available(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    /** Whether [confirm] can show the system prompt itself (API 29+); older phones use [keyguardIntent]. */
    fun promptsItself(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Shows the system's confirmation and calls [onResult] on the main thread. API 29 and up
     * only — see [promptsItself]; the caller falls back to [keyguardIntent] below that.
     */
    fun confirm(activity: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!available(activity)) { onResult(true); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { onResult(false); return }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val executor = ContextCompat.getMainExecutor(activity)
        builder.build().authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
                override fun onAuthenticationFailed() = Unit // one wrong try; the prompt stays up
            },
        )
    }

    /** The lock-screen confirmation as an Intent, for phones before API 29 (result via a launcher). */
    fun keyguardIntent(context: Context, title: String, subtitle: String): Intent? {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return null
        @Suppress("DEPRECATION")
        return km.createConfirmDeviceCredentialIntent(title, subtitle)
    }
}
