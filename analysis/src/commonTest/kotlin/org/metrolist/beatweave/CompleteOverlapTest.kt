package org.metrolist.beatweave

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompleteOverlapTest {
    private val rate = 22050

    @Test
    fun completeOverlapPreservesIncomingPrefixAndBothFilesTails() {
        val a = IndexedPcm(2.0, 0.1f)
        val b = IndexedPcm(2.0, 0.4f)
        val grid = BeatGrid(DoubleArray(20) { 0.5 * it })
        val mix = BeatMixer().prepare(a, b, MixPlan(grid, grid, 1, 3, outputSampleRate = rate))
        try {
            assertTrue(mix.schedule.isTranslationOnly)
            assertEquals(-rate.toLong(), mix.startFrame(MixMode.OVERLAP))
            assertEquals(2L * rate, mix.endFrame(MixMode.OVERLAP))
            assertEquals(3L * rate, mix.frameCount(MixMode.OVERLAP))
            val full = CollectingSink()
            mix.renderComplete(full, MixMode.OVERLAP)
            assertEquals((3L * rate * 2).toInt(), full.samples.size)
            for (fileFrame in 0 until 3 * rate) {
                val global = fileFrame - rate
                for (channel in 0..1) {
                    val expected =
                        (a.sample(global.toLong(), channel) * 0.46 +
                                b.sample(fileFrame.toLong(), channel) * 0.46)
                            .toFloat()
                    assertEquals(
                        expected,
                        full.samples[2 * fileFrame + channel],
                        "Wrong preserved sample at file frame $fileFrame, channel $channel",
                    )
                }
            }
            // Existing consumers retain global-zero semantics, while every
            // nonnegative seek is the exact corresponding complete-export crop.
            val legacy = CollectingSink()
            mix.render(legacy, MixMode.OVERLAP)
            assertEquals(full.samples.drop(rate * 2), legacy.samples)
            val crop = CollectingSink()
            mix.renderFrames(crop, 123, 4321, MixMode.OVERLAP)
            assertEquals(full.samples.subList((rate + 123) * 2, (rate + 4321) * 2), crop.samples)
            assertContentEquals(
                b.readFrames(0, 127, rate),
                mix.readIncoming(mix.startFrame(MixMode.OVERLAP), 127),
            )
            assertFailsWith<IllegalArgumentException> {
                mix.renderFrames(CollectingSink(), -1, 100, MixMode.OVERLAP)
            }
        } finally {
            mix.close()
        }
        assertFailsWith<IllegalStateException> { mix.startFrame(MixMode.OVERLAP) }
        assertFailsWith<IllegalStateException> { mix.frameCount(MixMode.OVERLAP) }
    }

    @Test
    fun transitionAndNonnegativeOriginOverlapKeepZeroOrigin() {
        val source = IndexedPcm(2.0, 0.2f)
        val grid = BeatGrid(DoubleArray(20) { 0.5 * it })
        for (mode in MixMode.entries) {
            val firstBeat = if (mode == MixMode.TRANSITION) 1 else 3
            val secondBeat = if (mode == MixMode.TRANSITION) 3 else 1
            val mix =
                BeatMixer()
                    .prepare(
                        source,
                        source,
                        MixPlan(
                            grid,
                            grid,
                            firstBeat,
                            secondBeat,
                            crossfadeBeats = 2,
                            outputSampleRate = rate,
                        ),
                    )
            try {
                assertEquals(0L, mix.startFrame(mode))
                assertEquals(mix.endFrame(mode), mix.frameCount(mode))
                val complete = CollectingSink()
                val legacy = CollectingSink()
                mix.renderComplete(complete, mode)
                mix.render(legacy, mode)
                assertEquals(legacy.samples, complete.samples)
            } finally {
                mix.close()
            }
        }
    }

    private inner class IndexedPcm(
        override val durationSeconds: Double,
        private val offset: Float,
    ) : StereoPcm {
        fun sample(frame: Long, channel: Int): Float {
            if (frame < 0 || frame >= (durationSeconds * rate).roundToLong()) return 0f
            return (offset + frame.toDouble() / (rate * 20.0) + channel * 0.03).toFloat()
        }

        override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray =
            readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

        override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
            assertEquals(rate, outputSampleRate)
            return FloatArray(frames * 2) { sample(startFrame + it / 2, it % 2) }
        }
    }

    private class CollectingSink : PcmSink {
        val samples = mutableListOf<Float>()

        override fun write(interleavedStereo: FloatArray, frames: Int) {
            samples.addAll(interleavedStereo.take(frames * 2))
        }
    }
}
