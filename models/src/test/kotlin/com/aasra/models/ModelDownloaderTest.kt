package com.aasra.models

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mock(Context::class.java)
    private val server = MockWebServer()
    private val bytes = "verified model bytes"
    private lateinit var entry: ModelRegistry.Entry
    private lateinit var downloader: ModelDownloader

    @Before
    fun setUp() {
        `when`(context.filesDir).thenReturn(temp.root)
        server.start()
        entry = ModelRegistry.Entry(
            "test.gguf", server.url("/model").toString(), bytes.length.toLong(),
            sha256(bytes), ModelRegistry.Tier.DEFAULT, "test",
        )
        downloader = ModelDownloader(context, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After fun tearDown() = server.close()

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun part(text: String): File = File(temp.root, "models/${entry.fileName}.part").apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun installed(text: String): File = ModelPaths.fileFor(context, entry).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun download() = runBlocking { downloader.download(entry, wifiOnly = false) }

    @Test fun completePartIsVerifiedAndAdoptedWithoutRequestingUnsatisfiableRange() {
        val part = part(bytes)
        server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */${bytes.length}"))

        assertTrue(download() is ModelDownloader.Result.Done)
        assertEquals(0, server.requestCount)
        assertFalse(part.exists())
        assertEquals(bytes, ModelPaths.fileFor(context, entry).readText())
    }

    @Test fun staleChecksumFailureCanRecoverExistingCompletePartAfterCatalogCorrection() {
        part(bytes)
        // The old app left these bytes after checking them against a Xet hash.
        server.enqueue(MockResponse().setResponseCode(416))
        assertTrue(download() is ModelDownloader.Result.Done)
        assertTrue(ModelPaths.isInstalled(context, entry))
    }

    @Test fun corruptCompletePartRestartsWithoutRange() {
        part("x".repeat(bytes.length))
        server.enqueue(MockResponse().setBody(bytes))

        assertTrue(download() is ModelDownloader.Result.Done)
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test fun partial416RestartsOnceInsteadOfPersistingTheRetryLoop() {
        part("old")
        server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */2"))
        server.enqueue(MockResponse().setBody(bytes))

        assertTrue(download() is ModelDownloader.Result.Done)
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun repeated416IsBoundedAndDoesNotPreserveBadPartial() {
        val part = part("old")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(416)) }

        assertTrue(download() is ModelDownloader.Result.Failed)
        assertFalse(part.exists())
        assertEquals(2, server.requestCount)
    }

    @Test fun resumesOnlyAtValidatedContentRange() {
        part(bytes.take(4))
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 4-${bytes.length - 1}/${bytes.length}")
            .setBody(bytes.drop(4)))

        assertTrue(download() is ModelDownloader.Result.Done)
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertEquals(bytes, ModelPaths.fileFor(context, entry).readText())
    }

    @Test fun invalidContentRangeNeverAppendsToPartial() {
        val part = part(bytes.take(4))
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 0-${bytes.length - 1}/${bytes.length}")
            .setBody(bytes))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = download()
        assertTrue(result is ModelDownloader.Result.Failed)
        assertEquals(bytes.take(4), part.readText())
        assertEquals(1, server.requestCount)
    }

    @Test fun ignoredRangeUsesSame200ResponseAndTruncatesPartial() {
        part(bytes.take(4))
        server.enqueue(MockResponse().setBody(bytes))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(download() is ModelDownloader.Result.Done)
        assertEquals(1, server.requestCount)
        assertEquals(bytes, ModelPaths.fileFor(context, entry).readText())
    }

    @Test fun secondChecksumFailureDeletesFullPartialBeforeNextUserRetry() {
        repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(bytes.length))) }
        val failed = download()
        assertTrue(failed is ModelDownloader.Result.Failed)
        assertTrue((failed as ModelDownloader.Result.Failed).reason.startsWith(ModelDownloader.CHECKSUM_PREFIX))
        assertFalse(File(temp.root, "models/${entry.fileName}.part").exists())
        assertFalse(ModelPaths.isInstalled(context, entry))
        assertEquals(2, server.requestCount)

        server.enqueue(MockResponse().setBody(bytes))
        assertTrue(download() is ModelDownloader.Result.Done)
        repeat(3) { assertNull(server.takeRequest().getHeader("Range")) }
    }

    @Test fun staleSizeFailsBeforeConsumingBodyEvenIfHashMatches() {
        entry = entry.copy(sizeBytes = entry.sizeBytes + 1)
        server.enqueue(MockResponse().setBody(bytes))

        val result = download()
        assertTrue(result is ModelDownloader.Result.Failed)
        assertFalse(ModelPaths.fileFor(context, entry).exists())
        assertEquals(1, server.requestCount)
    }

    @Test fun existingValidDownloadWorksWithoutWifi() {
        installed(bytes)
        assertTrue(runBlocking { downloader.download(entry) } is ModelDownloader.Result.Done)
        assertEquals(0, server.requestCount)
    }

    @Test fun readinessRejectsSameSizeCorruptionAndAcceptsCorrectedMetadata() {
        val file = installed("x".repeat(bytes.length))
        assertFalse(ModelPaths.isInstalled(context, entry))
        file.writeText(bytes)
        assertTrue(ModelPaths.isInstalled(context, entry))
        assertFalse(ModelPaths.isInstalled(context, entry.copy(sha256 = "0".repeat(64))))
    }

    @Test fun failedRenameNeverReportsDoneAndRetainsVerifiedPartial() {
        ModelPaths.fileFor(context, entry).apply { mkdirs(); resolve("occupied").writeText("x") }
        server.enqueue(MockResponse().setBody(bytes))

        assertTrue(download() is ModelDownloader.Result.Failed)
        assertEquals(bytes, File(temp.root, "models/${entry.fileName}.part").readText())
    }

    @Test fun cancellationAtLastProgressDoesNotInstallOrRetry() = runBlocking {
        server.enqueue(MockResponse().setBody(bytes))
        val job = launch {
            downloader.download(entry, wifiOnly = false) { done, total ->
                if (done == total) throw kotlinx.coroutines.CancellationException("left onboarding")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(ModelPaths.fileFor(context, entry).exists())
        assertEquals(bytes, File(temp.root, "models/${entry.fileName}.part").readText())
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationClosesStalledSocketAndKeepsResumableBytes() = runBlocking {
        val firstBytes = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setBody(bytes).throttleBody(4, 3, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) {
            downloader.download(entry, wifiOnly = false) { done, _ ->
                if (done > 0) firstBytes.complete(Unit)
            }
        }
        withTimeout(5_000) { firstBytes.await() }
        withTimeout(1_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
        assertFalse(ModelPaths.fileFor(context, entry).exists())
        assertEquals(bytes.take(4), File(temp.root, "models/${entry.fileName}.part").readText())
    }

    @Test fun interruptedResponseKeepsBytesForResume() {
        server.enqueue(MockResponse().setBody(bytes).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertTrue(download() is ModelDownloader.Result.Failed)
        val part = File(temp.root, "models/${entry.fileName}.part")
        assertTrue(part.length() in 1 until entry.sizeBytes)
        val offset = part.length().toInt()
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes $offset-${bytes.length - 1}/${bytes.length}")
            .setBody(bytes.drop(offset)))
        assertTrue(download() is ModelDownloader.Result.Done)
    }

    @Test fun unknownLengthResponseStillRequiresExactSizeAndHash() {
        server.enqueue(MockResponse().setChunkedBody(bytes, 3))
        assertTrue(download() is ModelDownloader.Result.Done)
        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        assertTrue(ModelPaths.isInstalled(context, entry))
    }

    @Test fun truncatedChunkedResponseRemainsResumableAndNotReady() {
        server.enqueue(MockResponse().setChunkedBody(bytes.take(4), 2))
        assertTrue(download() is ModelDownloader.Result.Failed)
        assertEquals(bytes.take(4), File(temp.root, "models/${entry.fileName}.part").readText())
        assertFalse(ModelPaths.isInstalled(context, entry))
    }

    @Test fun missingRangeHeaderIsRejectedWithoutTouchingPartial() {
        val part = part(bytes.take(4))
        server.enqueue(MockResponse().setResponseCode(206).setBody(bytes.drop(4)))
        assertTrue(download() is ModelDownloader.Result.Failed)
        assertEquals(bytes.take(4), part.readText())
    }

    @Test fun changedPartialContentGetsOneCleanChecksumRetry() {
        part("xxxx")
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 4-${bytes.length - 1}/${bytes.length}")
            .setBody(bytes.drop(4)))
        server.enqueue(MockResponse().setBody(bytes))
        assertTrue(download() is ModelDownloader.Result.Done)
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(bytes, ModelPaths.fileFor(context, entry).readText())
    }

    @Test fun completePartialCanBeRecoveredWithoutWifi() {
        part(bytes)
        assertTrue(runBlocking { downloader.download(entry) } is ModelDownloader.Result.Done)
        assertEquals(0, server.requestCount)
    }

    @Test fun noWifiDoesNotRequestMissingFile() {
        assertTrue(runBlocking { downloader.download(entry) } is ModelDownloader.Result.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidCatalogHashFailsBeforeNetworkOrDeletingExistingData() {
        val part = part(bytes)
        entry = entry.copy(sha256 = "SHA256_TBD")
        assertTrue(download() is ModelDownloader.Result.Failed)
        assertEquals(bytes, part.readText())
        assertEquals(0, server.requestCount)
    }

    @Test fun checksumScanHonorsCancellation() {
        val file = installed(bytes)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            ModelPaths.isVerifiedFile(file, entry) { throw kotlinx.coroutines.CancellationException("cancel hash") }
        }
    }

    @Test fun validArchiveAlreadyDownloadedInstallsOfflineAndKeepsReadinessMarker() {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(output)).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("voice/model.onnx").apply { size = bytes.length.toLong() })
            tar.write(bytes.toByteArray())
            tar.closeArchiveEntry()
        }
        val archive = output.toByteArray()
        entry = entry.copy(
            fileName = "voice.tar.bz2", sizeBytes = archive.size.toLong(), installDirectory = "voice",
            sha256 = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) },
        )
        val file = ModelPaths.downloadFileFor(context, entry).apply { parentFile!!.mkdirs(); writeBytes(archive) }
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        assertTrue(runBlocking { downloader.download(entry) } is ModelDownloader.Result.Done)
        assertEquals(0, server.requestCount)
        assertFalse(file.exists())
        assertEquals(bytes, ModelPaths.fileFor(context, entry).resolve("model.onnx").readText())
        assertTrue(ModelPaths.isInstalled(context, entry))
        assertFalse(ModelPaths.isInstalled(context, entry.copy(sha256 = "0".repeat(64))))
    }

    @Test fun corruptAdbPushedModelIsNotAdoptedOrReportedReady() {
        val external = temp.newFolder("external")
        `when`(context.getExternalFilesDir(null)).thenReturn(external)
        val entry = ModelRegistry.STT_ZIPFORMER_TOKENS
        File(external, "models/${entry.fileName}").apply {
            parentFile!!.mkdirs()
            writeText("x".repeat(entry.sizeBytes.toInt()))
        }

        assertEquals(0, ModelPaths.adoptAdbPushedFiles(context))
        assertFalse(ModelPaths.fileFor(context, entry).exists())
    }
}
