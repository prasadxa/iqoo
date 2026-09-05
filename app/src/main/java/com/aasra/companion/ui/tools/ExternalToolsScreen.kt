package com.aasra.companion.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aasra.companion.R
import com.aasra.companion.ui.components.PageHeader
import com.aasra.companion.mcp.McpError
import com.aasra.companion.mcp.mcpDisplayText

/** Optional caregiver-only advanced UI. The host owns navigation and the localized Material theme. */
@Composable
fun ExternalToolsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { ExternalToolsController(scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val state by controller.state.collectAsState()
    val back = { controller.close(); onBack() }
    BackHandler(onBack = back)
    ExternalToolsContent(state, controller, back)
}

@Composable
private fun ExternalToolsContent(state: ExternalToolsState, controller: ExternalToolsController, onBack: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        PageHeader(stringResource(R.string.external_tools_title), stringResource(R.string.external_tools_back), onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(stringResource(R.string.external_tools_simple_intro), style = MaterialTheme.typography.bodyMedium) }
            item {
                OutlinedTextField(
                    value = state.endpointDraft, onValueChange = controller::setEndpoint,
                    label = { Text(stringResource(R.string.external_tools_url)) },
                    supportingText = { Text(stringResource(R.string.external_tools_simple_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = !state.busy,
                    isError = state.error?.kind == McpError.INVALID_ENDPOINT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.connectedEndpoint != null || state.busy || state.endpointDraft.isNotEmpty()) {
                item {
                    TextButton(onClick = controller::forget, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Text(stringResource(R.string.external_tools_forget))
                    }
                }
            }
            state.connectedEndpoint?.let { endpoint ->
                item {
                    Text(stringResource(R.string.external_tools_connected, endpoint), style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text(stringResource(R.string.external_tools_tool_count, state.tools.size), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                }
                item { Text(stringResource(R.string.external_tools_metadata_warning), style = MaterialTheme.typography.bodyMedium) }
                items(state.tools, key = { it.name }) { tool ->
                    TextButton(
                        onClick = { controller.selectTool(tool.name) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Text(if (state.selectedName == tool.name) stringResource(R.string.external_tools_selected, tool.name) else tool.name)
                    }
                }
            }
            state.tools.singleOrNull { it.name == state.selectedName }?.let { tool ->
                item {
                    Text(tool.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
                tool.description?.let { description -> item { SelectionContainer { Text(mcpDisplayText(description)) } } }
                item { Text(stringResource(R.string.external_tools_schema), style = MaterialTheme.typography.titleMedium) }
                item { SelectionContainer { Text(mcpDisplayText(tool.inputSchemaJson), style = MaterialTheme.typography.bodySmall) } }
                if (tool.requiresTask) item { Text(stringResource(R.string.external_tools_tasks_unsupported), color = MaterialTheme.colorScheme.error) }
                item {
                    OutlinedTextField(
                        value = state.arguments, onValueChange = controller::setArguments,
                        label = { Text(stringResource(R.string.external_tools_arguments)) },
                        supportingText = { Text(stringResource(R.string.external_tools_arguments_help)) },
                        enabled = !state.busy, minLines = 4, maxLines = 12,
                        isError = state.error?.kind == McpError.INVALID_ARGUMENTS,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.busy) {
                item { Text(stringResource(R.string.external_tools_waiting), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            state.error?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(errorString(error.kind)), color = MaterialTheme.colorScheme.error)
                        if (error.detail.isNotBlank()) {
                            Text(stringResource(R.string.external_tools_error_detail, mcpDisplayText(error.detail)), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.lastCall != null) Text(stringResource(R.string.external_tools_unknown_outcome))
                    }
                }
            }
            state.result?.let { result ->
                item {
                    Text(stringResource(if (result.isError) R.string.external_tools_result_error else R.string.external_tools_result),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                }
                item { Text(stringResource(R.string.external_tools_result_warning)) }
                item {
                    val display = mcpDisplayText(result.json)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(display.take(32_768), style = MaterialTheme.typography.bodyMedium) }
                        if (display.length > 32_768) Text(stringResource(R.string.external_tools_truncated))
                    }
                }
            }
            item {
                TextButton(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.external_tools_info))
                }
            }
        }
        Button(onClick = { if (state.selectedName == null) controller.discover() else controller.review() },
            enabled = !state.busy && state.endpointDraft.isNotBlank() && state.tools.none { it.name == state.selectedName && it.requiresTask }, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(if (state.selectedName == null) R.string.external_tools_discover else R.string.external_tools_review))
        }
      }
    }
    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text(stringResource(R.string.external_tools_info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.external_tools_privacy))
                Text(stringResource(R.string.external_tools_url_help))
                Text(stringResource(R.string.external_tools_limits))
            }
        },
        confirmButton = { TextButton(onClick = { showInfo = false }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.home_close)) } },
    )

    state.approval?.let { approval ->
        AlertDialog(
            onDismissRequest = controller::cancelReview,
            title = { Text(stringResource(R.string.external_tools_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.external_tools_confirm_warning))
                    Text(stringResource(R.string.external_tools_destination), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(approval.endpoint) }
                    Text(stringResource(R.string.external_tools_tool_name), style = MaterialTheme.typography.titleSmall)
                    Text(approval.toolName)
                    Text(stringResource(R.string.external_tools_arguments), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(mcpDisplayText(approval.argumentsJson)) }
                    Text(stringResource(R.string.external_tools_confirm_exact))
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.approve(approval) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.external_tools_approve))
                }
            },
            dismissButton = {
                TextButton(onClick = controller::cancelReview, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.external_tools_cancel))
                }
            },
        )
    }
}

private fun errorString(error: McpError): Int = when (error) {
    McpError.INVALID_ENDPOINT -> R.string.external_tools_error_endpoint
    McpError.INVALID_ARGUMENTS -> R.string.external_tools_error_arguments
    McpError.APPROVAL_REQUIRED -> R.string.external_tools_error_approval
    McpError.DISCONNECTED -> R.string.external_tools_error_disconnected
    McpError.AUTH_UNSUPPORTED -> R.string.external_tools_error_auth
    McpError.SESSION_EXPIRED -> R.string.external_tools_error_session
    McpError.HTTP -> R.string.external_tools_error_http
    McpError.NETWORK -> R.string.external_tools_error_network
    McpError.PROTOCOL -> R.string.external_tools_error_protocol
    McpError.LIMIT -> R.string.external_tools_error_limit
    McpError.REMOTE -> R.string.external_tools_error_remote
}
