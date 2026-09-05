package com.aasra.companion.ui.models

import com.aasra.models.ModelDownloader
import com.aasra.models.ModelRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadControllerTest {
    private val entry = ModelRegistry.QWEN_LOW_MEMORY

    @Test fun fullTransferIsVerifyingUntilDownloaderReturnsDone() = runBlocking {
        val transferred = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = ModelDownloadController(listOf(entry), this,
            inspect = { LocalModelFiles(false, 0) },
            download = { _, _, progress ->
                withContext(Dispatchers.IO) { progress(entry.sizeBytes, entry.sizeBytes) }
                transferred.complete(Unit)
                finish.await()
                ModelDownloader.Result.Done(File("verified"))
            })
        controller.refresh().join()
        controller.start(listOf(entry), true)
        transferred.await()
        yield()
        assertEquals(ModelPhase.VERIFYING, controller.state.value.rows.single().phase)
        assertFalse(controller.state.value.allDone)
        finish.complete(Unit)
        controller.awaitIdle()
        assertTrue(controller.state.value.allDone)
    }

    @Test fun installedScanRunsOffCallerThreadAndRetainsPartialBytes() = runBlocking {
        val caller = Thread.currentThread()
        val controller = ModelDownloadController(listOf(entry), this,
            inspect = {
                assertNotSame(caller, Thread.currentThread())
                LocalModelFiles(false, 42)
            }, download = { _, _, _ -> error("No automatic downloads") })
        controller.refresh().join()
        assertEquals(ModelPhase.PAUSED, controller.state.value.rows.single().phase)
        assertEquals(42L, controller.state.value.rows.single().doneBytes)
    }

    @Test fun duplicateTapsAndCancellationCannotOverlapTargetsAcrossPanels() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = ModelDownloadController(listOf(entry), this,
            inspect = { LocalModelFiles(false, 0) },
            download = { _, _, _ ->
                calls++
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        release.await()
                    }
                }
            })
        first.start(listOf(entry), true)
        first.start(listOf(entry), true)
        started.await()
        first.cancel()
        cleaningUp.await()
        first.start(listOf(entry), true)
        val second = ModelDownloadController(listOf(entry), this,
            inspect = { LocalModelFiles(false, 0) },
            download = { _, _, _ ->
                calls++
                ModelDownloader.Result.Done(File("verified"))
            })
        second.start(listOf(entry), true)
        yield()
        assertEquals(1, calls)
        release.complete(Unit)
        first.awaitIdle()
        second.awaitIdle()
        assertEquals(2, calls)
        assertEquals(ModelPhase.PAUSED, first.state.value.rows.single().phase)
    }

    @Test fun retryPreservesDiagnosticAndSkipsInstalledRows() = runBlocking {
        val other = ModelRegistry.SILERO_VAD
        var calls = 0
        val controller = ModelDownloadController(listOf(entry, other), this,
            inspect = { LocalModelFiles(it == other, 0) },
            download = { target, _, _ ->
                assertEquals(entry, target)
                calls++
                if (calls == 1) ModelDownloader.Result.Failed(target, "Size/encoding mismatch")
                else ModelDownloader.Result.Done(File("verified"))
            })
        controller.refresh().join()
        controller.start(listOf(entry, other), true)
        controller.awaitIdle()
        assertEquals("Size/encoding mismatch", controller.state.value.rows.first().error)
        controller.start(listOf(entry, other), true)
        controller.awaitIdle()
        assertEquals(2, calls)
        assertTrue(controller.state.value.allDone)
    }
}
