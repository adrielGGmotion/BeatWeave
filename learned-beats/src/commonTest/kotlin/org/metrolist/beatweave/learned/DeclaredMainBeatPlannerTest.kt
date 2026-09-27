package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.test.*
import org.metrolist.beatweave.*

class DeclaredMainBeatPlannerTest {
    private fun song(triplet: Boolean, bars: Int = 40): LocalSongAnalysis {
        // Both clocks have identical main events; triplet subdivisions deliberately swing.
        val beats =
            if (triplet)
                List(bars * 12 + 1) { i ->
                    Beat(.2 + (i / 3) * .5 + doubleArrayOf(.0, .10, .35)[i % 3], .95f)
                }
            else List(bars * 4 + 1) { Beat(.2 + it * .5, .95f) }
        val duration = beats.last().seconds + 1.0
        val audio =
            Analysis(
                duration,
                if (triplet) 360.0 else 120.0,
                1.0,
                beats,
                -18.0,
                -6.0,
                800.0,
                "unknown",
                0.0,
                emptyList(),
                FloatArray(0),
                .02,
                emptyList(),
            )
        val pulse =
            PulseNormalizationResult(
                beats,
                beats,
                emptyList(),
                emptyList(),
                BeatGridQualityReport(beats.size, audio.bpm, emptyList()),
                1.0,
            )
        return LocalSongAnalysis(
            audio,
            LearnedBeatAnalysis(
                duration,
                beats,
                emptyList(),
                "generated-clock",
                BeatThisLogits(FloatArray(0), FloatArray(0)),
            ),
            pulse,
            audio,
        )
    }

    private fun declared(
        song: LocalSongAnalysis,
        stride: Int,
        supported: BooleanArray = BooleanArray((song.pulse.beats.size - 1) / stride / 4) { true },
    ): ObservedMainBeatGrid {
        val indices = (song.pulse.beats.indices step stride).toList().toIntArray()
        return ObservedMainBeatGrid(
            "original-audio-clock",
            song.pulse.beats.map { it.seconds }.toDoubleArray(),
            indices,
            IntArray((indices.size - 1) / 4 + 1) { it * 4 },
            supported,
            MainBeatDeclarationOrigin.CALLER_DECLARED,
            "known-generated-fixture",
            "four-main-beats",
            listOf(
                "Source construction provides the independent grouping; no model confidence used"
            ),
        )
    }

    @Test
    fun unequalSubdivisionsProjectOnlyObservedMainEventsForEveryExactBarLength() {
        val triplet = song(true)
        val straight = song(false)
        for ((a, b) in listOf(triplet to straight, straight to triplet)) {
            val aStride = if (a === triplet) 3 else 1
            val bStride = if (b === triplet) 3 else 1
            for (bars in listOf(2, 4, 8, 16, 32)) {
                val result =
                    LocalMixPlanner.declaredAutoTransition(
                        a,
                        b,
                        declared(a, aStride),
                        declared(b, bStride),
                        bars,
                    )
                val selection = assertNotNull(result.declaredMainSelection)
                assertEquals(40 - bars, selection.outgoingStartBar)
                assertEquals(0, selection.incomingStartBar)
                assertTrue(selection.automaticallySelectedCues)
                assertEquals(bars * 4 + 1, result.mixPlan.first.size)
                assertEquals(bars * 4 + 1, result.mixPlan.second.size)
                assertEquals(1, result.mixPlan.firstBeatsPerCycle)
                assertEquals(1, result.mixPlan.secondBeatsPerCycle)
                assertEquals(List(bars) { 4 * aStride }, selection.outgoingCanonicalPulsesPerBar)
                assertEquals(List(bars) { 4 * bStride }, selection.incomingCanonicalPulsesPerBar)
                assertEquals(List(bars) { 4 }, selection.mainBeatsPerBar)
                assertEquals(bars + 1, result.barMatches.size)
                assertTrue(
                    selection.matchedMainBeats.all { abs(it.originalOutputResidualSeconds) < 1e-8 }
                )
                assertTrue(
                    selection.matchedMainBeats.all {
                        it.originalIncomingSeconds == it.preparedIncomingSeconds
                    }
                )
                val schedule = WarpSchedule.from(result.mixPlan, b.audio.durationSeconds)
                assertTrue(
                    schedule.anchors.zipWithNext().all { (x, y) ->
                        abs((y.outputFrame - x.outputFrame) - (y.sourceFrame - x.sourceFrame)) <= 1L
                    }
                )
                assertEquals(
                    kotlin.math.ceil(b.audio.durationSeconds * 48000).toLong(),
                    schedule.sourceFrames,
                )
            }
        }
    }

    @Test
    fun callerPinsUseOriginalCanonicalIndicesAndCannotLandOnUnmatchedSubdivisions() {
        val a = song(false)
        val b = song(true)
        val ok =
            LocalMixPlanner.declaredTransition(
                a,
                b,
                declared(a, 1),
                declared(b, 3),
                1,
                2,
                4,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(27)),
            )
        assertTrue(ok.clockFit.adjustments[1].pinned)
        assertEquals(b.pulse.beats[27].seconds, ok.clockFit.adjustments[1].originalSourceSeconds)
        for (pin in listOf(25, 0, b.pulse.beats.size)) {
            val failure =
                assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredTransition(
                        a,
                        b,
                        declared(a, 1),
                        declared(b, 3),
                        1,
                        2,
                        4,
                        fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(pin)),
                    )
                }
            assertEquals(DeclaredTransitionFailureCode.UNMAPPED_CANONICAL_PIN, failure.code)
        }
    }

    @Test
    fun declarationCopiesClocksIndicesAcceptanceAndDiagnostics() {
        val source = song(false, 4)
        val clock = source.pulse.beats.map { it.seconds }.toDoubleArray()
        val indices = IntArray(clock.size) { it }
        val boundaries = intArrayOf(0, 4, 8, 12, 16)
        val supported = BooleanArray(4) { true }
        val diagnostics = mutableListOf("original")
        val grid =
            ObservedMainBeatGrid(
                "clock",
                clock,
                indices,
                boundaries,
                supported,
                MainBeatDeclarationOrigin.INFERRED_BY_PROVIDER,
                "provider-v1",
                "hypothesis",
                diagnostics,
            )
        clock[0] = 999.0
        indices[0] = 9
        boundaries[0] = 9
        supported[0] = false
        diagnostics[0] = "changed"
        grid.canonicalSeconds.fill(0.0)
        grid.canonicalBeatIndices.fill(0)
        grid.supportedBars.fill(false)
        assertEquals(.2, grid.at(0))
        assertEquals(0, grid.canonicalIndex(0))
        assertTrue(grid.isBarSupported(0))
        assertEquals(listOf("original"), grid.diagnostics)
        assertTrue(
            LocalMixPlanner.declaredTransition(source, source, grid, grid, 0, 0, 2)
                .clockFit
                .report
                .accepted
        )
        val shifted =
            source.copy(
                pulse =
                    source.pulse.copy(
                        beats = source.pulse.beats.map { it.copy(seconds = it.seconds + .001) }
                    )
            )
        assertEquals(
            DeclaredTransitionFailureCode.SOURCE_CLOCK_MISMATCH,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredTransition(shifted, source, grid, grid, 0, 0, 2)
                }
                .code,
        )
    }

    @Test
    fun unchangedClockLimitsSearchBudgetAndCancellationStillApply() {
        val a = song(false, 8)
        val fast =
            a.copy(
                pulse =
                    a.pulse.copy(
                        beats =
                            a.pulse.beats.mapIndexed { i, beat ->
                                beat.copy(seconds = .2 + i * .25)
                            }
                    )
            )
        assertEquals(
            DeclaredTransitionFailureCode.CLOCK_REJECTED,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredTransition(
                        a,
                        fast,
                        declared(a, 1),
                        declared(fast, 1),
                        0,
                        0,
                        2,
                    )
                }
                .code,
        )
        val onlyBeginning = declared(a, 1, BooleanArray(8) { it < 2 })
        assertEquals(
            DeclaredTransitionFailureCode.SEARCH_LIMIT_REACHED,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredAutoTransition(
                        a,
                        a,
                        onlyBeginning,
                        declared(a, 1),
                        2,
                        searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 1),
                    )
                }
                .code,
        )
        var checks = 0
        val unsupported = declared(a, 1, BooleanArray(8))
        assertFailsWith<MixCancelledException> {
            LocalMixPlanner.declaredAutoTransition(
                a,
                a,
                declared(a, 1),
                unsupported,
                2,
                isCancelled = { ++checks >= 3 },
            )
        }
        assertEquals(3, checks) // two source validations, then the first unsupported incoming range
    }

    @Test
    fun unsupportedRangesInvalidMeterAndUnsafeSourceEvidenceRemainTypedRejections() {
        val a = song(false, 8)
        val b = song(true, 8)
        val no = declared(a, 1, BooleanArray(8))
        assertEquals(
            DeclaredTransitionFailureCode.NO_COMPATIBLE_SUPPORTED_RANGE,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredAutoTransition(a, b, no, declared(b, 3), 2)
                }
                .code,
        )
        assertEquals(
            DeclaredTransitionFailureCode.NO_COMPATIBLE_SUPPORTED_RANGE,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredAutoTransition(a, b, declared(a, 1), declared(b, 3), 16)
                }
                .code,
        )
        val indices = IntArray(25) { it }
        val three =
            ObservedMainBeatGrid(
                "a",
                a.pulse.beats.map { it.seconds }.toDoubleArray(),
                indices,
                IntArray(9) { it * 3 },
                BooleanArray(8) { true },
                MainBeatDeclarationOrigin.CALLER_DECLARED,
                "fixture",
                "three",
            )
        assertEquals(
            DeclaredTransitionFailureCode.INCOMPATIBLE_MAIN_METER,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredTransition(a, b, three, declared(b, 3), 0, 0, 2)
                }
                .code,
        )
        val broken =
            a.copy(
                pulse =
                    a.pulse.copy(
                        quality =
                            a.pulse.quality.copy(
                                issues =
                                    listOf(
                                        BeatGridIssue(
                                            "BAD_SUBDIVISION",
                                            BeatIssueSeverity.ERROR,
                                            0.0,
                                            5.0,
                                            "unmatched subdivisions still gated",
                                        )
                                    )
                            )
                    )
            )
        assertEquals(
            DeclaredTransitionFailureCode.UNSUPPORTED_PULSE_RANGE,
            assertFailsWith<DeclaredTransitionPlanningException> {
                    LocalMixPlanner.declaredTransition(
                        broken,
                        b,
                        declared(a, 1),
                        declared(b, 3),
                        0,
                        0,
                        2,
                    )
                }
                .code,
        )
        assertFailsWith<MixCancelledException> {
            LocalMixPlanner.declaredAutoTransition(
                a,
                b,
                declared(a, 1),
                declared(b, 3),
                2,
                isCancelled = { true },
            )
        }
    }
}
