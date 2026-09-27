package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import org.metrolist.beatweave.*

/** Resource bounds, not permission to relax musical or clock acceptance. */
data class AutoMixSearchOptions(
    val maximumCandidatePairs: Int = 250_000,
    val maximumClockFits: Int = 64,
    val minimumOverlapBars: Int = 2,
) {
    init {
        require(maximumCandidatePairs > 0 && maximumClockFits > 0)
        require(minimumOverlapBars >= 2)
    }
}

enum class AutoMixFailureCode {
    NO_CONFIDENT_BAR_GRID,
    NO_COMPATIBLE_BAR_RANGE,
    SOURCE_COVERAGE_INVALID,
    CLOCK_REJECTED,
    SEARCH_LIMIT_REACHED,
}

enum class AutoMixTrackRole {
    OUTGOING,
    INCOMING,
}

enum class AutoMixSearchStrategy {
    ORDERED_TRANSITION_SCAN,
    CONSTANT_METER_MERGE,
    GENERAL_PREFIX_SCAN,
}

/** Joint acceptance is distinct from phase confidence and global pulse diagnostics. */
data class AutoMixTrackDiagnostic(
    val role: AutoMixTrackRole,
    val proposedBars: Int,
    val phaseUsableBars: Int,
    val pulseSupportedBars: Int,
    val jointUsableBars: Int,
    val longestSupportedBars: Int,
    val unusableReasonCounts: Map<BarUsabilityIssue, Int>,
    val sourcePulseErrorCodes: List<String>,
    /** Null for caller-supplied analyses without an independent acoustic audit. */
    val acousticallySupportedBars: Int? = null,
)

data class AutoMixSearchReport(
    val mode: MixMode,
    val requestedBars: Int?,
    val inspectedPairs: Int,
    val compatibleCandidates: Long,
    val rejectedClocks: Int,
    val failure: AutoMixFailureCode? = null,
    val strategy: AutoMixSearchStrategy? = null,
    val tracks: List<AutoMixTrackDiagnostic> = emptyList(),
)

/** A caller can skip synchronization on this typed outcome; no fallback audio change is applied. */
class AutoMixPlanningException(val report: AutoMixSearchReport, detail: String) :
    IllegalArgumentException(
        "Automatic ${report.mode.name.lowercase()} declined (${report.failure}): $detail"
    )

/** Original indices and observations remain meaningful even when a plan uses a scoped clock. */
data class MatchedBarBoundary(
    val outgoingBar: Int,
    val incomingBar: Int,
    val outgoingBeat: Int,
    val incomingBeat: Int,
    val outgoingSeconds: Double,
    val originalIncomingSeconds: Double,
    val preparedIncomingSeconds: Double,
    val originalBoundaryOutputResidualSeconds: Double,
)

enum class AutoMixSelectionPolicy {
    EARLY_INCOMING_LATE_OUTGOING,
    LONGEST_SUPPORTED_OVERLAP,
}

data class AutoMixSelection(
    val policy: AutoMixSelectionPolicy,
    val outgoingStartBar: Int,
    val incomingStartBar: Int,
    val barCount: Int,
    val pulsesPerBar: List<Int>,
    val outgoingStartBeat: Int,
    val outgoingEndBeat: Int,
    val incomingStartBeat: Int,
    val incomingEndBeat: Int,
    val outgoingStartSeconds: Double,
    val outgoingEndSeconds: Double,
    val incomingStartSeconds: Double,
    val incomingEndSeconds: Double,
    val search: AutoMixSearchReport,
)

/**
 * Selects actual supported bars, not assumed eight-bar musical phrases. Every corresponding bar
 * must have the same number of canonical pulses, including internal meter changes. Selection never
 * invents a 3:2 or double/half-time match.
 *
 * Only clock observations are scoped; PCM and source durations are retained. Overlap outside the
 * declared support continues at the endpoint speed and is explicitly unpaired. Use
 * PreparedMix.renderComplete for all overlap PCM.
 */
object AutoMixPlanner {
    fun transition(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        bars: Int = 16,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        searchOptions: AutoMixSearchOptions = AutoMixSearchOptions(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan {
        require(bars in TransitionPlanner.supportedBarCounts) { "Choose 2, 4, 8, 16 or 32 bars" }
        require(outputSampleRate in 22050..96000)
        val search = Search(MixMode.TRANSITION, bars, searchOptions, isCancelled)
        search.strategy = AutoMixSearchStrategy.ORDERED_TRANSITION_SCAN
        val a = supported(first, AutoMixTrackRole.OUTGOING, search)
        val b = supported(second, AutoMixTrackRole.INCOMING, search)
        search.requireSupportedTracks()
        // Incoming cue order is primary; among equally early entries prefer a late exit.
        for (incoming in 0 until b.grid.barCount) {
            if (b.endByStart[incoming] - incoming < bars) continue
            for (outgoing in a.grid.barCount - bars downTo 0) {
                search.inspect()
                if (
                    a.endByStart[outgoing] - outgoing < bars ||
                        !sameBars(a.grid, b.grid, outgoing, incoming, bars)
                )
                    continue
                if (a.grid.boundary(outgoing + bars) - a.grid.boundary(outgoing) > 512) continue
                search.compatible++
                val result =
                    tryCandidate(
                        first,
                        second,
                        a.grid,
                        b.grid,
                        outgoing,
                        incoming,
                        bars,
                        MixMode.TRANSITION,
                        outputSampleRate,
                        fitOptions,
                        qualityLimits,
                        search,
                        AutoMixSelectionPolicy.EARLY_INCOMING_LATE_OUTGOING,
                    )
                if (result != null) return result
            }
        }
        search.decline()
    }

    fun overlap(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        searchOptions: AutoMixSearchOptions = AutoMixSearchOptions(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan {
        require(outputSampleRate in 22050..96000)
        val search = Search(MixMode.OVERLAP, null, searchOptions, isCancelled)
        val a = supported(first, AutoMixTrackRole.OUTGOING, search)
        val b = supported(second, AutoMixTrackRole.INCOMING, search)
        search.requireSupportedTracks()
        val meter = a.grid.beatsInBar(0)
        val otherMeter = b.grid.beatsInBar(0)
        val constantA = (0 until a.grid.barCount).all { a.grid.beatsInBar(it) == meter }
        val constantB = (0 until b.grid.barCount).all { b.grid.beatsInBar(it) == otherMeter }
        if (constantA && constantB && meter == otherMeter) {
            search.strategy = AutoMixSearchStrategy.CONSTANT_METER_MERGE
            return constantMeterOverlap(
                first,
                second,
                a,
                b,
                outputSampleRate,
                fitOptions,
                qualityLimits,
                search,
            )
        }
        search.strategy = AutoMixSearchStrategy.GENERAL_PREFIX_SCAN
        if (constantA && constantB) search.decline()
        val candidates = ArrayList<Candidate>()
        var next = IntArray(b.grid.barCount + 1)
        // Longest-common-prefix dynamic program: two rows, one bounded pair scan.
        for (outgoing in a.grid.barCount - 1 downTo 0) {
            val row = IntArray(b.grid.barCount + 1)
            for (incoming in b.grid.barCount - 1 downTo 0) {
                search.inspect()
                if (a.grid.beatsInBar(outgoing) != b.grid.beatsInBar(incoming)) continue
                val count =
                    min(
                        1 + next[incoming + 1],
                        min(a.endByStart[outgoing] - outgoing, b.endByStart[incoming] - incoming),
                    )
                row[incoming] = count
                if (count >= searchOptions.minimumOverlapBars) {
                    val duration =
                        a.grid.beats.at(a.grid.boundary(outgoing + count)) -
                            a.grid.beats.at(a.grid.boundary(outgoing))
                    candidates +=
                        Candidate(
                            outgoing,
                            incoming,
                            count,
                            (duration * outputSampleRate).roundToLong(),
                        )
                }
            }
            next = row
        }
        candidates.sortWith(candidateOrder)
        search.compatible = candidates.size.toLong()
        for (candidate in candidates) {
            search.checkCancellation()
            val result =
                tryCandidate(
                    first,
                    second,
                    a.grid,
                    b.grid,
                    candidate.outgoing,
                    candidate.incoming,
                    candidate.bars,
                    MixMode.OVERLAP,
                    outputSampleRate,
                    fitOptions,
                    qualityLimits,
                    search,
                    AutoMixSelectionPolicy.LONGEST_SUPPORTED_OVERLAP,
                )
            if (result != null) return result
        }
        search.decline()
    }

    private data class Candidate(
        val outgoing: Int,
        val incoming: Int,
        val bars: Int,
        val durationFrames: Long,
    )

    private data class SupportedBars(val grid: BarGrid, val endByStart: IntArray)

    private val candidateOrder =
        compareByDescending<Candidate> { it.durationFrames }
            .thenBy { it.incoming }
            .thenBy { it.outgoing }

    /**
     * For one outgoing start and a run of incoming starts with a common supported end, candidate
     * duration is nonincreasing as the incoming start advances. Merge those sorted streams lazily,
     * preserving the general scan's exact ranking even when a rejected longest span requires a
     * shorter fallback. No Cartesian candidate list is needed for a shared constant meter.
     */
    private fun constantMeterOverlap(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        a: SupportedBars,
        b: SupportedBars,
        outputSampleRate: Int,
        fitOptions: ClockFitOptions,
        qualityLimits: WarpQualityLimits,
        search: Search,
    ): LocalMixPlan {
        data class StartRun(val start: Int, val endExclusive: Int, val supportEnd: Int)
        val minimum = search.options.minimumOverlapBars
        val runs = ArrayList<StartRun>()
        var incoming = 0
        while (incoming < b.grid.barCount) {
            search.checkCancellation()
            val start = incoming
            val end = b.endByStart[incoming]
            do {
                incoming++
            } while (incoming < b.grid.barCount && b.endByStart[incoming] == end)
            val lastExclusive = min(incoming, end - minimum + 1)
            if (start < lastExclusive) runs += StartRun(start, lastExclusive, end)
        }
        fun candidate(outgoing: Int, incoming: Int, supportEnd: Int): Candidate {
            val count = min(a.endByStart[outgoing] - outgoing, supportEnd - incoming)
            val duration =
                a.grid.beats.at(a.grid.boundary(outgoing + count)) -
                    a.grid.beats.at(a.grid.boundary(outgoing))
            return Candidate(outgoing, incoming, count, (duration * outputSampleRate).roundToLong())
        }
        val heap = CandidateHeap()
        for (outgoing in 0 until a.grid.barCount) {
            search.checkCancellation()
            if (a.endByStart[outgoing] - outgoing < minimum) continue
            for (run in runs) {
                search.inspect()
                search.compatible += (run.endExclusive - run.start).toLong()
                heap.add(
                    CandidateStream(
                        candidate(outgoing, run.start, run.supportEnd),
                        run.endExclusive,
                        run.supportEnd,
                    )
                )
            }
        }
        while (heap.isNotEmpty()) {
            search.checkCancellation()
            val stream = heap.removeFirst()
            val current = stream.candidate
            val result =
                tryCandidate(
                    first,
                    second,
                    a.grid,
                    b.grid,
                    current.outgoing,
                    current.incoming,
                    current.bars,
                    MixMode.OVERLAP,
                    outputSampleRate,
                    fitOptions,
                    qualityLimits,
                    search,
                    AutoMixSelectionPolicy.LONGEST_SUPPORTED_OVERLAP,
                )
            if (result != null) return result
            if (current.incoming + 1 < stream.endExclusive) {
                search.inspect()
                heap.add(
                    stream.copy(
                        candidate =
                            candidate(current.outgoing, current.incoming + 1, stream.supportEnd)
                    )
                )
            }
        }
        search.decline()
    }

    private data class CandidateStream(
        val candidate: Candidate,
        val endExclusive: Int,
        val supportEnd: Int,
    )

    /** Common-Kotlin priority queue; size is bounded by the candidate inspection budget. */
    private class CandidateHeap {
        private val values = ArrayList<CandidateStream>()

        fun isNotEmpty(): Boolean = values.isNotEmpty()

        fun add(value: CandidateStream) {
            values.add(value)
            var index = values.lastIndex
            while (index > 0) {
                val parent = (index - 1) ushr 1
                if (candidateOrder.compare(values[parent].candidate, value.candidate) <= 0) break
                values[index] = values[parent]
                index = parent
            }
            values[index] = value
        }

        fun removeFirst(): CandidateStream {
            val first = values[0]
            val last = values.removeAt(values.lastIndex)
            if (values.isNotEmpty()) {
                var index = 0
                while (index < values.size / 2) {
                    var child = 2 * index + 1
                    if (
                        child + 1 < values.size &&
                            candidateOrder.compare(
                                values[child + 1].candidate,
                                values[child].candidate,
                            ) < 0
                    )
                        child++
                    if (candidateOrder.compare(last.candidate, values[child].candidate) <= 0) break
                    values[index] = values[child]
                    index = child
                }
                values[index] = last
            }
            return first
        }
    }

    private fun supported(
        song: LocalSongAnalysis,
        role: AutoMixTrackRole,
        search: Search,
    ): SupportedBars {
        search.checkCancellation()
        val proposed =
            song.barTracking
                ?: search.fail(
                    AutoMixFailureCode.NO_CONFIDENT_BAR_GRID,
                    "Automatic bar selection requires audited meter/phase evidence",
                )
        // Public callers can assemble analyses themselves. Reapply the per-bar audit rather
        // than assuming the tracker already carries the facade's immutable acoustic mask.
        val tracking =
            try {
                song.acousticPulse?.let { proposed.withAcousticPulseSupport(it) } ?: proposed
            } catch (_: IllegalArgumentException) {
                search.fail(
                    AutoMixFailureCode.SOURCE_COVERAGE_INVALID,
                    "Acoustic evidence and proposed bars must use the same original source clock",
                )
            }
        val grid =
            try {
                tracking.grid()
            } catch (_: UncertainBarsException) {
                search.fail(
                    AutoMixFailureCode.NO_CONFIDENT_BAR_GRID,
                    "No complete proposed bar grid",
                )
            }
        if (
            !song.audio.durationSeconds.isFinite() ||
                song.audio.durationSeconds <= 0.0 ||
                song.audio.durationSeconds > 4 * 60 * 60 ||
                !grid.beats.times.contentEquals(
                    song.pulse.beats.map { it.seconds }.toDoubleArray()
                ) ||
                grid.beats.times.any {
                    !it.isFinite() || it < 0.0 || it >= song.audio.durationSeconds
                }
        )
            search.fail(
                AutoMixFailureCode.SOURCE_COVERAGE_INVALID,
                "Bar anchors must use the same canonical source clock and lie inside the recording",
            )
        val endByStart = IntArray(grid.barCount)
        var phaseUsable = 0
        var pulseSupported = 0
        val reasonCounts = mutableMapOf<BarUsabilityIssue, Int>()
        var contiguousEnd = grid.barCount
        for (bar in grid.barCount - 1 downTo 0) {
            search.checkCancellation()
            val issues = tracking.issuesForBar(bar).toMutableSet()
            if (
                issues.none {
                    it != BarUsabilityIssue.UNSUPPORTED_PULSE &&
                        it != BarUsabilityIssue.UNSUPPORTED_ACOUSTIC_PULSE
                }
            )
                phaseUsable++
            val pulseUsable =
                BarUsabilityIssue.UNSUPPORTED_PULSE !in issues &&
                    try {
                        song.requirePulseGeometryRange(
                            grid.boundary(bar),
                            grid.boundary(bar + 1) + 1,
                        )
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
            if (pulseUsable) pulseSupported++ else issues += BarUsabilityIssue.UNSUPPORTED_PULSE
            for (issue in issues) reasonCounts[issue] = (reasonCounts[issue] ?: 0) + 1
            if (!tracking.isBarUsable(bar) || !pulseUsable) {
                contiguousEnd = bar
                endByStart[bar] = bar
                continue
            }
            var low = bar
            var high = contiguousEnd
            // Pulse-region containment is monotone as the selected range grows.
            while (low < high) {
                val end = (low + high + 1) ushr 1
                val usable =
                    try {
                        song.requirePulseRange(grid.boundary(bar), grid.boundary(end) + 1)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                if (usable) low = end else high = end - 1
            }
            endByStart[bar] = low
        }
        search.tracks +=
            AutoMixTrackDiagnostic(
                role,
                grid.barCount,
                phaseUsable,
                pulseSupported,
                endByStart.indices.count { endByStart[it] > it },
                endByStart.indices.maxOfOrNull { endByStart[it] - it } ?: 0,
                reasonCounts.toMap(),
                song.pulse.quality.issues
                    .filter { it.severity == BeatIssueSeverity.ERROR }
                    .map { it.code }
                    .distinct(),
                tracking.barAcousticPulseSupported?.count { it },
            )
        return SupportedBars(grid, endByStart)
    }

    internal fun sameBars(
        a: BarGrid,
        b: BarGrid,
        outgoing: Int,
        incoming: Int,
        count: Int,
    ): Boolean =
        outgoing in 0 until a.barCount &&
            incoming in 0 until b.barCount &&
            count > 0 &&
            count <= a.barCount - outgoing &&
            count <= b.barCount - incoming &&
            (0 until count).all { a.beatsInBar(outgoing + it) == b.beatsInBar(incoming + it) }

    internal fun scopedPlan(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        a: BarGrid,
        b: BarGrid,
        outgoing: Int,
        incoming: Int,
        bars: Int,
        mode: MixMode,
        outputSampleRate: Int,
        fitOptions: ClockFitOptions,
        qualityLimits: WarpQualityLimits,
        isCancelled: () -> Boolean,
        selection: AutoMixSelection? = null,
    ): LocalMixPlan {
        require(
            a.beats.times.contentEquals(first.pulse.beats.map { it.seconds }.toDoubleArray()) &&
                b.beats.times.contentEquals(second.pulse.beats.map { it.seconds }.toDoubleArray())
        ) {
            "Bar evidence does not refer to the supplied canonical source clock"
        }
        require(sameBars(a, b, outgoing, incoming, bars)) {
            "Every corresponding bar must contain the same number of canonical pulses"
        }
        first.barTracking?.requireUsable(outgoing, bars)
        second.barTracking?.requireUsable(incoming, bars)
        // Independently check each bar even for caller-assembled analyses. A
        // quiet/unsupported bar cannot borrow activity from its louder neighbor.
        for (index in 0 until bars) {
            if (isCancelled()) throw MixCancelledException()
            first.requirePulseRange(
                a.boundary(outgoing + index),
                a.boundary(outgoing + index + 1) + 1,
            )
            second.requirePulseRange(
                b.boundary(incoming + index),
                b.boundary(incoming + index + 1) + 1,
            )
        }
        val aStart = a.boundary(outgoing)
        val aEnd = a.boundary(outgoing + bars)
        val bStart = b.boundary(incoming)
        val bEnd = b.boundary(incoming + bars)
        val firstGrid = first.matchingGrid(aStart, aEnd + 1)
        val secondGrid = second.matchingGrid(bStart, bEnd + 1)
        require(
            first.audio.durationSeconds.isFinite() &&
                second.audio.durationSeconds.isFinite() &&
                firstGrid.at(0) >= 0.0 &&
                secondGrid.at(0) >= 0.0 &&
                firstGrid.at(firstGrid.size - 1) < first.audio.durationSeconds &&
                secondGrid.at(secondGrid.size - 1) < second.audio.durationSeconds
        ) {
            "Selected bar boundaries must lie inside both unchanged source recordings"
        }
        val beatCount = aEnd - aStart
        require(mode != MixMode.TRANSITION || beatCount <= 512) {
            "Transition exceeds the renderer's 512-pulse fade limit"
        }
        val plan =
            MixPlan(
                firstGrid,
                secondGrid,
                0,
                0,
                min(512, beatCount),
                outputSampleRate = outputSampleRate,
                releaseAfterFade = mode == MixMode.TRANSITION,
            )
        val boundaryPins = (0..bars).map { b.boundary(incoming + it) - bStart }.toSet()
        val pinnedOptions =
            fitOptions.copy(pinnedIncomingBeats = fitOptions.pinnedIncomingBeats + boundaryPins)
        val fit =
            BeatClockRegularizer.regularize(
                plan,
                second.audio.durationSeconds,
                pinnedOptions,
                qualityLimits,
                isCancelled,
            )
        val accepted = fit.requireAccepted()
        val matched =
            (0..bars).map { index ->
                val ai = a.boundary(outgoing + index)
                val bi = b.boundary(incoming + index)
                val original = b.beats.at(bi)
                val adjusted = accepted.second.at(bi - bStart)
                val expected = a.beats.at(ai)
                check(adjusted == original) {
                    "A declared incoming bar boundary moved during clock fitting"
                }
                check(
                    abs(accepted.secondOutputTime(adjusted) - expected) <= 1.0 / outputSampleRate
                ) {
                    "An internal prepared bar boundary does not align"
                }
                MatchedBarBoundary(
                    outgoing + index,
                    incoming + index,
                    ai,
                    bi,
                    expected,
                    original,
                    adjusted,
                    accepted.secondOutputTime(original) - expected,
                )
            }
        return LocalMixPlan(
            fit,
            mode,
            outgoing,
            incoming,
            bars,
            bars,
            first.audio.durationSeconds,
            second.audio.durationSeconds,
            matched,
            selection,
        )
    }

    private fun tryCandidate(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        a: BarGrid,
        b: BarGrid,
        outgoing: Int,
        incoming: Int,
        bars: Int,
        mode: MixMode,
        outputSampleRate: Int,
        fitOptions: ClockFitOptions,
        qualityLimits: WarpQualityLimits,
        search: Search,
        policy: AutoMixSelectionPolicy,
    ): LocalMixPlan? {
        search.beforeFit()
        val selection =
            AutoMixSelection(
                policy,
                outgoing,
                incoming,
                bars,
                (0 until bars).map { a.beatsInBar(outgoing + it) },
                a.boundary(outgoing),
                a.boundary(outgoing + bars),
                b.boundary(incoming),
                b.boundary(incoming + bars),
                a.beats.at(a.boundary(outgoing)),
                a.beats.at(a.boundary(outgoing + bars)),
                b.beats.at(b.boundary(incoming)),
                b.beats.at(b.boundary(incoming + bars)),
                search.report(),
            )
        return try {
            scopedPlan(
                first,
                second,
                a,
                b,
                outgoing,
                incoming,
                bars,
                mode,
                outputSampleRate,
                fitOptions,
                qualityLimits,
                search.isCancelled,
                selection,
            )
        } catch (_: UnsafeClockFitException) {
            search.rejectedClocks++
            null
        }
    }

    private class Search(
        val mode: MixMode,
        val bars: Int?,
        val options: AutoMixSearchOptions,
        val isCancelled: () -> Boolean,
    ) {
        var pairs = 0
        var compatible = 0L
        var rejectedClocks = 0
        var strategy: AutoMixSearchStrategy? = null
        val tracks = ArrayList<AutoMixTrackDiagnostic>(2)
        private var fits = 0

        fun checkCancellation() {
            if (isCancelled()) throw MixCancelledException()
        }

        fun inspect() {
            checkCancellation()
            if (pairs >= options.maximumCandidatePairs)
                fail(
                    AutoMixFailureCode.SEARCH_LIMIT_REACHED,
                    "Candidate-pair budget exhausted without changing acceptance gates",
                )
            pairs++
        }

        fun beforeFit() {
            checkCancellation()
            if (fits >= options.maximumClockFits)
                fail(
                    AutoMixFailureCode.SEARCH_LIMIT_REACHED,
                    "Clock-fit budget exhausted without changing acceptance gates",
                )
            fits++
        }

        fun requireSupportedTracks() {
            val empty = tracks.filter { it.jointUsableBars == 0 }
            if (empty.isNotEmpty())
                fail(
                    AutoMixFailureCode.NO_CONFIDENT_BAR_GRID,
                    empty.joinToString("; ") {
                        "${it.role}: ${it.phaseUsableBars}/${it.proposedBars} phase-supported bars, " +
                            "${it.pulseSupportedBars}/${it.proposedBars} pulse-supported bars; ${it.unusableReasonCounts}"
                    },
                )
        }

        fun report(failure: AutoMixFailureCode? = null) =
            AutoMixSearchReport(
                mode,
                bars,
                pairs,
                compatible,
                rejectedClocks,
                failure,
                strategy,
                tracks.toList(),
            )

        fun fail(code: AutoMixFailureCode, detail: String): Nothing =
            throw AutoMixPlanningException(report(code), detail)

        fun decline(): Nothing =
            fail(
                if (rejectedClocks > 0) AutoMixFailureCode.CLOCK_REJECTED
                else AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE,
                "No compatible supported bar range passed all checks",
            )
    }
}
