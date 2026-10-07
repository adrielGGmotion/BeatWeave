/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.metrolist.beatweave.rubberband

import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*
import org.metrolist.beatweave.*

class RubberBandEngineTest {
    private val rate = 44100

    private fun plan(ratio: Double = 1.0) =
        MixPlan(
            BeatGrid(DoubleArray(20) { it * 0.5 * ratio }),
            BeatGrid(DoubleArray(20) { it * 0.5 }),
            0,
            0,
            4,
            outputSampleRate = rate,
        )

    private fun source(invalid: Boolean = false) =
        object : StereoPcm {
            override val durationSeconds = 2.0

            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                readFrames((startSeconds * rate).toLong(), frames, outputSampleRate)

            override fun readFrames(
                startFrame: Long,
                frames: Int,
                outputSampleRate: Int,
            ): FloatArray =
                FloatArray(frames * 2) { i ->
                    if (invalid) Float.NaN
                    else
                        (sin(2 * PI * 440 * (startFrame + i / 2) / rate) *
                                (if (i % 2 == 0) .4 else -.2))
                            .toFloat()
                }
        }

    private fun withCache(block: (File) -> Unit) {
        val cache = Files.createTempDirectory("beatweave-test-").toFile()
        try {
            block(cache)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun identityIsBitExactAndRandomAccessDoesNotDependOnPreviousReads() = withCache { cache ->
        val source = source()
        val prepared =
            RubberBandEngine(cache).prepare(
                source,
                WarpSchedule.from(plan(), source.durationSeconds),
            ) {}
        try {
            val expected = source.readFrames(12345, 6000, rate)
            assertContentEquals(expected, prepared.readFrames(12345, 6000, rate))
            prepared.readFrames(2, 600, rate)
            assertContentEquals(expected, prepared.readFrames(12345, 6000, rate))
            assertTrue(prepared.readFrames(-20, 20, rate).all { it == 0f })
            assertTrue(prepared.readFrames(88200, 20, rate).all { it == 0f })
        } finally {
            prepared.close()
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
        assertFailsWith<IllegalStateException> { prepared.readFrames(0, 1, rate) }
    }

    @Test
    fun identityUsesOnlyItsSourceSnapshotAndSingleFileDiskBudget() = withCache { cache ->
        val schedule = WarpSchedule.from(plan(), 2.0)
        val sourceBytes = schedule.sourceFrames * 8L
        val constrainedCache = object : File(cache.absolutePath) {
            override fun getUsableSpace() = 1024L * 1024L + sourceBytes + 1L
        }
        val original = source()
        var peakFiles = 0
        val observed = object : StereoPcm {
            override val durationSeconds = original.durationSeconds
            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                readFrames((startSeconds * outputSampleRate).toLong(), frames, outputSampleRate)
            override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
                peakFiles = maxOf(peakFiles, cache.listFiles()!!.size)
                return original.readFrames(startFrame, frames, outputSampleRate)
            }
        }

        val prepared = RubberBandEngine(constrainedCache).prepare(observed, schedule) {}
        try {
            assertEquals(1, peakFiles)
            assertContentEquals(
                original.readFrames(12345, 6000, rate),
                prepared.readFrames(12345, 6000, rate),
            )
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun fractionalStretchHasExactDurationAndIndependentStereoChannels() = withCache { cache ->
        val source = source()
        val schedule = WarpSchedule.from(plan(1.031), source.durationSeconds)
        val prepared = RubberBandEngine(cache).prepare(source, schedule) {}
        try {
            assertEquals(schedule.outputFrames.toDouble() / rate, prepared.durationSeconds)
            val output = prepared.readFrames(10000, 10000, rate)
            assertTrue(output.all { it.isFinite() })
            assertTrue(output.any { it != 0f })
            // Linked stereo must preserve the anti-correlated channel relationship.
            for (i in 0 until output.size / 2) assertEquals(
                -.5f * output[2 * i],
                output[2 * i + 1],
                0.002f,
            )
            assertContentEquals(output, prepared.readFrames(10000, 10000, rate))
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun cancellingStudyDeletesPartialFiles() = withCache { cache ->
        val source = source()
        assertFailsWith<MixCancelledException> {
            RubberBandEngine(cache).prepare(
                source,
                WarpSchedule.from(plan(1.02), source.durationSeconds),
            ) {
                if (it > .15) throw MixCancelledException()
            }
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun cancellingRenderDeletesPartialFiles() = withCache { cache ->
        val source = source()
        assertFailsWith<MixCancelledException> {
            RubberBandEngine(cache).prepare(
                source,
                WarpSchedule.from(plan(1.02), source.durationSeconds),
            ) {
                if (it > .65) throw MixCancelledException()
            }
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun invalidDecoderPcmFailsBeforeBecomingAPlayableResult() = withCache { cache ->
        val source = source(true)
        assertFailsWith<IllegalArgumentException> {
            RubberBandEngine(cache).prepare(
                source,
                WarpSchedule.from(plan(1.02), source.durationSeconds),
            ) {}
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun emptyNativeRenderCannotBecomeOnePaddedSample() = withCache { cache ->
        val tiny = object : StereoPcm {
            override val durationSeconds = 2.0 / rate
            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                FloatArray(frames * 2) { 0.25f }
        }
        val schedule = WarpSchedule.from(plan(0.5), tiny.durationSeconds)
        assertEquals(1L, schedule.outputFrames)
        assertFailsWith<IllegalStateException> {
            RubberBandEngine(cache).prepare(tiny, schedule) {}
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun incompatibleSampleRateIsExplicit() = withCache { cache ->
        val source = source()
        val prepared =
            RubberBandEngine(cache).prepare(
                source,
                WarpSchedule.from(plan(), source.durationSeconds),
            ) {}
        try {
            assertFailsWith<IllegalArgumentException> { prepared.readFrames(0, 8, 48000) }
        } finally {
            prepared.close()
        }
    }
}
