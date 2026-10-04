package org.metrolist.beatweave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MixerFailureTest {
    private val source =
        object : StereoPcm {
            override val durationSeconds = 0.01

            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                FloatArray(frames * 2)
        }
    private val plan =
        MixPlan(
            BeatGrid(DoubleArray(8) { it * 0.5 }),
            BeatGrid(DoubleArray(8) { it * 0.6 }),
            firstBeat = 0,
            outputSampleRate = 22050,
        )

    @Test
    fun invalidPreparedDurationRetainsValidationErrorWhenCleanupFails() {
        val engine = FailingCloseEngine(incorrectDuration = true)
        val failure = assertFailsWith<IllegalArgumentException> {
            BeatMixer(engine).prepare(source, source, plan)
        }
        assertEquals("Stretcher returned an incorrect duration", failure.message)
        assertSame(engine.closeFailure, failure.suppressedExceptions.single())
        assertEquals(1, engine.closes)
    }

    @Test
    fun finalProgressErrorRetainsItsIdentityWhenCleanupFails() {
        val engine = FailingCloseEngine()
        val progressFailure = UnsupportedOperationException("progress observer failed")
        val failure = assertFailsWith<UnsupportedOperationException> {
            BeatMixer(engine).prepare(source, source, plan) { throw progressFailure }
        }
        assertSame(progressFailure, failure)
        assertSame(engine.closeFailure, failure.suppressedExceptions.single())
        assertEquals(1, engine.closes)
    }

    @Test
    fun renderAndRenderRangeRetainSinkErrorsWhenCleanupFails() {
        for (complete in listOf(false, true)) {
            val engine = FailingCloseEngine()
            val sinkFailure = UnsupportedOperationException("disk full")
            val sink =
                object : PcmSink {
                    override fun write(interleavedStereo: FloatArray, frames: Int) {
                        throw sinkFailure
                    }
                }
            val mixer = BeatMixer(engine)
            val failure = assertFailsWith<UnsupportedOperationException> {
                if (complete) mixer.render(source, source, plan, sink)
                else mixer.renderRange(source, source, plan, sink, 0.0, 0.01)
            }
            assertSame(sinkFailure, failure)
            assertSame(engine.closeFailure, failure.suppressedExceptions.single())
            assertEquals(1, engine.closes)
        }
    }

    @Test
    fun cleanupErrorStillPropagatesWhenRenderingSucceeds() {
        val engine = FailingCloseEngine()
        val sink =
            object : PcmSink {
                override fun write(interleavedStereo: FloatArray, frames: Int) = Unit
            }
        val failure = assertFailsWith<UnsupportedOperationException> {
            BeatMixer(engine).renderRange(source, source, plan, sink, 0.0, 0.01)
        }
        assertSame(engine.closeFailure, failure)
        assertEquals(1, engine.closes)
    }

    private class FailingCloseEngine(private val incorrectDuration: Boolean = false) :
        PitchStretchEngine {
        val closeFailure = UnsupportedOperationException("cache cleanup failed")
        var closes = 0

        override fun prepare(
            source: StereoPcm,
            schedule: WarpSchedule,
            progress: (Double) -> Unit,
        ): PreparedStereoPcm =
            object : PreparedStereoPcm {
                override val durationSeconds =
                    schedule.outputFrames.toDouble() / schedule.sampleRate +
                        if (incorrectDuration) 1.0 else 0.0

                override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                    FloatArray(frames * 2)

                override fun close() {
                    closes++
                    throw closeFailure
                }
            }
    }
}
