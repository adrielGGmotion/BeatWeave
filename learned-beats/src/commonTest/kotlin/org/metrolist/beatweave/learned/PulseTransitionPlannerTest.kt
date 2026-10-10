package org.metrolist.beatweave.learned

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.*
import org.metrolist.beatweave.*

class PulseTransitionPlannerTest {
    private fun song(meter: Int = 4, period: Double = .4, bars: Int = 20): LocalSongAnalysis {
        val beats = List(meter * bars + 1) { Beat(.2 + it * period, .95f) }
        val downbeats = beats.filterIndexed { index, _ -> index % meter == 0 }.map { it.seconds }
        val duration = beats.last().seconds + 1
        val logits = FloatArray((duration * 50).roundToInt()) { -8f }
        downbeats.forEach { logits[(it * 50).roundToInt()] = 8f }
        val audio = Analysis(duration, 60 / period, 1.0, beats, -18.0, -6.0, 800.0,
            "F", 1.0, emptyList(), FloatArray(0), .02, emptyList(), downbeatSeconds = downbeats)
        val pulse = PulseNormalizationResult(beats, beats, downbeats, emptyList(),
            BeatGridQualityReport(beats.size, audio.bpm, emptyList()), 1.0)
        val tracking = BarTracker.track(beats, downbeats, logits,
            BarTrackingOptions(pulsesPerBar = listOf(meter)))
        return LocalSongAnalysis(audio, LearnedBeatAnalysis(duration, beats, downbeats,
            "annotated", BeatThisLogits(FloatArray(logits.size), logits)), pulse, audio, tracking)
    }

    @Test
    fun fourAgainstThreePairsEveryPulseAndAllowsAPartialFinalIncomingBar() {
        val a = song(4)
        val b = song(3, .41)
        val result = LocalMixPlanner.transition(a, b, 4, 1, 8)
        assertTrue(result.clockFit.report.accepted)
        assertEquals(32, result.mixPlan.crossfadeBeats)
        assertEquals(8, result.outgoingBars)
        assertNull(result.incomingBars)
        assertEquals(1, result.barMatches.size)
        val alignment = assertNotNull(result.transitionAlignment)
        assertEquals(35.0, alignment.incomingEndBeatPosition)
        assertEquals(1, alignment.outgoingPulsesPerCycle)
        assertEquals(1, alignment.incomingPulsesPerCycle)
        for (i in 0..32) {
            assertEquals(a.pulse.beats[16 + i].seconds,
                result.mixPlan.secondOutputTime(b.pulse.beats[3 + i].seconds), 1e-8)
        }
        assertEquals(a.pulse.beats[48].seconds, result.mixPlan.fadeEndSeconds, 1e-8)
        assertTrue(result.mixPlan.releaseAfterFade)
    }

    @Test
    fun halfAndDoubleTimeUseObservedOctaveRatiosWithoutResamplingTheGrid() {
        for ((a, b) in listOf(song(8, .2) to song(4, .4), song(4, .4) to song(8, .2))) {
            val result = LocalMixPlanner.transition(a, b, 2, 1, 8)
            val alignment = assertNotNull(result.transitionAlignment)
            assertEquals(if (a.audio.bpm > b.audio.bpm) 2 else 1,
                alignment.outgoingPulsesPerCycle)
            assertEquals(if (a.audio.bpm > b.audio.bpm) 1 else 2,
                alignment.incomingPulsesPerCycle)
            assertEquals(8, result.incomingBars)
            assertEquals(1.0, result.clockFit.report.adjustedQuality.minimumPlaybackSpeed, 1e-8)
            assertEquals(1.0, result.clockFit.report.adjustedQuality.maximumPlaybackSpeed, 1e-8)
            assertEquals(0, result.clockFit.report.sweeps)
            val start = alignment.incomingStartBeat
            assertEquals(b.pulse.beats.subList(start, alignment.incomingEndBeatPosition.toInt() + 1)
                .map { it.seconds }, result.mixPlan.second.times.toList())
        }
    }

    @Test
    fun pulseCountsAloneDoNotTriggerOctaveCorrection() {
        val result = LocalMixPlanner.transition(song(8), song(4), 2, 1, 2)
        assertEquals(1, result.mixPlan.firstBeatsPerCycle)
        assertEquals(1, result.mixPlan.secondBeatsPerCycle)
        assertEquals(4, result.incomingBars)
        assertEquals(1.0, result.clockFit.report.adjustedQuality.minimumPlaybackSpeed, 1e-8)
    }

    @Test
    fun compoundAndSimplePulseCountsDoNotForceBarDurationStretching() {
        val result = LocalMixPlanner.transition(song(6), song(4), 2, 1, 2)
        assertEquals(12, result.mixPlan.crossfadeBeats)
        assertEquals(3, result.incomingBars)
        assertEquals(1.0, result.clockFit.report.adjustedQuality.minimumPlaybackSpeed, 1e-8)
    }

    @Test
    fun sameSongTwiceAndExistingBarPairsRetainTheExactLegacyPlan() {
        val a = song(period = 60 / 94.86, bars = 40)
        val b = song(period = .41, bars = 40)
        for (second in listOf(a, b)) for (bars in TransitionPlanner.supportedBarCounts) {
            val legacy = AutoMixPlanner.scopedPlan(a, second, a.bars(), second.bars(), 2, 1,
                bars, MixMode.TRANSITION, 48000, ClockFitOptions(), WarpQualityLimits(), { false })
            val result = LocalMixPlanner.transition(a, second, 2, 1, bars)
            assertEquals(legacy.mixPlan.first.times.toList(), result.mixPlan.first.times.toList())
            assertEquals(legacy.mixPlan.second.times.toList(), result.mixPlan.second.times.toList())
            assertEquals(legacy.barMatches, result.barMatches)
            assertEquals(legacy.clockFit.adjustments, result.clockFit.adjustments)
            assertEquals(legacy.clockFit.report, result.clockFit.report)
            assertEquals(legacy.mixPlan.crossfadeBeats, result.mixPlan.crossfadeBeats)
            assertEquals(legacy.mixPlan.fadeEndSeconds, result.mixPlan.fadeEndSeconds)
            assertNull(result.transitionAlignment)
            if (a === second) {
                assertEquals(0, result.clockFit.report.sweeps)
                val offset = a.pulse.beats[8].seconds - a.pulse.beats[4].seconds
                assertEquals(a.audio.durationSeconds + offset,
                    result.mixPlan.secondOutputTime(a.audio.durationSeconds), 1e-8)
            }
        }
    }

    @Test
    fun pulseAlignmentPreservesOriginalPinsAndRejectsPinsBeyondTheFade() {
        val a = song(4)
        val b = song(3, .41)
        val result = LocalMixPlanner.transition(a, b, 4, 1, 8,
            fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(8)))
        assertTrue(result.clockFit.adjustments[5].pinned)
        assertEquals(b.pulse.beats[8].seconds, result.clockFit.adjustments[5].originalSourceSeconds)
        assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.transition(a, b, 4, 1, 8,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(36)))
        }
    }

    @Test
    fun fractionalHalfTimeEndpointHasCompleteObservedCoverage() {
        val a = song(3, .2)
        val b = song(4, .4)
        val result = LocalMixPlanner.transition(a, b, 1, 1, 2)
        assertEquals(7.0, result.transitionAlignment!!.incomingEndBeatPosition)
        assertNull(result.incomingBars)
        // An odd-pulse pair produces a half-pulse endpoint without inventing a source event.
        val odd = song(3, .2).let { base ->
            val starts = intArrayOf(0, 3, 6, 10, 13, 16, 19, 22, 25, 28, 31, 34, 37, 40, 43, 46, 49, 52, 55, 58)
            base.copy(barTracking = null, pulse = base.pulse.copy(
                downbeatSeconds = starts.map { base.pulse.beats[it].seconds }))
        }
        val fractional = LocalMixPlanner.transition(odd, b, 1, 1, 2)
        assertEquals(7.5, fractional.transitionAlignment!!.incomingEndBeatPosition)
        assertEquals(5, fractional.mixPlan.second.size) // Original observations 4..8 bracket 7.5.
    }

    @Test
    fun missingCoverageStrictPolicyAndCancellationRemainRejections() {
        assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.transition(song(4), song(3, bars = 4), 4, 1, 8)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.transition(song(4), song(3), 4, 1, 8,
                options = LocalTransitionOptions(allowDifferentPulseCounts = false))
        }
        assertFailsWith<MixCancelledException> {
            LocalMixPlanner.transition(song(4), song(3), 4, 1, 8, isCancelled = { true })
        }
        var calls = 0
        assertFailsWith<MixCancelledException> {
            LocalMixPlanner.transition(song(8, .2), song(4, .4), 4, 1, 8,
                isCancelled = { ++calls >= 8 })
        }
        assertFailsWith<MixCancelledException> { song().transitionCoverage { true } }
    }

    private fun rejectedOutro(base: LocalSongAnalysis, lastAcceptedBeat: Int): LocalSongAnalysis {
        val accepted = base.pulse.beats.take(lastAcceptedBeat + 1)
        return base.copy(pulse = base.pulse.copy(quality = base.pulse.quality.copy(issues = listOf(
            BeatGridIssue("UNSUPPORTED_OUTRO", BeatIssueSeverity.ERROR,
                base.pulse.beats[lastAcceptedBeat + 1].seconds, base.audio.durationSeconds,
                "sparse/rubato outro is unsupported")))), pulseRegions = listOf(
            AcceptedPulseRegion(0, lastAcceptedBeat + 1, accepted.first().seconds,
                accepted.last().seconds, BeatGridQuality.audit(accepted))))
    }

    @Test
    fun coverageLocatesAnEarlierBlendBeforeAnUnsupportedOutroWithoutExtendingIt() {
        val base = song()
        val rejected = rejectedOutro(base, 48)
        val report = rejected.transitionCoverage()
        assertEquals(48, report.lastAcceptedPulseBeat)
        assertEquals(base.pulse.beats[48].seconds, report.lastAcceptedPulseSeconds)
        assertEquals(base.pulse.beats[48].seconds, report.lastUsableBarEndSeconds)
        val region = report.regions.single()
        assertEquals(12, region.endBarExclusive)
        val result = LocalMixPlanner.transition(rejected, base, region.endBarExclusive - 4, 1, 4)
        assertTrue(result.clockFit.report.accepted)
        assertEquals(report.lastUsableBarEndSeconds, result.mixPlan.fadeEndSeconds)
        // Caller-annotated bars without BarTracker also audit the selected body, not the outro.
        val annotated = LocalMixPlanner.transition(rejected.copy(barTracking = null),
            base.copy(barTracking = null), region.endBarExclusive - 4, 1, 4)
        assertEquals(result.mixPlan.fadeEndSeconds, annotated.mixPlan.fadeEndSeconds)
        assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.transition(rejected, base, 12, 1, 2,
                options = LocalTransitionOptions(maximumInheritedOutroBars = 2))
        }
        assertEquals(base.pulse.beats, rejected.pulse.beats)
    }

    private fun withAcoustics(base: LocalSongAnalysis, quietFrom: Int = Int.MAX_VALUE,
        quietIntervals: Set<Int> = emptySet()): LocalSongAnalysis {
        val sampleRate = 4000
        val pcm = FloatArray((base.audio.durationSeconds * sampleRate).toInt())
        for (beat in base.pulse.beats.indices) if (beat < quietFrom && beat !in quietIntervals) {
            val start = ((base.pulse.beats[beat].seconds + .1) * sampleRate).toInt()
            for (j in 0 until 80) if (start + j < pcm.size)
                pcm[start + j] = (.8 * sin(2 * PI * 670 * j / sampleRate) * (1 - j / 80.0)).toFloat()
        }
        val prepared = MusicAnalyzer().prepare(pcm, sampleRate)
        val acoustic = AcousticPulseEvidence.assess(prepared.acousticAttacks, base.pulse.beats)
        return base.copy(acousticPulse = acoustic,
            barTracking = base.barTracking!!.withAcousticPulseSupport(acoustic))
    }

    @Test
    fun onlyAnExplicitSparseTrailingAcousticRunMayInheritConfidentTiming() {
        val base = song(period = .5, bars = 16)
        val good = withAcoustics(base)
        val fading = withAcoustics(base, quietFrom = 48)
        val lastSupported = (1..12).last { fading.barTracking!!.isBarUsable(it) }
        val start = lastSupported
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(fading, good, start, 2, 2)
        }
        val result = LocalMixPlanner.transition(fading, good, start, 2, 2,
            options = LocalTransitionOptions(maximumInheritedOutroBars = 2))
        assertEquals(listOf(start + 1), result.transitionAlignment!!.inheritedOutgoingBars)
        assertEquals(base.pulse.beats.subList(start * 4, (start + 2) * 4 + 1).map { it.seconds },
            result.mixPlan.first.times.toList())
        assertTrue(result.clockFit.report.accepted)
        // Incoming evidence never inherits, and the strict analysis audit remains unchanged.
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(good, fading, 2, start, 2,
                options = LocalTransitionOptions(maximumInheritedOutroBars = 2))
        }
        assertFalse(fading.barTracking!!.isBarUsable(start + 1))
        assertEquals(good.pulse.beats, fading.pulse.beats)
        assertTrue(fading.transitionCoverage().lastUsableBarEndSeconds!! < base.audio.durationSeconds)
    }

    @Test
    fun sparsePolicyCannotBridgeAnInternalGapOrBorrowAnEntireSilentTrack() {
        val base = song(period = .5, bars = 16)
        val good = withAcoustics(base)
        val gap = withAcoustics(base, quietIntervals = (32..35).toSet())
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(gap, good, 6, 2, 8,
                options = LocalTransitionOptions(maximumInheritedOutroBars = 8))
        }
        val silent = withAcoustics(base, quietFrom = 0)
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(silent, good, 4, 2, 4,
                options = LocalTransitionOptions(maximumInheritedOutroBars = 8))
        }
    }

    @Test
    fun sparsePolicyRetainsPhaseGatesAndTheCallerInheritanceLimit() {
        val base = song(period = .5, bars = 16)
        val good = withAcoustics(base)
        val fading = withAcoustics(base, quietFrom = 48)
        val start = (1..12).last { fading.barTracking!!.isBarUsable(it) }
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(fading, good, start, 2, 4,
                options = LocalTransitionOptions(maximumInheritedOutroBars = 2))
        }
        val tracking = fading.barTracking!!
        val weakPhase = BarTrackingResult(base.pulse.beats.map { it.seconds }.toDoubleArray(),
            tracking.rawDownbeatSeconds, tracking.boundaries.map {
                if (it.beatIndex == (start + 1) * 4) it.copy(modelDownbeatScore = .1) else it
            }, tracking.regions, tracking.edits, tracking.barMinimumStatePosteriors,
            BooleanArray(tracking.barCount) { true }, tracking.options)
            .withAcousticPulseSupport(fading.acousticPulse!!)
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(fading.copy(barTracking = weakPhase), good, start, 2, 2,
                options = LocalTransitionOptions(maximumInheritedOutroBars = 2))
        }
    }

    @Test
    fun coverageDoesNotMergeLocallySupportedBarsAcrossAdjacentSilentJoinIntervals() {
        val base = song(period = .5, bars = 16)
        val joinedRests = withAcoustics(base, quietIntervals = setOf(11, 12))
        val acoustic = joinedRests.acousticPulse!!
        assertTrue(acoustic.supportsRange(8, 13))
        assertTrue(acoustic.supportsRange(12, 17))
        assertFalse(acoustic.supportsRange(8, 17))
        val coverage = joinedRests.transitionCoverage()
        assertTrue(coverage.regions.none { it.startBar <= 2 && it.endBarExclusive >= 4 })
    }
}
