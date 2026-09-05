package com.aasra.models

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object ModelPaths {
    const val MODELS_DIR_NAME = "models"

    fun modelsDir(context: Context): File = File(context.filesDir, MODELS_DIR_NAME)

    fun fileFor(context: Context, entry: ModelRegistry.Entry): File =
        File(modelsDir(context), entry.installDirectory ?: entry.fileName)

    fun downloadFileFor(context: Context, entry: ModelRegistry.Entry): File =
        File(modelsDir(context), entry.fileName)

    fun fileFor(context: Context, fileName: String): File = File(modelsDir(context), fileName)

    /** Reads and hashes model files. Call from an IO dispatcher, not the UI thread. */
    fun isInstalled(context: Context, entry: ModelRegistry.Entry): Boolean {
        val target = fileFor(context, entry)
        return if (entry.installDirectory == null) {
            isVerifiedFile(target, entry)
        } else {
            target.isDirectory && File(target, ".${entry.sha256}.installed").isFile
        }
    }

    internal fun isVerifiedFile(
        file: File,
        entry: ModelRegistry.Entry,
        checkActive: () -> Unit = {},
    ): Boolean {
        if (!file.isFile || file.length() != entry.sizeBytes ||
            !entry.sha256.matches(Regex("[0-9a-fA-F]{64}"))
        ) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(entry.sha256, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    fun adoptAdbPushedFiles(context: Context): Int {
        val external = context.getExternalFilesDir(null)?.let { File(it, MODELS_DIR_NAME) } ?: return 0
        if (!external.isDirectory) return 0
        val destination = modelsDir(context).also(File::mkdirs)
        var adopted = 0
        ModelRegistry.ALL.forEach { entry ->
            if (isInstalled(context, entry)) return@forEach
            val source = File(external, entry.installDirectory ?: entry.fileName)
            val target = File(destination, entry.installDirectory ?: entry.fileName)
            runCatching {
                when {
                    entry.installDirectory != null && source.isDirectory &&
                        File(source, ".${entry.sha256}.installed").isFile -> {
                        source.copyRecursively(target, overwrite = true)
                    }
                    entry.installDirectory == null && isVerifiedFile(source, entry) -> {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = true)
                    }
                    else -> return@runCatching
                }
                adopted++
            }
        }
        return adopted
    }
}
