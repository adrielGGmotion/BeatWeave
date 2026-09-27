package org.metrolist.beatweave.learned

import org.metrolist.beatweave.*

/**
 * Preserves original model observations alongside the proposed canonical matching pulse. Passing
 * quality gates establishes timing consistency, not human-verified beat truth.
 */
data class LocalSongAnalysis(
    val audio: Analysis,
    val model: LearnedBeatAnalysis,
    val pulse: PulseNormalizationResult,
    /** Independent spectral timing evidence used to choose a canonical subdivision. */
    val reference: Analysis,
    /** Meter/phase inference is separate from beat-pulse acceptance. */
    val barTracking: BarTrackingResult? = null,
    val pulseSelection: PulseSelectionDiagnostics? = null,
    val pulseRegions: List<AcceptedPulseRegion> = emptyList(),
    val excludedPulseRegions: List<ExcludedPulseRegion> = emptyList(),
    /** Independent audible-pattern audit of the same canonical indices; never another clock. */
    val acousticPulse: AcousticPulseReport? = null,
) {
    fun matchingGrid(): BeatGrid {
        pulse.quality.requireUsable()
        requireAcousticPulseRange(0, pulse.beats.size)
        return BeatGrid(pulse.beats.map { it.seconds }.toDoubleArray())
    }

    /**
     * A local selection may be accepted while the unmodified full-track report rejects an outro.
     */
    fun requirePulseRange(startBeat: Int, endBeatExclusive: Int) {
        requirePulseGeometryRange(startBeat, endBeatExclusive)
        requireAcousticPulseRange(startBeat, endBeatExclusive)
    }

    /** Separate geometric/phase evidence for diagnostics; playback uses [requirePulseRange]. */
    internal fun requirePulseGeometryRange(startBeat: Int, endBeatExclusive: Int) {
        require(
            startBeat >= 0 &&
                endBeatExclusive <= pulse.beats.size &&
                endBeatExclusive - startBeat >= 2
        ) {
            "A pulse range requires at least two in-bounds canonical timestamps"
        }
        require(
            pulse.quality.safeForAutomaticMix ||
                pulseRegions.any { it.contains(startBeat, endBeatExclusive) }
        ) {
            "Requested pulse range crosses uncertain or rejected beat evidence"
        }
    }

    private fun requireAcousticPulseRange(startBeat: Int, endBeatExclusive: Int) {
        val evidence = acousticPulse ?: return
        evidence.requireClock(pulse.beats.map { it.seconds }.toDoubleArray())
        require(evidence.supportsRange(startBeat, endBeatExclusive)) {
            "Requested pulse range lacks complete recurring audible attack evidence: " +
                evidence.assessRange(startBeat, endBeatExclusive).issues.joinToString()
        }
    }

    /** Range anchors keep their original absolute source timestamps; audio is never trimmed. */
    fun matchingGrid(startBeat: Int, endBeatExclusive: Int): BeatGrid {
        requirePulseRange(startBeat, endBeatExclusive)
        return BeatGrid(
            pulse.beats.subList(startBeat, endBeatExclusive).map { it.seconds }.toDoubleArray()
        )
    }

    /**
     * Inspectable proposed geometry, including songs with rejected regions. A planner must still
     * requirePulseRange and validate the selected bar evidence before rendering.
     */
    fun bars(): BarGrid = barTracking?.grid() ?: BarGrid.from(matchingGrid(), pulse.downbeatSeconds)
}

/**
 * Local analysis pipeline used by the library demo. PCM must already be mono22050Hz. Own the
 * supplied backend outside this object and close it when finished. Serializing calls avoids
 * competing for mobile CPU; the ORT backend also serializes its session.
 */
class LocalSongAnalyzer(private val backend: BeatThisBackend) {
    fun analyze(
        mono22050: FloatArray,
        cancellationCheck: () -> Unit = {},
        onProgress: (Double) -> Unit = {},
    ): LocalSongAnalysis {
        cancellationCheck()
        onProgress(0.0)
        val spectral = MusicAnalyzer().prepare(mono22050, 22050, cancellationCheck)
        val initialReference = spectral.analyze(cancellationCheck = cancellationCheck)
        cancellationCheck()
        onProgress(0.25)
        val learned =
            BeatThisAnalyzer(backend).analyze(mono22050, cancellationCheck) {
                onProgress(0.25 + 0.7 * it)
            }
        cancellationCheck()
        val selection =
            AutomaticPulseSelector.select(
                learned,
                initialReference,
                analyzeReference = { bpm ->
                    spectral.analyze(preferredBpm = bpm, cancellationCheck = cancellationCheck)
                },
                cancellationCheck = cancellationCheck,
            )
        val reference = selection.reference
        val normalized = selection.pulse
        val acousticPulse =
            AcousticPulseEvidence.assess(
                spectral.acousticAttacks,
                normalized.beats,
                cancellationCheck,
            )
        val bars =
            BarTracker.track(
                    normalized.beats,
                    learned.downbeatSeconds,
                    learned.logits.downbeat,
                    cancellationCheck = cancellationCheck,
                )
                .withPulseSupport(selection.regions.accepted)
                .withAcousticPulseSupport(acousticPulse)
        val barWarnings =
            if (bars.safeForAutomaticBars) emptyList()
            else
                listOf(
                    "BAR_UNCERTAINTY: Some proposed bars lack complete required evidence; inspect issuesForBar for the selected range"
                )
        val timingWarnings =
            normalized.quality.issues.map { "${it.code}: ${it.message}" } +
                listOf("AUTOMATIC_PULSE_SELECTION: ${selection.diagnostics.reason}") +
                listOf(
                    "PULSE_LEVEL_IDENTIFICATION: ${selection.diagnostics.metrical.interpretation}"
                ) +
                selection.regions.excluded.map {
                    "EXCLUDED_PULSE_REGION: ${it.startSeconds}..${it.endSeconds}s (${it.reasons.joinToString()})"
                }
        val spans =
            normalized.beats
                .windowed(9)
                .map { (it.last().seconds - it.first().seconds) / 8 }
                .sorted()
        val tempo =
            if (spans.isEmpty()) normalized.quality.medianBpm else 60.0 / spans[spans.size / 2]
        // Reuse spectral/energy features. Original model events remain available in
        // model/rawBeats; this Analysis explicitly describes the canonical pulse.
        val summary =
            reference.copy(
                bpm = tempo,
                beats = normalized.beats,
                downbeatSeconds = bars.downbeatSeconds,
                tempoConfidence = normalized.canonicalAgreement,
                beatConfidence =
                    normalized.beats
                        .map { it.strength.toDouble().coerceIn(0.0, 1.0) }
                        .average()
                        .let { if (it.isFinite()) it else 0.0 },
                tempoCandidates =
                    selection.diagnostics.candidates.map {
                        TempoCandidate(it.bpm, it.spectralSupport)
                    },
                tempoAmbiguous =
                    selection.diagnostics.ambiguous ||
                        reference.tempoAmbiguous ||
                        normalized.repairs.isNotEmpty(),
                detectorId = learned.modelId + "+automatic-evidence-pulse-v4-acoustic",
                warnings = reference.warnings + timingWarnings + barWarnings,
            )
        onProgress(1.0)
        return LocalSongAnalysis(
            summary,
            learned,
            normalized,
            reference,
            bars,
            selection.diagnostics,
            selection.regions.accepted,
            selection.regions.excluded,
            acousticPulse,
        )
    }
}
