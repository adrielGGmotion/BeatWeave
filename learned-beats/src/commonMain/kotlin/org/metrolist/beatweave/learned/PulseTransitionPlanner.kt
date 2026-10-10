package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import org.metrolist.beatweave.*

/** Policy for explicit transitions. Automatic bar searches retain their existing policy. */
data class LocalTransitionOptions(
    /** Pair pulses from the entry downbeats when the corresponding bars differ. */
    val allowDifferentPulseCounts: Boolean = true,
    /** Try an observed 2:1 or 1:2 pulse mapping when it reduces a near-octave tempo discrepancy. */
    val allowOctaveCorrection: Boolean = true,
    /**
     * Opt-in: inherit at most this many trailing outgoing bars from a directly supported bar.
     * Only sparse/missing acoustic evidence may be inherited; geometry and meter remain required.
     */
    val maximumInheritedOutroBars: Int = 0,
) {
    init { require(maximumInheritedOutroBars in 0..8) }
}

/** Original canonical indices, including a fractional incoming fade endpoint at half time. */
data class TransitionPulseAlignment(
    val outgoingStartBeat: Int,
    val outgoingEndBeat: Int,
    val incomingStartBeat: Int,
    val incomingEndBeatPosition: Double,
    val outgoingPulsesPerCycle: Int,
    val incomingPulsesPerCycle: Int,
    /** These bars have accepted timing/phase but are not claimed as acoustically verified. */
    val inheritedOutgoingBars: List<Int> = emptyList(),
)

internal object PulseTransitionPlanner {
    fun transition(
        first: LocalSongAnalysis, second: LocalSongAnalysis,
        outgoing: Int, incoming: Int, bars: Int, outputSampleRate: Int,
        fitOptions: ClockFitOptions, qualityLimits: WarpQualityLimits,
        options: LocalTransitionOptions, isCancelled: () -> Boolean,
    ): LocalMixPlan {
        fun checkCancellation() { if (isCancelled()) throw MixCancelledException() }
        checkCancellation()
        // Construct proposed geometry without requiring the entire recording's evidence.
        // Only the selected source intervals are audited below, even for annotated callers.
        val a = proposedBars(first)
        val b = proposedBars(second)
        require(outgoing in 0 until a.barCount && bars <= a.barCount - outgoing) {
            "Not enough complete outgoing bars for the selected transition"
        }
        require(incoming in 0 until b.barCount) { "Incoming start bar is outside the bar grid" }
        val inherited = TransitionEvidence.inheritedTail(first, a, outgoing, bars, options, isCancelled)
        // Keep the original grids, pins, fit, boundary matches and fade for working bar pairs.
        if (AutoMixPlanner.sameBars(a, b, outgoing, incoming, bars))
            return AutoMixPlanner.scopedPlan(
                first, second, a, b, outgoing, incoming, bars, MixMode.TRANSITION,
                outputSampleRate, fitOptions, qualityLimits, isCancelled,
                inheritedOutgoingBars = inherited,
            )
        require(options.allowDifferentPulseCounts) {
            "Corresponding bar pulse counts differ; pulse alignment is disabled"
        }
        require(
            a.beats.times.contentEquals(first.pulse.beats.map { it.seconds }.toDoubleArray()) &&
                b.beats.times.contentEquals(second.pulse.beats.map { it.seconds }.toDoubleArray())
        ) { "Bar evidence does not refer to the supplied canonical source clock" }
        val aStart = a.boundary(outgoing)
        val aEnd = a.boundary(outgoing + bars)
        val bStart = b.boundary(incoming)
        val count = aEnd - aStart
        require(count <= 512) { "Transition exceeds the renderer's 512-pulse fade limit" }
        TransitionEvidence.requireBars(first, a, outgoing, bars, inherited, isCancelled)
        first.requirePulseGeometryRange(aStart, aEnd + 1)
        val directlySupportedEnd = inherited.minOrNull()?.let { a.boundary(it) } ?: aEnd
        if (directlySupportedEnd > aStart) first.requirePulseRange(aStart, directlySupportedEnd + 1)
        val firstGrid = BeatGrid(a.beats.times.copyOfRange(aStart, aEnd + 1))

        // Counts alone cannot distinguish two metrical levels from two different meters.
        // Use observed interval duration: correct only a tempo discrepancy within 15% of an octave.
        val outgoingPeriod = medianPeriod(a.beats, aStart, aEnd)
        val incomingPeriod = medianPeriod(b.beats, bStart, b.boundary(incoming + 1))
        val periodRatio = incomingPeriod / outgoingPeriod
        val cycles = mutableListOf(1 to 1)
        if (options.allowOctaveCorrection) {
            if (abs(ln(periodRatio / 2.0)) <= ln(1.15)) cycles.add(0, 2 to 1)
            if (abs(ln(periodRatio * 2.0)) <= ln(1.15)) cycles.add(0, 1 to 2)
        }
        var failure: RuntimeException? = null
        for ((outgoingCycle, incomingCycle) in cycles) {
            checkCancellation()
            try {
                val bEndPosition = bStart + count.toDouble() * incomingCycle / outgoingCycle
                val bEnd = ceil(bEndPosition).toInt()
                require(bEnd <= b.boundary(b.barCount)) {
                    "Not enough observed incoming pulses to cover the complete outgoing fade"
                }
                val touchedEndBar = b.barStartBeats.indexOfFirst { it >= bEnd }
                TransitionEvidence.requireBars(second, b, incoming, touchedEndBar - incoming,
                    emptySet(), isCancelled, endBeat = bEnd)
                second.requirePulseRange(bStart, bEnd + 1)
                require(fitOptions.pinnedIncomingBeats.all { it in bStart..bEnd && it <= bEndPosition }) {
                    "Pinned incoming canonical beat lies outside the selected pulse range"
                }
                val secondGrid = second.matchingGrid(bStart, bEnd + 1)
                require(
                    first.audio.durationSeconds.isFinite() && second.audio.durationSeconds.isFinite() &&
                        firstGrid.at(0) >= 0.0 && secondGrid.at(0) >= 0.0 &&
                        firstGrid.at(firstGrid.size - 1) < first.audio.durationSeconds &&
                        secondGrid.at(secondGrid.size - 1) < second.audio.durationSeconds
                ) { "Selected pulse anchors must lie inside both unchanged source recordings" }
                val plan = MixPlan(
                    firstGrid, secondGrid, 0, 0, count, outputSampleRate,
                    outgoingCycle, incomingCycle, releaseAfterFade = true,
                )
                // All consumed incoming downbeats retain their observed source times. Only the
                // entry is promised to coincide with an outgoing downbeat; interior meters may differ.
                val boundaryPins = b.barStartBeats.filter { it in bStart..bEnd }.map { it - bStart }
                val pins = fitOptions.pinnedIncomingBeats.map { it - bStart }.toSet() + boundaryPins
                val fit = BeatClockRegularizer.regularize(plan, second.audio.durationSeconds,
                    fitOptions.copy(pinnedIncomingBeats = pins), qualityLimits, isCancelled)
                val accepted = fit.requireAccepted()
                val endBoundary = b.barStartBeats.indexOfFirst { it.toDouble() == bEndPosition }
                return LocalMixPlan(
                    fit, MixMode.TRANSITION, outgoing, incoming, bars,
                    if (endBoundary < 0) null else endBoundary - incoming,
                    first.audio.durationSeconds, second.audio.durationSeconds,
                    barMatches = listOf(MatchedBarBoundary(outgoing, incoming, aStart, bStart,
                        a.beats.at(aStart), b.beats.at(bStart), accepted.second.at(0),
                        accepted.secondOutputTime(b.beats.at(bStart)) - a.beats.at(aStart))),
                    transitionAlignment = TransitionPulseAlignment(aStart, aEnd, bStart,
                        bEndPosition, outgoingCycle, incomingCycle, inherited.sorted()),
                )
            } catch (e: IllegalArgumentException) {
                // Cancellation is an IllegalStateException and deliberately never enters retries.
                failure = e
            } catch (e: UncertainBarsException) {
                failure = e
            }
        }
        throw checkNotNull(failure)
    }

    private fun medianPeriod(grid: BeatGrid, start: Int, end: Int): Double {
        val periods = DoubleArray(end - start) { grid.at(start + it + 1) - grid.at(start + it) }
        periods.sort()
        val middle = periods.size / 2
        return if (periods.size % 2 == 0) (periods[middle - 1] + periods[middle]) / 2 else periods[middle]
    }

    private fun proposedBars(song: LocalSongAnalysis): BarGrid = song.barTracking?.grid() ?:
        BarGrid.from(BeatGrid(song.pulse.beats.map { it.seconds }.toDoubleArray()), song.pulse.downbeatSeconds)
}

internal object TransitionEvidence {
    fun requireBars(
        song: LocalSongAnalysis, grid: BarGrid, start: Int, count: Int,
        inherited: Set<Int>, isCancelled: () -> Boolean, endBeat: Int = grid.boundary(start + count),
    ) {
        val tracking = song.barTracking
        for (bar in start until start + count) {
            if (isCancelled()) throw MixCancelledException()
            if (bar in inherited) {
                val issues = tracking?.issuesForBar(bar).orEmpty()
                if (issues.any { it != BarUsabilityIssue.UNSUPPORTED_ACOUSTIC_PULSE })
                    throw UncertainBarsException("Bar $bar is uncertain: ${issues.joinToString()}")
                song.requirePulseGeometryRange(grid.boundary(bar), grid.boundary(bar + 1) + 1)
            } else {
                // Check phase even for the partially consumed last incoming bar.
                if (tracking != null) {
                    val issues = tracking.issuesForBar(bar).filter {
                        it != BarUsabilityIssue.UNSUPPORTED_ACOUSTIC_PULSE ||
                            endBeat >= grid.boundary(bar + 1)
                    }
                    if (issues.isNotEmpty()) tracking.requireUsable(bar, 1)
                }
                song.requirePulseRange(grid.boundary(bar), minOf(grid.boundary(bar + 1), endBeat) + 1)
            }
        }
    }

    fun inheritedTail(
        song: LocalSongAnalysis, grid: BarGrid, start: Int, count: Int,
        options: LocalTransitionOptions, isCancelled: () -> Boolean,
    ): Set<Int> {
        if (options.maximumInheritedOutroBars == 0) return emptySet()
        val acoustic = song.acousticPulse ?: return emptySet()
        acoustic.requireClock(grid.beats.times)
        val end = start + count
        val firstWeak = (start until end).firstOrNull {
            !acoustic.supportsRange(grid.boundary(it), grid.boundary(it + 1) + 1)
        } ?: return emptySet()
        // A bounded trailing run needs a directly verified preceding bar, including geometry.
        if (firstWeak == 0 || end - firstWeak > options.maximumInheritedOutroBars) return emptySet()
        val anchor = firstWeak - 1
        try { requireBars(song, grid, anchor, 1, emptySet(), isCancelled) }
        catch (_: IllegalArgumentException) { return emptySet() }
        catch (_: UncertainBarsException) { return emptySet() }
        val anchorPeriod = (grid.beats.at(grid.boundary(anchor + 1)) -
            grid.beats.at(grid.boundary(anchor))) / grid.beatsInBar(anchor)
        val inherited = mutableSetOf<Int>()
        for (bar in firstWeak until end) {
            if (isCancelled()) throw MixCancelledException()
            val assessment = acoustic.assessRange(grid.boundary(bar), grid.boundary(bar + 1) + 1)
            if (assessment.supported) return emptySet() // Never bridge an internal failed gap.
            if (AcousticPulseIssue.INSUFFICIENT_LOCAL_ATTACKS !in assessment.issues &&
                assessment.issues != setOf(AcousticPulseIssue.INSUFFICIENT_CONTEXT)) return emptySet()
            if (song.barTracking?.issuesForBar(bar).orEmpty().any {
                it != BarUsabilityIssue.UNSUPPORTED_ACOUSTIC_PULSE
            }) return emptySet()
            if (grid.beatsInBar(bar) != grid.beatsInBar(anchor)) return emptySet()
            for (beat in grid.boundary(bar) until grid.boundary(bar + 1)) {
                val period = grid.beats.at(beat + 1) - grid.beats.at(beat)
                if (abs(period / anchorPeriod - 1.0) > 0.1) return emptySet()
            }
            // This option cannot expand a rejected canonical pulse region.
            song.requirePulseGeometryRange(grid.boundary(bar), grid.boundary(bar + 1) + 1)
            inherited += bar
        }
        return inherited
    }
}
