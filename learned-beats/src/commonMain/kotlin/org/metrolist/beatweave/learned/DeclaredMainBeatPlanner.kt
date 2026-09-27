package org.metrolist.beatweave.learned

import kotlin.math.abs
import org.metrolist.beatweave.*

/**
 * Observed-main geometry only. This planner does not infer a bar level from a pulse-count ratio.
 */
internal object DeclaredMainBeatPlanner {
    private fun fail(code: DeclaredTransitionFailureCode, detail: String): Nothing =
        throw DeclaredTransitionPlanningException(code, detail)

    private fun validate(
        song: LocalSongAnalysis,
        grid: ObservedMainBeatGrid,
        cancel: () -> Boolean,
    ) {
        if (cancel()) throw MixCancelledException()
        if (
            !song.audio.durationSeconds.isFinite() ||
                song.audio.durationSeconds <= 0.0 ||
                song.audio.durationSeconds > 4 * 60 * 60 ||
                song.pulse.beats.any {
                    !it.seconds.isFinite() ||
                        it.seconds < 0.0 ||
                        it.seconds >= song.audio.durationSeconds
                }
        )
            fail(
                DeclaredTransitionFailureCode.SOURCE_COVERAGE_INVALID,
                "All canonical observations must belong to the unchanged source recording",
            )
        if (!grid.matchesClock(song))
            fail(
                DeclaredTransitionFailureCode.SOURCE_CLOCK_MISMATCH,
                "Declaration ${grid.sourceClockId} does not contain this analysis's exact canonical clock",
            )
    }

    fun transition(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outgoing: ObservedMainBeatGrid,
        incoming: ObservedMainBeatGrid,
        outgoingStartBar: Int,
        incomingStartBar: Int,
        bars: Int,
        outputSampleRate: Int,
        fitOptions: ClockFitOptions,
        qualityLimits: WarpQualityLimits,
        isCancelled: () -> Boolean,
    ): LocalMixPlan {
        require(bars in TransitionPlanner.supportedBarCounts)
        require(outputSampleRate in 22050..96000)
        validate(first, outgoing, isCancelled)
        validate(second, incoming, isCancelled)
        return candidate(
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
            false,
            isCancelled,
        )
    }

    /** Earliest incoming supported entry, then latest compatible outgoing exit; exact bar count. */
    fun automatic(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        outgoing: ObservedMainBeatGrid,
        incoming: ObservedMainBeatGrid,
        bars: Int,
        outputSampleRate: Int,
        fitOptions: ClockFitOptions,
        qualityLimits: WarpQualityLimits,
        searchOptions: AutoMixSearchOptions,
        isCancelled: () -> Boolean,
    ): LocalMixPlan {
        require(bars in TransitionPlanner.supportedBarCounts)
        require(outputSampleRate in 22050..96000)
        validate(first, outgoing, isCancelled)
        validate(second, incoming, isCancelled)
        var inspected = 0
        var fits = 0
        for (b in 0..incoming.barCount - bars) {
            if (isCancelled()) throw MixCancelledException()
            if (!(b until b + bars).all { incoming.isBarSupported(it) }) continue
            for (a in outgoing.barCount - bars downTo 0) {
                if (isCancelled()) throw MixCancelledException()
                if (inspected >= searchOptions.maximumCandidatePairs)
                    fail(
                        DeclaredTransitionFailureCode.SEARCH_LIMIT_REACHED,
                        "Candidate inspection limit reached",
                    )
                inspected++
                if (!(a until a + bars).all { outgoing.isBarSupported(it) }) continue
                if (
                    !(0 until bars).all {
                        outgoing.mainBeatsInBar(a + it) == incoming.mainBeatsInBar(b + it)
                    }
                )
                    continue
                if (outgoing.boundaryMainIndex(a + bars) - outgoing.boundaryMainIndex(a) > 512)
                    continue
                try {
                    return candidate(
                        first,
                        second,
                        outgoing,
                        incoming,
                        a,
                        b,
                        bars,
                        outputSampleRate,
                        fitOptions,
                        qualityLimits,
                        true,
                        isCancelled,
                    ) {
                        if (fits >= searchOptions.maximumClockFits)
                            fail(
                                DeclaredTransitionFailureCode.SEARCH_LIMIT_REACHED,
                                "Clock-fit limit reached",
                            )
                        fits++
                    }
                } catch (e: DeclaredTransitionPlanningException) {
                    if (e.code == DeclaredTransitionFailureCode.SEARCH_LIMIT_REACHED) throw e
                }
            }
        }
        fail(
            DeclaredTransitionFailureCode.NO_COMPATIBLE_SUPPORTED_RANGE,
            "No exact $bars-bar provider-supported range passes the unchanged pulse and clock gates",
        )
    }

    private fun candidate(
        first: LocalSongAnalysis,
        second: LocalSongAnalysis,
        a: ObservedMainBeatGrid,
        b: ObservedMainBeatGrid,
        aBar: Int,
        bBar: Int,
        bars: Int,
        rate: Int,
        fitOptions: ClockFitOptions,
        limits: WarpQualityLimits,
        automatic: Boolean,
        cancel: () -> Boolean,
        beforeFit: () -> Unit = {},
    ): LocalMixPlan {
        if (cancel()) throw MixCancelledException()
        if (
            aBar < 0 ||
                bBar < 0 ||
                aBar.toLong() + bars > a.barCount ||
                bBar.toLong() + bars > b.barCount ||
                !(0 until bars).all { a.isBarSupported(aBar + it) && b.isBarSupported(bBar + it) }
        )
            fail(
                DeclaredTransitionFailureCode.UNSUPPORTED_BAR_RANGE,
                "Both exact declared ranges need provider support",
            )
        if (!(0 until bars).all { a.mainBeatsInBar(aBar + it) == b.mainBeatsInBar(bBar + it) })
            fail(
                DeclaredTransitionFailureCode.INCOMPATIBLE_MAIN_METER,
                "Every corresponding bar must contain the same declared observed-main-beat count",
            )
        val af = a.boundaryMainIndex(aBar)
        val ae = a.boundaryMainIndex(aBar + bars)
        val bf = b.boundaryMainIndex(bBar)
        val be = b.boundaryMainIndex(bBar + bars)
        val count = ae - af
        if (count > 512)
            fail(
                DeclaredTransitionFailureCode.INCOMPATIBLE_MAIN_METER,
                "The selected fade exceeds the renderer's 512-main-beat bound",
            )
        // Unmatched canonical subdivisions remain covered by the original pulse/audio evidence.
        try {
            first.requirePulseRange(a.canonicalIndex(af), a.canonicalIndex(ae) + 1)
            second.requirePulseRange(b.canonicalIndex(bf), b.canonicalIndex(be) + 1)
            // A weak bar cannot borrow local attack coverage from adjacent active bars.
            for (bar in 0 until bars) {
                if (cancel()) throw MixCancelledException()
                first.requirePulseRange(
                    a.boundaryCanonicalIndex(aBar + bar),
                    a.boundaryCanonicalIndex(aBar + bar + 1) + 1,
                )
                second.requirePulseRange(
                    b.boundaryCanonicalIndex(bBar + bar),
                    b.boundaryCanonicalIndex(bBar + bar + 1) + 1,
                )
            }
        } catch (_: IllegalArgumentException) {
            fail(
                DeclaredTransitionFailureCode.UNSUPPORTED_PULSE_RANGE,
                "The entire original canonical span needs accepted pulse and acoustic evidence",
            )
        }
        val incomingToMain = (bf..be).associate { b.canonicalIndex(it) to it - bf }
        if (!incomingToMain.keys.containsAll(fitOptions.pinnedIncomingBeats))
            fail(
                DeclaredTransitionFailureCode.UNMAPPED_CANONICAL_PIN,
                "Pins use original incoming canonical indices and must identify selected observed main beats",
            )
        val pins = fitOptions.pinnedIncomingBeats.map { incomingToMain.getValue(it) }.toMutableSet()
        for (bar in 0..bars) pins += b.boundaryMainIndex(bBar + bar) - bf
        val plan =
            MixPlan(
                BeatGrid(DoubleArray(count + 1) { a.at(af + it) }),
                BeatGrid(DoubleArray(count + 1) { b.at(bf + it) }),
                0,
                0,
                count,
                outputSampleRate = rate,
                releaseAfterFade = true,
            )
        beforeFit()
        val fit =
            BeatClockRegularizer.regularize(
                plan,
                second.audio.durationSeconds,
                fitOptions.copy(pinnedIncomingBeats = pins),
                limits,
                cancel,
            )
        if (!fit.report.accepted)
            throw DeclaredTransitionPlanningException(
                DeclaredTransitionFailureCode.CLOCK_REJECTED,
                "Observed main clock failed unchanged safety limits",
                fit.report,
            )
        val accepted = fit.requireAccepted()
        val boundaryIndices = (0..bars).map { b.boundaryMainIndex(bBar + it) - bf }.toSet()
        val matches =
            (0..count).map { i ->
                val original = b.at(bf + i)
                val prepared = accepted.second.at(i)
                val expected = a.at(af + i)
                val boundary = i in boundaryIndices
                if (
                    boundary &&
                        (prepared != original ||
                            abs(accepted.secondOutputTime(original) - expected) > 1e-7)
                )
                    fail(
                        DeclaredTransitionFailureCode.CLOCK_REJECTED,
                        "A declared downbeat moved or failed exact correspondence",
                    )
                MatchedMainBeat(
                    a.canonicalIndex(af + i),
                    b.canonicalIndex(bf + i),
                    expected,
                    original,
                    prepared,
                    accepted.secondOutputTime(original) - expected,
                    boundary,
                )
            }
        val barMatches =
            (0..bars).map { k ->
                val i = b.boundaryMainIndex(bBar + k) - bf
                val match = matches[i]
                MatchedBarBoundary(
                    aBar + k,
                    bBar + k,
                    match.outgoingCanonicalBeat,
                    match.incomingCanonicalBeat,
                    match.outgoingSeconds,
                    match.originalIncomingSeconds,
                    match.preparedIncomingSeconds,
                    match.originalOutputResidualSeconds,
                )
            }
        return LocalMixPlan(
            fit,
            MixMode.TRANSITION,
            aBar,
            bBar,
            bars,
            bars,
            first.audio.durationSeconds,
            second.audio.durationSeconds,
            barMatches = barMatches,
            declaredMainSelection =
                DeclaredMainBeatSelection(a, b, aBar, bBar, bars, automatic, matches),
        )
    }
}
