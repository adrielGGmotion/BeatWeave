package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/** Relative event rate, not a musical time signature or permission to change the clock. */
enum class MetricalPulseRatio(val numerator: Int, val denominator: Int) {
    HALF(1, 2),
    DOUBLE(2, 1),
    THIRD(1, 3),
    TRIPLE(3, 1),
}

enum class PulseLevelIdentification {
    UNRESOLVED,
    /** A consistent matching clock was selected; its quarter-note meaning was not established. */
    CANONICAL_PULSE_ONLY,
}

/**
 * A measured periodicity at an integer-related rate. A low neural adjacent-interval agreement can
 * explain why this rate was not selected as the matching clock; it does not disprove another
 * musical pulse level. Spectral support measures periodicity, not the probability of a meter.
 */
data class MetricalPulseAlternative(
    val bpm: Double,
    val relativeRate: MetricalPulseRatio,
    val spectralSupport: Double,
    val relativeSpectralSupport: Double,
    val independentlySupportedPeriodicity: Boolean,
    val neuralIntervalAgreement: Double,
    val evaluatedAsCanonicalCandidate: Boolean,
    val canonicalRejectionReasons: List<String>,
)

enum class UnsupportedMeterFamilyReason {
    FRACTIONAL_CANONICAL_PULSE_COUNT,
    COUNT_OUTSIDE_CONFIGURED_FAMILY,
}

/**
 * A representational limit, not a detected meter. If [alternativePulsesPerBar] described a bar at
 * [alternativeBpm], its length on the selected clock would be the given rational pulse count.
 */
data class UnsupportedMeterFamily(
    val alternativeBpm: Double,
    val alternativePulsesPerBar: Int,
    val requiredCanonicalPulseNumerator: Int,
    val requiredCanonicalPulseDenominator: Int,
    val reason: UnsupportedMeterFamilyReason,
)

/**
 * Keeps musical-level uncertainty separate from canonical timing acceptance. Absence of reported
 * aliases is not proof of a quarter-note level: only available spectral candidates within the
 * analyzer's measured search range are inspected. No extra audio pass or synthesized beat is used.
 */
data class MetricalPulseDiagnostics(
    val selectedPulseBpm: Double?,
    val alternatives: List<MetricalPulseAlternative> = emptyList(),
    val minimumAssessedBpm: Double = 40.0,
    val maximumAssessedBpm: Double = 240.0,
) {
    val levelIdentification: PulseLevelIdentification
        get() =
            if (selectedPulseBpm == null) PulseLevelIdentification.UNRESOLVED
            else PulseLevelIdentification.CANONICAL_PULSE_ONLY

    val hasSupportedAlternativeLevel: Boolean
        get() = alternatives.any { it.independentlySupportedPeriodicity }

    val interpretation: String
        get() =
            if (selectedPulseBpm == null) "No canonical matching pulse was selected"
            else
                "The selected BPM describes a canonical matching pulse; its quarter-note level and musical meter are not verified"

    /**
     * Checks the caller's explicit meter family at every independently supported alternate rate.
     * This makes omissions such as twelve canonical pulses inspectable without assuming four-beat
     * music, selecting a different meter, or weakening a bar acceptance gate. At most four aliases
     * and sixteen configured counts are considered. The supplied counts remain pulse counts, not
     * time-signature denominators.
     */
    fun unsupportedMeterFamilies(configuredPulsesPerBar: List<Int>): List<UnsupportedMeterFamily> {
        require(configuredPulsesPerBar.isNotEmpty() && configuredPulsesPerBar.size <= 16)
        require(
            configuredPulsesPerBar.all { it in 2..32 } &&
                configuredPulsesPerBar.distinct().size == configuredPulsesPerBar.size
        )
        val result = ArrayList<UnsupportedMeterFamily>()
        for (alias in alternatives.filter { it.independentlySupportedPeriodicity }.take(4)) {
            for (count in configuredPulsesPerBar) {
                val numerator = count * alias.relativeRate.denominator
                val denominator = alias.relativeRate.numerator
                val divisor = gcd(numerator, denominator)
                val n = numerator / divisor
                val d = denominator / divisor
                if (d != 1 || n !in configuredPulsesPerBar)
                    result +=
                        UnsupportedMeterFamily(
                            alias.bpm,
                            count,
                            n,
                            d,
                            if (d != 1)
                                UnsupportedMeterFamilyReason.FRACTIONAL_CANONICAL_PULSE_COUNT
                            else UnsupportedMeterFamilyReason.COUNT_OUTSIDE_CONFIGURED_FAMILY,
                        )
            }
        }
        return result
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val next = x % y
            x = y
            y = next
        }
        return x
    }
}

internal object MetricalPulseAudit {
    fun describe(
        selectedBpm: Double?,
        candidates: List<PulseCandidateAssessment>,
    ): MetricalPulseDiagnostics {
        if (selectedBpm == null) return MetricalPulseDiagnostics(null)
        val bestSupport = candidates.maxOfOrNull { it.spectralSupport } ?: 0.0
        val alternatives =
            MetricalPulseRatio.entries.mapNotNull { ratio ->
                val expectedRate = ratio.numerator.toDouble() / ratio.denominator
                val candidate =
                    candidates
                        .filter { it.bpm > 0 && it.bpm.isFinite() && !it.selected }
                        .filter { abs(ln(it.bpm / selectedBpm / expectedRate)) < 0.04 }
                        .maxByOrNull { it.spectralSupport } ?: return@mapNotNull null
                MetricalPulseAlternative(
                    candidate.bpm,
                    ratio,
                    candidate.spectralSupport,
                    candidate.spectralSupport / max(1e-9, bestSupport),
                    candidate.spectralSupport >= 0.06 &&
                        candidate.spectralSupport >= bestSupport * 0.35,
                    candidate.intervalAgreement,
                    candidate.evaluated,
                    candidate.rejectionReasons.toList(),
                )
            }
        return MetricalPulseDiagnostics(selectedBpm, alternatives)
    }
}
