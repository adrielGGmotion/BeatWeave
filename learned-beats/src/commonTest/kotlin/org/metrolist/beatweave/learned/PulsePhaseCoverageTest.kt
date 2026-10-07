package org.metrolist.beatweave.learned

import kotlin.math.*
import kotlin.test.*
import org.metrolist.beatweave.*

/** Synthetic source clocks fixed before the local-support implementation or corpus evaluation. */
class PulsePhaseCoverageTest {
    private val observed = List(240) { Beat(.2 + it * .5, .95f) }
    private val reference = List(242) { Beat(.2 + it * .5, .95f) }
    private val duration = reference.last().seconds + 1

    private fun shifted(from: Int, through: Int, seconds: Double): List<Beat> =
        observed.mapIndexed { index, beat ->
            beat.copy(seconds = beat.seconds + if (index in from..through) seconds else 0.0)
        }

    private fun select(
        beats: List<Beat>,
        independent: List<Beat> = reference,
    ): Pair<AutomaticPulseResult, LocalSongAnalysis> {
        val onsets = FloatArray(ceil(duration / .01).toInt() + 1)
        val logits = FloatArray(ceil(duration * 50).toInt() + 1) { -8f }
        beats.forEach {
            onsets[(it.seconds / .01).roundToInt()] = 1f
            logits[(it.seconds * 50).roundToInt()] = 5f
        }
        val audio =
            Analysis(
                duration,
                120.0,
                .8,
                independent,
                -12.0,
                -1.0,
                500.0,
                "unknown",
                0.0,
                emptyList(),
                onsets,
                .01,
                emptyList(),
                listOf(TempoCandidate(120.0, .8)),
                .8,
                false,
            )
        val model =
            LearnedBeatAnalysis(
                duration,
                beats,
                emptyList(),
                "synthetic-phase-coverage",
                BeatThisLogits(logits, logits.copyOf()),
            )
        val result = AutomaticPulseSelector.select(model, audio, { error("No alternative pulse") })
        return result to
            LocalSongAnalysis(
                audio,
                model,
                result.pulse,
                result.reference,
                pulseSelection = result.diagnostics,
                pulseRegions = result.regions.accepted,
                excludedPulseRegions = result.regions.excluded,
            )
    }

    private fun assertLocallyRejected(
        beats: List<Beat>,
        from: Int,
        until: Int,
        independent: List<Beat> = reference,
    ) {
        val (result, song) = select(beats, independent)
        // This is the exact former shortcut: a strong global majority retains the candidate.
        val candidate = result.diagnostics.candidates.single { it.selected }
        assertEquals(PulsePhaseRelation.DIRECT_ALIGNMENT, candidate.phaseEvidence?.relation)
        assertEquals(120.0, result.diagnostics.selectedBpm)
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertEquals(beats, result.pulse.beats)
        assertEquals(beats, result.pulse.rawBeats)
        assertTrue(result.pulse.repairs.isEmpty())
        assertTrue(result.pulse.quality.issues.any { it.code == "UNCERTAIN_REFERENCE_PHASE" })
        assertTrue(result.regions.accepted.none { it.contains(from, until) })
        assertFailsWith<IllegalArgumentException> { song.requirePulseRange(from, until) }
        assertFailsWith<IllegalArgumentException> { song.matchingGrid(from, until) }
        assertFailsWith<IllegalArgumentException> { song.matchingGrid() }
        assertTrue(result.regions.accepted.isNotEmpty(), "Unrelated measured regions remain usable")
        result.regions.accepted.forEach {
            song.requirePulseRange(it.startBeat, it.endBeatExclusive)
        }
    }

    @Test
    fun eightInteriorShiftedEventsCannotHideInsideGlobalMajority() {
        assertLocallyRejected(shifted(80, 87, .16), 80, 88)
    }

    @Test
    fun eightPrefixShiftedEventsCannotHideInsideGlobalMajority() {
        assertLocallyRejected(shifted(0, 7, .16), 0, 8)
    }

    @Test
    fun eightTailShiftedEventsCannotHideInsideGlobalMajority() {
        assertLocallyRejected(shifted(232, 239, .16), 232, 240)
    }

    @Test
    fun smoothLocalExcursionCannotHideInsideGlobalMajority() {
        val beats =
            observed.mapIndexed { index, beat ->
                beat.copy(
                    seconds =
                        beat.seconds +
                            when (index) {
                                in 80..84 -> (index - 79) * .04
                                in 85..89 -> (89 - index) * .04
                                else -> 0.0
                            }
                )
            }
        assertLocallyRejected(beats, 80, 90)
    }

    @Test
    fun isolatedOutlierCannotHideInsideAnOtherwiseStableWindow() {
        assertLocallyRejected(shifted(80, 80, .08), 79, 82)
    }

    @Test
    fun measuredReferenceCoverageCannotBeExtrapolatedToTail() {
        assertLocallyRejected(observed, 200, 240, reference.take(200))
    }

    @Test
    fun missingReferenceIntervalCannotProvidePhaseCoverageOrLocalSupport() {
        val beats = shifted(0, observed.lastIndex, .25)
        val sparseReference = reference.filterIndexed { index, _ -> index !in 100..107 }

        val evidence = PulsePhaseAudit.assess(beats, sparseReference)
        val supported = PulsePhaseAudit.locallySupported(beats, sparseReference, evidence)

        assertEquals(PulsePhaseRelation.STABLE_OFFSET, evidence.relation)
        assertEquals(231, evidence.observedEvents)
        assertEquals(231.0 / beats.size, evidence.coverage, 1e-12)
        assertTrue((99..107).none { supported[it] })
        assertTrue(supported[98])
        assertTrue(supported[108])
    }

    @Test
    fun constantAlignedClockRetainsWholeTrackSupportAndOriginalTimestamps() {
        val (result, song) = select(observed)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
        assertEquals(observed, result.pulse.beats)
        song.requirePulseRange(0, observed.size)
        song.matchingGrid()
    }

    @Test
    fun constantOffbeatIsComparedWithItsOwnCenterWithoutShiftingTimestamps() {
        val beats = shifted(0, observed.lastIndex, .25)
        val (result, song) = select(beats)
        assertEquals(
            PulsePhaseRelation.STABLE_OFFSET,
            result.diagnostics.candidates.single { it.selected }.phaseEvidence?.relation,
        )
        assertTrue(result.pulse.quality.safeForAutomaticMix)
        assertEquals(beats, result.pulse.beats)
        song.requirePulseRange(0, beats.size)
        song.matchingGrid()
    }

    @Test
    fun quietClockStrengthDoesNotChangeLocalPhaseCoverage() {
        // Phase evidence sees timestamps and normalized onset evidence, not PCM amplitude.
        val beats = observed.map { it.copy(strength = 1e-12f) }
        val independent = reference.map { it.copy(strength = 1e-12f) }
        val (result, song) = select(beats, independent)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
        assertEquals(beats, result.pulse.beats)
        song.requirePulseRange(0, beats.size)
    }

    @Test
    fun localAuditChecksCanonicalEventsRatherThanAssumingRawCandidateSupportApplies() {
        val candidate = PulsePhaseAudit.assess(observed, reference)
        val canonical = shifted(80, 80, .08)
        val supported = PulsePhaseAudit.locallySupported(canonical, reference, candidate)
        assertFalse(supported[80])
        assertTrue(supported.filterIndexed { index, _ -> index != 80 }.all { it })
    }

    @Test
    fun regionalMajoritiesCannotGrantSupportToTheirOwnOutliers() {
        val beats = shifted(80, 80, .08)
        val evidence =
            PulsePhaseAudit.assess(observed, reference)
                .copy(
                    relation = PulsePhaseRelation.REGIONAL_SUPPORT,
                    supportedRanges =
                        listOf(
                            PulsePhaseRange(
                                observed.first().seconds,
                                observed.last().seconds,
                                observed.size,
                                0.0,
                                1.0,
                                .99,
                            )
                        ),
                )
        val supported = PulsePhaseAudit.locallySupported(beats, reference, evidence)
        assertFalse(supported[80])
        assertTrue(supported.filterIndexed { index, _ -> index != 80 }.all { it })
    }
}
