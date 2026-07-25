package app.momoding.feature.outputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.momoding.core.data.FileChangeItem
import app.momoding.core.data.FileChangeRecord
import app.momoding.core.data.FileChangeRecordState
import app.momoding.core.data.FileChangeRepository
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.FileChangeSetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaskOutputsUiState(
    val loading: Boolean = true,
    val record: FileChangeRecord? = null,
    val sourceFolderName: String? = null,
    val corrupt: Boolean = false,
)

class TaskOutputsViewModel(
    private val taskId: String,
    repository: FileChangeRepository,
    private val folders: AuthorizedFoldersRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TaskOutputsUiState())
    val state: StateFlow<TaskOutputsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe(taskId, commitCallId = null).collect { source ->
                mutableState.value = when (source) {
                    FileChangeRecordState.Missing -> TaskOutputsUiState(loading = false)
                    FileChangeRecordState.Corrupt -> TaskOutputsUiState(
                        loading = false,
                        corrupt = true,
                    )
                    is FileChangeRecordState.Available -> TaskOutputsUiState(
                        loading = false,
                        record = source.record,
                        sourceFolderName = source.record.grantId?.let { grantId ->
                            try {
                                folders.folders().firstOrNull { it.grantId == grantId }
                                    ?.displayName
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                null
                            }
                        },
                    )
                }
            }
        }
    }

    class Factory(
        private val taskId: String,
        private val repository: FileChangeRepository,
        private val folders: AuthorizedFoldersRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TaskOutputsViewModel::class.java))
            return TaskOutputsViewModel(taskId, repository, folders) as T
        }
    }
}

@Composable
fun TaskOutputsRoute(
    taskId: String,
    repository: FileChangeRepository,
    folders: AuthorizedFoldersRepository,
    onBack: () -> Unit,
) {
    val viewModel: TaskOutputsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "task-outputs:$taskId",
        factory = TaskOutputsViewModel.Factory(taskId, repository, folders),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaskOutputsScreen(state, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskOutputsScreen(state: TaskOutputsUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outputs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = Modifier.testTag("task-outputs-screen"),
    ) { padding ->
        when {
            state.loading -> EmptyOutputs(
                title = "Loading outputs",
                body = "Reading the task's local file results.",
                modifier = Modifier.padding(padding),
            )
            state.corrupt -> EmptyOutputs(
                title = "Outputs unavailable",
                body = "The saved file result could not be verified.",
                modifier = Modifier.padding(padding),
            )
            state.record == null -> EmptyOutputs(
                title = "No file outputs yet",
                body = "Reviewed files created or changed by this task will appear here.",
                modifier = Modifier.padding(padding),
            )
            else -> OutputList(
                record = state.record,
                sourceFolderName = state.sourceFolderName,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun OutputList(
    record: FileChangeRecord,
    sourceFolderName: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("task-outputs-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        record.purpose,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        record.state.outputLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = record.state.outputColor(),
                        modifier = Modifier.testTag("task-outputs-state"),
                    )
                    record.failureCode?.let { failure ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            failure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item { SectionTitle("Files") }
        items(record.items, key = FileChangeItem::operationId) { item ->
            OutputFileRow(item)
        }
        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item { SectionTitle("Source scope") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        sourceFolderName ?: "Authorized mobile folder",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("task-outputs-source-folder"),
                    )
                    Text(
                        "Task-scoped Android SAF access",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputFileRow(item: FileChangeItem) {
    val name = item.afterName ?: item.beforeName ?: "File"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task-output-${item.operationId}")
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(item.kind.replace('_', ' '))
                    item.contentByteCount?.let { append(" · ").append(it).append(" bytes") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.resultState?.let { result ->
                Text(
                    result.replace('_', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result == "succeeded") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyOutputs(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun FileChangeSetState.outputLabel(): String = when (this) {
    FileChangeSetState.COMPLETED -> "Applied on this device"
    FileChangeSetState.PARTIALLY_FAILED -> "Applied with some failures"
    FileChangeSetState.FAILED -> "File changes failed"
    FileChangeSetState.REJECTED -> "Not applied"
    FileChangeSetState.CANCELLED -> "Cancelled"
    FileChangeSetState.EXPIRED -> "Expired before approval"
    FileChangeSetState.UNKNOWN -> "Final file state could not be verified"
    FileChangeSetState.PREPARED,
    FileChangeSetState.AWAITING_APPROVAL,
    -> "Waiting for your review"
    FileChangeSetState.APPROVED,
    FileChangeSetState.COMMITTING,
    -> "Applying on this device"
}

@Composable
private fun FileChangeSetState.outputColor() = when (this) {
    FileChangeSetState.FAILED,
    FileChangeSetState.PARTIALLY_FAILED,
    FileChangeSetState.UNKNOWN,
    -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}
