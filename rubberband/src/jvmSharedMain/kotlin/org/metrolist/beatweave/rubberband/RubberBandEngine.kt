/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.metrolist.beatweave.rubberband

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.roundToLong
import org.metrolist.beatweave.*

/**
 * Offline Rubber Band 3.3.0 R3, with linked stereo and independent pitch/tempo control.
 * Nonzero shifts use the high-quality offline path with optional formant preservation.
 * They require at least 100 ms of both source and output audio; zero pitch with
 * an identity schedule remains bit-exact even for shorter clips.
 * Preparation snapshots the decoded
 * PCM so the study and render passes see identical audio. Both the source snapshot and output are
 * disk-backed. Call off the UI thread. No fallback to resampling or the former per-window vocoder
 * is permitted.
 *
 * The caller owns [cacheDirectory]; Android callers should use Context.cacheDir. Close every
 * returned PreparedStereoPcm (or its owning PreparedMix). This optional module is GPL-2.0-or-later;
 * see its COPYING and THIRD_PARTY.md.
 */
class RubberBandEngine(private val cacheDirectory: File) : PitchShiftEngine {
    override fun prepare(
        source: StereoPcm,
        schedule: WarpSchedule,
        progress: (Double) -> Unit,
    ): PreparedStereoPcm = prepare(source, schedule, PitchShift.None, progress)

    override fun prepare(
        source: StereoPcm,
        schedule: WarpSchedule,
        pitchShift: PitchShift,
        progress: (Double) -> Unit,
    ): PreparedStereoPcm {
        require(pitchShift.isIdentity ||
            (schedule.sourceFrames >= (schedule.sampleRate + 9) / 10 &&
                schedule.outputFrames >= (schedule.sampleRate + 9) / 10)) {
            "Pitch shifting requires at least 100 ms of source and output audio"
        }
        if (!cacheDirectory.isDirectory) cacheDirectory.mkdirs()
        // Another preparation may create the same directory between the two calls.
        require(cacheDirectory.isDirectory) { "Cannot create audio cache directory" }
        val bytesNeeded =
            Math.multiplyExact(Math.addExact(schedule.sourceFrames, schedule.outputFrames), 8L)
        val free = cacheDirectory.usableSpace
        require(free <= 0 || free > bytesNeeded + 1024 * 1024) {
            "Not enough free space for prepared audio"
        }
        var handle = 0L
        var inputFile: File? = null
        var outputFile: File? = null
        try {
            // LinkageError is intentional: missing/incompatible native libraries cannot
            // silently select an engine with different timing or pitch behavior.
            val identity = schedule.isTranslationOnly && pitchShift.isIdentity
            handle = if (pitchShift.isIdentity) {
                RubberBandBridge.create(
                    schedule.sampleRate,
                    schedule.sourceFrames,
                    schedule.outputFrames,
                )
            } else {
                RubberBandBridge.createWithPitch(
                    schedule.sampleRate,
                    schedule.sourceFrames,
                    schedule.outputFrames,
                    pitchShift.ratio,
                    pitchShift.preserveFormants,
                )
            }
            check(handle != 0L && RubberBandBridge.engineVersion(handle) == 3) {
                "Rubber Band R3 is required"
            }
            val anchors = schedule.anchors
            RubberBandBridge.setKeyFrames(
                handle,
                LongArray(anchors.size) { anchors[it].sourceFrame },
                LongArray(anchors.size) { anchors[it].outputFrame },
            )
            inputFile = File.createTempFile("beatweave-source-", ".f32", cacheDirectory)
            outputFile = File.createTempFile("beatweave-stretched-", ".f32", cacheDirectory)
            val block = 4096
            val io = ByteBuffer.allocate(block * 8).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = FloatArray(block * 2)
            fun update(value: Double) {
                if (Thread.currentThread().isInterrupted) throw MixCancelledException()
                progress(value)
            }
            update(0.0)
            RandomAccessFile(inputFile, "rw").use { snapshot ->
                var frame = 0L
                while (frame < schedule.sourceFrames) {
                    update(0.45 * frame.toDouble() / schedule.sourceFrames)
                    val count = min(block.toLong(), schedule.sourceFrames - frame).toInt()
                    val data = source.readFrames(frame, count, schedule.sampleRate)
                    require(data.size == count * 2 && data.all { it.isFinite() }) {
                        "Decoder returned invalid stereo PCM"
                    }
                    io.clear()
                    for (x in data) io.putFloat(x)
                    io.flip()
                    writeFully(snapshot.channel, io)
                    if (!identity)
                        RubberBandBridge.study(
                            handle,
                            data,
                            count,
                            frame + count == schedule.sourceFrames,
                        )
                    frame += count
                }
                if (identity) {
                    // Identity must be bit-for-bit: running an unnecessary STFT can
                    // alter transients even when the requested stretch is exactly one.
                    snapshot.channel.force(false)
                    outputFile!!.delete()
                    outputFile = null
                    update(1.0)
                    val result =
                        DiskPreparedPcm(inputFile!!, schedule.sampleRate, schedule.outputFrames)
                    inputFile = null
                    return result
                }
                snapshot.seek(0)
                RandomAccessFile(outputFile, "rw").use { destination ->
                    var nativeFrames = 0L
                    var writtenFrames = 0L
                    fun drain() {
                        while (true) {
                            val available = RubberBandBridge.available(handle)
                            if (available <= 0) return
                            val count =
                                RubberBandBridge.retrieve(handle, pcm, min(available, block))
                            check(count > 0) { "Native engine stalled while draining output" }
                            nativeFrames += count
                            val keep =
                                min(count.toLong(), schedule.outputFrames - writtenFrames).toInt()
                            io.clear()
                            for (i in 0 until keep * 2) {
                                check(pcm[i].isFinite()) { "Native engine returned non-finite PCM" }
                                io.putFloat(pcm[i])
                            }
                            io.flip()
                            writeFully(destination.channel, io)
                            writtenFrames += keep
                        }
                    }
                    frame = 0L
                    while (frame < schedule.sourceFrames) {
                        update(0.45 + 0.54 * frame.toDouble() / schedule.sourceFrames)
                        // R3 updates a keyframe-map ratio once per process call.
                        // Large (4096-frame) calls delayed variable-tempo updates and
                        // attenuated one transient by 13 dB in the 120-second drift
                        // regression. Small calls retain the continuous native state
                        // while applying the map promptly; study still uses 4096.
                        val count = min(256L, schedule.sourceFrames - frame).toInt()
                        io.clear().limit(count * 8)
                        readFully(snapshot.channel, io)
                        io.flip()
                        for (i in 0 until count * 2) pcm[i] = io.float
                        RubberBandBridge.process(
                            handle,
                            pcm,
                            count,
                            frame + count == schedule.sourceFrames,
                        )
                        drain()
                        frame += count
                    }
                    drain()
                    check(RubberBandBridge.available(handle) == -1) {
                        "Native engine did not finish offline processing"
                    }
                    // Fixed-duration offline processing should be exact. Permit only a
                    // single sample of floating-point rounding, never a missing tail.
                    check(kotlin.math.abs(nativeFrames - schedule.outputFrames) <= 1L) {
                        "Native duration mismatch: expected ${schedule.outputFrames}, got $nativeFrames frames"
                    }
                    if (writtenFrames < schedule.outputFrames) {
                        io.clear().limit(8)
                        io.putLong(0L)
                        io.flip()
                        writeFully(destination.channel, io)
                    }
                    destination.channel.force(false)
                }
            }
            update(1.0)
            val result = DiskPreparedPcm(outputFile!!, schedule.sampleRate, schedule.outputFrames)
            outputFile = null
            return result
        } finally {
            if (handle != 0L) RubberBandBridge.destroy(handle)
            inputFile?.delete()
            outputFile?.delete()
        }
    }
}

private class DiskPreparedPcm(
    private val file: File,
    private val sampleRate: Int,
    private val frames: Long,
) : PreparedStereoPcm {
    private val input = RandomAccessFile(file, "r")
    private val bytes = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false
    override val durationSeconds: Double
        get() = frames.toDouble() / sampleRate

    @Synchronized
    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray {
        require(startSeconds.isFinite())
        return readFrames((startSeconds * sampleRate).roundToLong(), frames, outputSampleRate)
    }

    @Synchronized
    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
        check(!closed) { "Prepared audio is closed" }
        require(frames >= 0 && frames <= Int.MAX_VALUE / 2)
        require(outputSampleRate == sampleRate) {
            "Prepared audio must be read at its preparation sample rate"
        }
        val out = FloatArray(frames * 2)
        val start = startFrame
        val from = maxOf(0L, start)
        val until =
            minOf(
                this.frames,
                if (start > Long.MAX_VALUE - frames) Long.MAX_VALUE else start + frames,
            )
        if (from >= until) return out
        input.seek(from * 8)
        var offset = (from - start).toInt() * 2
        var remaining = until - from
        while (remaining > 0) {
            val count = minOf(4096L, remaining).toInt()
            bytes.clear().limit(count * 8)
            readFully(input.channel, bytes)
            bytes.flip()
            repeat(count * 2) { out[offset++] = bytes.float }
            remaining -= count
        }
        return out
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try {
                input.close()
            } finally {
                file.delete()
            }
        }
    }
}

private fun writeFully(channel: FileChannel, bytes: ByteBuffer) {
    while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "Could not write PCM cache" }
}

private fun readFully(channel: FileChannel, bytes: ByteBuffer) {
    while (bytes.hasRemaining()) check(channel.read(bytes) > 0) { "Truncated PCM cache" }
}
