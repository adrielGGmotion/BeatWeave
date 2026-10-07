package org.metrolist.beatweave

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClockEndpointTest {
    @Test
    fun unpairedIntroAndOutroDoNotCreateNewTempoChanges() {
        // Only A beats 3..6 have corresponding observed B beats. The unusual
        // outer A intervals must not manufacture a changing B outro clock.
        val a = BeatGrid(doubleArrayOf(0.0, 0.3, 0.65, 1.15, 1.65, 2.15, 2.65, 3.15, 3.35, 3.95))
        val b = BeatGrid(doubleArrayOf(0.25, 0.8, 1.35, 1.9))
        val plan = MixPlan(a, b, 4, 1, outputSampleRate = 48000)
        assertEquals(ObservedBeatCoverage(3, 6, 1.15, 2.65, 0.25, 1.9), plan.observedCoverage)
        for (index in -100..200) {
            val t = index / 10.0
            assertEquals(0.25 + (t - 1.15) * 1.1, plan.secondSourceTime(t), 1e-12)
            assertEquals(t, plan.secondOutputTime(plan.secondSourceTime(t)), 1e-8)
        }
        val quality = WarpQuality.assess(plan, -2.0, 10.0)
        assertTrue(quality.accepted, quality.toString())
        assertTrue(quality.maximumLogSpeedChangePerSecond < 1e-6)
    }

    @Test
    fun nonlinearClockHasContinuousEndpointSpeedsAndExactObservedKnots() {
        val a = BeatGrid(doubleArrayOf(0.1, 0.62, 1.17, 1.75, 2.35, 2.9, 3.4))
        val b = BeatGrid(doubleArrayOf(0.2, 0.65, 1.12, 1.56))
        val plan = MixPlan(a, b, 2, 0, outputSampleRate = 48000)
        val coverage = plan.observedCoverage
        val step = 1e-6
        for (endpoint in doubleArrayOf(coverage.outputStartSeconds, coverage.outputEndSeconds)) {
            val center = plan.secondSourceTime(endpoint)
            val before = (center - plan.secondSourceTime(endpoint - step)) / step
            val after = (plan.secondSourceTime(endpoint + step) - center) / step
            assertTrue(
                abs(after - before) < 1e-6,
                "Discontinuous speed at $endpoint: $before -> $after",
            )
        }
        for (beat in coverage.firstStartBeat..coverage.firstEndBeat) {
            assertEquals(b.at(beat - 2), plan.secondSourceTime(a.at(beat)), 1e-12)
        }
        for (beat in -100..200) {
            assertEquals(
                plan.secondSourceTime(plan.first.at(beat)),
                plan.secondSourceTimeAtFirstBeat(beat),
                1e-12,
            )
        }
        for (index in -100..200) {
            val t = index / 10.0
            assertEquals(t, plan.secondOutputTime(plan.secondSourceTime(t)), 1e-8)
        }
        assertQuantizedMap(plan, sourceDuration = 8.0)
    }

    @Test
    fun cycleRatiosReportOnlyFullyObservedPairedIntervals() {
        val a = BeatGrid(DoubleArray(20) { 0.12 + 0.4 * it })
        val b = BeatGrid(doubleArrayOf(0.1, 0.72, 1.31, 1.89, 2.50))
        val plan =
            MixPlan(
                a,
                b,
                2,
                1,
                outputSampleRate = 48000,
                firstBeatsPerCycle = 3,
                secondBeatsPerCycle = 2,
            )
        val coverage = plan.observedCoverage
        assertEquals(1, coverage.firstStartBeat)
        assertEquals(6, coverage.firstEndBeat)
        assertTrue(coverage.sourceStartSeconds >= b.at(0))
        assertTrue(coverage.sourceEndSeconds <= b.at(b.size - 1))
        for (beat in coverage.firstStartBeat..coverage.firstEndBeat) {
            assertEquals(b.at(1 + (beat - 2) * 2.0 / 3.0), plan.secondSourceTime(a.at(beat)), 1e-12)
        }
        assertQuantizedMap(plan, sourceDuration = 7.0)
    }

    @Test
    fun releaseRemainsContinuousAfterObservedCoverageEnds() {
        val a = BeatGrid(DoubleArray(20) { it * 0.5 })
        val b = BeatGrid(doubleArrayOf(0.0, 0.6, 1.2, 1.8))
        val plan = MixPlan(a, b, 1, 0, crossfadeBeats = 8, releaseAfterFade = true)
        for (beat in -20..40) {
            assertEquals(
                plan.secondSourceTime(plan.first.at(beat)),
                plan.secondSourceTimeAtFirstBeat(beat),
                1e-12,
            )
        }
        for (index in -100..200) {
            val t = index / 10.0
            assertEquals(t, plan.secondOutputTime(plan.secondSourceTime(t)), 1e-8)
        }
        val step = 1e-6
        val center = plan.secondSourceTime(plan.fadeEndSeconds)
        val before = (center - plan.secondSourceTime(plan.fadeEndSeconds - step)) / step
        val after = (plan.secondSourceTime(plan.fadeEndSeconds + step) - center) / step
        assertEquals(before, after, 1e-6)
        assertQuantizedMap(plan, sourceDuration = 10.0)
    }

    @Test
    fun noObservedPairedIntervalIsExplicitlyRejected() {
        val grid = BeatGrid(doubleArrayOf(0.0, 0.5, 1.0, 1.5))
        assertFailsWith<IllegalArgumentException> { MixPlan(grid, grid, 3, 0) }
    }

    private fun assertQuantizedMap(plan: MixPlan, sourceDuration: Double) {
        val schedule = WarpSchedule.from(plan, sourceDuration)
        val rate = schedule.sampleRate
        val coverage = plan.observedCoverage
        for (beat in coverage.firstStartBeat..coverage.firstEndBeat) {
            val t = plan.first.at(beat)
            val sourceFrame = (plan.secondSourceTime(t) * rate).roundToLong()
            if (sourceFrame !in 1 until schedule.sourceFrames) continue
            val exact = schedule.anchors.single { it.sourceFrame == sourceFrame }
            assertEquals((t * rate).roundToLong(), exact.outputFrame + schedule.outputOriginFrame)
        }
        for ((left, right) in schedule.anchors.zipWithNext()) {
            for (fraction in doubleArrayOf(0.25, 0.5, 0.75)) {
                val source =
                    (left.sourceFrame + (right.sourceFrame - left.sourceFrame) * fraction) / rate
                val target =
                    (schedule.outputOriginFrame +
                        left.outputFrame +
                        (right.outputFrame - left.outputFrame) * fraction) / rate
                assertTrue(abs(target - plan.secondOutputTime(source)) < 0.0011)
            }
        }
    }
}
