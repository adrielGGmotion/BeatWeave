package org.metrolist.beatweave.learned

import kotlin.test.*
import org.metrolist.beatweave.*

class LocalMixPlannerTest {
    private fun song(
        period: Double,
        offset: Double = 0.2,
        broken: Boolean = false,
        beatCount: Int = 145,
    ): LocalSongAnalysis {
        val beats = List(beatCount) { Beat(offset + it * period, 0.9f) }
        val downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }.map { it.seconds }
        val duration = beats.last().seconds + 0.8
        val audio =
            Analysis(
                duration,
                60 / period,
                1.0,
                beats,
                -18.0,
                -6.0,
                800.0,
                "unknown",
                0.0,
                emptyList(),
                FloatArray(0),
                0.02,
                emptyList(),
                downbeatSeconds = downbeats,
            )
        val issues =
            if (broken)
                listOf(
                    BeatGridIssue(
                        "UNCONFIRMED_PULSE",
                        BeatIssueSeverity.ERROR,
                        0.0,
                        duration,
                        "fixture rejects automatic matching",
                    )
                )
            else emptyList()
        val pulse =
            PulseNormalizationResult(
                beats,
                beats,
                downbeats,
                emptyList(),
                BeatGridQualityReport(beats.size, audio.bpm, issues),
                1.0,
            )
        return LocalSongAnalysis(
            audio,
            LearnedBeatAnalysis(
                duration,
                beats,
                downbeats,
                "annotated-fixture",
                BeatThisLogits(FloatArray(0), FloatArray(0)),
            ),
            pulse,
            audio,
        )
    }

    @Test
    fun overlapKeepsObservedCuesAndDoesNotRefitAnAcceptedClock() {
        val a = song(60.0 / 156)
        val b = song(60.0 / 155, 0.35)
        val result = LocalMixPlanner.beatOverlap(a, b, outgoingCueBeat = 8, incomingCueBeat = 4)
        assertEquals(MixMode.OVERLAP, result.mode)
        assertEquals(8, result.beatCoverage!!.outgoingCueBeat)
        assertEquals(4, result.beatCoverage!!.incomingCueBeat)
        assertFalse(result.mixPlan.releaseAfterFade)
        assertEquals(0, result.clockFit.report.sweeps)
        assertTrue(result.clockFit.adjustments.all { it.displacementSeconds == 0.0 })
        assertEquals(
            a.audio.beats[8].seconds,
            result.mixPlan.secondOutputTime(b.audio.beats[4].seconds),
            1e-8,
        )
    }

    @Test
    fun everySupportedBarCountSelectsCompleteBarsAndReleasesTempo() {
        val a = song(0.4)
        val b = song(0.41)
        for (bars in listOf(2, 4, 8, 16, 32)) {
            val result = LocalMixPlanner.transition(a, b, 1, 2, bars)
            assertEquals(bars, result.outgoingBars)
            assertEquals(bars, result.incomingBars)
            assertEquals(bars * 4, result.mixPlan.crossfadeBeats)
            assertEquals(MixMode.TRANSITION, result.mode)
            assertTrue(result.mixPlan.releaseAfterFade)
            assertTrue(result.clockFit.report.accepted)
        }
    }

    @Test
    fun explicitBarTransitionRemapsOriginalCanonicalPinsToItsSelectedClock() {
        val first = song(0.4)
        val second = song(0.41)
        val unpinned = LocalMixPlanner.transition(first, second, 8, 4, outgoingBars = 4)
        assertFalse(unpinned.clockFit.adjustments[5].pinned)
        val result = LocalMixPlanner.transition(
            first, second, 8, 4, outgoingBars = 4,
            fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(21)),
        )
        val pin = result.clockFit.adjustments[5]
        assertTrue(pin.pinned)
        assertEquals(second.pulse.beats[21].seconds, pin.originalSourceSeconds)
        assertEquals(pin.originalSourceSeconds, pin.adjustedSourceSeconds)
        assertEquals(0.0, pin.allowedDisplacementSeconds)

        // Unselected original pins must not be reinterpreted as local cropped indices.
        val outside = assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.transition(
                first, second, 8, 4, outgoingBars = 4,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(5)),
            )
        }
        assertTrue(outside.message.orEmpty().contains("outside the selected bar range"))
    }

    @Test
    fun failedPulseEvidenceAndCancellationCannotStartPreparation() {
        assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.overlap(song(0.4), song(0.4, broken = true))
        }
        assertFailsWith<MixCancelledException> {
            LocalMixPlanner.overlap(song(0.4), song(0.4), isCancelled = { true })
        }
    }

    @Test
    fun rejectedIncomingOutroAfterTheShorterTrackEndsDoesNotRejectFullPairedBeatOverlap() {
        val a = song(0.4, beatCount = 65)
        val original = song(0.41)
        val accepted = original.pulse.beats.take(97)
        val issue =
            BeatGridIssue(
                "UNSUPPORTED_OUTRO",
                BeatIssueSeverity.ERROR,
                original.pulse.beats[112].seconds,
                original.audio.durationSeconds,
                "unpaired tail is uncertain",
            )
        val b =
            original.copy(
                pulse =
                    original.pulse.copy(
                        quality = original.pulse.quality.copy(issues = listOf(issue))
                    ),
                pulseRegions =
                    listOf(
                        AcceptedPulseRegion(
                            0,
                            97,
                            accepted.first().seconds,
                            accepted.last().seconds,
                            BeatGridQuality.audit(accepted),
                        )
                    ),
            )
        assertFailsWith<IllegalArgumentException> { b.matchingGrid() }
        val result = LocalMixPlanner.beatOverlap(a, b)
        val coverage = assertNotNull(result.beatCoverage)
        assertEquals(65, coverage.pairedBeatCount)
        assertEquals(64, coverage.outgoingEndBeat)
        assertEquals(64, coverage.incomingEndBeat)
        assertTrue(coverage.incomingEndSeconds < issue.startSeconds)
        assertEquals(original.audio.durationSeconds, coverage.incomingSourceDurationSeconds)
        assertEquals(original.pulse.beats, b.pulse.beats)
        val schedule = WarpSchedule.from(result.mixPlan, b.audio.durationSeconds)
        assertEquals(
            kotlin.math.ceil(b.audio.durationSeconds * result.mixPlan.outputSampleRate).toLong(),
            schedule.sourceFrames,
        )
        assertTrue(result.clockFit.report.accepted)
        assertFalse(b.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun rejectedGapInsideTheJointBeatSpanCannotBecomeAnUnreportedEarlyCutoff() {
        val a = song(0.4)
        val original = song(0.41)
        fun region(start: Int, end: Int): AcceptedPulseRegion {
            val beats = original.pulse.beats.subList(start, end)
            return AcceptedPulseRegion(
                start,
                end,
                beats.first().seconds,
                beats.last().seconds,
                BeatGridQuality.audit(beats),
            )
        }
        val issue =
            BeatGridIssue(
                "INTERNAL_GAP",
                BeatIssueSeverity.ERROR,
                original.pulse.beats[60].seconds,
                original.pulse.beats[70].seconds,
                "both tracks continue after this gap",
            )
        val b =
            original.copy(
                pulse =
                    original.pulse.copy(
                        quality = original.pulse.quality.copy(issues = listOf(issue))
                    ),
                pulseRegions = listOf(region(0, 49), region(81, 145)),
            )
        val rejection =
            assertFailsWith<BeatOverlapPlanningException> { LocalMixPlanner.beatOverlap(a, b) }
        assertEquals(BeatOverlapFailureCode.UNSUPPORTED_SHARED_COVERAGE, rejection.code)
        assertEquals(145, rejection.coverage!!.pairedBeatCount)
        assertEquals(144, rejection.coverage!!.incomingEndBeat)
        assertTrue(rejection.coverage!!.incomingEndSeconds > issue.endSeconds)
    }

    @Test
    fun defaultCueUsesAcceptedEvidenceAndRemapsOriginalPinnedIndicesOnTheScopedClock() {
        val a = song(0.4)
        val original = song(0.41)
        val accepted = original.pulse.beats.drop(8)
        val issue =
            BeatGridIssue(
                "UNSUPPORTED_INTRO",
                BeatIssueSeverity.ERROR,
                0.0,
                original.pulse.beats[6].seconds,
                "incoming preroll precedes the supported cue",
            )
        val b =
            original.copy(
                pulse =
                    original.pulse.copy(
                        quality = original.pulse.quality.copy(issues = listOf(issue))
                    ),
                pulseRegions =
                    listOf(
                        AcceptedPulseRegion(
                            8,
                            145,
                            accepted.first().seconds,
                            accepted.last().seconds,
                            BeatGridQuality.audit(accepted),
                        )
                    ),
            )
        val result =
            LocalMixPlanner.beatOverlap(
                a,
                b,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(12)),
            )
        val coverage = assertNotNull(result.beatCoverage)
        assertEquals(0, coverage.outgoingCueBeat)
        assertEquals(8, coverage.incomingCueBeat)
        assertEquals(8, coverage.incomingStartBeat)
        assertEquals(137, coverage.pairedBeatCount)
        assertTrue(result.clockFit.adjustments[4].pinned)
        assertEquals(
            original.pulse.beats[12].seconds,
            result.clockFit.adjustments[4].originalSourceSeconds,
        )
        assertTrue(result.mixPlan.secondOutputTime(0.0) < 0.0)
    }

    @Test
    fun uncertainBarInterpretationBlocksBarTransitionsButPreservesAcceptedBeatOverlap() {
        val base = song(0.4)
        val unknownBars =
            BarTracker.track(
                base.pulse.beats,
                base.model.downbeatSeconds,
                FloatArray((base.audio.durationSeconds * 50).toInt()),
            )
        val uncertain = base.copy(barTracking = unknownBars)
        assertFalse(unknownBars.safeForAutomaticBars)
        assertFailsWith<UncertainBarsException> {
            LocalMixPlanner.transition(uncertain, uncertain, 0, 0, outgoingBars = 2)
        }
        assertFailsWith<AutoMixPlanningException> { LocalMixPlanner.overlap(uncertain, uncertain) }
        val overlap = LocalMixPlanner.beatOverlap(uncertain, uncertain)
        assertTrue(overlap.clockFit.report.accepted)
        assertEquals(MixMode.OVERLAP, overlap.mode)
        assertEquals(0, overlap.mixPlan.firstBeat)
        assertEquals(0, overlap.mixPlan.secondBeat)
        assertEquals(base.pulse.beats.map { it.seconds }, uncertain.matchingGrid().times.toList())
    }

    @Test
    fun sourceClockMismatchIsRejectedBeforeCallingAnEngine() {
        val a = song(0.4)
        val plan = LocalMixPlanner.beatOverlap(a, a)
        val wrongSource =
            object : StereoPcm {
                override val durationSeconds = a.audio.durationSeconds - 0.1

                override fun read(
                    startSeconds: Double,
                    frames: Int,
                    outputSampleRate: Int,
                ): FloatArray = error("must not read")
            }
        val engine =
            object : PitchStretchEngine {
                override fun prepare(
                    source: StereoPcm,
                    schedule: WarpSchedule,
                    progress: (Double) -> Unit,
                ): PreparedStereoPcm = error("must not prepare mismatched audio")
            }
        assertFailsWith<IllegalArgumentException> { plan.prepare(wrongSource, wrongSource, engine) }
    }
}
