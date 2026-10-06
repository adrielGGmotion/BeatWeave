package org.metrolist.beatweave.learned

import kotlin.math.*
import org.metrolist.beatweave.*

/** An evidence score is not a calibrated probability of rhythmic correctness. */
data class PulseCandidateAssessment(
    val bpm: Double,
    val spectralSupport: Double,
    val intervalAgreement: Double,
    val phaseAgreement: Double,
    val onsetAgreement: Double,
    val score: Double,
    val evaluated: Boolean,
    val selected: Boolean,
    val rejectionReasons: List<String>,
    /** A viable pulse interpretation can still have localized timestamp errors. */
    val fullTrackQuality: BeatGridQualityReport? = null,
    /** A stable nonzero relation is retained explicitly; neither clock is phase shifted. */
    val phaseEvidence: PulsePhaseEvidence? = null,
)

data class PulseSelectionDiagnostics(
    val modelPulseBpm: Double?,
    val selectedBpm: Double?,
    val candidates: List<PulseCandidateAssessment>,
    val ambiguous: Boolean,
    val reason: String,
    val additionalSpectralPasses: Int,
    /** Canonical timing acceptance does not identify a quarter-note level or musical meter. */
    val metrical: MetricalPulseDiagnostics = MetricalPulseDiagnostics(selectedBpm),
)

/** Indices address the original canonical pulse list; timestamps stay on the source clock. */
data class AcceptedPulseRegion(
    val startBeat: Int,
    val endBeatExclusive: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val quality: BeatGridQualityReport,
) {
    fun contains(start: Int, endExclusive: Int): Boolean =
        start >= startBeat && endExclusive <= endBeatExclusive && start < endExclusive
}

data class ExcludedPulseRegion(
    val startSeconds: Double,
    val endSeconds: Double,
    val reasons: List<String>,
)

data class PulseRegionAnalysis(
    val accepted: List<AcceptedPulseRegion>,
    val excluded: List<ExcludedPulseRegion>,
)

data class AutomaticPulseResult(
    val reference: Analysis,
    val pulse: PulseNormalizationResult,
    val diagnostics: PulseSelectionDiagnostics,
    val regions: PulseRegionAnalysis,
)

/**
 * Selects a pulse interpretation supported by both the audio periodicity and neural events. A
 * half/double conflict is a candidate-selection problem, not permission to weaken timing gates.
 * Only at most two independently supported alternatives are analyzed again. Other alternatives and
 * their rejection reasons remain inspectable.
 */
object AutomaticPulseSelector {
    fun select(
        model: LearnedBeatAnalysis,
        initialReference: Analysis,
        analyzeReference: (preferredBpm: Double) -> Analysis,
        cancellationCheck: () -> Unit = {},
    ): AutomaticPulseResult {
        cancellationCheck()
        val modelPulse = robustPulse(model.beats)
        val activations =
            FloatArray(model.logits.beat.size) {
                (1.0 / (1.0 + exp(-model.logits.beat[it].toDouble()))).toFloat()
            }
        val initialNormalized =
            PulseNormalizer.normalize(
                model.beats,
                initialReference,
                model.downbeatSeconds,
                activations,
            )
        val spectral =
            initialReference.tempoCandidates.filter {
                it.bpm.isFinite() && it.bpm in 40.0..240.0 && it.support.isFinite()
            }
        val bestSupport = spectral.maxOfOrNull { it.support } ?: 0.0
        // Collapse neighbouring peaks, retaining distinct supported metrical interpretations.
        val unique = ArrayList<TempoCandidate>()
        for (candidate in spectral.sortedByDescending { it.support }) {
            if (unique.none { abs(ln(it.bpm / candidate.bpm)) < 0.04 }) unique += candidate
        }
        val initialCandidate =
            unique.minByOrNull {
                if (initialReference.bpm > 0) abs(ln(it.bpm / initialReference.bpm))
                else Double.POSITIVE_INFINITY
            }
        data class Evaluation(
            val assessment: PulseCandidateAssessment,
            val reference: Analysis?,
            val pulse: PulseNormalizationResult?,
        )
        val evaluated = ArrayList<Evaluation>()
        var extraPasses = 0
        val onsetAgreement = onsetAgreement(model.beats, initialReference)
        val proposed =
            unique.sortedByDescending { candidate ->
                approximateAgreement(model.beats, candidate.bpm) * 0.8 +
                    candidate.support / max(1e-9, bestSupport) * 0.2
            }
        for (candidate in proposed) {
            cancellationCheck()
            val reasons = ArrayList<String>()
            val approximate = approximateAgreement(model.beats, candidate.bpm)
            // Reject chance periodicity and merely neural-imposed tempos. This evidence
            // threshold stays independent of the later grid geometry/warp safety gates.
            if (candidate.support < 0.06 || candidate.support < bestSupport * 0.35)
                reasons += "Insufficient independent spectral periodicity"
            if (approximate < 0.55)
                reasons += "Most neural intervals do not support this pulse level"
            val isInitial =
                initialCandidate === candidate ||
                    (initialReference.bpm > 0 &&
                        abs(ln(candidate.bpm / initialReference.bpm)) < 0.04)
            if (reasons.isNotEmpty()) {
                evaluated +=
                    Evaluation(
                        PulseCandidateAssessment(
                            candidate.bpm,
                            candidate.support,
                            approximate,
                            0.0,
                            onsetAgreement,
                            0.0,
                            false,
                            false,
                            reasons.toList(),
                        ),
                        null,
                        null,
                    )
                continue
            }
            if (!isInitial && extraPasses >= 2) {
                evaluated +=
                    Evaluation(
                        PulseCandidateAssessment(
                            candidate.bpm,
                            candidate.support,
                            approximate,
                            0.0,
                            onsetAgreement,
                            0.0,
                            false,
                            false,
                            listOf(
                                "Alternative analysis budget exhausted; interpretation remains unresolved"
                            ),
                        ),
                        null,
                        null,
                    )
                continue
            }
            val reference =
                if (isInitial) initialReference
                else {
                    extraPasses++
                    analyzeReference(candidate.bpm)
                }
            cancellationCheck()
            var pulse =
                if (isInitial) initialNormalized
                else
                    PulseNormalizer.normalize(
                        model.beats,
                        reference,
                        model.downbeatSeconds,
                        activations,
                    )
            val missingReference =
                pulse.quality.issues.firstOrNull { it.code == "MISSING_REFERENCE" }
            if (missingReference != null) {
                reasons += "MISSING_REFERENCE: ${missingReference.message}"
                evaluated +=
                    Evaluation(
                        PulseCandidateAssessment(
                            candidate.bpm,
                            candidate.support,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            false,
                            false,
                            reasons.toList(),
                            pulse.quality,
                        ),
                        reference,
                        pulse,
                    )
                continue
            }
            var phaseEvidence =
                PulsePhaseAudit.assess(model.beats, reference.beats, cancellationCheck)
            if (phaseEvidence.relation == PulsePhaseRelation.REGIONAL_SUPPORT) {
                val supported =
                    phaseEvidence.supportedRanges.filter { range ->
                        cancellationCheck()
                        val local =
                            model.beats.filter {
                                it.seconds >= range.startSeconds && it.seconds <= range.endSeconds
                            }
                        local.size >= 16 &&
                            onsetAgreement(local, reference) >= 0.50 &&
                            localCadenceAgreement(local, reference.beats) >= 0.55
                    }
                val coverage =
                    model.beats
                        .count { beat ->
                            supported.any {
                                beat.seconds >= it.startSeconds && beat.seconds <= it.endSeconds
                            }
                        }
                        .toDouble() / max(1, model.beats.size)
                phaseEvidence =
                    phaseEvidence.copy(
                        relation =
                            if (coverage >= 0.55) PulsePhaseRelation.REGIONAL_SUPPORT
                            else PulsePhaseRelation.INCOHERENT,
                        supportedRanges = supported,
                        regionalCoverage = coverage,
                    )
            }
            if (phaseEvidence.supportsCandidate) {
                // Candidate-level majorities must never grant local support to outliers or to
                // canonical observations beyond the independently measured reference clock.
                val localSupport =
                    PulsePhaseAudit.locallySupported(
                        pulse.beats,
                        reference.beats,
                        phaseEvidence,
                        cancellationCheck,
                    )
                val issues =
                    localPhaseUncertaintyIssues(pulse.beats, localSupport) +
                        if (phaseEvidence.relation == PulsePhaseRelation.REGIONAL_SUPPORT)
                            phaseUncertaintyIssues(phaseEvidence.supportedRanges, pulse.beats)
                        else emptyList()
                pulse =
                    pulse.copy(quality = pulse.quality.copy(issues = pulse.quality.issues + issues))
            }
            val phase = phaseEvidence.directAgreement
            if (reference.bpm <= 0 || abs(ln(reference.bpm / candidate.bpm)) > 0.08)
                reasons += "Tracked tempo does not preserve the proposed pulse interpretation"
            if (pulse.canonicalAgreement < 0.55)
                reasons += "Local audio cadence does not corroborate the neural pulse"
            if (!phaseEvidence.supportsCandidate)
                reasons +=
                    "Neural timestamps have neither direct nor stable-offset independent pulse support"
            if (phaseEvidence.relation == PulsePhaseRelation.STABLE_OFFSET && onsetAgreement < 0.50)
                reasons +=
                    "A stable offset requires independently measured attacks at a majority of neural events"
            if (onsetAgreement < 0.35)
                reasons +=
                    "Too few neural events coincide with independently measured audio attacks"
            val score =
                0.55 * pulse.canonicalAgreement +
                    0.25 * phaseEvidence.consistency +
                    0.10 * onsetAgreement +
                    0.10 * candidate.support / max(1e-9, bestSupport)
            evaluated +=
                Evaluation(
                    PulseCandidateAssessment(
                        candidate.bpm,
                        candidate.support,
                        pulse.canonicalAgreement,
                        phase,
                        onsetAgreement,
                        score,
                        true,
                        false,
                        reasons.toList(),
                        pulse.quality,
                        phaseEvidence,
                    ),
                    reference,
                    pulse,
                )
        }
        val viable =
            evaluated
                .filter { it.assessment.evaluated && it.assessment.rejectionReasons.isEmpty() }
                .sortedByDescending { it.assessment.score }
        val best = viable.firstOrNull()
        val splitPulseEvidence =
            best == null &&
                evaluated.count {
                    it.assessment.spectralSupport >= 0.06 &&
                        it.assessment.spectralSupport >= bestSupport * 0.35 &&
                        it.assessment.intervalAgreement >= 0.30
                } >= 2
        val ambiguous =
            splitPulseEvidence ||
                (best != null &&
                    (viable.drop(1).any {
                        abs(ln(it.assessment.bpm / best.assessment.bpm)) >= 0.08 &&
                            best.assessment.score - it.assessment.score < 0.08
                    } ||
                        evaluated.any {
                            !it.assessment.evaluated &&
                                it.assessment.rejectionReasons.any { reason ->
                                    reason.startsWith("Alternative analysis budget")
                                } &&
                                it.assessment.spectralSupport >=
                                    best.assessment.spectralSupport * 0.85 &&
                                it.assessment.intervalAgreement >=
                                    best.assessment.intervalAgreement - 0.05
                        }))
        val accepted = best != null && !ambiguous
        val reason =
            when {
                ambiguous -> "Different pulse interpretations retain comparable joint evidence"
                best == null ->
                    "No pulse candidate has independent spectral, phase, and neural interval support"
                best.assessment.phaseEvidence?.relation == PulsePhaseRelation.REGIONAL_SUPPORT ->
                    "Selected a pulse interpretation within independently supported contiguous regions; full-track phase remains uncertain"
                else ->
                    "Selected the pulse jointly supported by neural events and independent audio periodicity"
            }
        val chosenReference = best?.reference ?: initialReference
        var chosenPulse = best?.pulse ?: initialNormalized
        if (!accepted) {
            val issue =
                BeatGridIssue(
                    if (ambiguous) "AMBIGUOUS_AUTOMATIC_PULSE" else "NO_SUPPORTED_AUTOMATIC_PULSE",
                    BeatIssueSeverity.ERROR,
                    0.0,
                    initialReference.durationSeconds,
                    reason,
                )
            chosenPulse =
                chosenPulse.copy(
                    quality = chosenPulse.quality.copy(issues = chosenPulse.quality.issues + issue)
                )
        }
        val candidates =
            evaluated.map { evaluation ->
                evaluation.assessment.copy(
                    selected = accepted && evaluation === best,
                    rejectionReasons =
                        if (evaluation.assessment.rejectionReasons.isEmpty() && evaluation !== best)
                            listOf(
                                if (ambiguous) "Comparable alternative remains ambiguous"
                                else "Another candidate has stronger joint evidence"
                            )
                        else evaluation.assessment.rejectionReasons,
                )
            }
        val diagnostics =
            PulseSelectionDiagnostics(
                modelPulse,
                if (accepted) best!!.assessment.bpm else null,
                candidates,
                ambiguous,
                reason,
                extraPasses,
                MetricalPulseAudit.describe(
                    if (accepted) best!!.assessment.bpm else null,
                    candidates,
                ),
            )
        val regions =
            PulseRegions.analyze(chosenPulse, chosenReference, accepted, cancellationCheck)
        return AutomaticPulseResult(chosenReference, chosenPulse, diagnostics, regions)
    }

    private fun robustPulse(beats: List<Beat>): Double? {
        val periods =
            beats
                .windowed(9)
                .map { (it.last().seconds - it.first().seconds) / 8 }
                .filter { it > 0 && it.isFinite() }
                .sorted()
        return periods.getOrNull(periods.size / 2)?.let { 60 / it }
    }

    private fun approximateAgreement(beats: List<Beat>, bpm: Double): Double {
        if (beats.size < 2) return 0.0
        val period = 60 / bpm
        return beats
            .zipWithNext()
            .count { (a, b) -> abs((b.seconds - a.seconds) / period - 1) < 0.22 }
            .toDouble() / (beats.size - 1)
    }

    private fun onsetAgreement(beats: List<Beat>, reference: Analysis): Double {
        val step = reference.onsetHopSeconds
        val offset = reference.onsetTimeOffsetSeconds
        if (
            beats.isEmpty() ||
                reference.onsetEnvelope.isEmpty() ||
                step <= 0 ||
                !step.isFinite() ||
                !offset.isFinite()
        )
            return 0.0
        val tolerance = 0.055
        val measuredEnd = offset + reference.onsetEnvelope.lastIndex * step
        if (!measuredEnd.isFinite()) return 0.0
        var covered = 0
        var count = 0
        for (beat in beats) {
            if (!beat.seconds.isFinite()) return 0.0
            if (
                beat.seconds + tolerance < offset ||
                    beat.seconds - tolerance > measuredEnd
            )
                continue
            covered++
            val frame = (beat.seconds - offset) / step
            if (!frame.isFinite()) return 0.0
            val lo = max(0, ceil(frame - tolerance / step).toInt())
            val hi = min(reference.onsetEnvelope.lastIndex, floor(frame + tolerance / step).toInt())
            if (
                lo <= hi &&
                    (lo..hi).any {
                        reference.onsetEnvelope[it].let { value ->
                            value.isFinite() && value >= 0.30f
                        }
                    }
            )
                count++
        }
        val coverage = covered.toDouble() / beats.size
        return if (coverage >= 0.55) count.toDouble() / covered else 0.0
    }

    private fun localCadenceAgreement(beats: List<Beat>, reference: List<Beat>): Double {
        if (beats.size < 2 || reference.size < 2) return 0.0
        return beats
            .zipWithNext()
            .count { (a, b) ->
                val index = lowerBound(reference, (a.seconds + b.seconds) / 2)
                val periods =
                    (max(0, index - 8) until min(reference.lastIndex, index + 8))
                        .map { reference[it + 1].seconds - reference[it].seconds }
                        .filter { it > 0 && it.isFinite() }
                        .sorted()
                val period = periods.getOrNull(periods.size / 2)
                period != null && abs((b.seconds - a.seconds) / period - 1) < 0.22
            }
            .toDouble() / (beats.size - 1)
    }

    private fun localPhaseUncertaintyIssues(
        beats: List<Beat>,
        supported: BooleanArray,
    ): List<BeatGridIssue> {
        val result = ArrayList<BeatGridIssue>()
        var index = 0
        while (index < beats.size) {
            if (supported[index]) {
                index++
                continue
            }
            val start = index
            while (index + 1 < beats.size && !supported[index + 1]) index++
            result +=
                BeatGridIssue(
                    "UNCERTAIN_REFERENCE_PHASE",
                    BeatIssueSeverity.ERROR,
                    beats[start].seconds,
                    beats[index].seconds,
                    "Canonical events lack measured independent-reference coverage or exceed the supported phase relation's existing timing tolerance; source clocks were not shifted",
                )
            index++
        }
        return result
    }

    private fun phaseUncertaintyIssues(
        ranges: List<PulsePhaseRange>,
        beats: List<Beat>,
    ): List<BeatGridIssue> {
        if (ranges.isEmpty() || beats.isEmpty()) return emptyList()
        val result = ArrayList<BeatGridIssue>()
        fun issue(start: Double, end: Double) {
            result +=
                BeatGridIssue(
                    "UNCERTAIN_REFERENCE_PHASE",
                    BeatIssueSeverity.ERROR,
                    start,
                    end,
                    "Independent pulse phase is only supported in separate contiguous regions; source clocks were not shifted",
                )
        }
        val ordered = ranges.sortedBy { it.startSeconds }
        if (ordered.first().startSeconds > beats.first().seconds)
            issue(beats.first().seconds, ordered.first().startSeconds)
        for ((a, b) in ordered.zipWithNext()) issue(
            min(a.endSeconds, b.startSeconds),
            max(a.endSeconds, b.startSeconds),
        )
        if (ordered.last().endSeconds < beats.last().seconds)
            issue(ordered.last().endSeconds, beats.last().seconds)
        return result
    }

    internal fun lowerBound(beats: List<Beat>, time: Double): Int {
        var lo = 0
        var hi = beats.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (beats[mid].seconds < time) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Finds usable islands without rewriting or hiding the full-track rejection report. */
object PulseRegions {
    fun analyze(
        pulse: PulseNormalizationResult,
        reference: Analysis,
        candidateAccepted: Boolean,
        cancellationCheck: () -> Unit = {},
    ): PulseRegionAnalysis {
        cancellationCheck()
        val beats = pulse.beats
        val globalCodes =
            setOf(
                "INVALID_TIMESTAMPS",
                "MISSING_REFERENCE",
                "INSUFFICIENT_BEATS",
                "UNCONFIRMED_CANONICAL_PULSE",
                "EXCESSIVE_REPAIRS",
                "AMBIGUOUS_AUTOMATIC_PULSE",
                "NO_SUPPORTED_AUTOMATIC_PULSE",
            )
        val errors = pulse.quality.issues.filter { it.severity == BeatIssueSeverity.ERROR }
        if (!candidateAccepted || beats.size < 8 || errors.any { it.code in globalCodes }) {
            return PulseRegionAnalysis(
                emptyList(),
                listOf(
                    ExcludedPulseRegion(
                        0.0,
                        reference.durationSeconds,
                        if (errors.isEmpty()) listOf("No corroborated pulse interpretation")
                        else errors.map { it.code }.distinct(),
                    )
                ),
            )
        }
        val blocked = BooleanArray(beats.size)
        val exclusions = ArrayList<ExcludedPulseRegion>()
        for (error in errors) {
            cancellationCheck()
            // The cadence estimator uses eight neighbouring pulses on each side. Exclude
            // that context as well, rather than treating the next beat as independent.
            val from = max(0, AutomaticPulseSelector.lowerBound(beats, error.startSeconds) - 8)
            val through =
                min(beats.lastIndex, AutomaticPulseSelector.lowerBound(beats, error.endSeconds) + 8)
            for (index in from..through) blocked[index] = true
            exclusions +=
                ExcludedPulseRegion(
                    beats[max(0, from - 1)].seconds,
                    beats[min(beats.lastIndex, through + 1)].seconds,
                    listOf(error.code),
                )
        }
        val accepted = ArrayList<AcceptedPulseRegion>()
        var start = 0
        while (start < beats.size) {
            cancellationCheck()
            if (blocked[start]) {
                start++
                continue
            }
            var end = start + 1
            while (end < beats.size && !blocked[end]) end++
            if (end - start >= 8) {
                val quality =
                    BeatGridQuality.audit(
                        beats.subList(start, end),
                        reference.beats,
                        reference.durationSeconds,
                    )
                if (quality.safeForAutomaticMix)
                    accepted +=
                        AcceptedPulseRegion(
                            start,
                            end,
                            beats[start].seconds,
                            beats[end - 1].seconds,
                            quality,
                        )
                else
                    exclusions +=
                        ExcludedPulseRegion(
                            beats[start].seconds,
                            beats[end - 1].seconds,
                            quality.issues.map { it.code }.distinct(),
                        )
            } else
                exclusions +=
                    ExcludedPulseRegion(
                        beats[start].seconds,
                        beats[end - 1].seconds,
                        listOf("INSUFFICIENT_CONTIGUOUS_BEATS"),
                    )
            start = end
        }
        if (beats.first().seconds > 0)
            exclusions +=
                ExcludedPulseRegion(
                    0.0,
                    beats.first().seconds,
                    listOf("BEFORE_FIRST_OBSERVED_PULSE"),
                )
        if (beats.last().seconds < reference.durationSeconds)
            exclusions +=
                ExcludedPulseRegion(
                    beats.last().seconds,
                    reference.durationSeconds,
                    listOf("AFTER_LAST_OBSERVED_PULSE"),
                )
        val merged = ArrayList<ExcludedPulseRegion>()
        for (region in exclusions.sortedBy { it.startSeconds }) {
            val previous = merged.lastOrNull()
            if (previous != null && region.startSeconds <= previous.endSeconds) {
                merged[merged.lastIndex] =
                    ExcludedPulseRegion(
                        previous.startSeconds,
                        max(previous.endSeconds, region.endSeconds),
                        (previous.reasons + region.reasons).distinct(),
                    )
            } else merged += region
        }
        return PulseRegionAnalysis(accepted, merged)
    }
}
