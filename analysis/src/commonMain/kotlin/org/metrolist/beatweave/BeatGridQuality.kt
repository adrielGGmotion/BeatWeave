package org.metrolist.beatweave

import kotlin.math.*

enum class BeatIssueSeverity {
    WARNING,
    ERROR,
}

data class BeatGridIssue(
    val code: String,
    val severity: BeatIssueSeverity,
    val startSeconds: Double,
    val endSeconds: Double,
    val message: String,
)

data class BeatGridQualityReport(
    val beatCount: Int,
    val medianBpm: Double,
    val issues: List<BeatGridIssue>,
    /** Fraction of candidate intervals with usable independent reference evidence, if measured. */
    val referenceCoverage: Double? = null,
) {
    val safeForAutomaticMix: Boolean
        get() = beatCount >= 8 && issues.none { it.severity == BeatIssueSeverity.ERROR }

    fun requireUsable() {
        require(safeForAutomaticMix) { issues.joinToString("; ") { "${it.code}: ${it.message}" } }
    }
}

/** Geometry is a necessary quality gate, not proof that a musical interpretation is correct. */
object BeatGridQuality {
    fun audit(
        beats: List<Beat>,
        referenceBeats: List<Beat>? = null,
        durationSeconds: Double? = null,
    ): BeatGridQualityReport {
        val issues = ArrayList<BeatGridIssue>()
        fun error(code: String, a: Double, b: Double, message: String) {
            issues += BeatGridIssue(code, BeatIssueSeverity.ERROR, a, b, message)
        }
        if (beats.size < 8)
            error(
                "INSUFFICIENT_BEATS",
                0.0,
                0.0,
                "At least eight beats are required for an automatic transition",
            )
        if (durationSeconds != null && (!durationSeconds.isFinite() || durationSeconds <= 0.0)) {
            error(
                "INVALID_DURATION",
                0.0,
                0.0,
                "A supplied recording duration must be finite and positive",
            )
            return BeatGridQualityReport(beats.size, 0.0, issues)
        }
        if (
            beats.any {
                !it.seconds.isFinite() ||
                    it.seconds < 0 ||
                    !it.strength.isFinite() ||
                    (durationSeconds != null && it.seconds >= durationSeconds)
            } || beats.zipWithNext().any { (a, b) -> b.seconds <= a.seconds }
        ) {
            error(
                "INVALID_TIMESTAMPS",
                0.0,
                0.0,
                "Beat timestamps must be finite, strictly increasing, and inside the audio",
            )
            return BeatGridQualityReport(beats.size, 0.0, issues)
        }
        if (
            referenceBeats != null &&
                (referenceBeats.size < 3 ||
                    referenceBeats.any {
                        !it.seconds.isFinite() ||
                            it.seconds < 0 ||
                            !it.strength.isFinite() ||
                            (durationSeconds != null && it.seconds >= durationSeconds)
                    } || referenceBeats.zipWithNext().any { (a, b) -> b.seconds <= a.seconds })
        ) {
            error(
                "INVALID_REFERENCE_CLOCK",
                0.0,
                0.0,
                "At least three reference beats with finite strengths and finite, strictly increasing timestamps inside the audio are required",
            )
            return BeatGridQualityReport(beats.size, 0.0, issues)
        }
        val intervals = beats.zipWithNext { a, b -> b.seconds - a.seconds }
        val referencePeriods = referenceBeats?.let { DoubleArray(intervals.size) { Double.NaN } }
        var referenceCoverage: Double? = null
        if (referenceBeats != null && referencePeriods != null) {
            var nextReference = 1
            var coveredIntervals = 0
            for (index in intervals.indices) {
                val midpoint = beats[index].seconds + intervals[index] / 2
                while (
                    nextReference < referenceBeats.size &&
                        referenceBeats[nextReference].seconds < midpoint
                )
                    nextReference++
                if (
                    nextReference >= referenceBeats.size ||
                        midpoint < referenceBeats[nextReference - 1].seconds
                )
                    continue
                val localPeriod = PulseNormalizer.localPeriod(referenceBeats, midpoint) ?: continue
                val measuredGap =
                    referenceBeats[nextReference].seconds -
                        referenceBeats[nextReference - 1].seconds
                if (measuredGap / localPeriod > 1.48) continue
                referencePeriods[index] = localPeriod
                coveredIntervals++
            }
            val coverage =
                if (intervals.isEmpty()) 0.0 else coveredIntervals.toDouble() / intervals.size
            referenceCoverage = coverage
            if (coverage < 0.80) {
                error(
                    "INSUFFICIENT_REFERENCE_COVERAGE",
                    beats.firstOrNull()?.seconds ?: 0.0,
                    beats.lastOrNull()?.seconds ?: 0.0,
                    "Independent reference brackets only ${(coverage * 100).roundToInt()}% of candidate intervals",
                )
                return BeatGridQualityReport(beats.size, 0.0, issues, referenceCoverage)
            }
        }
        val median = intervals.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        for (i in intervals.indices) {
            val a = beats[i].seconds
            val b = beats[i + 1].seconds
            val interval = intervals[i]
            if (interval < 0.16)
                error(
                    "DUPLICATE_OR_SUBDIVISION",
                    a,
                    b,
                    "Adjacent pulses are only ${interval}s apart",
                )
            val midpoint = (a + b) / 2
            val local =
                referencePeriods?.get(i)?.takeIf { it.isFinite() }
                    ?: if (referenceBeats == null) PulseNormalizer.localPeriod(beats, midpoint)
                    else null
            if (local != null && (interval / local < 0.62 || interval / local > 1.48))
                error(
                    "PULSE_LEVEL_DISCONTINUITY",
                    a,
                    b,
                    "Interval does not agree with the surrounding canonical pulse",
                )
            if (i > 0 && max(interval / intervals[i - 1], intervals[i - 1] / interval) > 1.48)
                error(
                    "ABRUPT_PULSE_CHANGE",
                    a,
                    b,
                    "Consecutive beat periods change too abruptly for automatic stretching",
                )
        }
        return BeatGridQualityReport(
            beats.size,
            if (median > 0) 60 / median else 0.0,
            issues,
            referenceCoverage,
        )
    }
}

enum class PulseRepairKind {
    REMOVED_DUPLICATE,
    AUDIO_SUPPORTED_INSERTION,
    MODEL_SUPPORTED_INSERTION,
    CADENCE_INTERPOLATION,
    RELOCATED_WEAK_OBSERVATION,
}

data class PulseRepair(
    val kind: PulseRepairKind,
    val seconds: Double,
    val evidenceScore: Double,
    val detail: String,
    val originalSeconds: Double? = null,
)

data class PulseNormalizationResult(
    val rawBeats: List<Beat>,
    val beats: List<Beat>,
    val downbeatSeconds: List<Double>,
    val repairs: List<PulseRepair>,
    val quality: BeatGridQualityReport,
    val canonicalAgreement: Double,
)

/**
 * Canonicalizes a locally varying pulse only when most raw model intervals corroborate it. Raw
 * observations are always retained. It never imposes a song BPM or silently accepts an unsupported
 * metrical change. Downbeats are retained only when their observed beat survives.
 */
object PulseNormalizer {
    fun normalize(
        rawBeats: List<Beat>,
        fallback: Analysis,
        downbeatSeconds: List<Double> = emptyList(),
        activationScores: FloatArray? = null,
        activationHopSeconds: Double = 0.02,
    ): PulseNormalizationResult {
        require(activationHopSeconds.isFinite() && activationHopSeconds > 0)
        require(activationScores == null || activationScores.all { it.isFinite() && it in 0f..1f })
        val repairs = ArrayList<PulseRepair>()
        val additional = ArrayList<BeatGridIssue>()
        fun reject(code: String, a: Double, b: Double, message: String) {
            additional += BeatGridIssue(code, BeatIssueSeverity.ERROR, a, b, message)
        }
        val duration = fallback.durationSeconds
        val validDuration = duration.isFinite() && duration > 0.0
        fun validClock(beats: List<Beat>): Boolean =
            validDuration &&
                beats.all {
                it.seconds.isFinite() &&
                    it.seconds >= 0 &&
                    it.seconds < duration &&
                    it.strength.isFinite()
                } && beats.zipWithNext().all { (a, b) -> b.seconds > a.seconds }
        if (
            !validClock(rawBeats) ||
                !validClock(fallback.beats) ||
                rawBeats.size < 8 ||
                fallback.beats.size < 8
        ) {
            reject(
                "MISSING_REFERENCE",
                0.0,
                if (validDuration) duration else 0.0,
                "Finite ordered model beats and an independent local pulse reference inside a finite recording are required",
            )
            return result(rawBeats, rawBeats, downbeatSeconds, repairs, fallback, additional, 0.0)
        }
        val reference = fallback.beats
        val agreement =
            rawBeats
                .zipWithNext()
                .mapNotNull { (a, b) ->
                    measuredLocalPeriod(reference, (a.seconds + b.seconds) / 2)?.let {
                        abs((b.seconds - a.seconds) / it - 1) < 0.22
                    }
                }
                .let { if (it.isEmpty()) 0.0 else it.count { x -> x }.toDouble() / it.size }
        if (agreement < 0.55) {
            reject(
                "UNCONFIRMED_CANONICAL_PULSE",
                rawBeats.first().seconds,
                rawBeats.last().seconds,
                "Independent audio pulse agrees with only ${(agreement*100).roundToInt()}% of model intervals",
            )
            return result(
                rawBeats,
                rawBeats,
                downbeatSeconds,
                repairs,
                fallback,
                additional,
                agreement,
            )
        }
        val cleaned = rawBeats.toMutableList()
        var i = 0
        while (i < cleaned.size - 1) {
            val a = cleaned[i]
            val b = cleaned[i + 1]
            val period = measuredLocalPeriod(reference, (a.seconds + b.seconds) / 2)
            if (period != null && b.seconds - a.seconds < period * 0.56) {
                fun cost(remove: Int): Double {
                    val left = cleaned.getOrNull(remove - 1)
                    val right = cleaned.getOrNull(remove + 1)
                    var value = cleaned[remove].strength.coerceIn(0f, 1f) * 0.10
                    if (left != null && right != null) {
                        val multiple = (right.seconds - left.seconds) / period
                        value += abs(multiple - max(1.0, round(multiple)))
                    }
                    return value
                }
                val remove = if (cost(i) < cost(i + 1)) i else i + 1
                val removed = cleaned.removeAt(remove)
                repairs +=
                    PulseRepair(
                        PulseRepairKind.REMOVED_DUPLICATE,
                        removed.seconds,
                        removed.strength.toDouble(),
                        "Sub-pulse interval removed using neighboring cadence and model score",
                    )
                i = max(0, i - 1)
            } else i++
        }
        // Correct a weak phase outlier before filling gaps, so its misplaced timestamp
        // cannot anchor several new pulses. Integer gaps allow missing observations on
        // either side; strong observations are never relocated by this procedure.
        for (index in 1 until cleaned.lastIndex) {
            val left = cleaned[index - 1]
            val current = cleaned[index]
            val right = cleaned[index + 1]
            if (
                current.strength >= 0.65f ||
                    current.strength + 0.08 >= (left.strength + right.strength) / 2
            )
                continue
            val period = localPeriod(reference, current.seconds) ?: continue
            val total = (right.seconds - left.seconds) / period
            if (round(total) !in 2.0..5.0 || abs(total - round(total)) > 0.24) continue
            val provisional = nearest(reference, current.seconds) ?: continue
            val candidate =
                audioCandidate(fallback, provisional.seconds, min(0.065, period * 0.18)) ?: continue
            val move = abs(candidate.seconds - current.seconds)
            if (
                move > period * 0.4 ||
                    move < period * 0.02 ||
                    candidate.seconds <= left.seconds ||
                    candidate.seconds >= right.seconds ||
                    onsetSupport(fallback, candidate.seconds) < 0.30
            )
                continue
            fun phaseCost(time: Double): Double {
                val a = (time - left.seconds) / period
                val b = (right.seconds - time) / period
                return abs(a - max(1.0, round(a))) + abs(b - max(1.0, round(b)))
            }
            val oldCost = phaseCost(current.seconds)
            val newCost = phaseCost(candidate.seconds)
            if (newCost > 0.24 || newCost >= oldCost * 0.5 || oldCost - newCost < 0.15) continue
            cleaned[index] = Beat(candidate.seconds, min(0.5f, current.strength))
            repairs +=
                PulseRepair(
                    PulseRepairKind.RELOCATED_WEAK_OBSERVATION,
                    candidate.seconds,
                    onsetSupport(fallback, candidate.seconds),
                    "Weak phase outlier relocated with independent audio evidence and consistent neighboring pulse counts",
                    current.seconds,
                )
        }
        data class Insert(
            val left: Beat,
            val right: Beat,
            val predicted: Double,
            val candidate: Beat?,
            val kind: PulseRepairKind?,
        )
        val inserts = ArrayList<Insert>()
        for ((left, right) in cleaned.zipWithNext()) {
            val period = localPeriod(reference, (left.seconds + right.seconds) / 2) ?: continue
            val ratio = (right.seconds - left.seconds) / period
            if (ratio < 1.48) continue
            val count = ratio.roundToInt()
            if (count !in 2..4 || abs(ratio - count) > 0.24) {
                reject(
                    "UNSUPPORTED_GAP",
                    left.seconds,
                    right.seconds,
                    "Gap cannot be explained by a small whole number of canonical pulses",
                )
                continue
            }
            for (j in 1 until count) {
                val expected = left.seconds + (right.seconds - left.seconds) * j / count
                val tolerance = min(0.065, period * 0.18)
                val audio = audioCandidate(fallback, expected, tolerance)
                var model: Beat? = null
                if (activationScores != null) {
                    val lo = max(0, ((expected - tolerance) / activationHopSeconds).toInt())
                    val hi =
                        min(
                            activationScores.lastIndex,
                            ceil((expected + tolerance) / activationHopSeconds).toInt(),
                        )
                    val peak =
                        (lo..hi)
                            .filter { frame ->
                                abs(frame * activationHopSeconds - expected) <= tolerance &&
                                    (frame == 0 ||
                                        activationScores[frame] >= activationScores[frame - 1]) &&
                                    (frame == activationScores.lastIndex ||
                                        activationScores[frame] >= activationScores[frame + 1])
                            }
                            .maxByOrNull { frame ->
                                activationScores[frame] *
                                    exp(
                                        -0.5 *
                                            ((frame * activationHopSeconds - expected) /
                                                    (tolerance * 0.6))
                                                .pow(2)
                                    )
                            }
                    if (peak != null && activationScores[peak] >= 0.15f)
                        model = Beat(peak * activationHopSeconds, activationScores[peak])
                }
                val candidate = model ?: audio
                val kind =
                    if (model != null) PulseRepairKind.MODEL_SUPPORTED_INSERTION
                    else if (audio != null) PulseRepairKind.AUDIO_SUPPORTED_INSERTION else null
                inserts += Insert(left, right, expected, candidate, kind)
            }
        }
        val output = cleaned.toMutableList()
        for ((index, insert) in inserts.withIndex()) {
            if (insert.candidate != null) {
                val beat = insert.candidate
                output += beat
                repairs +=
                    PulseRepair(
                        insert.kind!!,
                        beat.seconds,
                        if (insert.kind == PulseRepairKind.MODEL_SUPPORTED_INSERTION)
                            beat.strength.toDouble()
                        else onsetSupport(fallback, beat.seconds),
                        "Missing canonical pulse supported independently near ${insert.predicted}s",
                    )
            } else {
                // One or two quiet implied pulses may be bridged only when directly supported
                // inserted pulses bracket them within three pulse periods on BOTH sides.
                val before = inserts.take(index).lastOrNull { it.candidate != null }
                val after = inserts.drop(index + 1).firstOrNull { it.candidate != null }
                val period = localPeriod(reference, insert.predicted) ?: Double.POSITIVE_INFINITY
                if (
                    before != null &&
                        after != null &&
                        insert.predicted - before.predicted < period * 3.1 &&
                        after.predicted - insert.predicted < period * 3.1
                ) {
                    output += Beat(insert.predicted, 0.15f)
                    repairs +=
                        PulseRepair(
                            PulseRepairKind.CADENCE_INTERPOLATION,
                            insert.predicted,
                            0.15,
                            "Quiet pulse interpolated between nearby independently supported canonical pulses",
                        )
                } else
                    reject(
                        "UNSUPPORTED_INSERTION",
                        insert.left.seconds,
                        insert.right.seconds,
                        "No model peak or independent audio attack confirms the missing pulse",
                    )
            }
        }
        output.sortBy { it.seconds }
        // A weak isolated event may be a syncopation rather than the pulse. Relocation
        // requires both surrounding observations and a separate audio attack to agree.
        for (index in 1 until output.lastIndex) {
            val left = output[index - 1]
            val current = output[index]
            val right = output[index + 1]
            val before = current.seconds - left.seconds
            val after = right.seconds - current.seconds
            if (
                max(before / after, after / before) <= 1.48 ||
                    current.strength >= 0.65f ||
                    current.strength + 0.08 >= (left.strength + right.strength) / 2
            )
                continue
            val period = localPeriod(reference, current.seconds) ?: continue
            if (abs((right.seconds - left.seconds) / period - 2) > 0.24) continue
            val midpoint = (left.seconds + right.seconds) / 2
            val candidate =
                audioCandidate(fallback, midpoint, min(0.065, period * 0.18)) ?: continue
            if (
                abs(candidate.seconds - midpoint) > period * 0.15 ||
                    abs(candidate.seconds - current.seconds) > period * 0.4 ||
                    abs(candidate.seconds - midpoint) >= abs(current.seconds - midpoint) * 0.5 ||
                    onsetSupport(fallback, candidate.seconds) < 0.30
            )
                continue
            output[index] = Beat(candidate.seconds, min(0.5f, candidate.strength))
            repairs +=
                PulseRepair(
                    PulseRepairKind.RELOCATED_WEAK_OBSERVATION,
                    candidate.seconds,
                    onsetSupport(fallback, candidate.seconds),
                    "Weak isolated event relocated to an independently supported attack between neighboring canonical pulses",
                    current.seconds,
                )
        }
        val repairFraction =
            repairs.count { it.kind != PulseRepairKind.REMOVED_DUPLICATE }.toDouble() /
                max(1, output.size)
        if (repairFraction > 0.25)
            reject(
                "EXCESSIVE_REPAIRS",
                0.0,
                fallback.durationSeconds,
                "More than a quarter of the canonical grid would be inferred instead of observed",
            )
        if (repairs.isNotEmpty())
            additional +=
                BeatGridIssue(
                    "REPAIRED_MODEL_PULSES",
                    BeatIssueSeverity.WARNING,
                    repairs.minOf { it.seconds },
                    repairs.maxOf { it.seconds },
                    "${repairs.size} pulse repairs are recorded; timing correctness is not guaranteed by grid geometry",
                )
        val retainedDownbeats =
            downbeatSeconds.filter { t -> output.any { abs(it.seconds - t) < 0.001 } }
        return result(rawBeats, output, retainedDownbeats, repairs, fallback, additional, agreement)
    }

    internal fun localPeriod(beats: List<Beat>, at: Double): Double? {
        if (beats.size < 3) return null
        val centre = lowerBound(beats, at)
        val lo = max(0, centre - 8)
        val hi = min(beats.lastIndex, centre + 8)
        val intervals =
            (lo until hi)
                .map { beats[it + 1].seconds - beats[it].seconds }
                .filter { it > 0 && it.isFinite() }
                .sorted()
        return intervals.getOrNull(intervals.size / 2)
    }

    /** A local cadence is evidence only when two nearby reference observations bracket it. */
    private fun measuredLocalPeriod(beats: List<Beat>, at: Double): Double? {
        val next =
            lowerBound(beats, at).let {
                if (it == 0 && at == beats.first().seconds) 1 else it
            }
        if (next == 0 || next == beats.size) return null
        val period = localPeriod(beats, at) ?: return null
        val measuredGap = beats[next].seconds - beats[next - 1].seconds
        return period.takeIf { measuredGap / it <= 1.48 }
    }

    private fun nearest(beats: List<Beat>, at: Double): Beat? {
        val i = lowerBound(beats, at)
        return listOfNotNull(beats.getOrNull(i - 1), beats.getOrNull(i)).minByOrNull {
            abs(it.seconds - at)
        }
    }

    private fun lowerBound(beats: List<Beat>, at: Double): Int {
        var lo = 0
        var hi = beats.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (beats[mid].seconds < at) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun audioCandidate(analysis: Analysis, expected: Double, tolerance: Double): Beat? {
        val tracked = nearest(analysis.beats, expected)
        // An implied (zero-onset-strength) grid position is not an audio observation.
        // Its DP tie can shift with tiny frontend differences; query actual onset peaks.
        if (
            tracked != null &&
                tracked.strength >= 0.30f &&
                abs(tracked.seconds - expected) <= tolerance &&
                onsetSupport(analysis, tracked.seconds) >= 0.30
        )
            return tracked
        val env = analysis.onsetEnvelope
        val step = analysis.onsetHopSeconds
        val offset = analysis.onsetTimeOffsetSeconds
        if (env.isEmpty() || !step.isFinite() || step <= 0 || !offset.isFinite()) return null
        val lo = max(0, ceil((expected - tolerance - offset) / step).toInt())
        val hi = min(env.lastIndex, floor((expected + tolerance - offset) / step).toInt())
        if (lo > hi) return null
        val peak =
            (lo..hi)
                .filter { frame ->
                    env[frame].isFinite() &&
                        env[frame] >= 0.30f &&
                        (frame == 0 || env[frame] >= env[frame - 1]) &&
                        (frame == env.lastIndex || env[frame] >= env[frame + 1])
                }
                .maxByOrNull { frame ->
                    env[frame] *
                        exp(-0.5 * ((frame * step + offset - expected) / (tolerance * 0.6)).pow(2))
                } ?: return null
        return Beat(peak * step + offset, env[peak].coerceAtMost(1f))
    }

    private fun onsetSupport(analysis: Analysis, time: Double): Double {
        if (
            analysis.onsetEnvelope.isEmpty() ||
                !analysis.onsetHopSeconds.isFinite() ||
                analysis.onsetHopSeconds <= 0 ||
                !analysis.onsetTimeOffsetSeconds.isFinite()
        ) return 0.0
        val lo =
            max(
                0,
                ((time - analysis.onsetTimeOffsetSeconds - 0.045) / analysis.onsetHopSeconds)
                    .toInt(),
            )
        val hi =
            min(
                analysis.onsetEnvelope.lastIndex,
                ceil((time - analysis.onsetTimeOffsetSeconds + 0.025) / analysis.onsetHopSeconds)
                    .toInt(),
            )
        if (lo > hi) return 0.0
        return (lo..hi)
            .maxOf {
                analysis.onsetEnvelope[it].let { value ->
                    if (value.isFinite()) value.toDouble() else 0.0
                }
            }
            .coerceAtLeast(0.0)
    }

    private fun result(
        raw: List<Beat>,
        beats: List<Beat>,
        downbeats: List<Double>,
        repairs: List<PulseRepair>,
        fallback: Analysis,
        additional: List<BeatGridIssue>,
        agreement: Double,
    ): PulseNormalizationResult {
        val report = BeatGridQuality.audit(beats, fallback.beats, fallback.durationSeconds)
        return PulseNormalizationResult(
            raw.toList(),
            beats.toList(),
            downbeats.toList(),
            repairs.toList(),
            report.copy(issues = report.issues + additional),
            agreement,
        )
    }
}
