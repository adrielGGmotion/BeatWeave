package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.metrolist.beatweave.Analysis
import org.metrolist.beatweave.BarGrid
import org.metrolist.beatweave.EnergyBlock

/** Measured dynamics behind an automatic cue choice. These are relative scores, not section labels. */
data class MusicalCueEvidence(
    val incomingEndLift: Double,
    val incomingStartLift: Double,
    val outgoingStartRelease: Double,
    val incomingBuild: Double,
    val lengthPreference: Double,
    val score: Double,
    /** Agreement of global, approximate song features; zero when evidence is inconclusive. */
    val longBlendAffinity: Double = 0.0,
    /** Similarity of measured levels at corresponding positions within this overlap. */
    val overlapLevelBalance: Double = 0.0,
)

/** Ranks timing-safe bar ranges using changes in energy and onset activity on the source clock. */
internal class MusicalCueRanking(
    first: Analysis,
    second: Analysis,
    cancellationCheck: () -> Unit = {},
) {
    private val outgoing = Timeline(first, cancellationCheck)
    private val incoming = Timeline(second, cancellationCheck)

    val available = outgoing.available && incoming.available
    private val longBlendAffinity = affinity(first, second)

    data class Part(
        val score: Double,
        val startChange: Double,
        val endChange: Double,
        val build: Double,
        val from: Double,
        val to: Double,
    )

    fun outgoing(grid: BarGrid, start: Int, bars: Int): Part {
        val from = grid.beats.at(grid.boundary(start))
        val to = grid.beats.at(grid.boundary(start + bars))
        val window = max(4.0, (to - from) / bars * 1.5)
        val entry = outgoing.change(from, window)
        val exit = outgoing.change(to, window)
        // Endpoint contexts overlap when a fade is shorter than two windows. Discount their
        // shared fraction so one dynamics event cannot count as two independent boundaries.
        val independentBoundaries = ((to - from) / (2.0 * window)).coerceIn(0.0, 1.0)
        val position = from / outgoing.duration
        // A sharp mid-song break should not routinely discard the rest of an outgoing track.
        val late = ((position - 0.70) / 0.20).coerceIn(-1.0, 1.0)
        val score =
            independentBoundaries *
                (1.2 * max(0.0, -entry) + 0.45 * max(0.0, -exit)) +
                1.1 * late + 0.15 * outgoing.quiet(to)
        return Part(score, entry, exit, 0.0, from, to)
    }

    fun incoming(grid: BarGrid, start: Int, bars: Int): Part {
        val from = grid.beats.at(grid.boundary(start))
        val to = grid.beats.at(grid.boundary(start + bars))
        val window = max(4.0, (to - from) / bars * 1.5)
        val entry = incoming.change(from, window)
        val exit = incoming.change(to, window)
        val independentBoundaries = ((to - from) / (2.0 * window)).coerceIn(0.0, 1.0)
        val startLevel = incoming.measuredLevel(from)
        val endLevel = incoming.measuredLevel(to)
        val build =
            if (startLevel != null && endLevel != null)
                ((endLevel - startLevel) / 8.0).coerceIn(-1.0, 1.0)
            else 0.0
        val position = from / incoming.duration
        val early = ((0.45 - position) / 0.4).coerceIn(-1.0, 1.0)
        val score =
            independentBoundaries *
                (2.0 * max(0.0, exit) + 0.65 * max(0.0, entry)) +
                0.45 * build + 0.25 * incoming.quiet(from) + 0.25 * early
        return Part(score, entry, exit, build, from, to)
    }

    fun evidence(out: Part, into: Part, bars: Int): MusicalCueEvidence {
        var completeLevelEvidence = available
        var mismatchSum = 0.0
        if (completeLevelEvidence) {
            for (i in 0 until 8) {
                val position = (i + 0.5) / 8.0
                val outgoingLevel =
                    outgoing.measuredLevel(out.from + (out.to - out.from) * position)
                val incomingLevel =
                    incoming.measuredLevel(into.from + (into.to - into.from) * position)
                if (outgoingLevel == null || incomingLevel == null) {
                    completeLevelEvidence = false
                    break
                }
                mismatchSum += abs(outgoingLevel - incomingLevel)
            }
        }
        val averageMismatchDb =
            if (completeLevelEvidence) mismatchSum / 8.0 else MISSING_LEVEL_MISMATCH_DB
        val balance =
            if (completeLevelEvidence)
                (1.0 - averageMismatchDb / MISSING_LEVEL_MISMATCH_DB).coerceIn(0.0, 1.0)
            else 0.0
        // An extended blend can carry two similar arrangements through several phrases. A short
        // fade benefits more from a decisive entry/exit lift. Avoid rewarding a long fade into a
        // section that is audibly winding down, even if the two levels happen to match.
        val incomingHolds = into.endChange >= -0.02 && into.build >= -0.20
        val longBonus =
            if (incomingHolds)
                when (bars) {
                    8 -> 1.0
                    16 -> 5.5
                    32 -> 3.0
                    else -> 0.0
                } * longBlendAffinity * balance
            else 0.0
        val length = lengthPreference(bars) + longBonus
        // Balance is one at equal levels and reaches zero at a six-decibel average mismatch.
        // Small arrangement differences are useful cue evidence, not a reason to reject a lift.
        // Beyond two decibels, however, a large lift previously won even when the selected songs
        // would hand over at conspicuously different levels. Keep this a ranking cost rather than
        // an acceptance gate: callers still receive the best timing-safe choice available.
        val levelPenalty =
            max(0.0, averageMismatchDb - UNPENALIZED_LEVEL_MISMATCH_DB) *
                LEVEL_MISMATCH_SCORE_PER_DB
        return MusicalCueEvidence(
            incomingEndLift = into.endChange,
            incomingStartLift = into.startChange,
            outgoingStartRelease = -out.startChange,
            incomingBuild = into.build,
            lengthPreference = length,
            score = out.score + into.score + length - levelPenalty,
            longBlendAffinity = longBlendAffinity,
            overlapLevelBalance = balance,
        )
    }

    private fun affinity(first: Analysis, second: Analysis): Double {
        if (!available || first.keyEstimate == "unknown" ||
            !harmonicallyCompatibleKeys(first.keyEstimate, second.keyEstimate) ||
            !first.keyConfidence.isFinite() || !second.keyConfidence.isFinite() ||
            min(first.keyConfidence, second.keyConfidence) < 0.12 ||
            !first.bpm.isFinite() || !second.bpm.isFinite() ||
            first.bpm <= 0 || second.bpm <= 0 ||
            !first.spectralCentroidHz.isFinite() || !second.spectralCentroidHz.isFinite() ||
            first.spectralCentroidHz <= 0 || second.spectralCentroidHz <= 0 ||
            !first.rmsDb.isFinite() || !second.rmsDb.isFinite()
        ) return 0.0
        val tempo = (1.0 - abs(ln(first.bpm / second.bpm)) / ln(1.04)).coerceIn(0.0, 1.0)
        val timbre =
            (1.0 - abs(ln(first.spectralCentroidHz / second.spectralCentroidHz)) / ln(1.25))
                .coerceIn(0.0, 1.0)
        val loudness = (1.0 - abs(first.rmsDb - second.rmsDb) / 6.0).coerceIn(0.0, 1.0)
        // Global chroma labels are fallible. Require independent tempo, timbre and loudness
        // agreement before they can influence the fade length.
        if (tempo < 0.8 || timbre < 0.5 || loudness < 0.5) return 0.0
        val key = (min(first.keyConfidence, second.keyConfidence) / 0.16).coerceIn(0.0, 1.0)
        return 0.30 * key + 0.25 * tempo + 0.25 * timbre + 0.20 * loudness
    }

    private data class TonalKey(val pitchClass: Int, val minor: Boolean)

    private fun harmonicallyCompatibleKeys(first: String, second: String): Boolean {
        val a = parseKey(first) ?: return false
        val b = parseKey(second) ?: return false
        val interval = (a.pitchClass - b.pitchClass + 12) % 12
        if (a.minor == b.minor) return interval == 0 || interval == 5 || interval == 7
        val major = if (a.minor) b else a
        val minor = if (a.minor) a else b
        return (minor.pitchClass - major.pitchClass + 12) % 12 == 9
    }

    private fun parseKey(value: String): TonalKey? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val tonic = parts[0].replace('♯', '#').replace('♭', 'b')
        if (tonic.length !in 1..2) return null
        var pitchClass =
            when (tonic[0].uppercaseChar()) {
                'C' -> 0
                'D' -> 2
                'E' -> 4
                'F' -> 5
                'G' -> 7
                'A' -> 9
                'B' -> 11
                else -> return null
            }
        if (tonic.length == 2) {
            pitchClass +=
                when (tonic[1]) {
                    '#' -> 1
                    'b' -> -1
                    else -> return null
                }
        }
        val minor =
            when (parts[1].lowercase()) {
                "major" -> false
                "minor" -> true
                else -> return null
            }
        return TonalKey((pitchClass + 12) % 12, minor)
    }

    companion object {
        private const val UNPENALIZED_LEVEL_MISMATCH_DB = 2.0
        private const val LEVEL_MISMATCH_SCORE_PER_DB = 0.5

        fun lengthPreference(bars: Int): Double =
            when (bars) {
                8 -> 0.65
                4 -> 0.42
                16 -> 0.15
                2 -> -0.40
                else -> -0.25
            }
    }

    private class Timeline(
        private val analysis: Analysis,
        private val cancellationCheck: () -> Unit,
    ) {
        init {
            cancellationCheck()
        }

        val duration = analysis.durationSeconds
        private val sourceBlocks = analysis.energyBlocks
        val available =
            duration.isFinite() && duration > 0.0 && sourceBlocks.size >= 4 &&
                validBlocks(sourceBlocks)
        private val blocks = if (available) sourceBlocks else emptyList()
        private val median =
            blocks.map { it.rmsDb }.sorted().let {
                cancellationCheck()
                it.getOrNull(it.size / 2) ?: 0.0
            }
        private val energyPrefixes = energyPrefixes(blocks)
        private val weightedEnergy = energyPrefixes.first
        private val coveredSeconds = energyPrefixes.second
        private val onset = prepareOnset()
        private val envelope = onset.first
        private val prefix = onset.second
        private val meanActivity =
            if (envelope.isEmpty() || !duration.isFinite() || duration <= 0.0) 0.0
            else measuredOnset(0.0, duration).mean

        private fun validBlocks(values: List<EnergyBlock>): Boolean {
            for (i in values.indices) {
                if (i % CANCELLATION_INTERVAL == 0) cancellationCheck()
                val block = values[i]
                if (
                    !block.startSeconds.isFinite() || !block.endSeconds.isFinite() ||
                        !block.rmsDb.isFinite() || block.endSeconds <= block.startSeconds ||
                        block.startSeconds < 0.0 || block.endSeconds > duration ||
                        i > 0 && block.startSeconds < values[i - 1].endSeconds
                ) return false
            }
            cancellationCheck()
            return true
        }

        private fun energyPrefixes(values: List<EnergyBlock>): Pair<DoubleArray, DoubleArray> {
            val weighted = DoubleArray(values.size + 1)
            val covered = DoubleArray(values.size + 1)
            for (i in values.indices) {
                if (i % CANCELLATION_INTERVAL == 0) cancellationCheck()
                val duration = values[i].endSeconds - values[i].startSeconds
                weighted[i + 1] = weighted[i] + values[i].rmsDb * duration
                covered[i + 1] = covered[i] + duration
            }
            cancellationCheck()
            return weighted to covered
        }

        private fun prepareOnset(): Pair<FloatArray, DoubleArray> {
            if (
                !analysis.onsetHopSeconds.isFinite() || analysis.onsetHopSeconds <= 0.0 ||
                    !analysis.onsetTimeOffsetSeconds.isFinite()
            ) return FloatArray(0) to DoubleArray(1)
            val values = analysis.onsetEnvelope
            val sums = DoubleArray(values.size + 1)
            for (i in values.indices) {
                if (i % CANCELLATION_INTERVAL == 0) cancellationCheck()
                val value = values[i]
                if (!value.isFinite()) return FloatArray(0) to DoubleArray(1)
                sums[i + 1] = sums[i] + max(0.0, value.toDouble())
            }
            cancellationCheck()
            return values to sums
        }

        fun measuredLevel(time: Double): Double? =
            measuredDb(time - LEVEL_HALF_WINDOW_SECONDS, time + LEVEL_HALF_WINDOW_SECONDS).let {
                if (
                    it.coveredSeconds >=
                        2.0 * LEVEL_HALF_WINDOW_SECONDS * MINIMUM_CHANGE_CONTEXT_FRACTION
                ) it.db
                else null
            }

        fun quiet(time: Double): Double =
            measuredLevel(time)?.let { ((median - it) / 8.0).coerceIn(-1.0, 1.0) } ?: 0.0

        fun change(time: Double, window: Double): Double {
            val before = measuredDb(time - window, time)
            val after = measuredDb(time, time + window)
            // Near a recording boundary (or a feature gap), the missing side previously fell
            // back to the song-wide median. That manufactures a lift/release from absent audio.
            // Require substantial measured context on both sides before claiming a change.
            if (
                before.coveredSeconds < window * MINIMUM_CHANGE_CONTEXT_FRACTION ||
                    after.coveredSeconds < window * MINIMUM_CHANGE_CONTEXT_FRACTION
            ) return 0.0
            val energy = ((after.db - before.db) / 6.0).coerceIn(-1.0, 1.0)
            val activityBefore = measuredOnset(time - window, time)
            val activityAfter = measuredOnset(time, time + window)
            val activity =
                if (
                    meanActivity <= 1e-9 ||
                        activityBefore.coveredSeconds < window * MINIMUM_CHANGE_CONTEXT_FRACTION ||
                        activityAfter.coveredSeconds < window * MINIMUM_CHANGE_CONTEXT_FRACTION
                ) 0.0
                else
                    ((activityAfter.mean - activityBefore.mean) /
                            (2.0 * meanActivity))
                        .coerceIn(-1.0, 1.0)
            return (0.8 * energy + 0.2 * activity).coerceIn(-1.0, 1.0)
        }

        private data class MeasuredLevel(val db: Double, val coveredSeconds: Double)

        private fun measuredDb(from: Double, to: Double): MeasuredLevel {
            val start = from.coerceIn(0.0, duration)
            val end = to.coerceIn(0.0, duration)
            if (end <= start || blocks.isEmpty()) return MeasuredLevel(median, 0.0)
            fun area(time: Double, prefix: DoubleArray, weighted: Boolean): Double {
                var low = 0
                var high = blocks.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (blocks[mid].startSeconds <= time) low = mid + 1 else high = mid
                }
                val index = low - 1
                if (index < 0) return 0.0
                val block = blocks[index]
                val partial = (min(time, block.endSeconds) - block.startSeconds).coerceAtLeast(0.0)
                return prefix[index] + partial * (if (weighted) block.rmsDb else 1.0)
            }
            val weighted = area(end, weightedEnergy, true) - area(start, weightedEnergy, true)
            val covered = area(end, coveredSeconds, false) - area(start, coveredSeconds, false)
            return MeasuredLevel(if (covered > 0) weighted / covered else median, covered)
        }

        private data class MeasuredActivity(val mean: Double, val coveredSeconds: Double)

        private fun measuredOnset(from: Double, to: Double): MeasuredActivity {
            if (envelope.isEmpty() || analysis.onsetHopSeconds <= 0)
                return MeasuredActivity(0.0, 0.0)
            val hop = analysis.onsetHopSeconds
            val offset = analysis.onsetTimeOffsetSeconds
            val start = max(max(0.0, from), offset)
            val end = min(min(duration, to), offset + envelope.size * hop)
            if (end <= start) return MeasuredActivity(0.0, 0.0)

            val first =
                floor((start - offset) / hop).toInt().coerceIn(0, envelope.lastIndex)
            val last =
                (ceil((end - offset) / hop).toInt() - 1).coerceIn(first, envelope.lastIndex)
            val weighted =
                if (first == last) {
                    max(0.0, envelope[first].toDouble()) * (end - start)
                } else {
                    val firstEnd = offset + (first + 1) * hop
                    val lastStart = offset + last * hop
                    val middle = (prefix[last] - prefix[first + 1]) * hop
                    max(0.0, envelope[first].toDouble()) * (firstEnd - start) +
                        middle +
                        max(0.0, envelope[last].toDouble()) * (end - lastStart)
                }
            val covered = end - start
            return MeasuredActivity(weighted / covered, covered)
        }
    }
}

private const val MINIMUM_CHANGE_CONTEXT_FRACTION = 0.5
private const val LEVEL_HALF_WINDOW_SECONDS = 2.0
private const val MISSING_LEVEL_MISMATCH_DB = 6.0
private const val CANCELLATION_INTERVAL = 4096
