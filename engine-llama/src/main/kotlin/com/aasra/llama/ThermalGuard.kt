package com.aasra.llama

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * SoC thermal guard (PLAN 7).
 *
 * Polls the kernel thermal zones plus [BatteryManager] as a fallback, logs
 * the temperature for the demo latency/thermal slide, and flips to
 * [ThermalState.THROTTLED] when sustained heat is seen. The pipeline reacts
 * by dropping the LLM tier to 0.8B and the TTS voice to Piper until the
 * state recovers to [ThermalState.NORMAL].
 */
class ThermalGuard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val warnTempC: Float = 43f,
    private val throttleTempC: Float = 47f,
    private val pollIntervalMs: Long = 15_000L,
    private val onStateChanged: (ThermalState) -> Unit = {},
) {
    enum class ThermalState { NORMAL, WARNING, THROTTLED }

    private val _state = MutableStateFlow(ThermalState.NORMAL)
    val state: StateFlow<ThermalState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                val temp = readSocTempC()
                val tempLabel = if (temp < 0) "n/a" else String.format("%.1f C", temp)
                Log.i(TAG, "SoC temp=" + tempLabel + " state=" + _state.value)
                val next = when {
                    temp < 0 -> _state.value // No sensor: never throttle on missing data.
                    temp >= throttleTempC -> ThermalState.THROTTLED
                    temp >= warnTempC -> ThermalState.WARNING
                    else -> ThermalState.NORMAL
                }
                if (next != _state.value) {
                    _state.value = next
                    onStateChanged(next)
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** True while throttled — pipeline should use 0.8B + Piper. */
    fun shouldDegrade(): Boolean = _state.value == ThermalState.THROTTLED

    /**
     * Reads the hottest thermal zone temp in Celsius. Falls back to the
     * battery thermistor via BatteryManager when no zone files are readable
     * (some OEM skins restrict /sys). Returns a negative value when nothing
     * is available.
     */
    fun readSocTempC(): Float {
        var maxC = Float.MIN_VALUE
        try {
            val sys = File("/sys/class/thermal")
            val zones = sys.listFiles { f -> f.name.startsWith("thermal_zone") }
                ?: emptyArray()
            for (zone in zones) {
                val raw = try {
                    File(zone, "temp").readText().trim()
                } catch (ignored: Exception) {
                    continue
                }
                val milli = raw.toLongOrNull() ?: continue
                if (milli <= 0 || milli > 150_000) continue // Bogus sensor, skip.
                maxC = maxOf(maxC, milli / 1000f)
            }
        } catch (ignored: SecurityException) {
            // Fall through to BatteryManager.
        }
        if (maxC != Float.MIN_VALUE) return maxC
        return try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1f
            val deci = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (deci == Int.MIN_VALUE || deci <= 0) -1f else deci / 10f
        } catch (ignored: Exception) {
            -1f
        }
    }

    companion object {
        private const val TAG = "ThermalGuard"
    }
}
