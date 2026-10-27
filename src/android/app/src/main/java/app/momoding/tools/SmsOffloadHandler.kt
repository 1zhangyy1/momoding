package app.momoding.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import com.openminis.app.sandbox.offload.OffloadGate
import com.openminis.app.sandbox.offload.OffloadOutput
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `android-sms`: the texts the phone received — pickup codes, bank charges, verification codes,
 * school notices. Android 15 hides code-like notification content, so the inbox is the only
 * place they can be read in full. Reading needs READ_SMS, got the usual way: turned on by itself
 * where it can (SelfGrant), else the system dialog, else the person is told.
 *
 *   android-sms list [--max N] [--days D] [--from <number or name>]
 *   android-sms search <words> [--max N] [--days D]
 */
class SmsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val raw = request.argv.drop(1)
        if (raw.isEmpty() || raw[0] == "-h" || raw[0] == "--help") return NativeOffloadResult(0, HELP)
        val args = OffloadArgs(raw)
        OffloadGate.enforce("sms", "android-sms", args, request)?.let { return it }
        val sub = raw[0]
        if (sub != "list" && sub != "search") return NativeOffloadResult(2, "android-sms: unknown subcommand '$sub'\n$HELP")
        val words = if (sub == "search") args.positional.drop(1).joinToString(" ").trim() else null
        if (words != null && words.isEmpty()) return NativeOffloadResult(2, "android-sms search: missing <words>\n")
        ensurePermission(args)?.let { return it }
        val max = (args.getInt("max") ?: if (sub == "list") 20 else 10).coerceIn(1, 200)
        val since = args.getInt("days")?.let { System.currentTimeMillis() - it * 86_400_000L }
        return NativeOffloadResult(0, OffloadOutput.formatBody(query(max, since, args.get("from"), words).toString(2), args) + "\n")
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun query(max: Int, since: Long?, from: String?, words: String?): JSONArray {
        val where = mutableListOf<String>()
        val params = mutableListOf<String>()
        since?.let { where += "${Telephony.Sms.DATE} >= ?"; params += it.toString() }
        from?.let { where += "${Telephony.Sms.ADDRESS} LIKE ?"; params += "%$it%" }
        // every word somewhere in the text or the sender
        words?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.forEach {
            where += "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)"; params += "%$it%"; params += "%$it%"
        }
        val out = JSONArray()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            where.joinToString(" AND ").ifEmpty { null }, params.toTypedArray().ifEmpty { null },
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.length() < max) {
                out.put(JSONObject().put("from", c.getString(0)).put("text", c.getString(1))
                    .put("at", time.format(Date(c.getLong(2)))).put("read", c.getInt(3) == 1))
            }
        }
        return out
    }

    private fun ensurePermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasPermission()) return null
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(listOf(Manifest.permission.READ_SMS))
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasPermission() })
            ) r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            r
        }
        if (result == OffloadPermissionManager.AndroidPermissionResult.GRANTED) return null
        val body = JSONObject().put("error", "permission_denied")
            .put("message", "Reading texts is off. Ask the person to turn it on (the sms card).")
        return NativeOffloadResult(77, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    private companion object {
        const val HELP = """android-sms — read the texts the phone received (newest first)

  android-sms list [--max N] [--days D] [--from <number or name>]
  android-sms search <words> [--max N] [--days D]   every word in the text or the sender

Each: {"from", "text", "at": "yyyy-MM-dd HH:mm", "read"}
"""
    }
}
