package app.momoding.script

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.momoding.guard.GateOutcome
import app.momoding.guard.GuardKind
import app.momoding.guard.RiskAssessment
import app.momoding.guard.RiskClass
import app.momoding.guard.RiskGate
import app.momoding.task.TaskGuard
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.mozilla.javascript.Context as Js

/**
 * A script the agent writes against Android's own API (本期计划 step 2; Fibona and Auto.js's idea):
 * whatever Momoding itself may do — the torch, the volume, media, Bluetooth, sensors, other apps'
 * links, notifications — without a command written for it first. Rhino in interpreted mode (Android
 * cannot load the bytecode it would generate), in Momoding's process, with Momoding's permissions.
 *
 * Trusted, with one gate: a call that reaches another person (a text, a call) or loses the person's
 * data (deleting or changing their contacts, calendar, messages, photos, phone files) goes through
 * [RiskGate] first, as the same step would from the shell. A refused one throws in the script.
 */
object AndroidScript {

    class Result(val output: String, val value: String?, val error: String?)

    private const val MAX_OUTPUT = 20_000

    fun run(context: Context, code: String, sessionId: String?, binding: TaskGuard.Binding?, timeoutMs: Long): Result {
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = Factory(deadline)
        val cx = factory.enterContext()
        return try {
            cx.isInterpretedMode = true
            cx.languageVersion = Js.VERSION_ES6
            cx.locale = java.util.Locale.ENGLISH // its own error messages, in the model's working language
            cx.instructionObserverThreshold = 10_000
            cx.wrapFactory = Gate(sessionId ?: "script", binding, code)
            val scope = ImporterTopLevel(cx)
            ScriptableObject.putProperty(scope, "context", Js.javaToJS(context.applicationContext, scope))
            ScriptableObject.putProperty(scope, "print", object : BaseFunction() {
                override fun call(cx: Js, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
                    if (out.length < MAX_OUTPUT) out.append(args.joinToString(" ") { Js.toString(it) }).append('\n')
                    return Undefined.instance
                }
            })
            // ui(fn): runs fn on the main thread and returns its value (views, toasts, some managers)
            ScriptableObject.putProperty(scope, "ui", object : BaseFunction() {
                override fun call(cx: Js, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                    val fn = args.firstOrNull() as? Function ?: return Undefined.instance
                    val result = AtomicReference<Any?>(); val error = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post {
                        val inner = factory.enterContext()
                        try { inner.isInterpretedMode = true; inner.wrapFactory = cx.wrapFactory; result.set(fn.call(inner, scope, scope, emptyArray())) }
                        catch (t: Throwable) { error.set(t) } finally { Js.exit(); done.countDown() }
                    }
                    if (!done.await(maxOf(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) throw IllegalStateException("ui() timed out")
                    error.get()?.let { throw it }
                    return result.get()
                }
            })
            val value = cx.evaluateString(scope, code, "script", 1, null)
            Result(out.toString(), value.takeUnless { it is Undefined || it == null }?.let { Js.toString(it) }?.take(MAX_OUTPUT), null)
        } catch (t: Throwable) {
            val why = (t as? org.mozilla.javascript.RhinoException)?.let { it.details() + " (line ${it.lineNumber()})" } ?: (t.message ?: t.javaClass.simpleName)
            Result(out.toString(), null, why)
        } finally {
            Js.exit()
        }
    }

    /** Stops a script that runs past its time (a loop, a wait that never ends). */
    private class Factory(private val deadline: Long) : ContextFactory() {
        override fun observeInstructionCount(cx: Js, instructionCount: Int) {
            if (System.currentTimeMillis() > deadline) throw Error("the script ran out of time")
        }
    }

    /** Every Java object handed to the script is wrapped; a call on an outward method asks first. */
    private class Gate(private val sessionId: String, private val binding: TaskGuard.Binding?, private val code: String) : WrapFactory() {
        init { isJavaPrimitiveWrap = false }

        override fun wrapAsJavaObject(cx: Js, scope: Scriptable, javaObject: Any, staticType: Class<*>?): Scriptable =
            object : NativeJavaObject(scope, javaObject, staticType) {
                override fun get(name: String, start: Scriptable): Any? {
                    val member = super.get(name, start)
                    if (member !is Function || !Outward.watched(javaObject, name)) return member
                    return object : BaseFunction() {
                        override fun call(cx: Js, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                            Outward.assess(javaObject, name, args.map { Js.jsToJava(it, Any::class.java) })?.let { ask(it, "$name(…) on ${javaObject.javaClass.simpleName}") }
                            return member.call(cx, scope, thisObj, args)
                        }
                    }
                }
            }

        private fun ask(assessment: RiskAssessment, preview: String) {
            // the card's saved recipe: the same script again, should the app restart before the answer
            val recipe = org.json.JSONObject().put("command", "momoding-android run -e " + quote(code)).put("tool_title", assessment.reason).toString()
            val outcome = runBlocking {
                RiskGate.check(sessionId, GuardKind.SHELL, assessment, preview = "momoding-android: $preview", summary = assessment.reason,
                    shellArgs = recipe, invocation = binding)
            }
            if (outcome !is GateOutcome.Allowed) throw IllegalStateException("NOT DONE — this ${assessment.reason} did not happen: " +
                ((outcome as? GateOutcome.Denied)?.message ?: "it is waiting for the person to decide") + ". Tell the person it was not done.")
        }

        private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** The outward calls of Android's API: what reaches another person or loses the person's data. */
internal object Outward {
    private val texts = setOf("sendTextMessage", "sendMultipartTextMessage", "sendDataMessage", "sendTextMessageWithoutPersisting")
    private val starts = setOf("startActivity", "startActivities", "startActivityForResult")

    fun watched(target: Any, name: String): Boolean = when {
        target is android.telephony.SmsManager -> name in texts
        target is android.telecom.TelecomManager -> name == "placeCall"
        target is android.content.ContentResolver -> name == "delete" || name == "update"
        target is android.content.Context -> name in starts
        target is java.io.File -> name == "delete" || name == "deleteOnExit"
        target is android.content.pm.PackageInstaller -> name == "uninstall"
        else -> false
    }

    /** What the call does, for the gate; null when this call, with these arguments, is not outward. */
    fun assess(target: Any, name: String, args: List<Any?>): RiskAssessment? = when {
        target is android.telephony.SmsManager -> RiskAssessment(RiskClass.OUTBOUND, "sends a text" + (args.firstOrNull()?.let { " to $it" } ?: ""), "sms")
        target is android.telecom.TelecomManager -> RiskAssessment(RiskClass.OUTBOUND, "places a phone call" + (args.firstOrNull()?.let { " to $it" } ?: ""), "calls")
        target is android.content.ContentResolver -> (args.firstOrNull() as? Uri)?.let { uri ->
            RiskAssessment(RiskClass.DESTRUCTIVE, (if (name == "delete") "deletes" else "changes") + " ${uri.authority ?: "data"} on the phone", uri.authority ?: "phone data")
        }
        target is android.content.Context -> args.filterIsInstance<Intent>().firstOrNull { it.action == Intent.ACTION_CALL }
            ?.let { RiskAssessment(RiskClass.OUTBOUND, "places a phone call to ${it.data?.schemeSpecificPart}", "calls") }
        target is java.io.File -> target.absolutePath.takeIf { it.startsWith("/storage/") || it.startsWith("/sdcard") }
            ?.let { RiskAssessment(RiskClass.DESTRUCTIVE, "deletes $it on the phone", "phone files") }
        target is android.content.pm.PackageInstaller -> RiskAssessment(RiskClass.DESTRUCTIVE, "uninstalls an app and its data", "apps")
        else -> null
    }
}
