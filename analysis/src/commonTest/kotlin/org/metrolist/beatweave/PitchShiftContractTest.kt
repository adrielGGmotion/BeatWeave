package org.metrolist.beatweave

import kotlin.test.*

class PitchShiftContractTest {
    @Test
    fun semitonesMapToIndependentPitchRatios() {
        assertEquals(1.0, PitchShift.None.ratio)
        assertEquals(2.0, PitchShift(12.0).ratio)
        assertEquals(0.5, PitchShift(-12.0).ratio)
        assertEquals(4.0, PitchShift(24.0).ratio)
        assertEquals(0.25, PitchShift(-24.0).ratio)
        assertTrue(PitchShift(-0.0, preserveFormants = false).isIdentity)
        assertFalse(PitchShift(0.01).isIdentity)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -24.01, 24.01)) {
            assertFailsWith<IllegalArgumentException> { PitchShift(invalid) }
        }
    }

    @Test
    fun pitchOnlyScheduleUsesExactFrameCountAndNoOffset() {
        val schedule = WarpSchedule.identity(44101L, 44100)
        assertEquals(44101L, schedule.sourceFrames)
        assertEquals(schedule.sourceFrames, schedule.outputFrames)
        assertEquals(0L, schedule.outputOriginFrame)
        assertTrue(schedule.isTranslationOnly)
        assertEquals(listOf(WarpAnchor(0, 0), WarpAnchor(44101, 44101)), schedule.anchors)
        assertFailsWith<IllegalArgumentException> { WarpSchedule.identity(0L, 44100) }
        assertFailsWith<IllegalArgumentException> { WarpSchedule.identity(1L, 0) }
        assertFailsWith<IllegalArgumentException> { WarpSchedule.identity(Double.NaN, 44100) }
    }

    @Test
    fun sampleDerivedDurationsDoNotGainAFrameOnRoundTrip() {
        for (rate in listOf(8000, 22050, 44100, 48000, 96000, 192000)) {
            for (frames in listOf(13L, 44101L, 88201L, rate.toLong() * 14399 - 1)) {
                assertEquals(frames, WarpSchedule.identity(frames.toDouble() / rate, rate).sourceFrames)
            }
        }
        val grid = BeatGrid(doubleArrayOf(0.0, 0.5, 1.0, 1.5))
        val plan = MixPlan(grid, grid, 0, outputSampleRate = 44100)
        assertEquals(13L, WarpSchedule.from(plan, 13.0 / 44100).sourceFrames)
        assertEquals(14L, WarpSchedule.identity(13.25 / 44100, 44100).sourceFrames)
    }

    @Test
    fun pitchOnlyConveniencePassesOptionsAndDurationToBackend() {
        val source = object : StereoPcm {
            override val durationSeconds = 1.0
            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                FloatArray(frames * 2)
        }
        val shift = PitchShift(3.5, preserveFormants = false)
        var progressSeen = -1.0
        val engine = object : PitchShiftEngine {
            override fun prepare(source: StereoPcm, schedule: WarpSchedule,
                progress: (Double) -> Unit): PreparedStereoPcm = error("Wrong overload")
            override fun prepare(source: StereoPcm, schedule: WarpSchedule,
                pitchShift: PitchShift, progress: (Double) -> Unit): PreparedStereoPcm {
                assertSame(shift, pitchShift)
                assertTrue(schedule.isTranslationOnly)
                assertEquals(48000L, schedule.outputFrames)
                progress(1.0)
                return object : PreparedStereoPcm {
                    override val durationSeconds = 1.0
                    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                        source.read(startSeconds, frames, outputSampleRate)
                    override fun close() = Unit
                }
            }
        }
        engine.preparePitchShift(source, 48000, shift) { progressSeen = it }.close()
        assertEquals(1.0, progressSeen)
    }
}
