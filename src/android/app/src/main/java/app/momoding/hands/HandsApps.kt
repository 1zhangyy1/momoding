// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/hands/HandsApps.kt
package app.momoding.hands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.openminis.app.logging.AppLogger

/** The apps on this phone that have a launcher icon, by the name the user sees. */
object HandsApps {
    private const val TAG = "HandsApps"

    data class App(val label: String, val packageName: String)

    @Volatile private var cache: Pair<Long, List<App>>? = null

    /** Launchable apps, sorted by label; cached for a minute (a query walks every package). */
    fun launchable(context: Context): List<App> {
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < 60_000) return list }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) null else App(label, pkg)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "query failed: ${t.message}")
            emptyList()
        }
        cache = System.currentTimeMillis() to list
        return list
    }

    /** The app the model means: exact label, then label contains / is contained, then package. */
    fun resolve(context: Context, name: String): App? {
        val q = name.trim()
        if (q.isEmpty()) return null
        val apps = launchable(context)
        val lower = q.lowercase()
        return apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.lowercase().contains(lower) }
            ?: apps.firstOrNull { lower.contains(it.label.lowercase()) && it.label.length >= 2 }
            ?: alias(lower)?.let { pkgs -> apps.firstOrNull { it.packageName in pkgs } }
            // the phone's own apps go by different labels per maker and language ("Clock", "时钟"); their packages agree
            ?: SYSTEM[lower]?.let { keys -> apps.firstOrNull { a -> keys.any { a.packageName.lowercase().contains(it) } } }
    }

    /** Names of the phone's own apps, in English and Chinese, to the words their package names carry. */
    private val SYSTEM: Map<String, List<String>> = buildMap {
        fun put(keys: List<String>, vararg names: String) = names.forEach { put(it, keys) }
        put(listOf("deskclock", "clock", "alarm"), "clock", "alarm", "alarms", "时钟", "闹钟")
        put(listOf("contacts", "dialer"), "contacts", "contact", "people", "通讯录", "联系人") // no Contacts app: the Phone app has them
        put(listOf("messaging", "mms", "message"), "messages", "message", "sms", "短信", "信息", "简讯")
        put(listOf("dialer", "phone", "incallui"), "phone", "dialer", "电话", "拨号")
        put(listOf("calendar"), "calendar", "日历")
        put(listOf("camera"), "camera", "相机")
        put(listOf("gallery", "photos", "album"), "gallery", "photos", "相册", "图库")
        put(listOf("calculator"), "calculator", "计算器")
        put(listOf("files", "documentsui", "filemanager"), "files", "file manager", "文件", "文件管理")
    }

    /** A few names people use that are not the label on the icon. */
    private fun alias(lower: String): Set<String>? = when (lower) {
        "wechat", "weixin", "微信" -> setOf("com.tencent.mm")
        "alipay", "支付宝" -> setOf("com.eg.android.AlipayGphone")
        "taobao", "淘宝" -> setOf("com.taobao.taobao")
        "12306", "铁路12306", "railway 12306" -> setOf("com.MobileTicket")
        "meituan", "美团" -> setOf("com.sankuai.meituan")
        "jd", "jingdong", "京东" -> setOf("com.jingdong.app.mall")
        "douyin", "抖音", "tiktok" -> setOf("com.ss.android.ugc.aweme", "com.zhiliaoapp.musically")
        "xiaohongshu", "小红书", "rednote" -> setOf("com.xingin.xhs")
        "amap", "gaode", "高德地图", "高德" -> setOf("com.autonavi.minimap")
        "chrome", "谷歌浏览器" -> setOf("com.android.chrome")
        "settings", "设置" -> setOf("com.android.settings")
        else -> null
    }

    /** Brings [app] to the front; false when Android refused (the model then finds the icon itself). */
    fun open(context: Context, app: App): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "open ${app.packageName} failed: ${t.message}")
            false
        }
    }

    /** The label of the app that owns [packageName], or the package itself. */
    fun labelOf(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        launchable(context).firstOrNull { it.packageName == packageName }?.let { return it.label }
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Throwable) {
            packageName
        }
    }
}
