package com.aasra.companion.ui.models

import com.aasra.models.ModelDownloader
import com.aasra.models.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ModelPhase { CHECKING, WAITING, DOWNLOADING, VERIFYING, DONE, FAILED, PAUSED }

internal data class LocalModelFiles(val installed: Boolean, val partialBytes: Long)

internal data class ModelRowUi(
    val entry: ModelRegistry.Entry,
    val phase: ModelPhase = ModelPhase.CHECKING,
    val doneBytes: Long = 0,
    val error: String? = null,
) {
    val progress: Float get() = (doneBytes.toFloat() / entry.sizeBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
}

internal data class ModelPanelState(
    val rows: List<ModelRowUi>,
    val busy: Boolean = false,
    val stopping: Boolean = false,
    val revision: Int = 0,
) {
    val allDone: Boolean get() = rows.isNotEmpty() && rows.all { it.phase == ModelPhase.DONE }
}

// A departing composition can still be closing sockets or hashing a file when its
// replacement appears. Hold the process-wide gate until that work has really exited.
private val modelWorkMutex = Mutex()

/** Owned by a Main-dispatcher UI scope; disposal cancels it, never deletes files. */
internal class ModelDownloadController(
    entries: List<ModelRegistry.Entry>,
    private val scope: CoroutineScope,
    private val inspect: suspend (ModelRegistry.Entry) -> LocalModelFiles,
    private val download: suspend (ModelRegistry.Entry, Boolean, (Long, Long) -> Unit) -> ModelDownloader.Result,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(ModelPanelState(entries.map { ModelRowUi(it) }))
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun refresh(): Job = runWork {
        for (row in state.value.rows) {
            update(row.entry) { it.copy(phase = ModelPhase.CHECKING, error = null) }
            try {
                val files = withContext(ioDispatcher) { inspect(row.entry) }
                update(row.entry) {
                    it.copy(
                        phase = when {
                            files.installed -> ModelPhase.DONE
                            files.partialBytes > 0 -> ModelPhase.PAUSED
                            else -> ModelPhase.WAITING
                        },
                        doneBytes = if (files.installed) row.entry.sizeBytes
                            else files.partialBytes.coerceIn(0, row.entry.sizeBytes),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update(row.entry) { it.copy(phase = ModelPhase.FAILED, error = error.message) }
            }
        }
        // An explicit recheck also retries native loading even if files did not change.
        mutableState.value = state.value.copy(revision = state.value.revision + 1)
    }

    fun start(entries: List<ModelRegistry.Entry>, wifiOnly: Boolean): Job = runWork {
        for (entry in entries.distinctBy { it.fileName }) {
            if (state.value.rows.none { it.entry == entry && it.phase != ModelPhase.DONE }) continue
            update(entry) { it.copy(phase = ModelPhase.CHECKING, error = null) }
            try {
                val result = coroutineScope {
                    // IO callbacks never touch Compose state. Drain the last progress
                    // event before publishing Done so a queued 100% cannot undo it.
                    val progress = Channel<Pair<Long, Long>>(Channel.CONFLATED)
                    val consumer = launch {
                        for ((done, total) in progress) {
                            update(entry) {
                                it.copy(
                                    doneBytes = done.coerceIn(0, entry.sizeBytes),
                                    phase = if (total > 0 && done >= total) ModelPhase.VERIFYING
                                        else ModelPhase.DOWNLOADING,
                                )
                            }
                        }
                    }
                    try {
                        download(entry, wifiOnly) { done, total -> progress.trySend(done to total) }
                    } finally {
                        progress.close()
                        consumer.join()
                    }
                }
                when (result) {
                    is ModelDownloader.Result.Done -> {
                        update(entry) { it.copy(phase = ModelPhase.DONE, doneBytes = entry.sizeBytes) }
                        mutableState.value = state.value.copy(revision = state.value.revision + 1)
                    }
                    is ModelDownloader.Result.Failed -> update(entry) {
                        it.copy(phase = ModelPhase.FAILED, error = result.reason)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update(entry) { it.copy(phase = ModelPhase.FAILED, error = error.message) }
            }
        }
    }

    fun cancel() {
        if (job?.isCompleted == false) {
            mutableState.value = state.value.copy(stopping = true)
            job?.cancel()
        }
    }

    suspend fun awaitIdle() { job?.join() }

    private fun runWork(block: suspend () -> Unit): Job {
        job?.takeUnless { it.isCompleted }?.let { return it }
        mutableState.value = state.value.copy(busy = true, stopping = false)
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                modelWorkMutex.withLock { block() }
            } finally {
                mutableState.value = state.value.copy(
                    busy = false,
                    stopping = false,
                    rows = state.value.rows.map {
                        if (it.phase in listOf(ModelPhase.CHECKING, ModelPhase.DOWNLOADING, ModelPhase.VERIFYING))
                            it.copy(phase = ModelPhase.PAUSED) else it
                    },
                )
            }
        }.also { job = it; it.start() }
    }

    private fun update(entry: ModelRegistry.Entry, transform: (ModelRowUi) -> ModelRowUi) {
        mutableState.value = state.value.copy(rows = state.value.rows.map {
            if (it.entry == entry) transform(it) else it
        })
    }
}
