package com.aasra.llama

/**
 * Decides which Qwen GGUF to load from the device's CURRENT free RAM.
 *
 * PLAN 4.5 / 7: if free RAM < 5 GB at load time, use Qwen3.5-0.8B instead
 * of Qwen3.5-2B so the phone never OOMs the foreground pipeline.
 */
object RamTier {

    /** 5 GB threshold, in bytes. */
    const val LOW_RAM_THRESHOLD_BYTES: Long = 5L * 1024L * 1024L * 1024L

    enum class LlmTier(
        /** File name of the GGUF in [com.aasra.models.ModelPaths.modelsDir]. */
        val modelFileName: String,
    ) {
        /** Full-quality tier for the 12 GB flagship. */
        FULL("Qwen3.5-2B-Instruct-Q4_K_M.gguf"),

        /** Degraded tier for ≤ 5 GB free RAM, thermal throttle, or load failure. */
        SMALL("Qwen3.5-0.8B-Instruct-Q4_K_M.gguf"),
    }

    /** Pure function — unit-testable without Android. */
    fun decide(freeBytes: Long): LlmTier =
        if (freeBytes < LOW_RAM_THRESHOLD_BYTES) LlmTier.SMALL else LlmTier.FULL

    /** Android entry point: reads [android.app.ActivityManager.MemoryInfo.availMem]. */
    fun decide(context: android.content.Context): LlmTier {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return LlmTier.SMALL
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return decide(info.availMem)
    }
}
