package org.metrolist.beatweave

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MixerPitchShiftTest {
    private val source =
        object : StereoPcm {
            override val durationSeconds = 0.01

            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                FloatArray(frames * 2) { 0.1f }
        }
    private val grid = BeatGrid(DoubleArray(8) { it * 0.5 })
    private val translationPlan = MixPlan(grid, grid, firstBeat = 0, outputSampleRate = 22050)
    private val warpPlan =
        MixPlan(grid, BeatGrid(DoubleArray(8) { it * 0.6 }), firstBeat = 0, outputSampleRate = 22050)

    @Test
    fun incomingPitchShiftProcessesEvenAnUnchangedBeatClockExactlyOnce() {
        val engine = RecordingEngine()
        val shift = PitchShift(3.5, preserveFormants = false)
        val prepared = BeatMixer(engine).prepare(source, source, translationPlan, shift) {}
        try {
            assertTrue(prepared.schedule.isTranslationOnly)
            assertEquals(1, engine.pitchCalls)
            assertEquals(0, engine.stretchCalls)
            assertEquals(shift, engine.lastShift)
            assertContentEquals(FloatArray(20) { 0.4f }, prepared.readIncoming(0, 10))
        } finally {
            prepared.close()
            prepared.close()
        }
        assertEquals(1, engine.closes)
    }

    @Test
    fun incomingPitchShiftAndTempoWarpShareOneEnginePreparation() {
        val engine = RecordingEngine()
        val prepared = BeatMixer(engine).prepare(source, source, warpPlan, PitchShift(-2.0))
        try {
            assertTrue(!prepared.schedule.isTranslationOnly)
            assertEquals(1, engine.pitchCalls)
            assertEquals(0, engine.stretchCalls)
            assertEquals(PitchShift(-2.0), engine.lastShift)
        } finally {
            prepared.close()
        }
    }

    @Test
    fun nonzeroShiftRequiresCapableEngineForTranslationAndWarp() {
        val stretchOnly =
            object : PitchStretchEngine {
                override fun prepare(
                    source: StereoPcm,
                    schedule: WarpSchedule,
                    progress: (Double) -> Unit,
                ): PreparedStereoPcm = error("Unsupported pitch must fail before stretching")
            }
        for (plan in listOf(translationPlan, warpPlan)) {
            for (mixer in listOf(BeatMixer(), BeatMixer(stretchOnly))) {
                assertFailsWith<MissingPitchShiftEngineException> {
                    mixer.prepare(source, source, plan, PitchShift(1.0))
                }
            }
        }
    }

    @Test
    fun zeroShiftKeepsDirectPcmAndLegacyCallbackOverloads() {
        val engine = RecordingEngine()
        val mixer = BeatMixer(engine)
        // Both existing positional callbacks and trailing progress lambdas remain valid.
        val legacy = mixer.prepare(source, source, translationPlan, { false }) {}
        val explicit = mixer.prepare(source, source, translationPlan, PitchShift.None) {}
        try {
            assertContentEquals(source.readFrames(0, 10, 22050), legacy.readIncoming(0, 10))
            assertContentEquals(legacy.readIncoming(0, 10), explicit.readIncoming(0, 10))
            assertEquals(0, engine.pitchCalls)
            assertEquals(0, engine.stretchCalls)
        } finally {
            legacy.close()
            explicit.close()
        }
        assertEquals(0, engine.closes)
        val warped = mixer.prepare(source, source, warpPlan, { false }) {}
        warped.close()
        assertEquals(1, engine.stretchCalls)
        assertEquals(0, engine.pitchCalls)
    }

    @Test
    fun oneShotRenderOverloadsPassTheRequestedShiftAndReleasePreparedPcm() {
        val sink =
            object : PcmSink {
                override fun write(interleavedStereo: FloatArray, frames: Int) = Unit
            }
        for (complete in listOf(false, true)) {
            val engine = RecordingEngine()
            val mixer = BeatMixer(engine)
            val shift = PitchShift(2.0)
            if (complete) mixer.render(source, source, translationPlan, shift, sink) {}
            else mixer.renderRange(source, source, translationPlan, shift, sink, 0.0, 0.01) {}
            assertEquals(1, engine.pitchCalls)
            assertEquals(shift, engine.lastShift)
            assertEquals(1, engine.closes)
        }
    }

    private class RecordingEngine : PitchShiftEngine {
        var pitchCalls = 0
        var stretchCalls = 0
        var closes = 0
        var lastShift: PitchShift? = null

        override fun prepare(
            source: StereoPcm,
            schedule: WarpSchedule,
            progress: (Double) -> Unit,
        ): PreparedStereoPcm {
            stretchCalls++
            return pcm(schedule)
        }

        override fun prepare(
            source: StereoPcm,
            schedule: WarpSchedule,
            pitchShift: PitchShift,
            progress: (Double) -> Unit,
        ): PreparedStereoPcm {
            pitchCalls++
            lastShift = pitchShift
            return pcm(schedule)
        }

        private fun pcm(schedule: WarpSchedule): PreparedStereoPcm =
            object : PreparedStereoPcm {
                override val durationSeconds = schedule.outputFrames.toDouble() / schedule.sampleRate

                override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                    FloatArray(frames * 2) { 0.4f }

                override fun close() {
                    closes++
                }
            }
    }
}
