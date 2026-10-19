package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.TouchApp // momoding
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.i18n.uppercaseForDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onRootfsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onHandsClick: () -> Unit = {}, // momoding: operating the phone's screen
    onRoutinesClick: () -> Unit = {}, // momoding: the routines list, on the first level
    onRulesClick: () -> Unit = {}, // momoding: remembered approvals
    onComputerClick: () -> Unit = {}, // momoding: its own computer (M14)
    onModelsClick: () -> Unit = {}, // momoding: which model thinks, which one sees
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    /** [T-p2-agent-settings] Settings › Agents (delegation on/off), below Memory. */
    onAgentsClick: () -> Unit = {},
    onAgentToolsClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    // Hook accepted for forward-compat with AppNavigation's About route. The
    // About row below still has a TODO onClick in HEAD; future settings-bucket
    // work will wire this through.
    onAboutClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var showFeedbackSheet by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // momoding: the first level is what a person needs; the rest is under Advanced
            var showAdvanced by rememberSaveable { mutableStateOf(false) }
            // prototype pSettings: 模型 · 手机权限 · 先问你, then the rest
            val momodingPerms = app.momoding.ui.settings.rememberPhonePermissions()
            SettingsSection(title = stringResource(R.string.mo_settings_models)) {
                SettingsItem(icon = Icons.Outlined.Lock, iconColor = Color(0xFF34C759),
                    title = app.momoding.ui.settings.currentModelLabel(context) ?: stringResource(R.string.mo_settings_models_none),
                    subtitle = stringResource(R.string.mo_settings_models_change), onClick = onModelsClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.mo_settings_phone)) {
                SettingsItem(icon = Icons.Outlined.TouchApp, iconColor = Color(0xFF2E7A12), title = stringResource(R.string.mo_hands_title),
                    subtitle = stringResource(if (app.momoding.hands.Hands.enabled(context)) R.string.mo_hands_on else R.string.mo_hands_off), onClick = onHandsClick)
                momodingPerms.forEachIndexed { i, p ->
                    SettingsItem(icon = p.icon, iconColor = if (p.granted) Color(0xFF34C759) else Color(0xFF8E8E93),
                        title = stringResource(p.title),
                        subtitle = stringResource(p.purpose) + " · " + stringResource(if (p.granted) R.string.mo_perm_state_on else R.string.mo_perm_state_off),
                        onClick = { app.momoding.ui.settings.openAppPermissionSettings(context, p) }, showDivider = i < momodingPerms.lastIndex)
                }
            }
            SettingsSection(title = stringResource(R.string.mo_settings_ask_first)) {
                val ruleCount = app.momoding.guard.Grants.always.collectAsState().value.size
                SettingsItem(icon = Icons.Outlined.Shield, iconColor = Color(0xFF007AFF), title = stringResource(R.string.mo_settings_rules_page),
                    subtitle = if (ruleCount == 0) stringResource(R.string.mo_settings_rules_none) else stringResource(R.string.mo_settings_rules_count, ruleCount),
                    onClick = onRulesClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.app_name)) {
                SettingsItem(icon = Icons.Outlined.Computer, iconColor = Color(0xFF34C759), title = stringResource(R.string.mo_computer_title),
                    subtitle = stringResource(R.string.mo_computer_row_sub), onClick = onComputerClick)
                SettingsItem(icon = Icons.Outlined.Schedule, iconColor = Color(0xFF5856D6), title = stringResource(R.string.mo_settings_routines),
                    subtitle = stringResource(R.string.mo_settings_routines_sub), onClick = onRoutinesClick)
                SettingsItem(icon = Icons.Outlined.BatteryFull, iconColor = Color(0xFFFF9500), title = stringResource(R.string.mo_settings_background),
                    subtitle = stringResource(R.string.mo_settings_background_sub), onClick = onBackgroundClick)
                SettingsItem(icon = Icons.Outlined.AutoAwesome, iconColor = Color(0xFFAF52DE), title = stringResource(R.string.mo_settings_about_bot),
                    subtitle = stringResource(R.string.mo_settings_about_bot_sub), onClick = onSoulClick)
                SettingsItem(icon = Icons.Outlined.Palette, iconColor = Color(0xFFFF2D55), title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.mo_settings_appearance_sub), onClick = onAppearanceClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.mo_settings_more)) {
                SettingsItem(icon = Icons.Outlined.Handyman, iconColor = Color(0xFF8E8E93), title = stringResource(R.string.mo_settings_advanced),
                    subtitle = stringResource(if (showAdvanced) R.string.mo_settings_advanced_hide else R.string.mo_settings_advanced_sub),
                    onClick = { showAdvanced = !showAdvanced }, showDivider = false)
            }
            if (showAdvanced) {
            // momoding: Advanced in Momoding's words — the same pages as upstream, grouped by what a
            // person is after; what the first level already has (appearance, background, its
            // manner) is not repeated here
            SettingsSection(title = stringResource(R.string.mo_adv_models)) {
                SettingsItem(icon = Icons.Outlined.Lock, iconColor = Color(0xFF007AFF), title = stringResource(R.string.mo_adv_providers),
                    subtitle = stringResource(R.string.mo_adv_providers_sub), onClick = onProvidersClick)
                SettingsItem(icon = Icons.Outlined.Settings, iconColor = Color(0xFF5856D6), title = stringResource(R.string.mo_adv_groups),
                    subtitle = stringResource(R.string.mo_adv_groups_sub), onClick = onModelGroupsClick)
                SettingsItem(icon = Icons.Outlined.BarChart, iconColor = Color(0xFF34C759), title = stringResource(R.string.mo_adv_usage),
                    subtitle = stringResource(R.string.mo_adv_usage_sub), onClick = onUsageClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.mo_adv_abilities)) {
                SettingsItem(icon = Icons.Outlined.Extension, iconColor = Color(0xFFFF9500), title = stringResource(R.string.mo_adv_skills),
                    subtitle = stringResource(R.string.mo_adv_skills_sub), onClick = onSkillsClick)
                SettingsItem(icon = Icons.Outlined.Handyman, iconColor = Color(0xFF8E8E93), title = stringResource(R.string.mo_adv_tools),
                    subtitle = stringResource(R.string.mo_adv_tools_sub), onClick = onAgentToolsClick)
                SettingsItem(icon = Icons.Outlined.Dashboard, iconColor = Color(0xFF5AC8FA), title = stringResource(R.string.mo_adv_services),
                    subtitle = stringResource(R.string.mo_adv_services_sub), onClick = onMcpClick)
                SettingsItem(icon = Icons.Outlined.Groups, iconColor = Color(0xFFAF52DE), title = stringResource(R.string.mo_adv_helpers),
                    subtitle = stringResource(R.string.mo_adv_helpers_sub), onClick = onAgentsClick)
                SettingsItem(icon = Icons.Outlined.Psychology, iconColor = Color(0xFFFF2D55), title = stringResource(R.string.mo_adv_memory),
                    subtitle = stringResource(R.string.mo_adv_memory_sub), onClick = onMemoryClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.mo_computer_title)) {
                SettingsItem(icon = Icons.Outlined.Storage, iconColor = Color(0xFF34C759), title = stringResource(R.string.mo_adv_linux),
                    subtitle = stringResource(R.string.mo_adv_linux_sub), onClick = onRootfsClick)
                SettingsItem(icon = Icons.Outlined.Terminal, iconColor = Color(0xFF1C1C1E), title = stringResource(R.string.mo_adv_terminal),
                    subtitle = stringResource(R.string.mo_adv_terminal_sub), onClick = onTerminalClick)
                SettingsItem(icon = Icons.Outlined.FolderShared, iconColor = Color(0xFF007AFF), title = stringResource(R.string.mo_adv_shared),
                    subtitle = stringResource(R.string.mo_adv_shared_sub), onClick = onSharedFoldersClick)
                SettingsItem(icon = Icons.Outlined.Folder, iconColor = Color(0xFFFF9500), title = stringResource(R.string.mo_adv_mounts),
                    subtitle = stringResource(R.string.mo_adv_mounts_sub), onClick = onMountedFoldersClick)
                SettingsItem(icon = Icons.Outlined.AutoAwesome, iconColor = Color(0xFF5856D6), title = stringResource(R.string.mo_adv_env),
                    subtitle = stringResource(R.string.mo_adv_env_sub), onClick = onEnvVarsClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.mo_adv_data)) {
                SettingsItem(icon = Icons.Outlined.Backup, iconColor = Color(0xFF34C759), title = stringResource(R.string.mo_adv_backup),
                    subtitle = stringResource(R.string.mo_adv_backup_sub), onClick = onBackupClick)
                SettingsItem(icon = Icons.Outlined.Description, iconColor = Color(0xFF8E8E93), title = stringResource(R.string.mo_adv_logs),
                    subtitle = stringResource(R.string.mo_adv_logs_sub), onClick = onLogsClick)
                SettingsItem(icon = Icons.Outlined.Shield, iconColor = Color(0xFF007AFF), title = stringResource(R.string.mo_adv_permissions),
                    subtitle = stringResource(R.string.mo_adv_permissions_sub), onClick = onPermissionsClick, showDivider = false)
            }
            SettingsSection(title = stringResource(R.string.mo_adv_about)) {
                SettingsItem(icon = Icons.Outlined.Info, iconColor = Color(0xFF8E8E93), title = stringResource(R.string.mo_adv_version),
                    subtitle = stringResource(R.string.mo_adv_version_sub), onClick = onAboutClick)
                SettingsItem(icon = Icons.Outlined.Shield, iconColor = Color(0xFF8E8E93), title = stringResource(R.string.settings_privacy_policy),
                    subtitle = null, onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") })
                SettingsItem(icon = Icons.Outlined.Feedback, iconColor = Color(0xFF8E8E93), title = stringResource(R.string.settings_feedback),
                    subtitle = null, onClick = { showFeedbackSheet = true }, showDivider = false)
            }
            } // momoding: end of Advanced

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(onDismissRequest = { showFeedbackSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_submit_github_issues),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = stringResource(R.string.settings_feedback_telegram),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, "https://t.me/+2NzhOJuzRyI1YmM1")
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.settings_feedback_email),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildFeedbackMailto())
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/OpenMinis/OpenMinis/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | Minis Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    return "https://github.com/OpenMinis/OpenMinis/issues/new" +
        "?template=bug_report.md" +
        "&title=$title" +
        "&body=$encodedBody"
}

/**
 * Compose a `mailto:` URL with a prefilled subject and body that include
 * app version, Android version, and device model. Mirrors iOS
 * `ContentView.makeFeedbackEmailURL()`.
 */
private fun buildFeedbackMailto(): String {
    val body = """
        Please describe your feedback:


        ---
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
        Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}

        Screenshot (optional): Please attach a screenshot if relevant.
    """.trimIndent()
    val subject = java.net.URLEncoder.encode("Minis Feedback", "UTF-8")
    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    return "mailto:dev@openminis.app?subject=$subject&body=$encodedBody"
}

/**
 * A grouped settings section with header and optional footer, matching iOS grouped List sections.
 */
@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        // Section header
        Text(
            text = title.uppercaseForDisplay(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // Section card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }

        // Section footer
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * A single settings row item with colored icon, title, optional subtitle, and chevron.
 * Styled to match iOS settings rows with SF Symbol-like colored circle icons.
 */
@Composable
private fun SettingsItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored circle icon (matching iOS settings style)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = iconColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            // Title + subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Chevron
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }

        // Divider between items (inset to match icon alignment)
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

