// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/guard/RiskText.kt
package app.momoding.guard

import android.content.Context
import com.openminis.app.R

/** The words on the card and the notification, in the user's language. */
object RiskText {
    fun targetLabel(context: Context, target: String?): String? = when (target) {
        null -> null
        ShellGuard.WORKSPACE -> context.getString(R.string.mo_risk_target_workspace)
        ShellGuard.SHARED -> context.getString(R.string.mo_risk_target_shared)
        else -> plainTarget(target)
    }

    /** The target as the card names it: for another device's request, the device's name. */
    fun targetLabel(context: Context, request: RiskRequest): String? =
        if (request.kind == GuardKind.DEVICE) request.pageUrl ?: targetLabel(context, request.assessment.target)
        else targetLabel(context, request.assessment.target)

    /** The target without its scope prefix (`app:`, `pc:`, `lark:`, `git:`, `device:`), for prose. */
    fun plainTarget(target: String?): String =
        (target ?: "").removePrefix("lark:").removePrefix("git:").removePrefix("pc:").removePrefix("app:").removePrefix("device:")

    fun tierLabel(context: Context, tier: RiskTier): String = when (tier) {
        RiskTier.HIGHEST -> context.getString(R.string.mo_risk_tier_highest)
        RiskTier.CONFIRM -> context.getString(R.string.mo_risk_tier_confirm)
        RiskTier.NOTICE -> context.getString(R.string.mo_risk_tier_notice)
    }

    fun title(context: Context, request: RiskRequest, agentName: String): String {
        val a = request.assessment
        val target = targetLabel(context, a.target)
        if (request.kind == GuardKind.DEVICE) return context.getString(R.string.mo_risk_title_device, request.pageUrl ?: target ?: "")
        if (a.warnings.isNotEmpty()) return context.getString(R.string.mo_risk_title_warning, agentName)
        return when (a.riskClass) {
            RiskClass.DESTRUCTIVE -> if (target != null) context.getString(R.string.mo_risk_title_destructive, agentName, target)
            else context.getString(R.string.mo_risk_title_destructive_generic, agentName)
            RiskClass.MONEY -> if (target != null) context.getString(R.string.mo_risk_title_money, agentName, target)
            else context.getString(R.string.mo_risk_title_money_generic, agentName)
            else -> if (target != null) context.getString(R.string.mo_risk_title_outbound, agentName, target)
            else context.getString(R.string.mo_risk_title_outbound_generic, agentName)
        }
    }

    fun description(context: Context, request: RiskRequest, agentName: String): String {
        val a = request.assessment
        if (a.warnings.isNotEmpty()) {
            return context.getString(R.string.mo_risk_desc_warning, a.warnings.joinToString(", ") { warningLabel(context, it) })
        }
        if (request.kind == GuardKind.BROWSER) {
            val host = a.target ?: request.pageUrl ?: ""
            return context.getString(R.string.mo_risk_desc_browser, agentName, request.elementText ?: "", host)
        }
        if (request.kind == GuardKind.SCREEN) {
            val app = request.pageUrl ?: a.target ?: context.getString(R.string.mo_hands_this_phone)
            return context.getString(R.string.mo_risk_desc_screen, agentName, request.elementText ?: "", app)
        }
        if (request.kind == GuardKind.DEVICE) {
            return context.getString(R.string.mo_risk_desc_device, request.pageUrl ?: "", request.elementText ?: "")
        }
        val target = targetLabel(context, a.target)
        val shell = when (a.riskClass) {
            RiskClass.DESTRUCTIVE -> if (target != null) context.getString(R.string.mo_risk_desc_shell_destructive, agentName, target)
            else context.getString(R.string.mo_risk_desc_shell_destructive_generic, agentName)
            RiskClass.MONEY -> if (target != null) context.getString(R.string.mo_risk_desc_shell_money, agentName, target)
            else context.getString(R.string.mo_risk_desc_shell_money_generic, agentName)
            else -> if (target != null) context.getString(R.string.mo_risk_desc_shell_outbound, agentName, target)
            else context.getString(R.string.mo_risk_desc_shell_outbound_generic, agentName)
        }
        // On a paired computer, not on this phone (0.1.13).
        if (request.kind == GuardKind.COMPUTER) {
            return shell + " " + context.getString(R.string.mo_risk_desc_on_computer, request.pageUrl ?: "")
        }
        return shell
    }

    fun classLabel(context: Context, riskClass: RiskClass): String = when (riskClass) {
        RiskClass.DESTRUCTIVE -> context.getString(R.string.mo_risk_class_destructive)
        RiskClass.OUTBOUND -> context.getString(R.string.mo_risk_class_outbound)
        RiskClass.REMOTE -> context.getString(R.string.mo_risk_class_remote)
        RiskClass.MONEY -> context.getString(R.string.mo_risk_class_money)
        RiskClass.INSTALL -> context.getString(R.string.mo_risk_class_install)
        RiskClass.SAFE -> ""
    }

    private fun warningLabel(context: Context, warning: String): String = when {
        warning.startsWith("wipes ") -> context.getString(R.string.mo_risk_warn_wipes, warning.removePrefix("wipes "))
        warning == "disk-level operation" -> context.getString(R.string.mo_risk_warn_disk)
        warning == "writes to a block device" -> context.getString(R.string.mo_risk_warn_disk)
        warning == "system-level command" -> context.getString(R.string.mo_risk_warn_system)
        warning == "world-writable permissions" -> context.getString(R.string.mo_risk_warn_perms)
        warning == "force push" -> context.getString(R.string.mo_risk_warn_force_push)
        warning == "fork bomb" -> context.getString(R.string.mo_risk_warn_fork_bomb)
        warning.startsWith("pipes a download") -> context.getString(R.string.mo_risk_warn_pipe_sh)
        else -> warning
    }
}
