package app.momoding.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillSource
import app.momoding.core.extensions.ExtensionPackageCompatibility
import app.momoding.core.extensions.ExtensionPackageCompatibilityDiagnostic
import app.momoding.ui.components.MomodingTopBar

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ExtensionsScreen(
    state: ExtensionsUiState,
    onAction: (ExtensionsAction) -> Unit,
) {
    var credentialValue by remember(state.pendingCredentialBinding?.key()) {
        mutableStateOf("")
    }
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    val addEnabled = !state.importing &&
        state.busySkillName == null &&
        !state.importingExtensionPackage &&
        state.busyExtensionPackageId == null &&
        state.busyCredentialBindingKey == null
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            MomodingTopBar(
                title = "Skills & extensions",
                subtitle = "Instructions and tools for Momoding",
                onBack = { onAction(ExtensionsAction.Back) },
                actions = {
                    Button(
                        onClick = { addSheetOpen = true },
                        enabled = addEnabled,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 88.dp)
                            .testTag("add-skill-extension"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Add")
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("extensions-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { ExtensionsIntro() }
                state.notice?.let { notice ->
                    item {
                        NoticeCard(
                            notice = notice,
                            recoveryLabel = state.noticeRecovery?.label(),
                            onRecovery = { onAction(ExtensionsAction.UseNoticeRecovery) },
                            onDismiss = { onAction(ExtensionsAction.ClearNotice) },
                        )
                    }
                }
                state.compatibilityDiagnostic?.let { diagnostic ->
                    item {
                        CompatibilityDiagnosticCard(
                            diagnostic = diagnostic,
                            onChooseAnother = {
                                onAction(ExtensionsAction.ImportExtensionPackage)
                            },
                            onDismiss = { onAction(ExtensionsAction.ClearNotice) },
                        )
                    }
                }
                item { SectionHeading("Skills", "Instructions that teach Momoding how to work") }
                if (state.loading && state.skills.isEmpty()) {
                    item { LoadingCard("Loading Skills") }
                } else if (state.skills.isEmpty()) {
                    item { EmptySkillsCard() }
                } else {
                    items(state.skills, key = ExtensionSkillUiState::name) { skill ->
                        SkillCard(
                            skill = skill,
                            busy = state.busySkillName == skill.name,
                            controlsEnabled = !state.importing && state.busySkillName == null,
                            onEnabledChange = { enabled ->
                                onAction(ExtensionsAction.SetSkillEnabled(skill.name, enabled))
                            },
                            onDelete = {
                                onAction(ExtensionsAction.RequestDeleteSkill(skill.name))
                            },
                        )
                    }
                }
                item { SectionHeading("Extensions", "Tools installed on this phone") }
                if (state.packagesLoading && state.extensionPackages.isEmpty()) {
                    item { LoadingCard("Loading Extensions") }
                } else if (state.extensionPackages.isEmpty()) {
                    item { EmptyExtensionPackagesCard() }
                } else {
                    items(state.extensionPackages, key = ExtensionPackageUiState::id) { item ->
                        ExtensionPackageCard(
                            item = item,
                            busy = state.busyExtensionPackageId == item.id,
                            controlsEnabled = !state.importingExtensionPackage &&
                                state.busyExtensionPackageId == null &&
                                state.busyCredentialBindingKey == null,
                            busyCredentialBindingKey = state.busyCredentialBindingKey,
                            onToggleDetails = {
                                onAction(ExtensionsAction.ToggleExtensionPackageDetails(item.id))
                            },
                            onEnabledChange = { enabled ->
                                onAction(ExtensionsAction.SetExtensionPackageEnabled(item.id, enabled))
                            },
                            onDelete = {
                                onAction(ExtensionsAction.RequestDeleteExtensionPackage(item.id))
                            },
                            onBindCredential = { binding ->
                                onAction(ExtensionsAction.RequestBindCredential(
                                    binding.packageId,
                                    binding.slot,
                                    binding.origin,
                                ))
                            },
                            onRemoveCredential = { binding ->
                                onAction(ExtensionsAction.RemoveCredential(
                                    binding.packageId,
                                    binding.slot,
                                    binding.origin,
                                ))
                            },
                        )
                    }
                }
                item { AddOnsExplainer() }
            }
        }
    }

    if (addSheetOpen) {
        ModalBottomSheet(onDismissRequest = { addSheetOpen = false }) {
            AddContentSheet(
                onSkillFolder = {
                    addSheetOpen = false
                    onAction(ExtensionsAction.ImportSkillPackage)
                },
                onExtensionFolder = {
                    addSheetOpen = false
                    onAction(ExtensionsAction.ImportExtensionPackage)
                },
                onSingleSkill = {
                    addSheetOpen = false
                    onAction(ExtensionsAction.ImportSkill)
                },
            )
        }
    }

    val pendingDelete = state.pendingDeleteSkillName
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { onAction(ExtensionsAction.CancelDeleteSkill) },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Remove “$pendingDelete”?") },
            text = {
                Text(
                    "Momoding will delete its private copy. Your original file or folder will not be changed.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { onAction(ExtensionsAction.ConfirmDeleteSkill) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove Skill") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(ExtensionsAction.CancelDeleteSkill) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            },
        )
    }
    val pendingExtensionDelete = state.pendingDeleteExtensionPackageId
    if (pendingExtensionDelete != null) {
        val label = state.extensionPackages.firstOrNull { it.id == pendingExtensionDelete }?.name
            ?: pendingExtensionDelete
        AlertDialog(
            onDismissRequest = { onAction(ExtensionsAction.CancelDeleteExtensionPackage) },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Remove Extension package?") },
            text = {
                Text(
                    "“$label” will be disabled and removed from this phone. New Tool calls will be blocked; the original folder is unchanged.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { onAction(ExtensionsAction.ConfirmDeleteExtensionPackage) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(ExtensionsAction.CancelDeleteExtensionPackage) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            },
        )
    }
    state.pendingCredentialBinding?.let { binding ->
        AlertDialog(
            onDismissRequest = {
                credentialValue = ""
                onAction(ExtensionsAction.CancelBindCredential)
            },
            title = { Text(if (binding.bound) "Replace bearer credential?" else "Add bearer credential") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${binding.slot} is only sent by Android to ${binding.origin}. " +
                            "The value is encrypted in Android Vault and will not be shown again.",
                    )
                    OutlinedTextField(
                        value = credentialValue,
                        onValueChange = { if (it.length <= 4_096) credentialValue = it },
                        label = { Text("Bearer token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("extension-credential-value"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val submitted = credentialValue
                        credentialValue = ""
                        onAction(ExtensionsAction.BindCredential(submitted))
                    },
                    enabled = credentialValue.isNotEmpty(),
                    modifier = Modifier.testTag("extension-credential-save"),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    credentialValue = ""
                    onAction(ExtensionsAction.CancelBindCredential)
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ExtensionsIntro() {
    Text(
        "Skills teach Momoding how to approach a task. Extensions add tools it can call. " +
            "You stay in control of what is installed and turned on.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AddContentSheet(
    onSkillFolder: () -> Unit,
    onExtensionFolder: () -> Unit,
    onSingleSkill: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Add to Momoding", style = MaterialTheme.typography.titleLarge)
        Text(
            "Momoding copies the selected content once into private app storage. " +
                "It does not change the original or keep access to the folder after import.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddOptionButton(
            icon = Icons.Outlined.FolderOpen,
            title = "Skill folder",
            detail = "Recommended · Includes SKILL.md and its local resources",
            testTag = "add-skill-folder",
            onClick = onSkillFolder,
        )
        AddOptionButton(
            icon = Icons.Outlined.Extension,
            title = "Extension folder",
            detail = "Adds tools · Installed off until you review it",
            testTag = "add-extension-folder",
            onClick = onExtensionFolder,
        )
        AddOptionButton(
            icon = Icons.Outlined.Description,
            title = "Single SKILL.md",
            detail = "Compatibility option · Does not include local resources",
            testTag = "add-single-skill",
            onClick = onSingleSkill,
        )
        Text(
            "Skill scripts are installed as readable resources and never run automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddOptionButton(
    icon: ImageVector,
    title: String,
    detail: String,
    testTag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag(testTag),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddOnsExplainer() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "About add-ons",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Skills are instructions. Extensions add executable tools and stay off after a new install or update until you review them. Android permissions are still requested when a tool needs them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.semantics { heading() }) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillCard(
    skill: ExtensionSkillUiState,
    busy: Boolean,
    controlsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val available = skill.availability == SkillAvailability.AVAILABLE
    val status = when {
        busy -> "Saving"
        skill.availability == SkillAvailability.UNAVAILABLE -> "Needs attention"
        skill.availability == SkillAvailability.ERROR -> "Problem"
        skill.enabled -> "On"
        else -> "Off"
    }
    val source = when {
        skill.source == SkillSource.BUNDLED -> "Built in"
        skill.packageFileCount > 1 -> "Imported · ${skill.packageFileCount} files"
        else -> "Imported · 1 file"
    }
    val issue = skillIssueCopy(skill)
    SectionCard(modifier = Modifier.testTag("skill-${skill.name}")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Extension, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$source · $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Switch(
                checked = skill.enabled && available,
                onCheckedChange = onEnabledChange,
                enabled = available && controlsEnabled,
                modifier = Modifier
                    .testTag("skill-toggle-${skill.name}")
                    .semantics {
                        role = Role.Switch
                        stateDescription = status
                        contentDescription = when {
                            busy -> "Saving ${skill.name}"
                            skill.availability == SkillAvailability.UNAVAILABLE ->
                                "${skill.name} needs attention"
                            skill.availability == SkillAvailability.ERROR ->
                                "${skill.name} has a problem"
                            skill.enabled -> "Turn off ${skill.name}"
                            else -> "Turn on ${skill.name}"
                        }
                    },
            )
        }
        if (!available && issue != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(issue.title, style = MaterialTheme.typography.labelLarge)
                    Text(
                        issue.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (skill.source == SkillSource.IMPORTED) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = onDelete,
                enabled = controlsEnabled,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp)
                    .testTag("delete-skill-${skill.name}"),
            ) { Text("Remove") }
        }
    }
}

private data class SkillIssueCopy(val title: String, val detail: String)

private fun skillIssueCopy(skill: ExtensionSkillUiState): SkillIssueCopy? = when {
    skill.availability == SkillAvailability.AVAILABLE -> null
    skill.diagnosticCode == "RELATIVE_DEPENDENCY_UNSUPPORTED" -> SkillIssueCopy(
        title = "Add the whole Skill folder",
        detail = "This SKILL.md refers to other files. Remove this copy, then use Add → Skill folder.",
    )
    skill.availability == SkillAvailability.UNAVAILABLE && skill.source == SkillSource.IMPORTED -> SkillIssueCopy(
        title = "This Skill can’t be turned on",
        detail = "Remove it, then add its whole Skill folder again.",
    )
    skill.availability == SkillAvailability.UNAVAILABLE -> SkillIssueCopy(
        title = "This built-in Skill can’t be turned on",
        detail = "Restart Momoding. If the problem remains, open Help & diagnostics.",
    )
    skill.source == SkillSource.IMPORTED -> SkillIssueCopy(
        title = "This Skill couldn’t be loaded",
        detail = "Remove it, then add a valid Skill again.",
    )
    else -> SkillIssueCopy(
        title = "This built-in Skill couldn’t be loaded",
        detail = "Restart Momoding. If the problem remains, open Help & diagnostics.",
    )
}

@Composable
private fun ExtensionPackageCard(
    item: ExtensionPackageUiState,
    busy: Boolean,
    controlsEnabled: Boolean,
    busyCredentialBindingKey: String?,
    onToggleDetails: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onBindCredential: (ExtensionCredentialUiState) -> Unit,
    onRemoveCredential: (ExtensionCredentialUiState) -> Unit,
) {
    var moreMenuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var developerDetailsOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    val status = when {
        busy -> "Saving"
        item.enabled -> "On"
        else -> "Off"
    }
    val missingCredentialCount = item.credentialBindings.count { !it.bound }
    SectionCard(modifier = Modifier.testTag("extension-package-${item.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Extension, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v${item.version} · $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (missingCredentialCount > 0) {
                    Text(
                        if (missingCredentialCount == 1) {
                            "1 credential is not configured"
                        } else {
                            "$missingCredentialCount credentials are not configured"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = onEnabledChange,
                enabled = controlsEnabled && item.activationAvailable,
                modifier = Modifier
                    .testTag("extension-toggle-${item.id}")
                    .semantics {
                        role = Role.Switch
                        stateDescription = status
                        contentDescription = when {
                            busy -> "Saving ${item.name}"
                            item.enabled -> "Turn off ${item.name}"
                            else -> "Turn on ${item.name}"
                        }
                    },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier.heightIn(min = 48.dp).testTag("review-extension-${item.id}"),
            ) {
                Text(if (item.expanded) "Close review" else "Review")
            }
            Box {
                IconButton(
                    onClick = { moreMenuOpen = true },
                    enabled = controlsEnabled,
                    modifier = Modifier.testTag("extension-more-${item.id}"),
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${item.name}")
                }
                DropdownMenu(
                    expanded = moreMenuOpen,
                    onDismissRequest = { moreMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Remove extension") },
                        onClick = {
                            moreMenuOpen = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("remove-extension-${item.id}"),
                    )
                }
            }
        }
        if (item.expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailBlock(
                    "What it can do",
                    extensionPurposeSummary(item),
                )
                DetailBlock(
                    "Phone access",
                    extensionPhoneAccessSummary(item),
                )
                DetailBlock(
                    "Internet access",
                    extensionInternetAccessSummary(item),
                )
                Text("Credentials", style = MaterialTheme.typography.labelMedium)
                if (item.credentialBindings.isEmpty()) {
                    Text(
                        "No credentials requested",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    item.credentialBindings.forEach { binding ->
                        val credentialBusy = busyCredentialBindingKey == binding.key()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${binding.slot} · ${if (binding.bound) "Saved" else "Not configured"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    binding.origin,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (credentialBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(
                                    onClick = { onBindCredential(binding) },
                                    enabled = controlsEnabled,
                                    modifier = Modifier.testTag("bind-credential-${item.id}-${binding.slot}"),
                                ) { Text(if (binding.bound) "Replace" else "Add") }
                                if (binding.bound) {
                                    TextButton(
                                        onClick = { onRemoveCredential(binding) },
                                        enabled = controlsEnabled,
                                        modifier = Modifier.testTag("remove-credential-${item.id}-${binding.slot}"),
                                    ) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Turning this on does not grant Android permissions. Momoding asks when a tool needs access.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { developerDetailsOpen = !developerDetailsOpen },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("extension-developer-details-${item.id}"),
                ) {
                    Text(if (developerDetailsOpen) "Hide developer details" else "Developer details")
                }
                if (developerDetailsOpen) {
                    ExtensionDeveloperDetails(item)
                }
            }
        }
    }
}

@Composable
private fun ExtensionDeveloperDetails(item: ExtensionPackageUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailBlock(
            "Tools",
            if (item.tools.isEmpty()) "None" else item.tools.joinToString("\n") { tool ->
                "${tool.name} · ${tool.type}" + (tool.targetTool?.let { " → $it" } ?: "")
            },
        )
        if (item.hostTools.isNotEmpty()) {
            DetailBlock(
                "Android Host Tools",
                item.hostTools.joinToString("\n") { host ->
                    "${host.name} → ${host.targetTool}" +
                        (host.capability?.let { " · $it" } ?: "")
                },
            )
        }
        DetailBlock(
            "Runtime",
            when (item.runtime) {
                "javascript-v1" -> "Isolated QuickJS"
                "pi-register-tool-v1" -> "Pi registerTool Mobile Profile · isolated Android Worker"
                else -> "Declarative · no package code executed"
            },
        )
        item.entrypoint?.let { DetailBlock("Entrypoint", it) }
        DetailBlock("Package", "${item.fileCount} files · Digest ${item.packageDigest.take(12)}…")
    }
}

internal fun extensionPurposeSummary(item: ExtensionPackageUiState): String =
    if (item.tools.isEmpty()) {
        "No callable tools declared"
    } else {
        item.tools.joinToString("\n") { tool ->
            val label = tool.label?.takeIf(String::isNotBlank) ?: tool.name
            "$label — ${tool.description}"
        }
    }

internal fun extensionPhoneAccessSummary(item: ExtensionPackageUiState): String {
    val targetTools = (
        item.tools.mapNotNull { it.targetTool } + item.hostTools.map { it.targetTool }
    ).distinct()
    if (
        item.requiredCapabilities.isEmpty() &&
        item.optionalCapabilities.isEmpty() &&
        targetTools.isEmpty()
    ) return "No phone access declared"
    return buildList {
        if (item.requiredCapabilities.isNotEmpty()) {
            add("Required: ${item.requiredCapabilities.joinToString { extensionAccessLabel(it) }}")
        }
        if (item.optionalCapabilities.isNotEmpty()) {
            add("Optional: ${item.optionalCapabilities.joinToString { extensionAccessLabel(it) }}")
        }
        val hostAccess = targetTools.map(::extensionHostToolLabel).distinct()
        if (hostAccess.isNotEmpty()) add("Tools: ${hostAccess.joinToString()}")
    }.joinToString("\n")
}

internal fun extensionInternetAccessSummary(item: ExtensionPackageUiState): String {
    val origins = item.httpPolicy.origins.ifEmpty { item.networkOrigins }
    if (origins.isEmpty()) return "No internet access"
    return if (item.runtime == "pi-register-tool-v1") {
        val methods = item.httpPolicy.methods
        if (methods.isEmpty()) {
            origins.joinToString("\n") { origin -> "$origin · No request methods declared" }
        } else {
            origins.joinToString("\n") { origin -> "$origin · ${methods.joinToString()}" }
        }
    } else {
        "Declared destinations (this runtime does not provide direct web requests):\n" +
            origins.joinToString("\n")
    }
}

internal fun extensionAccessLabel(value: String): String = when (value) {
    "saf_folders" -> "Files and folders you choose"
    "photo_library" -> "Photos and videos"
    "calendar" -> "Calendar"
    "contacts" -> "Contacts"
    "location" -> "Location"
    "notifications" -> "Momoding notifications"
    "accessibility_control" -> "On-screen content and controls"
    "screen_capture" -> "Screen capture"
    "all_files" -> "All files in shared storage"
    "shizuku_shell_uid" -> "Shizuku system access"
    else -> value.replace('_', ' ')
}

private fun extensionHostToolLabel(targetTool: String): String = when (targetTool) {
    "device_capabilities_get" -> "Phone capability status"
    "device_media_list" -> "Photo library"
    "device_calendar" -> "Calendar"
    "device_contacts" -> "Contacts"
    "device_location" -> "Location"
    "device_clipboard" -> "Clipboard"
    "device_notification" -> "Momoding notifications"
    "device_ui_inspect" -> "On-screen content"
    "device_ui_action" -> "On-screen controls"
    "device_packages_list", "device_package_inspect" -> "Installed app details"
    else -> targetTool.replace('_', ' ')
}

private fun ExtensionCredentialUiState.key(): String =
    "$packageId\u0000$packageDigest\u0000$slot\u0000$origin"

@Composable
private fun CompatibilityDiagnosticCard(
    diagnostic: ExtensionPackageCompatibilityDiagnostic,
    onChooseAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    var developerDetailsOpen by rememberSaveable(diagnostic.code) { mutableStateOf(false) }
    val copy = compatibilityCopy(diagnostic)
    SectionCard(modifier = Modifier.testTag("extension-compatibility-diagnostic")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                diagnostic.packageName ?: "Extension compatibility",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(copy.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Why", style = MaterialTheme.typography.labelMedium)
            Text(
                copy.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("What to do", style = MaterialTheme.typography.labelMedium)
            Text(copy.next, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { developerDetailsOpen = !developerDetailsOpen }) {
                    Text(if (developerDetailsOpen) "Hide developer details" else "Developer details")
                }
                Row {
                    TextButton(onClick = onChooseAnother) { Text("Choose another") }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            if (developerDetailsOpen) {
                Text(
                    "Diagnostic code: ${diagnostic.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class CompatibilityCopy(
    val status: String,
    val why: String,
    val next: String,
)

private fun compatibilityCopy(
    diagnostic: ExtensionPackageCompatibilityDiagnostic,
): CompatibilityCopy = when (diagnostic.code) {
    "EXTENSION_PACKAGE_MOBILE_BUILD_REQUIRED" -> CompatibilityCopy(
        status = "Needs a desktop build",
        why = "This folder contains Extension source code, not an installable Android package.",
        next = "Build it with Momoding’s trusted packer on a development computer, then add the generated folder.",
    )
    "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED" -> CompatibilityCopy(
        status = "Not supported on Android",
        why = "This Extension needs desktop features that Momoding does not provide on Android.",
        next = "Choose a mobile-ready Extension or ask its developer for an Android build.",
    )
    "EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED" -> CompatibilityCopy(
        status = "Needs a mobile integration",
        why = "This Extension uses a desktop Pi feature outside Momoding’s mobile Extension profile.",
        next = "Ask its developer for a Momoding-compatible build, or choose another Extension.",
    )
    "EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID",
    "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID" -> CompatibilityCopy(
        status = "Couldn’t verify this Extension",
        why = "Its mobile build information is missing or invalid.",
        next = "Choose a valid packaged Extension, or ask its developer to rebuild it.",
    )
    else -> CompatibilityCopy(
        status = when (diagnostic.compatibility) {
            ExtensionPackageCompatibility.DIRECT -> "Ready to import"
            ExtensionPackageCompatibility.BUILD_REQUIRED -> "Needs a desktop build"
            ExtensionPackageCompatibility.HOST_SHIM_REQUIRED -> "Needs a mobile integration"
            ExtensionPackageCompatibility.UNSUPPORTED -> "Not supported on Android"
            ExtensionPackageCompatibility.REJECTED -> "Couldn’t inspect this Extension"
        },
        why = diagnostic.detail,
        next = diagnostic.nextAction,
    )
}

@Composable
private fun DetailBlock(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyExtensionPackagesCard() {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("No Extension packages installed", style = MaterialTheme.typography.titleSmall)
            Text(
                "Import a built folder with momoding-extension.json, or select Pi source to get a compatibility diagnosis. Android never runs package scripts or source builds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun LoadingCard(label: String) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptySkillsCard() {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("No Skills added", style = MaterialTheme.typography.titleSmall)
            Text(
                "Use Add to choose a Skill folder. A single SKILL.md is also supported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeCard(
    notice: String,
    recoveryLabel: String?,
    onRecovery: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("extensions-notice")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                recoveryLabel?.let { label ->
                    TextButton(
                        onClick = onRecovery,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("extensions-notice-recovery"),
                    ) { Text(label) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Dismiss")
                }
            }
        }
    }
}

private fun ExtensionsNoticeRecovery.label(): String = when (kind) {
    ExtensionsNoticeRecoveryKind.ADD_SINGLE_SKILL -> "Choose file"
    ExtensionsNoticeRecoveryKind.ADD_SKILL_FOLDER -> "Choose folder"
    ExtensionsNoticeRecoveryKind.ADD_EXTENSION_FOLDER -> "Choose folder"
    ExtensionsNoticeRecoveryKind.REVIEW_EXTENSION -> "Review"
    ExtensionsNoticeRecoveryKind.ADD_CREDENTIAL -> "Try again"
    ExtensionsNoticeRecoveryKind.REMOVE_CREDENTIAL -> "Try again"
    ExtensionsNoticeRecoveryKind.CLEAN_PACKAGE_CREDENTIALS -> "Retry cleanup"
    ExtensionsNoticeRecoveryKind.REMOVE_EXTENSION -> "Try remove"
}
