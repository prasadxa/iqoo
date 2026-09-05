package com.aasra.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Resumable model downloader (PLAN 6.3).
 *
 * - HTTP `Range` resume: a `.part` file keeps already-fetched bytes.
 * - Wi-Fi-only by default; pass `wifiOnly = false` for the hotspot override.
 * - Ongoing progress in a low-priority notification (survives screen-off
 *   alongside the foreground service).
 * - SHA-256 verified before the file is adopted; on mismatch the file is
 *   DELETED and downloaded once more from scratch, then the error surfaces
 *   so onboarding can show a retry prompt (never a silent corrupt model).
 */
class ModelDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    sealed interface Result {
        data class Done(val file: File) : Result
        /** Recoverable: caller should surface a spoken + visual retry prompt. */
        data class Failed(val entry: ModelRegistry.Entry, val reason: String) : Result
    }

    suspend fun download(
        entry: ModelRegistry.Entry,
        wifiOnly: Boolean = true,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        jobContext.ensureActive()
        if (entry.sizeBytes <= 0 || !entry.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            return@withContext Result.Failed(entry, "Invalid integrity metadata for ${entry.fileName}; update the model catalog.")
        }
        try {
            val installed = ModelPaths.fileFor(context, entry)
            if (entry.installDirectory == null &&
                ModelPaths.isVerifiedFile(installed, entry) { jobContext.ensureActive() }
            ) {
                return@withContext Result.Done(installed)
            }
            if (entry.installDirectory != null && ModelPaths.isInstalled(context, entry)) {
                return@withContext Result.Done(installed)
            }

            val download = ModelPaths.downloadFileFor(context, entry)
            val part = File(download.parent!!, download.name + PART_SUFFIX)
            var attempt = if (entry.installDirectory != null &&
                ModelPaths.isVerifiedFile(download, entry) { jobContext.ensureActive() }
            ) {
                Result.Done(download)
            } else {
                fetch(entry, part, wifiOnly, onProgress)
            }
            if (attempt is Result.Failed && attempt.reason.startsWith(CHECKSUM_PREFIX)) {
                notify(context, entry, "Checksum mismatch: downloading ${entry.fileName} again.")
                attempt = fetch(entry, part, wifiOnly, onProgress)
            }
            if (attempt is Result.Done && entry.installDirectory != null) {
                val result = installArchive(attempt.file, installed, entry)
                if (result is Result.Done) attempt.file.delete()
                result
            } else {
                attempt
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.Failed(entry, "Could not download ${entry.fileName}: ${error.message}")
        } finally {
            cancelProgress(entry)
        }
    }

    /**
     * Sequential batch download for the onboarding wizard (PLAN 6.3).
     *
     * Additive helper only: iterates [entries] in order through [download],
     * so Range resume, Wi-Fi gating, and checksum rules stay in one place.
     * Already-verified files short-circuit to [Result.Done] inside
     * [download], which is what makes the `adb push` fast-path free.
     */
    suspend fun downloadAll(
        entries: List<ModelRegistry.Entry> = ModelRegistry.DEFAULT_SET,
        wifiOnly: Boolean = true,
        onEntryProgress: (entry: ModelRegistry.Entry, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
        onEntryResult: (entry: ModelRegistry.Entry, result: Result) -> Unit = { _, _ -> },
    ): List<Pair<ModelRegistry.Entry, Result>> = withContext(Dispatchers.IO) {
        entries.map { entry ->
            val result = download(
                entry,
                wifiOnly,
                onProgress = { done, total -> onEntryProgress(entry, done, total) },
            )
            onEntryResult(entry, result)
            entry to result
        }
    }

    private suspend fun fetch(
        entry: ModelRegistry.Entry,
        part: File,
        wifiOnly: Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Result = coroutineScope {
        part.parentFile?.mkdirs()
        // Recover full downloads left by cancellation or an older catalog before asking for Range.
        if (part.isFile && part.length() >= entry.sizeBytes) {
            if (ModelPaths.isVerifiedFile(part, entry) { ensureActive() }) {
                return@coroutineScope promote(entry, part)
            }
            check(part.delete()) { "Could not remove invalid partial ${part.name}." }
        }
        if (wifiOnly && !isWifiConnected()) {
            return@coroutineScope Result.Failed(entry, "Waiting for Wi-Fi to download ${entry.fileName}.")
        }
        var rangeReset = false
        while (true) {
            ensureActive()
            val resumeFrom = if (part.isFile) part.length() else 0L
            val request = Request.Builder().url(entry.url)
                .header("Accept-Encoding", "identity")
                .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
                .build()
            val call = client.newCall(request)
            // execute() and body reads block; cancellation must close the socket, not just set a flag.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            var restart = false
            try {
                call.execute().use { response ->
                    if (response.code == 416 && resumeFrom > 0 && !rangeReset) {
                        check(part.delete()) { "Could not reset partial ${part.name}." }
                        rangeReset = true
                        restart = true
                        return@use
                    }
                    if (response.code != 200 && response.code != 206) {
                        return@coroutineScope Result.Failed(entry, "HTTP ${response.code} for ${entry.fileName}.")
                    }
                    val body = response.body ?: return@coroutineScope Result.Failed(entry, "Empty response for ${entry.fileName}.")
                    val offset = if (response.code == 206) resumeFrom else 0L
                    val range = response.header("Content-Range")?.let {
                        Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it)?.groupValues
                            ?.drop(1)?.map { value -> value.toLongOrNull() }
                    }
                    if (response.code == 206 && (range == null || range.any { it == null } ||
                            range[0] != offset || range[2] != entry.sizeBytes ||
                            range[1]!! < offset || range[1]!! >= entry.sizeBytes)
                    ) {
                        return@coroutineScope Result.Failed(entry, "Invalid Content-Range for ${entry.fileName}; model metadata or server response changed.")
                    }
                    val expectedBody = if (response.code == 206) range!![1]!! - offset + 1 else entry.sizeBytes
                    if ((body.contentLength() >= 0 && body.contentLength() != expectedBody) ||
                        response.header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true
                    ) {
                        return@coroutineScope Result.Failed(entry, "Size/encoding mismatch for ${entry.fileName}; update the model catalog or retry later.")
                    }
                    RandomAccessFile(part, "rw").use { raf ->
                        // A 200 is already the full body, even if the request included Range.
                        raf.setLength(offset)
                        raf.seek(offset)
                        val buf = ByteArray(256 * 1024)
                        val input = body.byteStream()
                        var downloaded = offset
                        var lastNotified = offset
                        onProgress(downloaded, entry.sizeBytes)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            ensureActive()
                            if (downloaded + n > offset + expectedBody) {
                                return@coroutineScope Result.Failed(entry, "Response exceeds expected size for ${entry.fileName}.")
                            }
                            raf.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, entry.sizeBytes)
                            if (downloaded - lastNotified >= NOTIFY_STEP_BYTES || downloaded == entry.sizeBytes) {
                                notifyProgress(entry, downloaded, entry.sizeBytes)
                                lastNotified = downloaded
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                // Network drop: retain .part for the next attempt.
                return@coroutineScope Result.Failed(entry, "Download paused for ${entry.fileName}: ${error.message}")
            } finally {
                cancellation.cancel()
            }
            if (!restart) break
        }
        ensureActive()
        if (part.length() != entry.sizeBytes) {
            return@coroutineScope Result.Failed(entry, "Incomplete download for ${entry.fileName}: ${part.length()}/${entry.sizeBytes} bytes; retry to resume.")
        }
        if (!ModelPaths.isVerifiedFile(part, entry) { ensureActive() }) {
            // Also delete the SECOND failed attempt: a full corrupt part must never poison retry.
            check(part.delete()) { "Could not remove invalid partial ${part.name}." }
            return@coroutineScope Result.Failed(entry, CHECKSUM_PREFIX + entry.fileName)
        }
        promote(entry, part)
    }

    private suspend fun promote(entry: ModelRegistry.Entry, part: File): Result {
        currentCoroutineContext().ensureActive()
        val dest = File(part.parent!!, part.name.removeSuffix(PART_SUFFIX))
        if (!part.renameTo(dest)) {
            return Result.Failed(entry, "Could not install ${entry.fileName}; verified partial retained for retry.")
        }
        return Result.Done(dest)
    }

    private suspend fun installArchive(archive: File, target: File, entry: ModelRegistry.Entry): Result = try {
        val root = ModelPaths.modelsDir(context).canonicalFile
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))),
        ).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val item = tar.nextEntry ?: break
                val output = File(root, item.name).canonicalFile
                if (!output.path.startsWith(root.path + File.separator)) {
                    return Result.Failed(entry, "Unsafe archive path in ${entry.fileName}.")
                }
                if (item.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { stream ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        if (!target.isDirectory) {
            Result.Failed(entry, "${entry.fileName} did not contain ${entry.installDirectory}.")
        } else {
            currentCoroutineContext().ensureActive()
            File(target, ".${entry.sha256}.installed").writeText(entry.sha256)
            Result.Done(target)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.Failed(entry, "Could not unpack ${entry.fileName}: ${error.message}")
    }

    /**
     * Public so the onboarding wizard can gate its download step on Wi-Fi
     * and ask for metered consent before passing `wifiOnly = false`.
     */
    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ── progress notification ──

    private fun notifications(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun channel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notifyProgress(entry: ModelRegistry.Entry, done: Long, total: Long) {
        val nm = notifications() ?: return
        channel(nm)
        val pct = if (total > 0) (100 * done / total).toInt() else 0
        nm.notify(
            entry.fileName.hashCode(),
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Downloading ${entry.fileName}")
                .setContentText("$pct%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, pct, total <= 0)
                .setOngoing(true)
                .build(),
        )
    }

    private fun notify(context: Context, entry: ModelRegistry.Entry, text: String) {
        val nm = notifications() ?: return
        channel(nm)
        nm.notify(
            entry.fileName.hashCode(),
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Aasra models")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build(),
        )
    }

    private fun cancelProgress(entry: ModelRegistry.Entry) {
        notifications()?.cancel(entry.fileName.hashCode())
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val CHECKSUM_PREFIX = "Checksum mismatch for "
        const val CHANNEL_ID = "aasra_models"
        const val NOTIFY_STEP_BYTES = 4L * 1024L * 1024L
    }
}
