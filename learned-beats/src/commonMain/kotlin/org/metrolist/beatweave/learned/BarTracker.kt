package org.metrolist.beatweave.learned

import kotlin.math.*
import org.metrolist.beatweave.AcousticPulseReport
import org.metrolist.beatweave.BarGrid
import org.metrolist.beatweave.Beat
import org.metrolist.beatweave.BeatGrid

/**
 * Meter means canonical pulses per bar, not a time-signature denominator. For example, a six-pulse
 * bar can describe compound meter. Odd meters are supported by supplying their pulse counts. No
 * four-beat default is silently imposed.
 */
data class BarTrackingOptions(
    val pulsesPerBar: List<Int> = listOf(2, 3, 4, 6, 8),
    /** Same conservative bar-boundary change prior as madmom's bar tracker. */
    val meterChangeProbability: Double = 1e-7,
    val minimumStatePosterior: Double = 0.9,
    val minimumBarsPerRegion: Int = 3,
    /** Beat This! uses a +/-3-frame tolerance at 50 frames per second. */
    val observationRadiusSeconds: Double = 0.06,
    val activationHopSeconds: Double = 0.02,
) {
    init {
        require(pulsesPerBar.isNotEmpty() && pulsesPerBar.size <= 16)
        require(
            pulsesPerBar.all { it in 2..32 } && pulsesPerBar.distinct().size == pulsesPerBar.size
        )
        require(
            meterChangeProbability.isFinite() &&
                meterChangeProbability > 0.0 &&
                meterChangeProbability < 1.0
        )
        require(minimumStatePosterior.isFinite() && minimumStatePosterior in 0.5..1.0)
        require(minimumBarsPerRegion >= 2)
        require(observationRadiusSeconds.isFinite() && observationRadiusSeconds in 0.0..0.1)
        require(activationHopSeconds.isFinite() && activationHopSeconds > 0.0)
    }
}

data class MeterRegion(
    val startBeat: Int,
    val endBeatExclusive: Int,
    val pulsesPerBar: Int,
    val completeBars: Int,
    val meanStatePosterior: Double,
    val minimumStatePosterior: Double,
    val observedBoundaries: Int,
    val meanDownbeatScore: Double,
    val meanOtherBeatScore: Double,
)

enum class BarBoundaryEditKind {
    INFERRED,
    REMOVED,
}

data class BarBoundaryEdit(
    val kind: BarBoundaryEditKind,
    val seconds: Double,
    val nearestBeatIndex: Int,
    val modelDownbeatScore: Double,
)

data class BarBoundaryEvidence(
    val beatIndex: Int,
    val seconds: Double,
    val observed: Boolean,
    /** Uncalibrated sigmoid model score, distinct from the HMM posterior. */
    val modelDownbeatScore: Double,
    /** Sum of the HMM phase-zero probabilities across meters. */
    val downbeatPosterior: Double,
    /** False at a clipped observation window; an edge prediction remains visible but unaccepted. */
    val completeObservationWindow: Boolean,
)

class UncertainBarsException(message: String) : IllegalStateException(message)

/** Independent reasons a proposed bar cannot be selected automatically. */
enum class BarUsabilityIssue {
    UNSUPPORTED_PULSE,
    INCOMPLETE_OBSERVATION_WINDOW,
    WEAK_BOUNDARY_EVIDENCE,
    CONFLICTING_DOWNBEAT_EVIDENCE,
    INSUFFICIENT_METER_EVIDENCE,
    UNCERTAIN_METER_PHASE,
    UNSUPPORTED_ACOUSTIC_PULSE,
}

/**
 * A proposed bar interpretation and an audit of every changed boundary. HMM posteriors express
 * certainty under this model; they are not measured musical accuracy. Retaining this result never
 * alters the beat grid or raw observations.
 */
class BarTrackingResult
internal constructor(
    beatSeconds: DoubleArray,
    val rawDownbeatSeconds: List<Double>,
    val boundaries: List<BarBoundaryEvidence>,
    val regions: List<MeterRegion>,
    val edits: List<BarBoundaryEdit>,
    barStatePosteriors: DoubleArray,
    barRegionSupported: BooleanArray,
    val options: BarTrackingOptions,
    pulseSupported: BooleanArray? = null,
    acousticPulseSupported: BooleanArray? = null,
) {
    private val seconds = beatSeconds.copyOf()
    private val statePosteriors = barStatePosteriors.copyOf()
    private val regionSupported = barRegionSupported.copyOf()
    private val pulseSupport = pulseSupported?.copyOf()
    private val acousticPulseSupport = acousticPulseSupported?.copyOf()
    /**
     * A positive raw downbeat excluded by the HMM is conflicting evidence when both its source time
     * and nearest canonical beat are strictly inside a proposed bar. Near-boundary duplicate
     * observations are therefore excluded. This local consistency guard uses the existing 0.5
     * evidence threshold; it cannot identify a systematic wrong meter shared by model and HMM.
     */
    private val conflictingDownbeat =
        BooleanArray(max(0, boundaries.size - 1)).also { conflict ->
            for (edit in edits) {
                if (edit.kind != BarBoundaryEditKind.REMOVED || edit.modelDownbeatScore < 0.5)
                    continue
                val boundary = boundaries.binarySearchBy(edit.nearestBeatIndex) { it.beatIndex }
                if (boundary >= 0) continue
                val bar = -boundary - 2
                if (
                    bar in conflict.indices &&
                        edit.seconds > boundaries[bar].seconds &&
                        edit.seconds < boundaries[bar + 1].seconds
                )
                    conflict[bar] = true
            }
        }
    val barCount: Int
        get() = max(0, boundaries.size - 1)

    val downbeatSeconds: List<Double>
        get() = boundaries.map { it.seconds }

    val barMinimumStatePosteriors: DoubleArray
        get() = statePosteriors.copyOf()

    /** Pure BarTracker output describes phase evidence; the local facade adds pulse acceptance. */
    val hasPulseSupportAudit: Boolean
        get() = pulseSupport != null

    val barPulseSupported: BooleanArray?
        get() = pulseSupport?.copyOf()

    val hasAcousticPulseAudit: Boolean
        get() = acousticPulseSupport != null

    val barAcousticPulseSupported: BooleanArray?
        get() = acousticPulseSupport?.copyOf()

    val safeForAutomaticBars: Boolean
        get() = barCount > 0 && (0 until barCount).all { isBarUsable(it) }

    fun isBarUsable(bar: Int): Boolean {
        require(bar in 0 until barCount)
        return (pulseSupport?.get(bar) != false) &&
            (acousticPulseSupport?.get(bar) != false) &&
            boundaries[bar].completeObservationWindow &&
            boundaries[bar + 1].completeObservationWindow &&
            boundaries[bar].modelDownbeatScore >= 0.5 &&
            boundaries[bar + 1].modelDownbeatScore >= 0.5 &&
            !conflictingDownbeat[bar] &&
            regionSupported[bar] &&
            statePosteriors[bar] >= options.minimumStatePosterior
    }

    /** All failing gates, without changing the proposal or suppressing concurrent uncertainty. */
    fun issuesForBar(bar: Int): List<BarUsabilityIssue> {
        require(bar in 0 until barCount)
        return buildList {
            if (pulseSupport?.get(bar) == false) add(BarUsabilityIssue.UNSUPPORTED_PULSE)
            if (acousticPulseSupport?.get(bar) == false)
                add(BarUsabilityIssue.UNSUPPORTED_ACOUSTIC_PULSE)
            if (
                !boundaries[bar].completeObservationWindow ||
                    !boundaries[bar + 1].completeObservationWindow
            )
                add(BarUsabilityIssue.INCOMPLETE_OBSERVATION_WINDOW)
            if (
                boundaries[bar].modelDownbeatScore < 0.5 ||
                    boundaries[bar + 1].modelDownbeatScore < 0.5
            )
                add(BarUsabilityIssue.WEAK_BOUNDARY_EVIDENCE)
            if (conflictingDownbeat[bar]) add(BarUsabilityIssue.CONFLICTING_DOWNBEAT_EVIDENCE)
            if (!regionSupported[bar]) add(BarUsabilityIssue.INSUFFICIENT_METER_EVIDENCE)
            if (statePosteriors[bar] < options.minimumStatePosterior)
                add(BarUsabilityIssue.UNCERTAIN_METER_PHASE)
        }
    }

    /**
     * Adds an immutable pulse-evidence mask without changing any proposal or posterior. A complete
     * bar, including both endpoints, must be contained in one independently accepted canonical
     * pulse region. Empty input declines all.
     */
    fun withPulseSupport(regions: List<AcceptedPulseRegion>): BarTrackingResult {
        val support =
            BooleanArray(barCount) { bar ->
                regions.any {
                    it.quality.safeForAutomaticMix &&
                        it.contains(boundaries[bar].beatIndex, boundaries[bar + 1].beatIndex + 1)
                }
            }
        return BarTrackingResult(
            seconds,
            rawDownbeatSeconds,
            boundaries,
            this.regions,
            edits,
            statePosteriors,
            regionSupported,
            options,
            support,
            acousticPulseSupport,
        )
    }

    /** Requires direct local activity and complete context for every interval in each bar. */
    fun withAcousticPulseSupport(evidence: AcousticPulseReport): BarTrackingResult {
        evidence.requireClock(seconds)
        val support =
            BooleanArray(barCount) { bar ->
                evidence.supportsRange(boundaries[bar].beatIndex, boundaries[bar + 1].beatIndex + 1)
            }
        return BarTrackingResult(
            seconds,
            rawDownbeatSeconds,
            boundaries,
            regions,
            edits,
            statePosteriors,
            regionSupported,
            options,
            pulseSupport,
            support,
        )
    }

    /** Proposed geometry only. Call [requireUsable] on the selected range before using it. */
    fun grid(): BarGrid {
        if (boundaries.size < 2)
            throw UncertainBarsException("At least two supported bar boundaries are required")
        return BarGrid(BeatGrid(seconds), boundaries.map { it.beatIndex }.toIntArray())
    }

    /** Uncertainty elsewhere in a song does not invalidate a reliable selected transition. */
    fun requireUsable(firstBar: Int = 0, barCount: Int = this.barCount): BarTrackingResult {
        if (this.barCount == 0) throw UncertainBarsException("No complete bars were found")
        require(firstBar >= 0 && barCount > 0 && firstBar <= this.barCount - barCount)
        val uncertain = (firstBar until firstBar + barCount).firstOrNull { !isBarUsable(it) }
        if (uncertain != null) {
            val reason =
                if (pulseSupport?.get(uncertain) == false)
                    "underlying beat timestamps are outside a jointly accepted pulse region"
                else if (acousticPulseSupport?.get(uncertain) == false)
                    "complete local recurring attack evidence is unavailable for this bar"
                else if (
                    !boundaries[uncertain].completeObservationWindow ||
                        !boundaries[uncertain + 1].completeObservationWindow
                )
                    "a boundary has an incomplete activation window at the recording edge"
                else if (
                    boundaries[uncertain].modelDownbeatScore < 0.5 ||
                        boundaries[uncertain + 1].modelDownbeatScore < 0.5
                )
                    "a boundary lacks positive downbeat model evidence despite its inferred phase"
                else if (conflictingDownbeat[uncertain])
                    "an excluded positive raw downbeat contradicts this bar's interior"
                else if (!regionSupported[uncertain])
                    "insufficient repeated meter/downbeat evidence"
                else
                    "meter/phase posterior ${statePosteriors[uncertain]} is below ${options.minimumStatePosterior}"
            throw UncertainBarsException("Bar $uncertain is uncertain: $reason")
        }
        return this
    }
}

/**
 * Beat-synchronous hidden Markov bar tracker. State is (meter, phase), phase advances on each
 * supplied beat, and meter may change only at a bar boundary. Viterbi selects a path;
 * forward/backward computes separate confidence evidence.
 *
 * Structure follows the standard DBNBarTrackingProcessor model described in madmom and Krebs et
 * al., ISMIR 2015. This is an independent implementation, using unweighted Bernoulli emissions from
 * Beat This! downbeat activations. It does not claim parity with madmom or turn model probabilities
 * into truth. Runtime is O(beats * (sum(meters) + numberOfMeters^2)); no PCM or networking.
 */
object BarTracker {
    fun track(
        beats: List<Beat>,
        rawDownbeatSeconds: List<Double>,
        downbeatLogits: FloatArray,
        options: BarTrackingOptions = BarTrackingOptions(),
        cancellationCheck: () -> Unit = {},
    ): BarTrackingResult {
        cancellationCheck()
        require(beats.all { it.seconds.isFinite() && it.seconds >= 0.0 })
        require(beats.zipWithNext().all { (a, b) -> b.seconds > a.seconds })
        require(rawDownbeatSeconds.all { it.isFinite() && it >= 0.0 })
        require(rawDownbeatSeconds.zipWithNext().all { (a, b) -> b > a })
        require(downbeatLogits.all { it.isFinite() })
        val times = beats.map { it.seconds }.toDoubleArray()
        val meters = options.pulsesPerBar.sorted()
        val snapshot = options.copy(pulsesPerBar = meters.toList())
        if (times.isEmpty() || downbeatLogits.isEmpty()) {
            return BarTrackingResult(
                times,
                rawDownbeatSeconds.toList(),
                emptyList(),
                emptyList(),
                emptyList(),
                DoubleArray(0),
                BooleanArray(0),
                snapshot,
            )
        }
        require(times.last() <= downbeatLogits.size * options.activationHopSeconds) {
            "Downbeat activations do not cover the supplied beat clock"
        }
        val states =
            meters.flatMap { meter -> (0 until meter).map { phase -> State(meter, phase) } }
        val stateCount = states.size
        val first = meters.associateWith { meter -> states.indexOfFirst { it.meter == meter } }
        val downbeatStates = IntArray(meters.size) { first.getValue(meters[it]) }
        val incoming = Array(stateCount) { ArrayList<Edge>() }
        val outgoing = Array(stateCount) { ArrayList<Edge>() }
        fun edge(from: Int, to: Int, probability: Double) {
            val cost = ln(probability)
            incoming[to].add(Edge(from, cost))
            outgoing[from].add(Edge(to, cost))
        }
        states.forEachIndexed { index, state ->
            if (state.phase < state.meter - 1) edge(index, index + 1, 1.0)
            else
                for (meter in meters) {
                    val probability =
                        when {
                            meters.size == 1 -> 1.0
                            meter == state.meter -> 1.0 - options.meterChangeProbability
                            else -> options.meterChangeProbability / (meters.size - 1)
                        }
                    edge(index, first.getValue(meter), probability)
                }
        }
        val completeWindow = BooleanArray(times.size)
        val observedLogits =
            DoubleArray(times.size) { beat ->
                if (beat % 256 == 0) cancellationCheck()
                val leftInterval =
                    if (beat > 0) times[beat] - times[beat - 1] else Double.POSITIVE_INFINITY
                val rightInterval =
                    if (beat < times.lastIndex) times[beat + 1] - times[beat]
                    else Double.POSITIVE_INFINITY
                val radius =
                    min(options.observationRadiusSeconds, min(leftInterval, rightInterval) / 4)
                completeWindow[beat] =
                    times[beat] - radius >= -1e-9 &&
                        times[beat] + radius <=
                            downbeatLogits.lastIndex * options.activationHopSeconds + 1e-9
                val start =
                    ceil((times[beat] - radius) / options.activationHopSeconds - 1e-9)
                        .toInt()
                        .coerceAtLeast(0)
                val end =
                    floor((times[beat] + radius) / options.activationHopSeconds + 1e-9)
                        .toInt()
                        .coerceAtMost(downbeatLogits.lastIndex)
                (start..end).maxOfOrNull { downbeatLogits[it].toDouble() } ?: -1000.0
            }
        val noDownbeat = DoubleArray(times.size) { -softplus(observedLogits[it]) }
        val downbeat = DoubleArray(times.size) { -softplus(-observedLogits[it]) }
        fun emission(beat: Int, state: Int): Double =
            if (states[state].phase == 0) downbeat[beat] else noDownbeat[beat]
        val alpha = Array(times.size) { DoubleArray(stateCount) }
        val back = Array(times.size) { IntArray(stateCount) }
        var best =
            DoubleArray(stateCount) { state ->
                -ln(meters.size.toDouble()) - ln(states[state].meter.toDouble()) +
                    emission(0, state)
            }
        best.copyInto(alpha[0])
        for (beat in 1 until times.size) {
            if (beat % 256 == 0) cancellationCheck()
            val next = DoubleArray(stateCount)
            for (state in states.indices) {
                var predecessor = 0
                var maximum = Double.NEGATIVE_INFINITY
                var sum = Double.NEGATIVE_INFINITY
                for (edge in incoming[state]) {
                    val value = best[edge.state] + edge.logProbability
                    if (value > maximum) {
                        maximum = value
                        predecessor = edge.state
                    }
                    sum = logAdd(sum, alpha[beat - 1][edge.state] + edge.logProbability)
                }
                next[state] = maximum + emission(beat, state)
                alpha[beat][state] = sum + emission(beat, state)
                back[beat][state] = predecessor
            }
            best = next
        }
        val path = IntArray(times.size)
        path[path.lastIndex] = best.indices.maxBy { best[it] }
        for (beat in path.lastIndex downTo 1) path[beat - 1] = back[beat][path[beat]]
        val evidence = alpha.last().fold(Double.NEGATIVE_INFINITY, ::logAdd)
        val statePosterior = DoubleArray(times.size)
        val downbeatPosterior = DoubleArray(times.size)
        var beta = DoubleArray(stateCount)
        for (beat in path.lastIndex downTo 0) {
            if (beat % 256 == 0) cancellationCheck()
            statePosterior[beat] =
                exp(alpha[beat][path[beat]] + beta[path[beat]] - evidence).coerceIn(0.0, 1.0)
            var downbeatProbability = 0.0
            for (state in downbeatStates)
                downbeatProbability += exp(alpha[beat][state] + beta[state] - evidence)
            downbeatPosterior[beat] = downbeatProbability.coerceIn(0.0, 1.0)
            if (beat > 0) {
                val previous =
                    DoubleArray(stateCount) { state ->
                        outgoing[state].fold(Double.NEGATIVE_INFINITY) { sum, edge ->
                            logAdd(
                                sum,
                                edge.logProbability + emission(beat, edge.state) + beta[edge.state],
                            )
                        }
                    }
                beta = previous
            }
        }
        val mappedRaw = rawDownbeatSeconds.map { time -> nearest(times, time) }
        val observed = BooleanArray(times.size)
        val retainedRaw = IntArray(times.size) { -1 }
        mappedRaw.forEachIndexed { raw, index ->
            val distance = abs(rawDownbeatSeconds[raw] - times[index])
            if (
                distance <= options.observationRadiusSeconds + options.activationHopSeconds / 2 &&
                    (retainedRaw[index] < 0 ||
                        distance < abs(rawDownbeatSeconds[retainedRaw[index]] - times[index]))
            ) {
                observed[index] = true
                retainedRaw[index] = raw
            }
        }
        val boundaryIndices = times.indices.filter { states[path[it]].phase == 0 }
        val boundaries =
            boundaryIndices.map { beat ->
                BarBoundaryEvidence(
                    beat,
                    times[beat],
                    observed[beat],
                    sigmoid(observedLogits[beat]),
                    downbeatPosterior[beat],
                    completeWindow[beat],
                )
            }
        val boundarySet = boundaryIndices.toSet()
        val edits = ArrayList<BarBoundaryEdit>()
        boundaries
            .filter { !it.observed }
            .forEach {
                edits.add(
                    BarBoundaryEdit(
                        BarBoundaryEditKind.INFERRED,
                        it.seconds,
                        it.beatIndex,
                        it.modelDownbeatScore,
                    )
                )
            }
        rawDownbeatSeconds.forEachIndexed { raw, time ->
            val index = mappedRaw[raw]
            if (retainedRaw[index] != raw || index !in boundarySet)
                edits.add(
                    BarBoundaryEdit(
                        BarBoundaryEditKind.REMOVED,
                        time,
                        index,
                        sigmoid(observedLogits[index]),
                    )
                )
        }
        val regionStarts =
            listOf(0) +
                (1 until times.size).filter {
                    states[path[it]].meter != states[path[it - 1]].meter
                } +
                times.size
        val regionIndex = IntArray(times.size)
        val regions =
            regionStarts.zipWithNext().mapIndexed { index, (start, end) ->
                for (beat in start until end) regionIndex[beat] = index
                val meter = states[path[start]].meter
                val complete =
                    boundaryIndices.count {
                        it >= start && it + meter <= end && it + meter <= times.lastIndex
                    }
                val downbeatScores =
                    (start until end)
                        .filter { it in boundarySet }
                        .map { sigmoid(observedLogits[it]) }
                val otherScores =
                    (start until end)
                        .filter { it !in boundarySet }
                        .map { sigmoid(observedLogits[it]) }
                MeterRegion(
                    start,
                    end,
                    meter,
                    complete,
                    (start until end).sumOf { statePosterior[it] } / (end - start),
                    (start until end).minOf { statePosterior[it] },
                    (start until end).count {
                        it in boundarySet && observed[it] && observedLogits[it] > 0.0
                    },
                    downbeatScores.average().let { if (it.isFinite()) it else 0.0 },
                    otherScores.average().let { if (it.isFinite()) it else 0.0 },
                )
            }
        val barMinimum =
            DoubleArray(max(0, boundaryIndices.size - 1)) { bar ->
                (boundaryIndices[bar]..boundaryIndices[bar + 1]).minOf { statePosterior[it] }
            }
        val supported =
            BooleanArray(barMinimum.size) { bar ->
                val start = boundaryIndices[bar]
                val end = boundaryIndices[bar + 1]
                val region = regions[regionIndex[start]]
                val nextRegion = regions[regionIndex[end]]
                fun supported(r: MeterRegion): Boolean =
                    r.completeBars >= options.minimumBarsPerRegion &&
                        r.observedBoundaries >= options.minimumBarsPerRegion &&
                        r.meanDownbeatScore > r.meanOtherBeatScore
                end - start == region.pulsesPerBar && supported(region) && supported(nextRegion)
            }
        cancellationCheck()
        return BarTrackingResult(
            times,
            rawDownbeatSeconds.toList(),
            boundaries,
            regions,
            edits.sortedBy { it.seconds },
            barMinimum,
            supported,
            snapshot,
        )
    }

    private data class State(val meter: Int, val phase: Int)

    private data class Edge(val state: Int, val logProbability: Double)

    private fun softplus(value: Double): Double = max(0.0, value) + ln1p(exp(-abs(value)))

    private fun sigmoid(value: Double): Double = exp(-softplus(-value))

    private fun logAdd(a: Double, b: Double): Double {
        if (a == Double.NEGATIVE_INFINITY) return b
        if (b == Double.NEGATIVE_INFINITY) return a
        return max(a, b) + ln1p(exp(-abs(a - b)))
    }

    private fun nearest(times: DoubleArray, value: Double): Int {
        var left = 0
        var right = times.lastIndex
        while (left < right) {
            val middle = (left + right) ushr 1
            if (times[middle] < value) left = middle + 1 else right = middle
        }
        return if (left > 0 && abs(times[left - 1] - value) <= abs(times[left] - value)) left - 1
        else left
    }
}
