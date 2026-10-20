package app.momoding.chat

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends a draft SMS through the phone's SMS service: the number from the draft, or the one
 * contact with that name; waits for the radio to report each part sent. The system writes it to
 * the Messages app's sent box (Android does that for apps that are not the default SMS app).
 */
object SmsSend {
    private const val TAG = "SmsSend"
    private const val ACTION_SENT = "app.momoding.SMS_SENT"

    sealed class Resolved {
        data class One(val number: String) : Resolved()
        data class Several(val count: Int) : Resolved()
        object None : Resolved()
    }

    fun canSend(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun canReadContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private val NUMBER = Regex("""^\+?[0-9][0-9 \-]{4,}$""")
    private fun clean(n: String) = n.replace(Regex("[^0-9+]"), "")

    /** The number to send to: given, typed as the name, or the one match in the contacts. */
    fun resolve(context: Context, d: Drafts.Draft): Resolved {
        if (NUMBER.matches(d.number)) return Resolved.One(clean(d.number))
        if (NUMBER.matches(d.to.trim())) return Resolved.One(clean(d.to))
        val name = d.to.trim()
        if (name.isEmpty() || !canReadContacts(context)) return Resolved.None
        val exact = lookup(context, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name))
        val found = exact.ifEmpty { lookup(context, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%")) }
        val numbers = found.map { it.first }.distinct()
        return when {
            numbers.isEmpty() -> Resolved.None
            numbers.size == 1 -> Resolved.One(numbers[0])
            else -> {
                // one person with a mobile and a landline: the mobile
                val mobiles = found.filter { it.second == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }.map { it.first }.distinct()
                if (mobiles.size == 1) Resolved.One(mobiles[0]) else Resolved.Several(numbers.size)
            }
        }
    }

    private fun lookup(context: Context, where: String, args: Array<String>): List<Pair<String, Int>> = runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            where, args, null,
        )?.use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.let { add(clean(it) to c.getInt(1)) } }
        }.orEmpty().filter { it.first.length >= 5 }
    }.getOrDefault(emptyList())

    /** Sends [text] to [number]; true once every part is reported sent (30 s at most). */
    fun send(context: Context, number: String, text: String): Boolean {
        val sms = runCatching {
            if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        }.getOrNull() ?: return false
        val parts = sms.divideMessage(text)
        val latch = CountDownLatch(parts.size)
        val ok = AtomicBoolean(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != Activity.RESULT_OK) { ok.set(false); AppLogger.warning(TAG, "part not sent: result=$resultCode") }
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val sent = ArrayList(parts.indices.map { k ->
                PendingIntent.getBroadcast(context, k, Intent(ACTION_SENT).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            })
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
            latch.await(30, TimeUnit.SECONDS) && ok.get()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "send failed: ${t.message}")
            false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** The Messages app on a new message with [text] filled in (to [number] when known). */
    fun openPrefilled(context: Context, number: String?, text: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (number ?: ""))).putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ); true
    }.getOrDefault(false)
}
