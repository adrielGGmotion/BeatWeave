package org.metrolist.beatweave.learned

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.metrolist.beatweave.Analysis
import org.metrolist.beatweave.BarGrid

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
internal class MusicalCueRanking(first: Analysis, second: Analysis) {
    private val outgoing = Timeline(first)
    private val incoming = Timeline(second)

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
        val position = from / outgoing.duration
        // A sharp mid-song break should not routinely discard the rest of an outgoing track.
        val late = ((position - 0.70) / 0.20).coerceIn(-1.0, 1.0)
        val score =
            1.2 * max(0.0, -entry) + 0.45 * max(0.0, -exit) +
                1.1 * late + 0.15 * outgoing.quiet(to)
        return Part(score, entry, exit, 0.0, from, to)
    }

    fun incoming(grid: BarGrid, start: Int, bars: Int): Part {
        val from = grid.beats.at(grid.boundary(start))
        val to = grid.beats.at(grid.boundary(start + bars))
        val window = max(4.0, (to - from) / bars * 1.5)
        val entry = incoming.change(from, window)
        val exit = incoming.change(to, window)
        val build = ((incoming.level(to) - incoming.level(from)) / 8.0).coerceIn(-1.0, 1.0)
        val position = from / incoming.duration
        val early = ((0.45 - position) / 0.4).coerceIn(-1.0, 1.0)
        val score =
            2.0 * max(0.0, exit) + 0.65 * max(0.0, entry) +
                0.45 * build + 0.25 * incoming.quiet(from) + 0.25 * early
        return Part(score, entry, exit, build, from, to)
    }

    fun evidence(out: Part, into: Part, bars: Int): MusicalCueEvidence {
        val balance =
            if (available) {
                val difference =
                    (0 until 8).sumOf { i ->
                        val position = (i + 0.5) / 8.0
                        abs(
                            outgoing.level(out.from + (out.to - out.from) * position) -
                                incoming.level(into.from + (into.to - into.from) * position)
                        )
                    } / 8.0
                (1.0 - difference / 6.0).coerceIn(0.0, 1.0)
            } else 0.0
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
        return MusicalCueEvidence(
            incomingEndLift = into.endChange,
            incomingStartLift = into.startChange,
            outgoingStartRelease = -out.startChange,
            incomingBuild = into.build,
            lengthPreference = length,
            score = out.score + into.score + length,
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
        fun lengthPreference(bars: Int): Double =
            when (bars) {
                8 -> 0.65
                4 -> 0.42
                16 -> 0.15
                2 -> -0.40
                else -> -0.25
            }
    }

    private class Timeline(private val analysis: Analysis) {
        val duration = analysis.durationSeconds
        private val sourceBlocks = analysis.energyBlocks
        val available =
            duration.isFinite() && duration > 0.0 && sourceBlocks.size >= 4 &&
                sourceBlocks.all {
                    it.startSeconds.isFinite() && it.endSeconds.isFinite() &&
                        it.rmsDb.isFinite() && it.endSeconds > it.startSeconds
                } &&
                sourceBlocks.zipWithNext().all { (a, b) -> b.startSeconds >= a.endSeconds }
        private val blocks = if (available) sourceBlocks else emptyList()
        private val median = blocks.map { it.rmsDb }.sorted().let { it.getOrNull(it.size / 2) ?: 0.0 }
        private val weightedEnergy = DoubleArray(blocks.size + 1).also { sums ->
            for (i in blocks.indices)
                sums[i + 1] = sums[i] + blocks[i].rmsDb * (blocks[i].endSeconds - blocks[i].startSeconds)
        }
        private val coveredSeconds = DoubleArray(blocks.size + 1).also { sums ->
            for (i in blocks.indices)
                sums[i + 1] = sums[i] + blocks[i].endSeconds - blocks[i].startSeconds
        }
        private val envelope =
            if (
                analysis.onsetHopSeconds.isFinite() && analysis.onsetHopSeconds > 0.0 &&
                    analysis.onsetTimeOffsetSeconds.isFinite() &&
                    analysis.onsetEnvelope.all { it.isFinite() }
            ) analysis.onsetEnvelope else FloatArray(0)
        private val prefix = DoubleArray(envelope.size + 1).also { sums ->
            for (i in envelope.indices) sums[i + 1] = sums[i] + max(0.0, envelope[i].toDouble())
        }
        private val meanActivity = if (envelope.isEmpty()) 0.0 else prefix.last() / envelope.size

        fun level(time: Double): Double = meanDb(time - 2.0, time + 2.0)

        fun quiet(time: Double): Double = ((median - level(time)) / 8.0).coerceIn(-1.0, 1.0)

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

        private fun meanDb(from: Double, to: Double): Double =
            measuredDb(from, to).let { if (it.coveredSeconds > 0.0) it.db else median }

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
            fun frame(time: Double) =
                ceil((time - analysis.onsetTimeOffsetSeconds) / analysis.onsetHopSeconds)
                    .toInt()
                    .coerceIn(0, envelope.size)
            val start = frame(max(0.0, from))
            val end = frame(min(duration, to))
            return if (end > start)
                MeasuredActivity(
                    (prefix[end] - prefix[start]) / (end - start),
                    min(to - from, (end - start) * analysis.onsetHopSeconds),
                )
            else MeasuredActivity(0.0, 0.0)
        }
    }
}

private const val MINIMUM_CHANGE_CONTEXT_FRACTION = 0.5
