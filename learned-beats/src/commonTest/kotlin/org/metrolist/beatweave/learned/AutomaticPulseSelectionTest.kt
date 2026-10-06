package org.metrolist.beatweave.learned

import kotlin.math.*
import kotlin.test.*
import org.metrolist.beatweave.*

class AutomaticPulseSelectionTest {
    private val slow = List(96) { Beat(.2 + it * .75, .95f) }
    private val fast = List(191) { Beat(.2 + it * .375, .95f) }
    private val duration = slow.last().seconds + 1
    private val candidates = listOf(TempoCandidate(160.0, .8), TempoCandidate(80.0, .75))

    private fun reference(beats: List<Beat>, evidence: List<Beat> = beats): Analysis {
        val onset = FloatArray((duration / .01).toInt() + 1)
        evidence.forEach { onset[(it.seconds / .01).roundToInt()] = 1f }
        return Analysis(
            duration,
            60 / (beats[1].seconds - beats[0].seconds),
            .8,
            beats,
            -12.0,
            -1.0,
            500.0,
            "unknown",
            0.0,
            emptyList(),
            onset,
            .01,
            emptyList(),
            candidates,
            .8,
            true,
        )
    }

    private fun model(beats: List<Beat>): LearnedBeatAnalysis {
        val logits = FloatArray((duration * 50).toInt() + 1) { -8f }
        beats.forEach { logits[(it.seconds * 50).roundToInt()] = 5f }
        return LearnedBeatAnalysis(
            duration,
            beats,
            emptyList(),
            "fixture",
            BeatThisLogits(logits, logits.copyOf()),
        )
    }

    @Test
    fun independentlySupportedLowerPulseKeepsAllModelTimestamps() {
        var passes = 0
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(fast, slow),
                {
                    passes++
                    assertEquals(80.0, it)
                    reference(slow)
                },
            )
        assertEquals(1, passes)
        assertEquals(80.0, result.diagnostics.selectedBpm)
        assertEquals(slow, result.pulse.beats)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
        assertTrue(
            result.diagnostics.candidates.any {
                it.bpm == 160.0 && it.rejectionReasons.isNotEmpty()
            }
        )
    }

    @Test
    fun alternativePulseUsesItsOwnFullOnsetCoverage() {
        val full = reference(fast, slow.take(47))
        val measuredEndFrame =
            ceil((slow[46].seconds + 0.055) / full.onsetHopSeconds).toInt()
        val partialInitial =
            full.copy(onsetEnvelope = full.onsetEnvelope.copyOf(measuredEndFrame + 1))
        var passes = 0

        val result =
            AutomaticPulseSelector.select(
                model(slow),
                partialInitial,
                {
                    passes++
                    assertEquals(80.0, it)
                    reference(slow)
                },
            )

        assertEquals(1, passes)
        val candidate = result.diagnostics.candidates.single { it.bpm == 80.0 }
        assertEquals(1.0, candidate.onsetAgreement)
        assertEquals(80.0, result.diagnostics.selectedBpm)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun independentlySupportedHigherPulseAlsoWorks() {
        val result =
            AutomaticPulseSelector.select(model(fast), reference(slow, fast), { reference(fast) })
        assertEquals(160.0, result.diagnostics.selectedBpm)
        assertEquals(fast, result.pulse.beats)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun invalidOnsetClockCannotFabricateAudioSupportedPulseRepairs() {
        val missing = slow.toMutableList().also { it.removeAt(40) }
        val measured = reference(slow)
        val unclocked = measured.onsetEnvelope.copyOf().also { it[0] = 1.0f }
        val nonFiniteEnvelope =
            measured.onsetEnvelope.copyOf().also {
                it[(slow[40].seconds / measured.onsetHopSeconds).roundToInt()] =
                    Float.POSITIVE_INFINITY
            }
        val invalidEvidence =
            listOf(
                measured.copy(onsetEnvelope = unclocked, onsetHopSeconds = Double.NaN),
                measured.copy(
                    onsetEnvelope = unclocked,
                    onsetHopSeconds = Double.POSITIVE_INFINITY,
                ),
                measured.copy(
                    onsetEnvelope = unclocked,
                    onsetTimeOffsetSeconds = Double.NaN,
                ),
                measured.copy(onsetEnvelope = nonFiniteEnvelope),
            )

        for (invalid in invalidEvidence) {
            val pulse = PulseNormalizer.normalize(missing, invalid)
            assertFalse(
                pulse.repairs.any { it.kind == PulseRepairKind.AUDIO_SUPPORTED_INSERTION }
            )
            assertFalse(pulse.quality.safeForAutomaticMix)
            assertTrue(pulse.quality.issues.any { it.code == "UNSUPPORTED_INSERTION" })
        }
    }

    @Test
    fun invalidFallbackBeatClockCannotConfirmModelPulse() {
        val measured = reference(slow)
        val negativeStart = slow.toMutableList().also { it[0] = it[0].copy(seconds = -0.55) }
        val outsideEnd =
            slow.toMutableList().also {
                it[it.lastIndex] = it.last().copy(seconds = duration)
            }
        val duplicateTime = slow.toMutableList().also { it[40] = it[39] }
        val nonFiniteStrength =
            slow.toMutableList().also {
                it[40] = it[40].copy(strength = Float.NaN)
            }
        val invalidReferences =
            listOf(
                measured.copy(durationSeconds = Double.POSITIVE_INFINITY),
                measured.copy(beats = negativeStart),
                measured.copy(beats = outsideEnd),
                measured.copy(beats = duplicateTime),
                measured.copy(beats = nonFiniteStrength),
            )

        for (invalid in invalidReferences) {
            val pulse = PulseNormalizer.normalize(slow, invalid)
            assertEquals(0.0, pulse.canonicalAgreement)
            assertFalse(pulse.quality.safeForAutomaticMix)
            assertTrue(pulse.quality.issues.any { it.code == "MISSING_REFERENCE" })
        }
    }

    @Test
    fun selectorReportsInvalidFallbackClockWithoutPhaseEvidence() {
        val duplicateTime = slow.toMutableList().also { it[40] = it[39] }
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(slow).copy(beats = duplicateTime),
                { error("An invalid initial reference must not trigger another analysis") },
            )

        val candidate = result.diagnostics.candidates.single { it.bpm == 80.0 }
        assertFalse(candidate.evaluated)
        assertEquals(0.0, candidate.intervalAgreement)
        assertEquals(0.0, candidate.phaseAgreement)
        assertEquals(0.0, candidate.onsetAgreement)
        assertEquals(0.0, candidate.score)
        assertNull(candidate.phaseEvidence)
        assertTrue(
            candidate.rejectionReasons.any {
                it.contains("Finite ordered model beats and an independent local pulse reference")
            }
        )
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertTrue(result.regions.accepted.isEmpty())
    }

    @Test
    fun invalidOnsetEvidenceCannotQualifyAutomaticPulseCandidate() {
        val measured = reference(slow)
        val frameZero = FloatArray(measured.onsetEnvelope.size).also { it[0] = 1.0f }
        val nonFinitePeaks =
            FloatArray(measured.onsetEnvelope.size).also { onset ->
                slow.forEach {
                    onset[(it.seconds / measured.onsetHopSeconds).roundToInt()] =
                        Float.POSITIVE_INFINITY
                }
            }
        val invalidEvidence =
            listOf(
                measured.copy(
                    onsetEnvelope = frameZero,
                    onsetTimeOffsetSeconds = Double.NaN,
                ),
                measured.copy(onsetEnvelope = nonFinitePeaks),
            )

        for (invalid in invalidEvidence) {
            val result =
                AutomaticPulseSelector.select(
                    model(slow),
                    invalid,
                    { error("The initial pulse level is already the supported candidate") },
                )
            val candidate = result.diagnostics.candidates.single { it.bpm == 80.0 }
            assertEquals(0.0, candidate.onsetAgreement)
            assertNull(result.diagnostics.selectedBpm)
            assertTrue(
                candidate.rejectionReasons.any {
                    it.contains("Too few neural events coincide")
                }
            )
            assertFalse(result.pulse.quality.safeForAutomaticMix)
        }
    }

    @Test
    fun onsetAgreementScoresOnlyEventsInsideMeasuredEnvelopeCoverage() {
        val full = reference(slow, slow.take(30))
        val measuredEndFrame =
            ceil((slow[57].seconds + 0.055) / full.onsetHopSeconds).toInt()
        val partial = full.copy(onsetEnvelope = full.onsetEnvelope.copyOf(measuredEndFrame + 1))

        val result =
            AutomaticPulseSelector.select(
                model(slow),
                partial,
                { error("The initial pulse level is already the supported candidate") },
            )

        val candidate = result.diagnostics.candidates.single { it.bpm == 80.0 }
        assertEquals(30.0 / 58.0, candidate.onsetAgreement, 1e-9)
        assertEquals(80.0, result.diagnostics.selectedBpm)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun onsetAgreementRejectsEvidenceWithoutMajorityEnvelopeCoverage() {
        val full = reference(slow, slow.take(47))
        val measuredEndFrame =
            ceil((slow[46].seconds + 0.055) / full.onsetHopSeconds).toInt()
        val partial = full.copy(onsetEnvelope = full.onsetEnvelope.copyOf(measuredEndFrame + 1))

        val result =
            AutomaticPulseSelector.select(
                model(slow),
                partial,
                { error("The initial pulse level is already the supported candidate") },
            )

        val candidate = result.diagnostics.candidates.single { it.bpm == 80.0 }
        assertEquals(0.0, candidate.onsetAgreement)
        assertNull(result.diagnostics.selectedBpm)
        assertTrue(candidate.rejectionReasons.any { it.contains("Too few neural events") })
        assertFalse(result.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun outOfRecordingOnsetPaddingCannotQualifyAutomaticPulseCandidate() {
        val nearEnd = List(96) { Beat(duration - 0.03 - (95 - it) * 0.75, 0.95f) }
        val trailingMeasured = reference(nearEnd, nearEnd.take(33))
        val trailingPadding =
            trailingMeasured.onsetEnvelope.copyOf(trailingMeasured.onsetEnvelope.size + 2).also {
                it[trailingMeasured.onsetEnvelope.size] = 1.0f
            }
        val nearStart = List(96) { Beat(0.03 + it * 0.75, 0.95f) }
        val leadingMeasured = reference(nearStart, nearStart.drop(1).take(33))
        val leadingPadding = floatArrayOf(1.0f) + leadingMeasured.onsetEnvelope
        val paddedCases =
            listOf(
                model(nearEnd) to trailingMeasured.copy(onsetEnvelope = trailingPadding),
                model(nearStart) to
                    leadingMeasured.copy(
                        onsetEnvelope = leadingPadding,
                        onsetTimeOffsetSeconds = -0.01,
                    ),
            )

        for ((learned, padded) in paddedCases) {
            val result =
                AutomaticPulseSelector.select(
                    learned,
                    padded,
                    { error("The initial pulse level is already the supported candidate") },
                )

            val candidate = result.diagnostics.candidates.single { it.bpm == 80.0 }
            assertEquals(33.0 / 96.0, candidate.onsetAgreement, 1e-9)
            assertNull(result.diagnostics.selectedBpm)
            assertTrue(candidate.rejectionReasons.any { it.contains("Too few neural events") })
            assertFalse(result.pulse.quality.safeForAutomaticMix)
        }
    }

    @Test
    fun integerRelatedAlternativesRemainVisibleWithoutChangingTheSelectedClock() {
        val spectral =
            listOf(
                TempoCandidate(80.0, .8),
                TempoCandidate(40.0, .6),
                TempoCandidate(160.0, .7),
                TempoCandidate(240.0, .65),
            )
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(slow).copy(tempoCandidates = spectral),
                { error("Alias diagnostics must not trigger another analysis") },
            )
        assertEquals(80.0, result.diagnostics.selectedBpm)
        assertEquals(slow, result.pulse.beats)
        assertTrue(result.pulse.quality.safeForAutomaticMix)
        assertEquals(0, result.diagnostics.additionalSpectralPasses)
        val metrical = result.diagnostics.metrical
        assertEquals(PulseLevelIdentification.CANONICAL_PULSE_ONLY, metrical.levelIdentification)
        assertEquals(
            setOf(MetricalPulseRatio.HALF, MetricalPulseRatio.DOUBLE, MetricalPulseRatio.TRIPLE),
            metrical.alternatives.map { it.relativeRate }.toSet(),
        )
        assertTrue(metrical.hasSupportedAlternativeLevel)
        for (alias in metrical.alternatives) {
            assertTrue(alias.independentlySupportedPeriodicity)
            assertEquals(0.0, alias.neuralIntervalAgreement)
            assertTrue(alias.canonicalRejectionReasons.any { it.contains("neural intervals") })
        }
        assertTrue(metrical.interpretation.contains("not verified"))
    }

    @Test
    fun thirdRateAliasExposesUnsupportedAndFractionalMeterFamiliesWithoutAssumingFour() {
        val pulse = List(96) { Beat(.2 + it * .5, .95f) }
        val reference =
            reference(pulse)
                .copy(
                    tempoCandidates =
                        listOf(
                            TempoCandidate(120.0, .8),
                            TempoCandidate(40.0, .6),
                            TempoCandidate(240.0, .7),
                        )
                )
        val result =
            AutomaticPulseSelector.select(
                model(pulse),
                reference,
                { error("No metrical reanalysis") },
            )
        val evidence = result.diagnostics.metrical
        assertEquals(120.0, result.diagnostics.selectedBpm)
        assertEquals(pulse, result.pulse.beats)
        assertTrue(evidence.alternatives.any { it.relativeRate == MetricalPulseRatio.THIRD })
        val limits = evidence.unsupportedMeterFamilies(listOf(2, 3, 4, 6, 8))
        assertTrue(
            limits.any {
                it.alternativeBpm == 40.0 &&
                    it.alternativePulsesPerBar == 4 &&
                    it.requiredCanonicalPulseNumerator == 12 &&
                    it.requiredCanonicalPulseDenominator == 1 &&
                    it.reason == UnsupportedMeterFamilyReason.COUNT_OUTSIDE_CONFIGURED_FAMILY
            }
        )
        assertTrue(
            limits.any {
                it.alternativeBpm == 240.0 &&
                    it.alternativePulsesPerBar == 3 &&
                    it.requiredCanonicalPulseNumerator == 3 &&
                    it.requiredCanonicalPulseDenominator == 2 &&
                    it.reason == UnsupportedMeterFamilyReason.FRACTIONAL_CANONICAL_PULSE_COUNT
            }
        )
        assertFalse(evidence.alternatives.any { it.relativeRate == MetricalPulseRatio.TRIPLE })
        assertEquals(240.0, evidence.maximumAssessedBpm)
    }

    @Test
    fun weakAliasEvidenceAndNoAliasesDoNotEstablishAQuarterNoteLevel() {
        val weak =
            reference(slow)
                .copy(
                    tempoCandidates = listOf(TempoCandidate(80.0, .8), TempoCandidate(160.0, .01))
                )
        val result = AutomaticPulseSelector.select(model(slow), weak, { error("Weak alias") })
        val alias = result.diagnostics.metrical.alternatives.single()
        assertFalse(alias.independentlySupportedPeriodicity)
        assertEquals(.01, alias.spectralSupport)
        assertFalse(result.diagnostics.metrical.hasSupportedAlternativeLevel)
        val absent =
            AutomaticPulseSelector.select(
                model(slow),
                weak.copy(tempoCandidates = listOf(TempoCandidate(80.0, .8))),
                { error("No alias") },
            )
        assertTrue(absent.diagnostics.metrical.alternatives.isEmpty())
        assertEquals(
            PulseLevelIdentification.CANONICAL_PULSE_ONLY,
            absent.diagnostics.metrical.levelIdentification,
        )
        val unsupported =
            AutomaticPulseSelector.select(
                model(slow),
                weak.copy(tempoCandidates = listOf(TempoCandidate(80.0, .01))),
                { error("No candidate") },
            )
        assertEquals(
            PulseLevelIdentification.UNRESOLVED,
            unsupported.diagnostics.metrical.levelIdentification,
        )
        assertTrue(unsupported.diagnostics.metrical.alternatives.isEmpty())
    }

    @Test
    fun absentIndependentEvidenceCannotBeReplacedByConfidentNeuralEvents() {
        val noPeriodicity =
            reference(slow).copy(tempoCandidates = listOf(TempoCandidate(80.0, .01)))
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                noPeriodicity,
                { error("Unsupported reanalysis") },
            )
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertTrue(result.regions.accepted.isEmpty())
        val noOnsets = reference(slow, emptyList())
        val silent = AutomaticPulseSelector.select(model(slow), noOnsets, { noOnsets })
        assertTrue(silent.regions.accepted.isEmpty())
    }

    @Test
    fun independentlyMeasuredOffbeatAttacksDoNotForceAClockShift() {
        val offbeatReference = slow.map { it.copy(seconds = it.seconds + .375) }
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(offbeatReference, slow),
                { error("The existing tempo already agrees") },
            )
        assertEquals(80.0, result.diagnostics.selectedBpm)
        // The first model event precedes this measured reference. A stable offset supports
        // the covered interior, but cannot authorize extrapolating the independent clock.
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertTrue(result.pulse.quality.issues.any { it.code == "UNCERTAIN_REFERENCE_PHASE" })
        assertTrue(result.regions.accepted.isNotEmpty())
        assertTrue(result.regions.accepted.none { it.contains(0, 2) })
        assertEquals(slow, result.pulse.beats)
        val phase = result.diagnostics.candidates.single { it.selected }.phaseEvidence!!
        assertEquals(PulsePhaseRelation.STABLE_OFFSET, phase.relation)
        assertEquals(.5, abs(phase.offsetCycles!!), 1e-9)
        assertEquals(0.0, phase.directAgreement)
        assertTrue(phase.concentration > .99)
    }

    @Test
    fun coherentOffsetWithoutAttacksAtModelEventsRemainsRejected() {
        val offbeatReference = slow.map { it.copy(seconds = it.seconds + .375) }
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(offbeatReference),
                { error("No additional candidate") },
            )
        assertTrue(result.regions.accepted.isEmpty())
        assertTrue(
            result.diagnostics.candidates.any {
                it.rejectionReasons.any { reason -> reason.contains("majority") }
            }
        )
    }

    @Test
    fun aClockThatDriftsThroughAnEntirePulseCannotFitAStableOffset() {
        val drifting =
            slow.mapIndexed { index, beat ->
                beat.copy(seconds = beat.seconds + index * .75 / slow.size)
            }
        val phase = PulsePhaseAudit.assess(drifting, slow)
        assertEquals(PulsePhaseRelation.INCOHERENT, phase.relation)
        assertFalse(phase.supportsCandidate)
        assertTrue(phase.concentration < .1)
        val result =
            AutomaticPulseSelector.select(
                model(drifting),
                reference(slow, drifting),
                { error("No additional candidate") },
            )
        assertTrue(result.regions.accepted.isEmpty())
    }

    @Test
    fun changingOffbeatSubdivisionsRetainExplicitSeparateRegions() {
        val changing =
            slow.mapIndexed { index, beat ->
                beat.copy(seconds = beat.seconds + if (index < slow.size / 2) .20 else .50)
            }
        val phase = PulsePhaseAudit.assess(changing, slow)
        assertEquals(PulsePhaseRelation.REGIONAL_SUPPORT, phase.relation)
        assertEquals(2, phase.supportedRanges.size)
        assertTrue(phase.offsetAgreement < .8)
    }

    @Test
    fun stableHalvesWithAReferencePhaseSwitchKeepGlobalRejectionAndGuardTheSwitch() {
        val changingReference =
            slow.mapIndexed { index, beat ->
                beat.copy(seconds = beat.seconds + if (index < 48) 0.0 else .375)
            }
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(changingReference, slow),
                { error("Existing pulse level") },
            )
        assertEquals(slow, result.pulse.beats)
        assertEquals(80.0, result.diagnostics.selectedBpm)
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertTrue(result.pulse.quality.issues.any { it.code == "UNCERTAIN_REFERENCE_PHASE" })
        assertEquals(2, result.regions.accepted.size)
        assertTrue(result.regions.accepted.none { it.contains(40, 56) })
        val song =
            LocalSongAnalysis(
                reference(slow),
                model(slow),
                result.pulse,
                result.reference,
                pulseRegions = result.regions.accepted,
            )
        assertFailsWith<IllegalArgumentException> { song.matchingGrid() }
        assertFailsWith<IllegalArgumentException> { song.requirePulseRange(24, 72) }
        result.regions.accepted.forEach {
            song.requirePulseRange(it.startBeat, it.endBeatExclusive)
        }
    }

    @Test
    fun localPhaseSupportRequiresLocalAudioAttacks() {
        val changingReference =
            slow.mapIndexed { index, beat ->
                beat.copy(seconds = beat.seconds + if (index < 48) 0.0 else .375)
            }
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(changingReference, slow.take(48)),
                { error("Existing level") },
            )
        assertNull(result.diagnostics.selectedBpm)
        assertTrue(result.regions.accepted.isEmpty())
        assertFalse(result.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun slowlyDriftingPhaseCannotBecomeAChainOfArtificialStableRegions() {
        val drifting =
            slow.mapIndexed { index, beat ->
                beat.copy(seconds = beat.seconds + index * .30 / slow.size)
            }
        val phase = PulsePhaseAudit.assess(drifting, slow)
        assertEquals(PulsePhaseRelation.INCOHERENT, phase.relation)
        assertTrue(phase.supportedRanges.isEmpty())
    }

    @Test
    fun localPulseLevelContradictionCannotPassJustBecausePhaseCoincides() {
        val changedPulse =
            slow.take(64) + List(64) { index -> Beat(slow[64].seconds + index * .375, .95f) }
        val result =
            AutomaticPulseSelector.select(
                model(slow),
                reference(changedPulse, slow),
                { error("Existing pulse level") },
            )
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertTrue(result.regions.accepted.isNotEmpty())
        assertTrue(result.regions.accepted.none { it.contains(72, 80) })
        assertTrue(result.pulse.quality.issues.any { it.severity == BeatIssueSeverity.ERROR })
    }

    @Test
    fun offsetClockFollowsRealTempoChangesAndCircularWrap() {
        var time = .2
        val reference = List(96) { i -> Beat(time, .9f).also { time += .60 + .003 * i } }
        val observed =
            reference.zipWithNext().mapIndexed { index, (a, b) ->
                Beat(
                    a.seconds + (b.seconds - a.seconds) * (.5 + if (index % 2 == 0) .02 else -.02),
                    .9f,
                )
            }
        val phase = PulsePhaseAudit.assess(observed, reference)
        assertEquals(PulsePhaseRelation.STABLE_OFFSET, phase.relation)
        assertTrue(phase.concentration > .98)
        assertTrue(abs(abs(phase.offsetCycles!!) - .5) < .01)
        assertEquals(1.0, phase.stableWindowFraction)
    }

    @Test
    fun aSmallCoveredFragmentCannotCorroborateAnEntireSong() {
        val offbeatReference = slow.take(20).map { it.copy(seconds = it.seconds + .375) }
        val phase = PulsePhaseAudit.assess(slow, offbeatReference)
        assertFalse(phase.supportsCandidate)
        assertTrue(phase.coverage < .25)
    }

    @Test
    fun invalidReferenceCannotProducePhaseEvidence() {
        val invalid = slow.toMutableList().also { it[40] = it[39] }
        val phase = PulsePhaseAudit.assess(slow, invalid)
        assertEquals(PulsePhaseRelation.INSUFFICIENT, phase.relation)
        assertFalse(phase.supportsCandidate)
    }

    @Test
    fun cancellationInterruptsThePhaseAudit() {
        class Cancelled : RuntimeException()
        var checks = 0
        assertFailsWith<Cancelled> {
            PulsePhaseAudit.assess(slow, slow) {
                checks++
                if (checks == 3) throw Cancelled()
            }
        }
        assertEquals(3, checks)
    }

    @Test
    fun SplitMetricalEvidenceIsExplicitlyAmbiguous() {
        val split = slow.take(48) + List(48) { Beat(slow[47].seconds + (it + 1) * .375, .95f) }
        val result =
            AutomaticPulseSelector.select(
                model(split),
                reference(fast, split),
                { error("No dominant level") },
            )
        assertTrue(result.diagnostics.ambiguous)
        assertFalse(result.pulse.quality.safeForAutomaticMix)
        assertTrue(result.regions.accepted.isEmpty())
    }

    @Test
    fun InteriorAndTrailingErrorsKeepTheirGlobalRejectionWhileYieldingUsableRegions() {
        val reference = reference(slow)
        for (index in listOf(48, 92)) {
            val disturbed =
                slow.toMutableList().also {
                    it[index] = it[index].copy(seconds = it[index].seconds + .30, strength = .99f)
                }
            val pulse = PulseNormalizer.normalize(disturbed, reference)
            val regions = PulseRegions.analyze(pulse, reference, true)
            assertFalse(pulse.quality.safeForAutomaticMix)
            assertEquals(if (index == 48) 2 else 1, regions.accepted.size)
            assertTrue(regions.accepted.none { it.contains(index - 1, index + 2) })
            val song =
                LocalSongAnalysis(
                    reference,
                    model(slow),
                    pulse,
                    reference,
                    pulseRegions = regions.accepted,
                )
            val accepted = regions.accepted.first()
            song.requirePulseRange(accepted.startBeat, accepted.endBeatExclusive)
            assertFailsWith<IllegalArgumentException> { song.requirePulseRange(0, slow.size) }
            assertEquals(duration, song.audio.durationSeconds)
            assertEquals(slow, song.model.beats)
        }
    }

    @Test
    fun CancellationStopsAdditionalAnalysesAndRegionDiscovery() {
        class Cancelled : RuntimeException()
        var checks = 0
        assertFailsWith<Cancelled> {
            AutomaticPulseSelector.select(
                model(slow),
                reference(fast, slow),
                { reference(slow) },
                {
                    checks++
                    if (checks == 2) throw Cancelled()
                },
            )
        }
        assertFailsWith<Cancelled> {
            PulseRegions.analyze(
                PulseNormalizer.normalize(slow, reference(slow)),
                reference(slow),
                true,
            ) {
                throw Cancelled()
            }
        }
    }
}
