package com.aasra.companion.ui.tools

import com.aasra.companion.mcp.McpCallApproval
import com.aasra.companion.mcp.McpClient
import com.aasra.companion.mcp.McpError
import com.aasra.companion.mcp.McpException
import com.aasra.companion.mcp.McpTool
import com.aasra.companion.mcp.McpToolResult
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ExternalToolsState(
    val endpointDraft: String = "",
    val connectedEndpoint: String? = null,
    val tools: List<McpTool> = emptyList(),
    val selectedName: String? = null,
    val arguments: String = "{}",
    val approval: McpCallApproval? = null,
    val lastCall: McpCallApproval? = null,
    val result: McpToolResult? = null,
    val error: McpException? = null,
    val busy: Boolean = false,
)

/** UI-thread confined; callbacks run in the supplied UI scope. No saved state or automatic connection. */
internal class ExternalToolsController(
    private val scope: CoroutineScope,
    private val createClient: (String) -> McpClient = { McpClient(it) },
) : Closeable {
    private val mutableState = MutableStateFlow(ExternalToolsState())
    val state = mutableState.asStateFlow()
    private var client: McpClient? = null
    private var job: Job? = null
    private var generation = 0
    private var closed = false

    fun setEndpoint(value: String) {
        if (closed) return
        // Keep an over-limit sentinel character: never turn a long paste into a valid truncated URL.
        reset(value.take(2049))
    }

    fun forget() { if (!closed) reset("") }

    private fun reset(endpoint: String) {
        generation++
        job?.cancel()
        client?.close()
        client = null
        mutableState.value = ExternalToolsState(endpointDraft = endpoint)
    }

    fun discover(): Job? {
        if (closed || state.value.busy) return null
        val endpoint = state.value.endpointDraft
        reset(endpoint)
        return runAction {
            val connection = createClient(endpoint)
            client = connection
            try {
                val tools = connection.discoverTools()
                mutableState.value = state.value.copy(connectedEndpoint = connection.endpoint, tools = tools)
            } catch (e: Exception) {
                connection.close()
                if (client === connection) client = null
                throw e
            }
        }
    }

    fun selectTool(name: String) {
        if (closed || state.value.busy || state.value.tools.none { it.name == name }) return
        cancelReview()
        mutableState.value = state.value.copy(selectedName = name, arguments = "{}", error = null, result = null, lastCall = null)
    }

    fun setArguments(value: String) {
        if (closed || state.value.busy) return
        cancelReview()
        mutableState.value = state.value.copy(arguments = value.take(McpClient.MAX_ARGUMENT_BYTES + 1), error = null, result = null, lastCall = null)
    }

    fun review(): Job? {
        val connection = client ?: return null
        val current = state.value
        val name = current.selectedName ?: return null
        return runAction {
            val approval = connection.prepareCall(name, current.arguments)
            mutableState.value = state.value.copy(approval = approval)
        }
    }

    fun cancelReview() {
        state.value.approval?.let { client?.discardApproval(it) }
        mutableState.value = state.value.copy(approval = null)
    }

    fun approve(approval: McpCallApproval): Job? {
        if (closed || state.value.busy || state.value.approval !== approval) return null
        val connection = client ?: return null
        mutableState.value = state.value.copy(approval = null, lastCall = approval, result = null)
        return runAction {
            val result = connection.callTool(approval)
            mutableState.value = state.value.copy(result = result)
        }
    }

    private fun runAction(action: suspend () -> Unit): Job? {
        if (closed || state.value.busy) return null
        val currentGeneration = generation
        mutableState.value = state.value.copy(busy = true, error = null)
        return scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == currentGeneration) {
                    val error = e as? McpException ?: McpException(McpError.PROTOCOL)
                    if (error.kind == McpError.SESSION_EXPIRED || error.kind == McpError.DISCONNECTED) {
                        client?.close()
                        client = null
                        mutableState.value = state.value.copy(connectedEndpoint = null, tools = emptyList(), selectedName = null, approval = null)
                    }
                    mutableState.value = state.value.copy(error = error)
                }
            } finally {
                if (generation == currentGeneration) mutableState.value = state.value.copy(busy = false)
            }
        }.also { job = it }
    }

    override fun close() { reset(""); closed = true }
}
