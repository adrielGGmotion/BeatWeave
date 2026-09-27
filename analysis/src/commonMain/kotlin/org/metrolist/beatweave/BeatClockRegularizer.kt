package org.metrolist.beatweave

import kotlin.math.*

/**
 * A bounded estimate-refinement policy, not permission to move musical ground truth. The default 20
 * ms bound is one frame of the optional 50 Hz learned detector. Callers with more precise
 * observations should supply a smaller bound, including zero.
 */
data class ClockFitOptions(
    val maximumDisplacementSeconds: Double = 0.020,
    /** Weight in s^-2 of squared timestamp displacement relative to squared speed differences. */
    val observationPenalty: Double = 30.0,
    val maximumOutputResidualP95Seconds: Double = 0.015,
    val maximumOutputResidualSeconds: Double = 0.025,
    val convergenceToleranceSeconds: Double = 1e-9,
    val maximumSweeps: Int = 10_000,
    /**
     * Incoming grid indices that must remain exactly at their observed times. Bar planners pin
     * every selected downbeat, including interior boundaries. Indices address the grid passed to
     * regularize, not an uncropped song grid.
     */
    val pinnedIncomingBeats: Set<Int> = emptySet(),
) {
    init {
        require(maximumDisplacementSeconds.isFinite() && maximumDisplacementSeconds >= 0.0)
        require(observationPenalty.isFinite() && observationPenalty > 0.0)
        require(
            maximumOutputResidualP95Seconds.isFinite() && maximumOutputResidualP95Seconds >= 0.0
        )
        require(maximumOutputResidualSeconds.isFinite() && maximumOutputResidualSeconds >= 0.0)
        require(maximumOutputResidualP95Seconds <= maximumOutputResidualSeconds)
        require(convergenceToleranceSeconds.isFinite() && convergenceToleranceSeconds > 0.0)
        require(maximumSweeps in 1..1_000_000)
        require(pinnedIncomingBeats.all { it >= 0 })
    }
}

enum class ClockFitFailureCode {
    INSUFFICIENT_OBSERVED_SUPPORT,
    NUMERICAL_FAILURE,
    NOT_CONVERGED,
    UNSAFE_CLOCK,
    ORIGINAL_ANCHOR_RESIDUAL_EXCEEDED,
}

/** All displacements refer to the original incoming estimates; outgoing anchors never move. */
data class BeatClockAdjustment(
    val incomingBeat: Int,
    val originalSourceSeconds: Double,
    val adjustedSourceSeconds: Double,
    val allowedDisplacementSeconds: Double,
    val pinned: Boolean,
    /** Null outside jointly observed, actively matched support. */
    val expectedOutputSeconds: Double?,
    /**
     * Actual mapping of the ORIGINAL incoming timestamp minus its original outgoing expectation.
     */
    val originalAnchorOutputResidualSeconds: Double?,
) {
    val displacementSeconds: Double
        get() = adjustedSourceSeconds - originalSourceSeconds
}

data class ClockFitReport(
    val converged: Boolean,
    val sweeps: Int,
    /** Infinity norm of the diagonally scaled, box-projected KKT residual, in seconds. */
    val projectedResidualSeconds: Double,
    val initialObjective: Double,
    val finalObjective: Double,
    val maximumSourceDisplacementSeconds: Double,
    val originalAnchorOutputResidualP95Seconds: Double,
    val maximumOriginalAnchorOutputResidualSeconds: Double,
    val observedAnchorCount: Int,
    val originalQuality: WarpQualityReport,
    val adjustedQuality: WarpQualityReport,
    val issues: List<ClockFitFailureCode>,
) {
    val accepted: Boolean
        get() = issues.isEmpty()
}

class BeatClockFitResult
internal constructor(
    val originalPlan: MixPlan,
    /** Inspection only on a declined fit. Use [requireAccepted] before rendering. */
    val candidatePlan: MixPlan,
    adjustments: List<BeatClockAdjustment>,
    val report: ClockFitReport,
) {
    val adjustments: List<BeatClockAdjustment> = adjustments.toList()
    val acceptedPlan: MixPlan?
        get() = candidatePlan.takeIf { report.accepted }

    fun requireAccepted(): MixPlan = acceptedPlan ?: throw UnsafeClockFitException(report)
}

class UnsafeClockFitException(val report: ClockFitReport) :
    IllegalArgumentException("Beat-clock refinement declined: ${report.issues.joinToString()}")

/**
 * Refines incoming timestamp estimates with a strictly convex, box-constrained fit:
 *
 *     sum ((x[i+1]-x[i])/h[i] - (x[i]-x[i-1])/h[i-1])^2
 *       + observationPenalty * sum (x[i] - original[i])^2.
 *
 * Here h contains intervals of the unchanged outgoing clock. The optimizer changes no beat count,
 * beat index, meter, pitch, cue, or outgoing timestamp. The first and last observed fit anchors,
 * the selected cue, and caller-selected boundaries are pinned. Unobserved regions and incoming
 * material after a released transition are not fitted. Each movable anchor is also bounded to less
 * than half each neighboring original interval, so every possible feasible result retains strict
 * beat order.
 *
 * Cyclic coordinate descent minimizes the positive-definite quadratic exactly in each coordinate.
 * Convergence is checked using independent projected KKT residuals. The complete source clock and
 * ORIGINAL-anchor output residuals are then checked; optimizer convergence alone never makes a
 * result acceptable. This is geometric validation of estimates, not proof of their musical
 * correctness or audio quality.
 */
object BeatClockRegularizer {
    fun regularize(
        plan: MixPlan,
        sourceDurationSeconds: Double,
        options: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        isCancelled: () -> Boolean = { false },
    ): BeatClockFitResult {
        require(
            sourceDurationSeconds.isFinite() &&
                sourceDurationSeconds > 0.0 &&
                sourceDurationSeconds <= 4 * 60 * 60
        )
        fun checkCancellation() {
            if (isCancelled()) throw MixCancelledException()
        }
        checkCancellation()
        fun quality(candidate: MixPlan): WarpQualityReport {
            checkCancellation()
            return WarpQuality.assess(
                candidate,
                candidate.secondOutputTime(0.0),
                candidate.secondOutputTime(sourceDurationSeconds),
                qualityLimits,
            )
        }
        val raw = plan.second.times
        val pinned = options.pinnedIncomingBeats.toSet()
        require(pinned.all { it in raw.indices }) {
            "A pinned incoming beat lies outside the supplied grid"
        }
        val originalQuality = quality(plan)
        val incomingPerOutgoing = plan.secondBeatsPerCycle.toDouble() / plan.firstBeatsPerCycle
        val outputPositions =
            DoubleArray(raw.size) { plan.firstBeat + (it - plan.secondBeat) / incomingPerOutgoing }
        // The clock supports complete paired outgoing intervals. With unequal
        // cycle ratios, a fractional endpoint just inside a grid can still lie
        // outside this support and use affine continuation instead.
        val coverage = plan.observedCoverage
        // Source observations outside the recording are not independent beat evidence.
        val supported =
            raw.indices.filter { i ->
                outputPositions[i] >= coverage.firstStartBeat &&
                    outputPositions[i] <= coverage.firstEndBeat &&
                    raw[i] >= 0.0 &&
                    raw[i] <= sourceDurationSeconds &&
                    (!plan.releaseAfterFade ||
                        outputPositions[i] <= plan.firstBeat + plan.crossfadeBeats)
            }
        val lower = supported.firstOrNull() ?: 0
        val upper = supported.lastOrNull() ?: -1
        val active = BooleanArray(raw.size) { it in lower..upper }
        val outputTimes = DoubleArray(raw.size) { plan.first.at(outputPositions[it]) }
        val bounds =
            DoubleArray(raw.size) { i ->
                if (
                    !active[i] ||
                        i == lower ||
                        i == upper ||
                        i == 0 ||
                        i == raw.lastIndex ||
                        i == plan.secondBeat ||
                        i in pinned
                )
                    0.0
                else
                    min(
                        options.maximumDisplacementSeconds,
                        0.49 * min(raw[i] - raw[i - 1], raw[i + 1] - raw[i]),
                    )
            }

        fun result(
            candidate: MixPlan,
            converged: Boolean,
            sweeps: Int,
            kkt: Double,
            before: Double,
            after: Double,
            failures: List<ClockFitFailureCode>,
        ): BeatClockFitResult {
            checkCancellation()
            val adjusted = candidate.second.times
            val adjustments =
                raw.indices.map { i ->
                    val expected = if (active[i]) outputTimes[i] else null
                    BeatClockAdjustment(
                        i,
                        raw[i],
                        adjusted[i],
                        bounds[i],
                        bounds[i] == 0.0,
                        expected,
                        expected?.let { candidate.secondOutputTime(raw[i]) - it },
                    )
                }
            val errors =
                adjustments
                    .mapNotNull { it.originalAnchorOutputResidualSeconds?.let(::abs) }
                    .sorted()
            val p95 = quantile(errors, 0.95)
            val maximum = errors.lastOrNull() ?: 0.0
            val measuredQuality = if (candidate === plan) originalQuality else quality(candidate)
            val issues = failures.toMutableList()
            if (!measuredQuality.accepted) issues += ClockFitFailureCode.UNSAFE_CLOCK
            if (
                p95 > options.maximumOutputResidualP95Seconds + 1e-9 ||
                    maximum > options.maximumOutputResidualSeconds + 1e-9
            )
                issues += ClockFitFailureCode.ORIGINAL_ANCHOR_RESIDUAL_EXCEEDED
            return BeatClockFitResult(
                plan,
                candidate,
                adjustments,
                ClockFitReport(
                    converged,
                    sweeps,
                    kkt,
                    before,
                    after,
                    adjustments.maxOf { abs(it.displacementSeconds) },
                    p95,
                    maximum,
                    errors.size,
                    originalQuality,
                    measuredQuality,
                    issues.distinct(),
                ),
            )
        }

        // Preserve already acceptable observations bit for bit; no gratuitous smoothing.
        if (originalQuality.accepted) return result(plan, true, 0, 0.0, 0.0, 0.0, emptyList())
        if (supported.size < 3)
            return result(
                plan,
                false,
                0,
                Double.POSITIVE_INFINITY,
                0.0,
                0.0,
                listOf(ClockFitFailureCode.INSUFFICIENT_OBSERVED_SUPPORT),
            )

        val count = upper - lower + 1
        val h = DoubleArray(count - 1) { outputTimes[lower + it + 1] - outputTimes[lower + it] }
        if (h.any { !it.isFinite() || it <= 0.0 })
            return result(
                plan,
                false,
                0,
                Double.POSITIVE_INFINITY,
                0.0,
                0.0,
                listOf(ClockFitFailureCode.NUMERICAL_FAILURE),
            )
        val c0 = DoubleArray(count - 2) { 1.0 / h[it] }
        val c2 = DoubleArray(count - 2) { 1.0 / h[it + 1] }
        val c1 = DoubleArray(count - 2) { -(c0[it] + c2[it]) }
        val residual =
            DoubleArray(count - 2) {
                (raw[lower + it + 2] - raw[lower + it + 1]) / h[it + 1] -
                    (raw[lower + it + 1] - raw[lower + it]) / h[it]
            }
        val diagonal = DoubleArray(count) { options.observationPenalty }
        for (r in residual.indices) {
            diagonal[r] += c0[r] * c0[r]
            diagonal[r + 1] += c1[r] * c1[r]
            diagonal[r + 2] += c2[r] * c2[r]
        }
        if (diagonal.any { !it.isFinite() } || residual.any { !it.isFinite() })
            return result(
                plan,
                false,
                0,
                Double.POSITIVE_INFINITY,
                0.0,
                0.0,
                listOf(ClockFitFailureCode.NUMERICAL_FAILURE),
            )

        val displacement = DoubleArray(count)
        val initialObjective = residual.sumOf { it * it }
        fun gradient(i: Int): Double {
            var value = options.observationPenalty * displacement[i]
            if (i < residual.size) value += c0[i] * residual[i]
            if (i >= 1 && i - 1 < residual.size) value += c1[i - 1] * residual[i - 1]
            if (i >= 2) value += c2[i - 2] * residual[i - 2]
            return value
        }
        fun projected(i: Int): Double {
            val bound = bounds[lower + i]
            return (displacement[i] - gradient(i) / diagonal[i]).coerceIn(-bound, bound)
        }
        fun update(i: Int) {
            val next = projected(i)
            val delta = next - displacement[i]
            displacement[i] = next
            if (i < residual.size) residual[i] += c0[i] * delta
            if (i >= 1 && i - 1 < residual.size) residual[i - 1] += c1[i - 1] * delta
            if (i >= 2) residual[i - 2] += c2[i - 2] * delta
        }
        var sweeps = 0
        var kkt = Double.POSITIVE_INFINITY
        while (sweeps < options.maximumSweeps) {
            checkCancellation()
            for (i in 0 until count) update(i)
            for (i in count - 1 downTo 0) update(i)
            sweeps++
            kkt = (0 until count).maxOf { abs(displacement[it] - projected(it)) }
            if (!kkt.isFinite() || kkt <= options.convergenceToleranceSeconds) break
        }
        val converged = kkt.isFinite() && kkt <= options.convergenceToleranceSeconds
        if (displacement.any { !it.isFinite() })
            return result(
                plan,
                false,
                sweeps,
                kkt,
                initialObjective,
                Double.NaN,
                listOf(ClockFitFailureCode.NUMERICAL_FAILURE),
            )
        val fitted = raw.copyOf()
        for (i in 0 until count) fitted[lower + i] += displacement[i]
        val objective =
            residual.sumOf { it * it } + options.observationPenalty * displacement.sumOf { it * it }
        return result(
            plan.copy(second = BeatGrid(fitted)),
            converged,
            sweeps,
            kkt,
            initialObjective,
            objective,
            if (converged) emptyList() else listOf(ClockFitFailureCode.NOT_CONVERGED),
        )
    }

    private fun quantile(sorted: List<Double>, probability: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val coordinate = (sorted.size - 1) * probability
        val low = floor(coordinate).toInt()
        val high = ceil(coordinate).toInt()
        return sorted[low] + (coordinate - low) * (sorted[high] - sorted[low])
    }
}
