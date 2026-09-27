package org.metrolist.beatweave

import kotlin.math.abs
import kotlin.test.*

class WarpClockRatesTest {
    @Test
    fun lateIdentityCueWithInverseEndpointSliverRemainsAnIdentityWarp() {
        val clock = BeatGrid(DoubleArray(500) { it * .5 })
        val plan = MixPlan(clock, clock, firstBeat = 280, secondBeat = 280, crossfadeBeats = 16)
        // Inverting an interior knot uses a finite binary search. Its small positive remainder
        // used to produce an almost-zero final sampling section and spurious clock acceleration.
        val endpoint = plan.secondOutputTime(clock.at(296))
        val sliver = endpoint - plan.fadeEndSeconds
        assertTrue(
            sliver > 0.0 && sliver < 1e-8,
            "Fixture must exercise the inverse endpoint sliver: $sliver",
        )
        val report = WarpQuality.assess(plan, plan.startSeconds, endpoint)
        assertTrue(report.accepted, report.toString())
        assertEquals(1.0, report.minimumPlaybackSpeed, 1e-10)
        assertEquals(1.0, report.maximumPlaybackSpeed, 1e-10)
        assertTrue(report.maximumLogSpeedChangePerSecond < 1e-9, report.toString())
        for (time in listOf(plan.startSeconds, plan.fadeEndSeconds, endpoint)) {
            val (speed, acceleration) = plan.secondClockRates(time)
            assertEquals(1.0, speed, 1e-12)
            assertEquals(0.0, acceleration, 1e-12)
        }
    }

    private fun variablePlan(release: Boolean): MixPlan {
        val first =
            BeatGrid(
                doubleArrayOf(.10, .62, 1.17, 1.75, 2.35, 2.90, 3.40, 3.98, 4.59, 5.13, 5.70, 6.24)
                    .map { it + 137.0 }
                    .toDoubleArray()
            )
        val second =
            BeatGrid(
                doubleArrayOf(.20, .65, 1.12, 1.56, 2.06, 2.64, 3.06, 3.52, 4.08, 4.61, 5.10, 5.64)
                    .map { it + 83.0 }
                    .toDoubleArray()
            )
        return MixPlan(
            first,
            second,
            firstBeat = 3,
            secondBeat = 2,
            crossfadeBeats = 4,
            firstBeatsPerCycle = 3,
            secondBeatsPerCycle = 2,
            releaseAfterFade = release,
        )
    }

    /** Numerical oracle evaluates only the public source clock, not its derivative formulas. */
    private fun assertNumericalRates(plan: MixPlan, time: Double) {
        val step = .001
        val before = plan.secondSourceTime(time - step)
        val center = plan.secondSourceTime(time)
        val after = plan.secondSourceTime(time + step)
        val firstDifference =
            (plan.secondSourceTime(time - 2 * step) - 8 * before + 8 * after -
                plan.secondSourceTime(time + 2 * step)) / (12 * step)
        val secondDifference = (after - 2 * center + before) / (step * step)
        val (speed, acceleration) = plan.secondClockRates(time)
        assertEquals(firstDifference, speed, 2e-7, "First rate at $time")
        assertEquals(secondDifference, acceleration, 5e-6, "Second rate at $time")
    }

    @Test
    fun analyticRatesMatchIndependentDifferencesWithinNonlinearUnequalCycleIntervals() {
        val plan = variablePlan(release = false)
        val coverage = plan.observedCoverage
        var changingIntervals = 0
        for (beat in coverage.firstStartBeat until coverage.firstEndBeat) {
            val start = plan.first.at(beat)
            val length = plan.first.at(beat + 1) - start
            for (fraction in listOf(.17, .5, .83)) {
                val time = start + fraction * length
                assertNumericalRates(plan, time)
                if (abs(plan.secondClockRates(time).second) > .01) changingIntervals++
            }
        }
        assertTrue(changingIntervals >= 6, "Oracle must inspect a genuinely nonlinear clock")
        for (time in listOf(coverage.outputStartSeconds - .4, coverage.outputEndSeconds + .4)) {
            assertNumericalRates(plan, time)
            assertEquals(0.0, plan.secondClockRates(time).second, 1e-12)
        }
    }

    @Test
    fun releaseRatesMatchIndependentDifferencesAndReturnToUnitSpeed() {
        val plan = variablePlan(release = true)
        for (elapsed in listOf(.13, .53, 1.17, 1.81, 2.3)) assertNumericalRates(
            plan,
            plan.fadeEndSeconds + elapsed,
        )
        val afterRelease = plan.secondClockRates(plan.fadeEndSeconds + 2.3)
        assertEquals(1.0, afterRelease.first, 1e-12)
        assertEquals(0.0, afterRelease.second, 1e-12)
        assertTrue(
            abs(plan.secondClockRates(plan.fadeEndSeconds + .13).second) > .01,
            "Release fixture must exercise nonzero acceleration",
        )
    }

    @Test
    fun realAbruptTempoChangeRemainsRejectedEvenWhenSpeedsStayWithinPolicy() {
        val first = BeatGrid(DoubleArray(80) { it * .5 })
        val second = BeatGrid(DoubleArray(80) { if (it <= 8) it * .5 else 4.0 + (it - 8) * .7 })
        val plan = MixPlan(first, second, firstBeat = 0, crossfadeBeats = 24)
        val report = WarpQuality.assess(plan)
        assertFalse(report.accepted)
        assertTrue(report.minimumPlaybackSpeed >= .5)
        assertTrue(report.maximumPlaybackSpeed <= 2.0)
        assertTrue(report.maximumLogSpeedChangePerSecond > .8)
        assertTrue(report.issues.any { it.code == WarpQualityIssueCode.TEMPO_CHANGE_TOO_ABRUPT })
        assertTrue(report.issues.none { it.code == WarpQualityIssueCode.INVALID_CLOCK })
        val failure = assertFailsWith<UnsafeWarpException> { report.requireAccepted() }
        assertEquals(report, failure.report)
    }
}
