package com.aasra.sherpa

import java.io.File

/**
 * Shared helpers for the sherpa-onnx wrappers.
 *
 * BINDING STRATEGY (PLAN 4.1/4.3/4.4/4.6): every wrapper talks to sherpa-onnx
 * ONLY through reflection ([SherpaBinding.clazz]). Rationale:
 *
 *  1. The module compiles with zero native .so present — and even with the
 *     JitPack AAR commented out (see engine-sherpa/build.gradle.kts).
 *  2. At runtime a missing/broken native lib degrades to ready=false instead
 *     of crashing the app with UnsatisfiedLinkError (demo-day safety net).
 *
 * Once JitPack is wired at root and models are on-device, init() binds the
 * real classes; until then every method returns its documented fallback.
 */
internal object SherpaBinding {
    /**
     * Returns the sherpa class, or null when the AAR/.so is absent.
     * Expected package (sherpa-onnx v1.12.x Android AAR): com.k2fsa.sherpa.onnx.*
     */
    fun clazz(simpleName: String): Class<*>? = try {
        Class.forName("com.k2fsa.sherpa.onnx.$simpleName")
    } catch (_: Throwable) {
        null
    }

    /** True once the native lib actually loads (not just the Java stubs). */
    fun nativeLoads(): Boolean = try {
        val c = clazz("Vad") ?: return false
        // Touching the class is not enough; attempt the real load path sherpa
        // documents (System.loadLibrary("sherpa-onnx-jni")) and report.
        System.loadLibrary("sherpa-onnx-jni")
        c.name.isNotEmpty()
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Throwable) {
        false
    }
}

/**
 * Model-file staging. Models are downloaded by the models/ track (or `adb
 * push` for the demo, PLAN 10) into filesDir/models; bundled fallbacks (the
 * 2 MB silero_vad.onnx) may ship in assets/. This copies asset -> filesDir on
 * first launch so sherpa always loads from a plain file path.
 */
object ModelAssets {
    /**
     * @param openAsset lambda opening an asset InputStream, or null if absent
     *   (app/ implements it as `context.assets.open(name)` wrapped in try/catch).
     * @return the usable model file, or null when neither asset nor file exists.
     */
    fun ensureFile(
        filesDir: File,
        relativePath: String,
        openAsset: ((String) -> java.io.InputStream?)? = null,
    ): File? {
        val out = File(filesDir, relativePath)
        if (out.isFile && out.length() > 0) return out
        if (openAsset == null) return null
        return try {
            val stream = openAsset(relativePath.substringAfterLast('/')) ?: return null
            stream.use { input ->
                out.parentFile?.mkdirs()
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.isFile && out.length() > 0) out else null
        } catch (_: Exception) {
            null
        }
    }
}
