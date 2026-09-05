package com.aasra.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe circular PCM16 buffer.
 *
 * One slot per sample. The recorder writes 20 ms frames continuously; readers
 * (VAD/STT) drain what they need. When full, the oldest samples are dropped by
 * default so a stalled consumer can never block the mic thread. The same buffer
 * backs [AudioRecorder.lastSeconds], which the cloud re-listen path (PLAN 5.2)
 * needs: the last VAD segment is uploaded for saaras:v4 transcription.
 */
class RingBuffer(
    capacitySamples: Int,
    private val overwriteOldest: Boolean = true,
) {
    private val buf = ShortArray(capacitySamples.coerceAtLeast(1))
    private var readPos = 0
    private var writePos = 0
    private var count = 0
    private val lock = ReentrantLock()

    val capacity: Int get() = buf.size

    /** Samples currently held. */
    fun available(): Int = lock.withLock { count }

    /** Free slots. */
    fun free(): Int = lock.withLock { buf.size - count }

    /** Writes up to [length] samples. Returns samples actually stored. */
    fun write(data: ShortArray, offset: Int = 0, length: Int = data.size - offset): Int =
        lock.withLock {
            var written = 0
            while (written < length) {
                if (count == buf.size) {
                    if (!overwriteOldest) break
                    readPos = (readPos + 1) % buf.size
                    count--
                }
                buf[writePos] = data[offset + written]
                writePos = (writePos + 1) % buf.size
                count++
                written++
            }
            written
        }

    /** Reads up to [length] samples, consuming them. Returns samples read. */
    fun read(dst: ShortArray, offset: Int = 0, length: Int = dst.size - offset): Int =
        lock.withLock {
            val n = minOf(length, count)
            for (i in 0 until n) {
                dst[offset + i] = buf[readPos]
                readPos = (readPos + 1) % buf.size
            }
            count -= n
            n
        }

    /** Copies everything currently held WITHOUT consuming it (for re-listen upload). */
    fun copyAll(): ShortArray = lock.withLock {
        val out = ShortArray(count)
        var p = readPos
        for (i in out.indices) {
            out[i] = buf[p]
            p = (p + 1) % buf.size
        }
        out
    }

    fun clear() = lock.withLock {
        readPos = 0
        writePos = 0
        count = 0
    }
}
