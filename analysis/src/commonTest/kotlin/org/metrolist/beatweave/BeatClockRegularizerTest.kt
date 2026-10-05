package org.metrolist.beatweave

import kotlin.math.*
import kotlin.test.*

class BeatClockRegularizerTest {
    private fun jittered(amplitude: Double = 0.024): MixPlan {
        val outgoing = BeatGrid(DoubleArray(80) { 0.2 + it * 0.4 })
        val incoming =
            BeatGrid(
                DoubleArray(80) {
                    0.3 + it * 0.4 + if (it in 1..78) amplitude * sin(it * 1.9) else 0.0
                }
            )
        return MixPlan(outgoing, incoming, 0, outputSampleRate = 48000)
    }

    @Test
    fun acceptableClockIsReturnedWithoutChangingAnyEstimateOrPlanOption() {
        val a = BeatGrid(DoubleArray(100) { 0.2 + it * 0.4 })
        val b = BeatGrid(DoubleArray(100) { 0.3 + it * 0.6 })
        val plan = MixPlan(a, b, 8, 4, 12, 48000, 3, 2, true)
        val fit = BeatClockRegularizer.regularize(plan, 61.0)
        assertSame(plan, fit.requireAccepted())
        assertEquals(0, fit.report.sweeps)
        assertTrue(fit.adjustments.all { it.displacementSeconds == 0.0 })
    }

    @Test
    fun boundedIncomingFitPreservesCuesOutgoingClockAndOriginalOracle() {
        val original = jittered()
        val a = original.first.times
        val b = original.second.times
        val fit = BeatClockRegularizer.regularize(original, 32.5)
        val adjusted = fit.requireAccepted()
        assertSame(original.first, adjusted.first)
        assertContentEquals(a, original.first.times)
        assertContentEquals(b, original.second.times)
        assertEquals(b.first(), adjusted.second.at(0))
        assertEquals(b.last(), adjusted.second.at(b.lastIndex))
        assertTrue(fit.report.sweeps > 0)
        assertTrue(fit.report.finalObjective < fit.report.initialObjective)
        assertTrue(fit.report.projectedResidualSeconds <= 1e-9)
        assertFalse(fit.report.originalQuality.accepted)
        assertTrue(fit.report.adjustedQuality.accepted)
        for (item in fit.adjustments) {
            assertTrue(abs(item.displacementSeconds) <= item.allowedDisplacementSeconds + 1e-12)
            if (item.pinned) assertEquals(item.originalSourceSeconds, item.adjustedSourceSeconds)
            item.expectedOutputSeconds?.let { target ->
                // The oracle is the original observation, never the fitted timestamp.
                assertEquals(
                    adjusted.secondOutputTime(b[item.incomingBeat]) - target,
                    item.originalAnchorOutputResidualSeconds!!,
                    1e-12,
                )
            }
        }
        assertTrue(
            adjusted.second.times.asList().zipWithNext().all { (left, right) -> right > left }
        )
    }

    @Test
    fun numericallySafeClockIsRejectedWhenItMovesOriginalBeatsTooFar() {
        val fit = BeatClockRegularizer.regularize(jittered(0.030), 32.5)
        assertTrue(fit.report.converged)
        assertTrue(fit.report.adjustedQuality.accepted)
        assertTrue(ClockFitFailureCode.ORIGINAL_ANCHOR_RESIDUAL_EXCEEDED in fit.report.issues)
        assertNull(fit.acceptedPlan)
        val failure = assertFailsWith<UnsafeClockFitException> { fit.requireAccepted() }
        assertSame(fit.report, failure.report)
    }

    @Test
    fun insufficientDisplacementBudgetDeclinesInsteadOfExpandingTheBounds() {
        val fit =
            BeatClockRegularizer.regularize(
                jittered(),
                32.5,
                ClockFitOptions(maximumDisplacementSeconds = 0.00001),
            )
        assertTrue(fit.report.converged)
        assertTrue(ClockFitFailureCode.UNSAFE_CLOCK in fit.report.issues)
        assertTrue(fit.report.maximumSourceDisplacementSeconds <= 0.00001 + 1e-12)
        assertNull(fit.acceptedPlan)
    }

    @Test
    fun iterationLimitCannotProduceAnAcceptedUnconvergedFit() {
        val fit =
            BeatClockRegularizer.regularize(jittered(), 32.5, ClockFitOptions(maximumSweeps = 1))
        assertFalse(fit.report.converged)
        assertTrue(ClockFitFailureCode.NOT_CONVERGED in fit.report.issues)
        assertNull(fit.acceptedPlan)
    }

    @Test
    fun cancellationIsCheckedBeforeWorkAndDuringOptimization() {
        assertFailsWith<MixCancelledException> {
            BeatClockRegularizer.regularize(jittered(), 32.5, isCancelled = { true })
        }
        var polls = 0
        assertFailsWith<MixCancelledException> {
            BeatClockRegularizer.regularize(jittered(), 32.5, isCancelled = { ++polls >= 7 })
        }
        assertEquals(7, polls)
    }

    @Test
    fun cancellationInterruptsLongClockQualityAssessment() {
        val grid = BeatGrid(DoubleArray(50_000) { it * 0.01 })
        val plan = MixPlan(grid, grid, 0)
        var polls = 0

        assertFailsWith<MixCancelledException> {
            BeatClockRegularizer.regularize(
                plan,
                grid.at(grid.size - 1) + 0.01,
                isCancelled = { ++polls == 5 },
            )
        }

        // Before quality-scan polling, an already acceptable clock returned after three polls
        // and completed the entire 50,000-beat assessment instead of observing cancellation.
        assertEquals(5, polls)
    }

    @Test
    fun releasedTransitionDoesNotFitOrJudgeIntentionalPostFadeDivergence() {
        val plan =
            jittered()
                .copy(firstBeat = 6, secondBeat = 2, crossfadeBeats = 16, releaseAfterFade = true)
        val fit = BeatClockRegularizer.regularize(plan, 32.5)
        val adjusted = fit.requireAccepted()
        assertEquals(plan.firstBeat, adjusted.firstBeat)
        assertEquals(plan.secondBeat, adjusted.secondBeat)
        assertEquals(plan.crossfadeBeats, adjusted.crossfadeBeats)
        assertEquals(plan.outputSampleRate, adjusted.outputSampleRate)
        assertTrue(adjusted.releaseAfterFade)
        assertEquals(plan.second.at(2), adjusted.second.at(2))
        assertEquals(plan.second.at(18), adjusted.second.at(18))
        for (i in 19 until plan.second.size) {
            assertEquals(plan.second.at(i), adjusted.second.at(i))
            assertNull(fit.adjustments[i].originalAnchorOutputResidualSeconds)
        }
    }

    @Test
    fun incomingOutroWithoutOutgoingObservationsIsLeftUnchanged() {
        val plan = jittered().let { it.copy(first = BeatGrid(it.first.times.copyOf(32))) }
        val fit = BeatClockRegularizer.regularize(plan, 32.5)
        val adjusted = fit.requireAccepted()
        for (i in 31 until plan.second.size) assertEquals(plan.second.at(i), adjusted.second.at(i))
        assertTrue(fit.adjustments.drop(32).all { it.expectedOutputSeconds == null })
    }

    @Test
    fun fractionalCycleEndpointOutsideCompletePairedIntervalsIsNotAnObservedOracle() {
        val plan =
            jittered()
                .copy(
                    firstBeat = 2,
                    secondBeat = 1,
                    firstBeatsPerCycle = 3,
                    secondBeatsPerCycle = 2,
                )
        // Incoming beat0 would correspond to outgoing position0.5, before the
        // first complete paired interval at outgoing beat1. It is continuation.
        assertEquals(1, plan.observedCoverage.firstStartBeat)
        val fit =
            BeatClockRegularizer.regularize(
                plan,
                32.5,
                qualityLimits = WarpQualityLimits(maximumLogSpeedChangePerSecond = 0.001),
            )
        assertNull(fit.adjustments[0].expectedOutputSeconds)
        assertNull(fit.adjustments[0].originalAnchorOutputResidualSeconds)
        assertEquals(plan.second.at(0), fit.candidatePlan.second.at(0))
        for (item in fit.adjustments) item.expectedOutputSeconds?.let { time ->
            assertTrue(time >= plan.observedCoverage.outputStartSeconds)
            assertTrue(time <= plan.observedCoverage.outputEndSeconds)
        }
    }

    @Test
    fun invalidNumericalPolicyIsRejectedBeforeOptimization() {
        assertFailsWith<IllegalArgumentException> { ClockFitOptions(observationPenalty = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            ClockFitOptions(maximumDisplacementSeconds = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            ClockFitOptions(maximumOutputResidualSeconds = 0.001)
        }
        assertFailsWith<IllegalArgumentException> {
            BeatClockRegularizer.regularize(jittered(), Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            ClockFitOptions(pinnedIncomingBeats = setOf(-1))
        }
        assertFailsWith<IllegalArgumentException> {
            BeatClockRegularizer.regularize(
                jittered(),
                32.5,
                ClockFitOptions(pinnedIncomingBeats = setOf(80)),
            )
        }
    }

    @Test
    fun interiorBarBoundariesStayObservedWhileOtherBeatsCanBeRefined() {
        val original =
            jittered().let { plan ->
                plan.copy(
                    second =
                        BeatGrid(
                            DoubleArray(80) { i ->
                                0.3 +
                                    i * 0.4 +
                                    if (i in 1..78 && i % 4 != 0) 0.024 * sin(i * 1.9) else 0.0
                            }
                        )
                )
            }
        val boundaries = (0 until 80 step 4).toSet()
        val fit =
            BeatClockRegularizer.regularize(
                original,
                32.5,
                ClockFitOptions(pinnedIncomingBeats = boundaries),
            )
        assertTrue(fit.report.sweeps > 0)
        assertTrue(fit.adjustments.any { !it.pinned && abs(it.displacementSeconds) > 1e-6 })
        for (index in boundaries) {
            val item = fit.adjustments[index]
            assertTrue(item.pinned)
            assertEquals(0.0, item.allowedDisplacementSeconds)
            assertEquals(original.second.at(index), fit.candidatePlan.second.at(index))
            assertEquals(
                original.first.at(index),
                fit.candidatePlan.secondOutputTime(original.second.at(index)),
                1e-8,
            )
        }
    }

    @Test
    fun pinnedUnsafeObservationsAreRejectedInsteadOfQuietlyMoved() {
        val original = jittered()
        val fit =
            BeatClockRegularizer.regularize(
                original,
                32.5,
                ClockFitOptions(pinnedIncomingBeats = original.second.times.indices.toSet()),
            )
        assertTrue(fit.report.converged)
        assertTrue(ClockFitFailureCode.UNSAFE_CLOCK in fit.report.issues)
        assertNull(fit.acceptedPlan)
        assertContentEquals(original.second.times, fit.candidatePlan.second.times)
        assertTrue(fit.adjustments.all { it.pinned && it.displacementSeconds == 0.0 })
    }

    @Test
    fun quadraticOptimizerAgreesWithAnIndependentlySolvedOneVariableProblem() {
        val a = BeatGrid(doubleArrayOf(0.0, 0.4, 0.8, 1.2))
        val b = BeatGrid(doubleArrayOf(0.0, 0.42, 0.8, 1.2))
        // Endpoints and cue1 are fixed: only incoming anchor2 can move by d.
        // The objective is (-0.1 + 2.5d)^2 + (0.05 - 5d)^2 + 30d^2.
        // Its unconstrained minimum is d = 0.5 / 61.25.
        val plan = MixPlan(a, b, 1, 1)
        val strictClock = WarpQualityLimits(maximumLogSpeedChangePerSecond = 0.0001)
        val unconstrained =
            BeatClockRegularizer.regularize(
                plan,
                1.4,
                ClockFitOptions(maximumDisplacementSeconds = 0.1),
                strictClock,
            )
        assertTrue(unconstrained.report.converged)
        assertEquals(0.8 + 0.5 / 61.25, unconstrained.candidatePlan.second.at(2), 1e-12)
        // A box below that minimum must be active, rather than exceeded.
        val constrained =
            BeatClockRegularizer.regularize(
                plan,
                1.4,
                ClockFitOptions(maximumDisplacementSeconds = 0.005),
                strictClock,
            )
        assertTrue(constrained.report.converged)
        assertEquals(0.805, constrained.candidatePlan.second.at(2), 1e-12)
    }
}
