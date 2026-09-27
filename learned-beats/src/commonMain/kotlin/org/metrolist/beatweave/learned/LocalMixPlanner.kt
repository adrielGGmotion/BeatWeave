package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.metrolist.beatweave.*

enum class BeatOverlapFailureCode {
    NO_SUPPORTED_CUE,
    INSUFFICIENT_SHARED_COVERAGE,
    UNSUPPORTED_SHARED_COVERAGE,
    SOURCE_COVERAGE_INVALID,
    CLOCK_REJECTED,
}

/**
 * The assessed subspan after removing only unavailable acoustic edge context; source PCM is intact.
 */
data class BeatOverlapMatchedSpan(
    val outgoingStartBeat: Int,
    val outgoingEndBeat: Int,
    val incomingStartBeat: Int,
    val incomingEndBeat: Int,
    val outgoingStartSeconds: Double,
    val outgoingEndSeconds: Double,
    val incomingStartSeconds: Double,
    val incomingEndSeconds: Double,
) {
    val pairedBeatCount: Int
        get() = outgoingEndBeat - outgoingStartBeat + 1
}

/**
 * Entire original paired observation span at the selected cue offset. [matchedSpan] explicitly
 * identifies the narrower assessed span when the acoustic audit lacks context at source edges.
 * Unpaired/unevaluated edge PCM is preserved and is not claimed to be independently verified.
 */
data class BeatOverlapCoverage(
    val outgoingCueBeat: Int,
    val incomingCueBeat: Int,
    val outgoingStartBeat: Int,
    val outgoingEndBeat: Int,
    val incomingStartBeat: Int,
    val incomingEndBeat: Int,
    val outgoingStartSeconds: Double,
    val outgoingEndSeconds: Double,
    val incomingStartSeconds: Double,
    val incomingEndSeconds: Double,
    val outgoingSourceDurationSeconds: Double,
    val incomingSourceDurationSeconds: Double,
    val matchedSpan: BeatOverlapMatchedSpan? = null,
) {
    val pairedBeatCount: Int
        get() = outgoingEndBeat - outgoingStartBeat + 1
}

class BeatOverlapPlanningException(
    val code: BeatOverlapFailureCode,
    detail: String,
    val coverage: BeatOverlapCoverage? = null,
    val clockReport: ClockFitReport? = null,
) : IllegalArgumentException("Beat overlap declined ($code): $detail")

/** A fully checked plan, with the original observations and every clock adjustment retained. */
class LocalMixPlan
internal constructor(
    val clockFit: BeatClockFitResult,
    val mode: MixMode,
    val outgoingStartBar: Int?,
    val incomingStartBar: Int?,
    val outgoingBars: Int?,
    val incomingBars: Int?,
    private val firstDurationSeconds: Double,
    private val secondDurationSeconds: Double,
    val barMatches: List<MatchedBarBoundary> = emptyList(),
    val automaticSelection: AutoMixSelection? = null,
    val beatCoverage: BeatOverlapCoverage? = null,
    val declaredMainSelection: DeclaredMainBeatSelection? = null,
) {
    val mixPlan: MixPlan = clockFit.requireAccepted()

    /**
     * Run on a worker thread. Source decoders remain owned by the caller; close the returned
     * session. Playback, seeking and export then reuse that same session. Decode the same untrimmed
     * sources used for analysis; equal duration alone cannot establish audio identity.
     */
    fun prepare(
        first: StereoPcm,
        second: StereoPcm,
        engine: PitchStretchEngine,
        isCancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {},
    ): PreparedMix {
        require(
            abs(first.durationSeconds - firstDurationSeconds) <= 0.002 &&
                abs(second.durationSeconds - secondDurationSeconds) <= 0.002
        ) {
            "Decoded audio duration differs from its analysis; use the same source clock without trimming"
        }
        return BeatMixer(engine).prepare(first, second, mixPlan, isCancelled, progress)
    }
}

/**
 * The automatic integration entry point: audit pulse and selected-bar evidence, refine only when
 * necessary, and validate the complete prepared clock. A declined fit throws a typed exception; it
 * never silently changes BPM, meter, phase, pitch or acceptance limits to force a render.
 */
object LocalMixPlanner {
    /**
     * Automatically chooses a common confident bar span; all other PCM remains explicitly unpaired.
     */
    fun overlap(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        searchOptions: AutoMixSearchOptions = AutoMixSearchOptions(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan =
        AutoMixPlanner.overlap(
            first,
            second,
            outputSampleRate,
            fitOptions,
            qualityLimits,
            searchOptions,
            isCancelled,
        )

    /**
     * No manual cues required. Requested length is exact; unsupported lengths are not silently
     * shortened.
     */
    fun autoTransition(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        bars: Int = 16,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        searchOptions: AutoMixSearchOptions = AutoMixSearchOptions(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan =
        AutoMixPlanner.transition(
            first,
            second,
            bars,
            outputSampleRate,
            fitOptions,
            qualityLimits,
            searchOptions,
            isCancelled,
        )

    /**
     * Plans caller/provider-declared bars over actual observed main-beat subsets. Declarations do
     * not overwrite inferred bars. Pins use ORIGINAL incoming canonical indices and must map to
     * selected main events; every declared downbeat remains fixed through clock preparation.
     */
    fun declaredTransition(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outgoing: ObservedMainBeatGrid,
        incoming: ObservedMainBeatGrid,
        outgoingStartBar: Int,
        incomingStartBar: Int,
        bars: Int = 16,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan =
        DeclaredMainBeatPlanner.transition(
            first,
            second,
            outgoing,
            incoming,
            outgoingStartBar,
            incomingStartBar,
            bars,
            outputSampleRate,
            fitOptions,
            qualityLimits,
            isCancelled,
        )

    /**
     * Automatically chooses cues within the supplied evidence-supported declarations. It does not
     * infer those declarations or resolve a provider's ambiguous metrical level. The earliest
     * incoming entry and latest compatible outgoing exit are tried under explicit resource bounds.
     */
    fun declaredAutoTransition(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outgoing: ObservedMainBeatGrid,
        incoming: ObservedMainBeatGrid,
        bars: Int = 16,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        searchOptions: AutoMixSearchOptions = AutoMixSearchOptions(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan =
        DeclaredMainBeatPlanner.automatic(
            first,
            second,
            outgoing,
            incoming,
            bars,
            outputSampleRate,
            fitOptions,
            qualityLimits,
            searchOptions,
            isCancelled,
        )

    /**
     * Explicit beat-only operation. Requires accepted evidence throughout the entire paired
     * geometry and every interval with available acoustic context. Only unassessed source-edge
     * context is excluded from the matched-span claim. A failed assessed interval or internal gap
     * still declines. An unpaired longer-song outro is irrelevant; full PCM and original paired
     * coverage remain unchanged.
     */
    fun beatOverlap(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outgoingCueBeat: Int? = null,
        incomingCueBeat: Int? = null,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan {
        if (isCancelled()) throw MixCancelledException()
        require(outputSampleRate in 22050..96000)
        val a = observedGrid(first)
        val b = observedGrid(second)
        val firstCue = outgoingCueBeat ?: defaultCue(first, a, isCancelled)
        val secondCue = incomingCueBeat ?: defaultCue(second, b, isCancelled)
        if (!supportedCue(first, firstCue) || !supportedCue(second, secondCue))
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.NO_SUPPORTED_CUE,
                "Both cues must lie inside an accepted pulse region",
            )
        // Index correspondence covers every jointly available observed interval,
        // not merely an easy prefix. A rejected internal passage must decline.
        val aStart = max(0, firstCue - secondCue)
        val bStart = secondCue + aStart - firstCue
        val aEnd = min(a.size - 1, firstCue + b.size - 1 - secondCue)
        val bEnd = secondCue + aEnd - firstCue
        if (aEnd <= aStart)
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.INSUFFICIENT_SHARED_COVERAGE,
                "The cues leave no complete paired beat interval",
            )
        var coverage =
            BeatOverlapCoverage(
                firstCue,
                secondCue,
                aStart,
                aEnd,
                bStart,
                bEnd,
                a.at(aStart),
                a.at(aEnd),
                b.at(bStart),
                b.at(bEnd),
                first.audio.durationSeconds,
                second.audio.durationSeconds,
            )
        // Geometry errors remain fatal across the ORIGINAL paired span, even at its edges.
        try {
            first.requirePulseGeometryRange(aStart, aEnd + 1)
            second.requirePulseGeometryRange(bStart, bEnd + 1)
        } catch (_: IllegalArgumentException) {
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.UNSUPPORTED_SHARED_COVERAGE,
                "Rejected geometry occurs inside the original paired span; no gap was skipped",
                coverage,
            )
        }
        var trimStart = 0
        var trimEnd = 0
        for ((song, start, end) in
            listOf(Triple(first, aStart, aEnd), Triple(second, bStart, bEnd))) {
            val acoustic = song.acousticPulse ?: continue
            acoustic.requireClock(song.pulse.beats.map { it.seconds }.toDoubleArray())
            val evaluated =
                acoustic.evaluatedIntervalRange
                    ?: throw BeatOverlapPlanningException(
                        BeatOverlapFailureCode.UNSUPPORTED_SHARED_COVERAGE,
                        "No acoustic interval has complete observation context",
                        coverage,
                    )
            trimStart = max(trimStart, evaluated.first - start)
            trimEnd = max(trimEnd, end - (evaluated.last + 1))
        }
        val matchedAStart = aStart + trimStart
        val matchedBStart = bStart + trimStart
        val matchedAEnd = aEnd - trimEnd
        val matchedBEnd = bEnd - trimEnd
        if (
            matchedAEnd <= matchedAStart ||
                firstCue !in matchedAStart..matchedAEnd ||
                secondCue !in matchedBStart..matchedBEnd
        )
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.INSUFFICIENT_SHARED_COVERAGE,
                "Complete acoustic context leaves no shared span containing both cues",
                coverage,
            )
        if (trimStart != 0 || trimEnd != 0)
            coverage =
                coverage.copy(
                    matchedSpan =
                        BeatOverlapMatchedSpan(
                            matchedAStart,
                            matchedAEnd,
                            matchedBStart,
                            matchedBEnd,
                            a.at(matchedAStart),
                            a.at(matchedAEnd),
                            b.at(matchedBStart),
                            b.at(matchedBEnd),
                        )
                )
        try {
            first.requirePulseRange(matchedAStart, matchedAEnd + 1)
            second.requirePulseRange(matchedBStart, matchedBEnd + 1)
        } catch (_: IllegalArgumentException) {
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.UNSUPPORTED_SHARED_COVERAGE,
                "An assessed interval lacks recurring audible evidence; no interior cutoff was applied",
                coverage,
            )
        }
        if (isCancelled()) throw MixCancelledException()
        val firstClock = first.matchingGrid(matchedAStart, matchedAEnd + 1)
        val secondClock = second.matchingGrid(matchedBStart, matchedBEnd + 1)
        val plan =
            MixPlan(
                firstClock,
                secondClock,
                firstCue - matchedAStart,
                secondCue - matchedBStart,
                outputSampleRate = outputSampleRate,
                releaseAfterFade = false,
            )
        require(fitOptions.pinnedIncomingBeats.all { it < b.size }) {
            "Pinned beat lies outside the original incoming grid"
        }
        require(
            fitOptions.pinnedIncomingBeats.none {
                it in bStart..bEnd && it !in matchedBStart..matchedBEnd
            }
        ) {
            "A pinned incoming beat lies in an unassessed clipped edge; it cannot be silently discarded"
        }
        val scopedOptions =
            fitOptions.copy(
                pinnedIncomingBeats =
                    fitOptions.pinnedIncomingBeats
                        .filter { it in matchedBStart..matchedBEnd }
                        .map { it - matchedBStart }
                        .toSet()
            )
        val fit =
            BeatClockRegularizer.regularize(
                plan,
                second.audio.durationSeconds,
                scopedOptions,
                qualityLimits,
                isCancelled,
            )
        if (!fit.report.accepted)
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.CLOCK_REJECTED,
                "The complete source clock failed the unchanged fit/speed/residual gates",
                coverage,
                fit.report,
            )
        return LocalMixPlan(
            fit,
            MixMode.OVERLAP,
            null,
            null,
            null,
            null,
            first.audio.durationSeconds,
            second.audio.durationSeconds,
            beatCoverage = coverage,
        )
    }

    private fun observedGrid(song: LocalSongAnalysis): BeatGrid {
        if (
            !song.audio.durationSeconds.isFinite() ||
                song.audio.durationSeconds <= 0.0 ||
                song.audio.durationSeconds > 4 * 60 * 60 ||
                song.pulse.beats.size < 2 ||
                song.pulse.beats.any {
                    !it.seconds.isFinite() ||
                        it.seconds < 0.0 ||
                        it.seconds >= song.audio.durationSeconds
                } ||
                song.pulse.beats.zipWithNext().any { (a, b) -> a.seconds >= b.seconds }
        )
            throw BeatOverlapPlanningException(
                BeatOverlapFailureCode.SOURCE_COVERAGE_INVALID,
                "Observed beats must be ordered and lie inside the unchanged source recording",
            )
        return BeatGrid(song.pulse.beats.map { it.seconds }.toDoubleArray())
    }

    private fun supportedCue(song: LocalSongAnalysis, beat: Int): Boolean {
        if (beat !in song.pulse.beats.indices || song.pulse.beats.size < 2) return false
        val start = min(beat, song.pulse.beats.lastIndex - 1)
        return try {
            song.requirePulseRange(start, start + 2)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun defaultCue(
        song: LocalSongAnalysis,
        grid: BeatGrid,
        isCancelled: () -> Boolean,
    ): Int {
        for (downbeat in song.pulse.downbeatSeconds) {
            if (isCancelled()) throw MixCancelledException()
            if (!downbeat.isFinite()) continue
            val nearest = grid.position(downbeat).roundToInt().coerceIn(0, grid.size - 1)
            if (abs(grid.at(nearest) - downbeat) <= 0.07 && supportedCue(song, nearest))
                return nearest
        }
        for (beat in 0 until grid.size) {
            if (isCancelled()) throw MixCancelledException()
            if (supportedCue(song, beat)) return beat
        }
        throw BeatOverlapPlanningException(
            BeatOverlapFailureCode.NO_SUPPORTED_CUE,
            "No observed cue has accepted local pulse evidence",
        )
    }

    fun transition(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outgoingStartBar: Int,
        incomingStartBar: Int,
        outgoingBars: Int = 16,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        isCancelled: () -> Boolean = { false },
    ): LocalMixPlan {
        if (isCancelled()) throw MixCancelledException()
        require(outgoingBars in TransitionPlanner.supportedBarCounts) {
            "Choose 2, 4, 8, 16 or 32 bars"
        }
        val a = first.barTracking?.grid() ?: first.bars()
        val b = second.barTracking?.grid() ?: second.bars()
        return AutoMixPlanner.scopedPlan(
            first,
            second,
            a,
            b,
            outgoingStartBar,
            incomingStartBar,
            outgoingBars,
            MixMode.TRANSITION,
            outputSampleRate,
            fitOptions,
            qualityLimits,
            isCancelled,
        )
    }
}
