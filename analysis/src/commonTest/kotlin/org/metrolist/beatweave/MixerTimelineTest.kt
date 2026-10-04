package org.metrolist.beatweave

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MixerTimelineTest {
    @Test
    fun acceptedLateCueCanReadAndRenderItsTailBeyondFourHours() {
        val rate = 22050
        val first = ConstantPcm(4.0 * 60 * 60)
        val second = ConstantPcm(1000.0)
        val firstGrid = BeatGrid(DoubleArray(8) { 14000.0 + it * 0.5 })
        val secondGrid = BeatGrid(DoubleArray(8) { it * 0.5 })
        val mix =
            BeatMixer().prepare(first, second, MixPlan(firstGrid, secondGrid, 0, outputSampleRate = rate))
        try {
            val end = 15000L * rate
            assertEquals(end, mix.endFrame())
            assertContentEquals(floatArrayOf(0.25f, 0.25f), mix.readIncoming(end - 1, 1))
            var written = 0
            val sink =
                object : PcmSink {
                    override fun write(interleavedStereo: FloatArray, frames: Int) {
                        written += frames
                    }
                }
            mix.renderFrames(sink, end - 1, end)
            assertEquals(1, written)
            mix.renderRange(sink, 14999.0, 15000.0)
            assertEquals(1 + rate, written)
            assertFailsWith<IllegalArgumentException> {
                mix.renderFrames(sink, 8L * 60 * 60 * rate, 8L * 60 * 60 * rate + 1)
            }
            assertFailsWith<IllegalArgumentException> {
                mix.readIncoming(8L * 60 * 60 * rate + 1, 1)
            }
        } finally {
            mix.close()
        }
    }

    private class ConstantPcm(override val durationSeconds: Double) : StereoPcm {
        override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray {
            val startFrame = (startSeconds * outputSampleRate).roundToLong()
            return FloatArray(frames * 2) {
                val frame = startFrame + it / 2
                if (frame >= 0 && frame < (durationSeconds * outputSampleRate).roundToLong()) 0.25f
                else 0f
            }
        }
    }
}
