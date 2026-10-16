// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/guard/TapWords.kt
package app.momoding.guard

/**
 * What a button's label says it does. Shared by the browser (the element's text) and the
 * phone screen (the label the screen model reports for the tap), so the same words stop for
 * the same approval on every rung of the ladder.
 */
object TapWords {
    val secretField = Regex(
        "passw|pwd|密码|口令|verif|otp|一次性|验证码|校验码|动态码|短信码|sms.?code|auth.?code|security.?code|\\bpin\\b|captcha|图形码|cvv|cvc|安全码",
        RegexOption.IGNORE_CASE,
    )
    private val moneyTap = Regex(
        "支付|付款|立即购买|确认购买|去支付|确认支付|结算|下单|提交订单|立即下单|买单|充值|转账|打赏|购买|订阅|开通|续费|确认付款|立即支付|\\bpay\\b|pay now|payment|checkout|place order|buy now|purchase|(?<!un)subscribe|donate|transfer|top ?up|confirm order|complete order|confirm purchase|book now|reserve",
        RegexOption.IGNORE_CASE,
    )
    private val destructiveTap = Regex(
        "删除|移除|清空|注销|解绑|退订|取消订单|退款|\\bdelete\\b|\\bremove\\b|clear all|unsubscribe|deactivate|cancel order|\\brefund\\b|\\bdiscard\\b|\\berase\\b",
        RegexOption.IGNORE_CASE,
    )
    private val outboundTap = Regex(
        "^发送$|发送|发布|回复|投递|转发|评论|发表|发帖|提交|确认发送|\\bsend\\b|\\bpost\\b|\\bpublish\\b|\\breply\\b|\\bshare\\b|\\btweet\\b|\\bcomment\\b|\\bsubmit\\b|\\bapply\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Apps where Enter in the text field sends the message. */
    private val chatApps = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework", "com.alibaba.android.rimet",
        "com.ss.android.lark", "com.larksuite.suite", "org.telegram.messenger", "com.whatsapp", "com.whatsapp.w4b",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.discord", "com.Slack", "com.microsoft.teams",
        "com.skype.raider", "jp.naver.line.android", "com.google.android.apps.messaging", "com.android.mms",
        "com.sina.weibo", "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.aweme",
    )
    private val sendField = Regex(
        "发送|发消息|消息|说点什么|输入消息|评论|回复|留言|\\bmessage\\b|\\bsend\\b|\\bchat\\b|\\breply\\b|\\bcomment\\b|say something|write a message|type a message",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether Enter in the focused field amounts to sending: the app is a messenger, or the
     * field's own hint says message / send / reply. A search box's Enter is just a search.
     */
    fun entersSend(packageName: String?, fieldHint: String?): Boolean =
        (packageName != null && packageName in chatApps) || (!fieldHint.isNullOrBlank() && sendField.containsMatchIn(fieldHint))

    /** The class a tap on [label] falls in, or null when it is an ordinary tap. */
    fun classify(label: String): RiskClass? = when {
        label.isBlank() -> null
        moneyTap.containsMatchIn(label) -> RiskClass.MONEY
        destructiveTap.containsMatchIn(label) -> RiskClass.DESTRUCTIVE
        outboundTap.containsMatchIn(label) -> RiskClass.OUTBOUND
        else -> null
    }

    /**
     * The class of a tap judged by two accounts of the button: the label the screen model
     * reported and the text the accessibility tree has at the point the finger lands. The
     * stricter of the two wins, so a model that calls a pay button "Next" still stops.
     */
    fun classify(reported: String, onScreen: String?): RiskClass? {
        val a = classify(reported)
        val b = onScreen?.let { classify(it) }
        return listOfNotNull(a, b).minByOrNull { rank(it) }
    }

    /** The label worth showing on the card: the model's, or the screen's when it has none. */
    fun shown(reported: String, onScreen: String?): String =
        reported.trim().ifBlank { onScreen?.trim().orEmpty() }

    private fun rank(cls: RiskClass): Int = when (cls) {
        RiskClass.MONEY -> 0
        RiskClass.DESTRUCTIVE -> 1
        else -> 2
    }

    /** The reason line for the card, from the class and the label. */
    fun reason(cls: RiskClass, short: String, where: String?): String = when (cls) {
        RiskClass.MONEY -> "taps “$short”: looks like a payment"
        RiskClass.DESTRUCTIVE -> "taps “$short”: looks like it deletes something"
        else -> "taps “$short”: looks like it sends or posts"
    } + (where?.let { " on $it" } ?: "")

    fun looksSecret(text: String): Boolean = secretField.containsMatchIn(text)
}
