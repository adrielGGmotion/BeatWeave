package org.metrolist.beatweave.learned

import kotlin.math.roundToInt
import kotlin.test.*
import org.metrolist.beatweave.*

class AutoMixPlannerTest {
    private fun song(
        meters: List<Int>,
        period: Double = 0.4,
        periods: List<Double>? = null,
    ): LocalSongAnalysis {
        val starts = ArrayList<Int>()
        var count = 0
        meters.forEach {
            starts += count
            count += it
        }
        starts += count
        val intervals =
            meters.flatMapIndexed { bar, pulses -> List(pulses) { periods?.get(bar) ?: period } }
        var seconds = 0.2
        val beats =
            List(count + 1) {
                if (it > 0) seconds += intervals[it - 1]
                Beat(if (periods == null) 0.2 + it * period else seconds, 0.95f)
            }
        val raw = starts.map { beats[it].seconds }
        val duration = beats.last().seconds + 1.0
        val logits = FloatArray((duration * 50).roundToInt()) { -8f }
        raw.forEach { logits[(it * 50).roundToInt()] = 8f }
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
                downbeatSeconds = raw,
            )
        val pulse =
            PulseNormalizationResult(
                beats,
                beats,
                raw,
                emptyList(),
                BeatGridQualityReport(beats.size, audio.bpm, emptyList()),
                1.0,
            )
        val tracking =
            BarTracker.track(
                beats,
                raw,
                logits,
                BarTrackingOptions(
                    pulsesPerBar = (listOf(2, 3, 4, 6, 8) + meters).distinct().sorted()
                ),
            )
        return LocalSongAnalysis(
            audio,
            LearnedBeatAnalysis(
                duration,
                beats,
                raw,
                "synthetic-annotated",
                BeatThisLogits(FloatArray(logits.size), logits),
            ),
            pulse,
            audio,
            tracking,
        )
    }

    @Test
    fun automaticTransitionsChooseEarlyIncomingAndLateOutgoingForEachRequestedLength() {
        val a = song(List(40) { 4 })
        val b = song(List(40) { 4 }, 0.41)
        for (bars in listOf(2, 4, 8, 16, 32)) {
            val result = LocalMixPlanner.autoTransition(a, b, bars)
            val selection = assertNotNull(result.automaticSelection)
            assertEquals(40 - bars, selection.outgoingStartBar)
            assertEquals(0, selection.incomingStartBar)
            assertEquals(bars, selection.barCount)
            assertEquals(bars + 1, result.barMatches.size)
            assertTrue(
                result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
            )
            assertTrue(
                result.barMatches.all {
                    kotlin.math.abs(it.originalBoundaryOutputResidualSeconds) < 1e-8
                }
            )
            assertEquals(
                a.audio.beats[selection.outgoingStartBeat].seconds,
                result.mixPlan.first.at(0),
            )
            assertTrue(result.mixPlan.releaseAfterFade)
        }
    }

    @Test
    fun allInteriorBarBoundariesMustMatchEvenWhenTotalPulsesMatch() {
        val a = song(List(4) { 3 } + List(4) { 5 }).copy(barTracking = null)
        val b = song(List(8) { 4 }).copy(barTracking = null)
        assertEquals(a.pulse.beats.size, b.pulse.beats.size)
        assertFailsWith<IllegalArgumentException> { LocalMixPlanner.transition(a, b, 0, 0, 8) }
        val mismatch =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(song(List(12) { 3 }), song(List(12) { 4 }), 4)
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, mismatch.report.failure)
    }

    @Test
    fun automaticOverlapMatchesSupportedMeterChangesAndPinsEveryBoundary() {
        val meters = List(4) { 8 } + List(10) { 6 }
        val a = song(meters)
        val b = song(meters, 0.41)
        val result = LocalMixPlanner.overlap(a, b)
        val selection = assertNotNull(result.automaticSelection)
        assertEquals(14, selection.barCount)
        assertEquals(meters, selection.pulsesPerBar)
        assertEquals(15, result.barMatches.size)
        assertFalse(result.mixPlan.releaseAfterFade)
        assertTrue(
            result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
        )
        assertEquals(AutoMixSelectionPolicy.LONGEST_SUPPORTED_OVERLAP, selection.policy)
    }

    @Test
    fun equivalentDurationTieDoesNotMoveTheAutomaticCueBecauseOfFloatingPointNoise() {
        val result = AutoMixPlanner.overlap(song(List(20) { 4 }), song(List(4) { 4 }))
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(4, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
    }

    @Test
    fun longConstantMeterSongsAvoidQuadraticCandidateMaterialization() {
        val a = song(List(900) { 4 })
        val b = song(List(900) { 4 }, 0.41)
        val result = AutoMixPlanner.overlap(a, b)
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(900, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
        assertEquals(AutoMixSearchStrategy.CONSTANT_METER_MERGE, selected.search.strategy)
        assertEquals(899L * 899L, selected.search.compatibleCandidates)
        assertEquals(899, selected.search.inspectedPairs)
        assertEquals(901, result.barMatches.size)
        assertTrue(
            result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
        )
    }

    @Test
    fun incompatibleConstantMetersAreDeclinedWithoutExhaustingPairBudget() {
        val failure =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(
                    song(List(30) { 3 }),
                    song(List(30) { 4 }),
                    searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 1),
                )
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, failure.report.failure)
        assertEquals(0, failure.report.inspectedPairs)
        assertEquals(0L, failure.report.compatibleCandidates)
    }

    @Test
    fun lazyOverlapAdvancesRejectedStreamsToTheHighestRankedShorterSpan() {
        val a = song(List(4) { 4 })
        val b = song(List(4) { 4 }, periods = listOf(0.6, 0.4, 0.4, 0.4))
        val result =
            AutoMixPlanner.overlap(
                a,
                b,
                qualityLimits = WarpQualityLimits(maximumPlaybackSpeed = 1.1),
            )
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(3, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(1, selected.incomingStartBar)
        assertTrue(selected.search.rejectedClocks > 0)
        assertTrue(
            result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
        )
    }

    @Test
    fun lazyOverlapKeepsDurationRankingWhenOutgoingTempoVaries() {
        val a = song(List(8) { 4 }, periods = List(4) { 0.4 } + List(4) { 0.6 })
        val b = song(List(4) { 4 })
        val result =
            AutoMixPlanner.overlap(
                a,
                b,
                qualityLimits = WarpQualityLimits(minimumPlaybackSpeed = 0.95),
            )
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(4, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
        assertTrue(selected.search.rejectedClocks > 0)
    }

    @Test
    fun rejectionReportSeparatesUsablePhaseFromRejectedPulseEvidence() {
        val good = song(List(10) { 4 })
        val issue =
            BeatGridIssue(
                "UNSUPPORTED_TEST_PULSES",
                BeatIssueSeverity.ERROR,
                0.0,
                good.audio.durationSeconds,
                "deliberately unsupported clock",
            )
        val bad =
            good.copy(
                pulse = good.pulse.copy(quality = good.pulse.quality.copy(issues = listOf(issue))),
                barTracking = good.barTracking!!.withPulseSupport(emptyList()),
            )
        val failure =
            assertFailsWith<AutoMixPlanningException> { AutoMixPlanner.transition(bad, good, 4) }
        assertEquals(AutoMixFailureCode.NO_CONFIDENT_BAR_GRID, failure.report.failure)
        val diagnostics = failure.report.tracks
        assertEquals(2, diagnostics.size)
        val outgoing = diagnostics.first { it.role == AutoMixTrackRole.OUTGOING }
        assertEquals(10, outgoing.phaseUsableBars)
        assertEquals(0, outgoing.pulseSupportedBars)
        assertEquals(0, outgoing.jointUsableBars)
        assertEquals(0, outgoing.longestSupportedBars)
        assertEquals(
            mapOf(BarUsabilityIssue.UNSUPPORTED_PULSE to 10),
            outgoing.unusableReasonCounts,
        )
        assertEquals(listOf("UNSUPPORTED_TEST_PULSES"), outgoing.sourcePulseErrorCodes)
        assertEquals(10, diagnostics.first { it.role == AutoMixTrackRole.INCOMING }.jointUsableBars)
    }

    @Test
    fun unrelatedRejectedOutroDoesNotBlockAConfidentScopedOverlap() {
        val base = song(List(16) { 4 })
        val cutoff = 32
        val issue =
            BeatGridIssue(
                "UNSUPPORTED_OUTRO",
                BeatIssueSeverity.ERROR,
                base.pulse.beats[40].seconds,
                base.audio.durationSeconds,
                "adversarial unsupported tail",
            )
        val subset = base.pulse.beats.take(cutoff + 1)
        val accepted =
            AcceptedPulseRegion(
                0,
                cutoff + 1,
                subset.first().seconds,
                subset.last().seconds,
                BeatGridQuality.audit(subset),
            )
        val a =
            base.copy(
                pulse = base.pulse.copy(quality = base.pulse.quality.copy(issues = listOf(issue))),
                pulseRegions = listOf(accepted),
            )
        assertFailsWith<IllegalArgumentException> { a.matchingGrid() }
        val result = AutoMixPlanner.overlap(a, base)
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(8, selected.barCount)
        assertEquals(cutoff, selected.outgoingEndBeat)
        assertEquals(base.audio.durationSeconds, a.audio.durationSeconds)
        assertEquals(base.pulse.beats, a.pulse.beats)
        assertTrue(result.mixPlan.observedCoverage.outputEndSeconds < a.audio.durationSeconds)
        assertFalse(a.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun noCompatibleLengthDoesNotSilentlyShrinkOrForceClockGates() {
        val short = song(List(12) { 4 })
        val missing =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(short, short, 16)
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, missing.report.failure)
        val unsafe =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(
                    song(List(5) { 4 }),
                    song(List(5) { 4 }, 0.6),
                    2,
                    qualityLimits = WarpQualityLimits(maximumPlaybackSpeed = 1.01),
                )
            }
        assertEquals(AutoMixFailureCode.CLOCK_REJECTED, unsafe.report.failure)
        assertTrue(unsafe.report.rejectedClocks > 0)
    }

    @Test
    fun scopedAutomaticPlanStillPreparesTheEntireUntrimmedSourceAndPreservesOutro() {
        val a = song(List(12) { 4 })
        val b = song(List(12) { 4 }, 0.41)
        fun source(duration: Double) =
            object : StereoPcm {
                override val durationSeconds = duration

                override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                    FloatArray(frames * 2)
            }
        val first = source(a.audio.durationSeconds)
        val second = source(b.audio.durationSeconds)
        var receivedFullSource = false
        var closed = false
        val engine =
            object : PitchStretchEngine {
                override fun prepare(
                    source: StereoPcm,
                    schedule: WarpSchedule,
                    progress: (Double) -> Unit,
                ): PreparedStereoPcm {
                    receivedFullSource = source === second
                    assertEquals(
                        kotlin.math.ceil(b.audio.durationSeconds * schedule.sampleRate).toLong(),
                        schedule.sourceFrames,
                    )
                    return object : PreparedStereoPcm {
                        override val durationSeconds =
                            schedule.outputFrames.toDouble() / schedule.sampleRate

                        override fun read(
                            startSeconds: Double,
                            frames: Int,
                            outputSampleRate: Int,
                        ) = FloatArray(frames * 2)

                        override fun close() {
                            closed = true
                        }
                    }
                }
            }
        val plan = AutoMixPlanner.overlap(a, b)
        val prepared = plan.prepare(first, second, engine)
        assertTrue(receivedFullSource)
        assertTrue(
            prepared.endFrame(MixMode.OVERLAP) >=
                kotlin.math.ceil(a.audio.durationSeconds * 48000).toLong()
        )
        assertTrue(
            prepared.endFrame(MixMode.OVERLAP) >
                plan.mixPlan.observedCoverage.outputEndSeconds * 48000
        )
        prepared.close()
        assertTrue(closed)
    }

    @Test
    fun boundedSearchAndCancellationReturnExplicitOutcomes() {
        val a = song(List(12) { 4 })
        val limited =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(
                    a,
                    a,
                    searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 1),
                )
            }
        assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, limited.report.failure)
        assertFailsWith<MixCancelledException> {
            AutoMixPlanner.transition(a, a, isCancelled = { true })
        }
        val absent =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(a.copy(barTracking = null), a)
            }
        assertEquals(AutoMixFailureCode.NO_CONFIDENT_BAR_GRID, absent.report.failure)
    }

    @Test
    fun unsupportedFadeSpanAndInvalidSourceCoverageHaveExplicitOutcomes() {
        val longMeter = song(List(36) { 32 })
        val unsupported =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(longMeter, longMeter, 32)
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, unsupported.report.failure)
        val a = song(List(12) { 4 })
        val truncated =
            a.copy(audio = a.audio.copy(durationSeconds = a.pulse.beats.last().seconds - 0.1))
        val missing =
            assertFailsWith<AutoMixPlanningException> { AutoMixPlanner.overlap(truncated, a) }
        assertEquals(AutoMixFailureCode.SOURCE_COVERAGE_INVALID, missing.report.failure)
        assertFailsWith<IllegalArgumentException> {
            AutoMixPlanner.overlap(a, a, outputSampleRate = 0)
        }
    }
}
