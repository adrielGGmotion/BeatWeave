package org.metrolist.beatweave.learned

import kotlin.math.*
import org.metrolist.beatweave.Beat

/** Spectral onsets can consistently occupy an offbeat subdivision of the musical pulse. */
enum class PulsePhaseRelation {
    DIRECT_ALIGNMENT,
    STABLE_OFFSET,
    REGIONAL_SUPPORT,
    INCOHERENT,
    INSUFFICIENT,
}

/** A contiguous source-clock span whose phase relation remains stable throughout. */
data class PulsePhaseRange(
    val startSeconds: Double,
    val endSeconds: Double,
    val observedEvents: Int,
    val offsetCycles: Double,
    val concentration: Double,
    val offsetAgreement: Double,
)

/**
 * Evidence about the relation between two clocks, not permission to shift either clock.
 * [offsetCycles] is the neural clock's circular offset within the independent spectral pulse.
 * Concentration and agreement fractions are descriptive statistics, not probabilities.
 */
data class PulsePhaseEvidence(
    val relation: PulsePhaseRelation,
    val observedEvents: Int,
    val coverage: Double,
    val directAgreement: Double,
    val offsetCycles: Double?,
    val concentration: Double,
    val offsetAgreement: Double,
    val stableWindowFraction: Double,
    val supportedRanges: List<PulsePhaseRange> = emptyList(),
    val regionalCoverage: Double = 0.0,
) {
    val supportsCandidate: Boolean
        get() =
            relation == PulsePhaseRelation.DIRECT_ALIGNMENT ||
                relation == PulsePhaseRelation.STABLE_OFFSET ||
                relation == PulsePhaseRelation.REGIONAL_SUPPORT

    val consistency: Double
        get() =
            when (relation) {
                PulsePhaseRelation.DIRECT_ALIGNMENT -> directAgreement
                PulsePhaseRelation.STABLE_OFFSET -> offsetAgreement * stableWindowFraction
                PulsePhaseRelation.REGIONAL_SUPPORT -> regionalCoverage
                else -> 0.0
            }
}

/**
 * Recognizes an independently measured clock that consistently follows another subdivision.
 * Candidate support needs a concentrated circular distribution, timing inliers and stable
 * overlapping windows. Separate stable spans remain inspectable when the reference changes phase;
 * the caller must audit their local onset/cadence evidence and exclude the intervening uncertainty.
 * A gradual drift cannot acquire regional support by being divided into many short windows: every
 * merged span is audited again as a whole, including its first-half/second-half phase difference.
 * These majority statistics never replace [locallySupported] for canonical range acceptance.
 */
object PulsePhaseAudit {
    /**
     * Candidate statistics describe the predominant relation, not every observation. A requested
     * range needs measured reference coverage and an inlier at each canonical event. In particular,
     * a globally stable majority cannot authorize its outliers or an unmeasured source tail.
     * Regional evidence uses each retained range's own center; no source timestamp is adjusted.
     */
    internal fun locallySupported(
        observed: List<Beat>,
        reference: List<Beat>,
        evidence: PulsePhaseEvidence,
        cancellationCheck: () -> Unit = {},
    ): BooleanArray {
        cancellationCheck()
        val supported = BooleanArray(observed.size)
        if (
            !evidence.supportsCandidate ||
                reference.size < 2 ||
                reference.any { !it.seconds.isFinite() } ||
                reference.zipWithNext().any { (a, b) -> b.seconds <= a.seconds }
        )
            return supported
        val referencePeriods = measuredReferencePeriods(reference, cancellationCheck)
        for ((index, beat) in observed.withIndex()) {
            if (index % 32 == 0) cancellationCheck()
            if (!beat.seconds.isFinite()) continue
            val next =
                AutomaticPulseSelector.lowerBound(reference, beat.seconds).let {
                    if (it == 0 && beat.seconds == reference.first().seconds) 1 else it
                }
            if (next == 0 || next == reference.size) continue
            val period = referencePeriods[next - 1]
            if (!period.isFinite()) continue
            val phase = (beat.seconds - reference[next - 1].seconds) / period
            val tolerance = min(0.16, 0.075 / period)
            supported[index] =
                when (evidence.relation) {
                    PulsePhaseRelation.DIRECT_ALIGNMENT,
                    PulsePhaseRelation.STABLE_OFFSET ->
                        evidence.offsetCycles?.let {
                            it.isFinite() && circularDistance(phase, it) <= tolerance
                        } ?: false
                    PulsePhaseRelation.REGIONAL_SUPPORT ->
                        evidence.supportedRanges.any {
                            beat.seconds >= it.startSeconds &&
                                beat.seconds <= it.endSeconds &&
                                it.offsetCycles.isFinite() &&
                                circularDistance(phase, it.offsetCycles) <= tolerance
                        }
                    else -> false
                }
        }
        return supported
    }

    fun assess(
        observed: List<Beat>,
        reference: List<Beat>,
        cancellationCheck: () -> Unit = {},
    ): PulsePhaseEvidence {
        cancellationCheck()
        val insufficient =
            PulsePhaseEvidence(PulsePhaseRelation.INSUFFICIENT, 0, 0.0, 0.0, null, 0.0, 0.0, 0.0)
        if (observed.isEmpty() || reference.size < 2) return insufficient
        if (
            reference.any { !it.seconds.isFinite() } ||
                reference.zipWithNext().any { (a, b) -> b.seconds <= a.seconds }
        )
            return insufficient

        data class Observation(
            val seconds: Double,
            val phase: Double,
            val tolerance: Double,
            val referenceSegment: Int,
        )
        val phases = ArrayList<Observation>()
        val referencePeriods = measuredReferencePeriods(reference, cancellationCheck)
        val referenceSegments = IntArray(referencePeriods.size)
        var referenceSegment = 0
        for (index in referencePeriods.indices) {
            referenceSegments[index] = referenceSegment
            if (!referencePeriods[index].isFinite()) referenceSegment++
        }
        var directlyAligned = 0
        for ((eventIndex, beat) in observed.withIndex()) {
            if (eventIndex % 32 == 0) cancellationCheck()
            if (!beat.seconds.isFinite()) continue
            val next =
                AutomaticPulseSelector.lowerBound(reference, beat.seconds).let {
                    if (it == 0 && beat.seconds == reference.first().seconds) 1 else it
                }
            // Do not extrapolate an independent clock beyond the measured reference.
            if (next == 0 || next == reference.size) continue
            val left = reference[next - 1].seconds
            val period = referencePeriods[next - 1]
            if (!period.isFinite()) continue
            val phase = (beat.seconds - left) / period
            val tolerance = min(0.16, 0.075 / period)
            if (circularDistance(phase, 0.0) <= tolerance) directlyAligned++
            phases += Observation(beat.seconds, phase, tolerance, referenceSegments[next - 1])
        }
        val direct = directlyAligned.toDouble() / observed.size
        val coverage = phases.size.toDouble() / observed.size
        if (phases.isEmpty()) return insufficient.copy(directAgreement = direct)
        fun statistics(from: Int, until: Int): Pair<Double, Double> {
            var x = 0.0
            var y = 0.0
            for (i in from until until) {
                x += cos(2 * PI * phases[i].phase)
                y += sin(2 * PI * phases[i].phase)
            }
            return atan2(y, x) / (2 * PI) to hypot(x, y) / (until - from)
        }
        fun tolerance(from: Int, until: Int): Double =
            phases.subList(from, until).map { it.tolerance }.sorted().let { it[it.size / 2] }
        fun inlierFraction(from: Int, until: Int, center: Double): Double =
            (from until until)
                .count { circularDistance(phases[it].phase, center) <= phases[it].tolerance }
                .toDouble() / (until - from)
        fun halfDrift(from: Int, until: Int): Double {
            val middle = (from + until) / 2
            if (middle == from || middle == until) return Double.POSITIVE_INFINITY
            return circularDistance(statistics(from, middle).first, statistics(middle, until).first)
        }
        fun windowConsistency(from: Int, until: Int, center: Double): Double {
            var good = 0
            var count = 0
            for (start in from..until - 8 step 8) {
                val end = min(until, start + 16)
                val (localCenter, concentration) = statistics(start, end)
                count++
                if (
                    concentration >= 0.80 &&
                        circularDistance(localCenter, center) <= tolerance(start, end)
                )
                    good++
            }
            return if (count == 0) 0.0 else good.toDouble() / count
        }
        val (center, concentration) = statistics(0, phases.size)
        val inliers =
            phases.count { circularDistance(it.phase, center) <= it.tolerance }.toDouble() /
                phases.size
        var windows = 0
        var stableWindows = 0
        for (start in 0..max(0, phases.size - 8) step 8) {
            cancellationCheck()
            val end = min(phases.size, start + 16)
            if (end - start < 8) continue
            val (windowCenter, windowConcentration) = statistics(start, end)
            val tolerance = tolerance(start, end)
            windows++
            if (windowConcentration >= 0.80 && circularDistance(windowCenter, center) <= tolerance)
                stableWindows++
        }
        val stable = if (windows == 0) 0.0 else stableWindows.toDouble() / windows
        val globalStable =
            coverage >= 0.80 &&
                concentration >= 0.80 &&
                inliers >= 0.80 &&
                stable >= 0.80 &&
                halfDrift(0, phases.size) <= tolerance(0, phases.size) * 0.5

        data class Window(
            var start: Int,
            var end: Int,
            var center: Double,
            val referenceSegment: Int,
        )
        val merged = ArrayList<Window>()
        if (!globalStable && phases.size >= 16) {
            for (start in 0..phases.size - 16) {
                if (start % 32 == 0) cancellationCheck()
                val end = start + 16
                val segment = phases[start].referenceSegment
                if ((start until end).any { phases[it].referenceSegment != segment }) continue
                val (center, concentration) = statistics(start, end)
                val tolerance = tolerance(start, end)
                if (
                    concentration < 0.80 ||
                        inlierFraction(start, end, center) < 0.80 ||
                        halfDrift(start, end) > tolerance * 0.5
                )
                    continue
                val previous = merged.lastOrNull()
                if (
                    previous != null &&
                        previous.end >= start &&
                        previous.referenceSegment == segment &&
                        circularDistance(previous.center, center) <= tolerance
                ) {
                    previous.end = end
                    // Compare neighbouring windows during merging; audit the complete union below.
                    previous.center = center
                } else merged += Window(start, end, center, segment)
            }
        }
        val ranges =
            merged.mapNotNull { span ->
                val (center, concentration) = statistics(span.start, span.end)
                val inliers = inlierFraction(span.start, span.end, center)
                if (
                    concentration < 0.80 ||
                        inliers < 0.80 ||
                        halfDrift(span.start, span.end) > tolerance(span.start, span.end) * 0.5 ||
                        windowConsistency(span.start, span.end, center) < 0.80
                )
                    null
                else
                    PulsePhaseRange(
                        phases[span.start].seconds,
                        phases[span.end - 1].seconds,
                        span.end - span.start,
                        center,
                        concentration,
                        inliers,
                    )
            }
        val regionalCoverage =
            phases
                .count { phase ->
                    ranges.any {
                        phase.seconds >= it.startSeconds && phase.seconds <= it.endSeconds
                    }
                }
                .toDouble() / observed.size
        val relation =
            when {
                phases.size >= 8 && globalStable && direct >= 0.45 ->
                    PulsePhaseRelation.DIRECT_ALIGNMENT
                phases.size >= 16 && globalStable -> PulsePhaseRelation.STABLE_OFFSET
                regionalCoverage >= 0.55 -> PulsePhaseRelation.REGIONAL_SUPPORT
                phases.size < 8 -> PulsePhaseRelation.INSUFFICIENT
                else -> PulsePhaseRelation.INCOHERENT
            }
        return PulsePhaseEvidence(
            relation,
            phases.size,
            coverage,
            direct,
            center,
            concentration,
            inliers,
            stable,
            ranges,
            regionalCoverage,
        )
    }

    /** Reject brackets that skip one or more pulses instead of treating the whole hole as a period. */
    private fun measuredReferencePeriods(
        reference: List<Beat>,
        cancellationCheck: () -> Unit,
    ): DoubleArray {
        val measured = DoubleArray(reference.size - 1) { Double.NaN }
        for (intervalIndex in measured.indices) {
            if (intervalIndex % 32 == 0) cancellationCheck()
            val centre = intervalIndex + 1
            val lo = max(0, centre - 8)
            val hi = min(reference.lastIndex, centre + 8)
            val localIntervals =
                (lo until hi)
                    .filter { it != intervalIndex }
                    .map { reference[it + 1].seconds - reference[it].seconds }
                    .filter { it > 0 && it.isFinite() }
                    .sorted()
            val localPeriod = localIntervals.getOrNull(localIntervals.size / 2) ?: continue
            val period = reference[intervalIndex + 1].seconds - reference[intervalIndex].seconds
            if (period / localPeriod in 0.62..1.48) measured[intervalIndex] = period
        }
        return measured
    }

    private fun circularDistance(a: Double, b: Double): Double = abs((a - b + 1.5) % 1.0 - 0.5)
}
