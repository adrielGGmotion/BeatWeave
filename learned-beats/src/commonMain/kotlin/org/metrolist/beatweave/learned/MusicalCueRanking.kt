package org.metrolist.beatweave.learned

import kotlin.math.ceil
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
)

/** Ranks timing-safe bar ranges using changes in energy and onset activity on the source clock. */
internal class MusicalCueRanking(first: Analysis, second: Analysis) {
    private val outgoing = Timeline(first)
    private val incoming = Timeline(second)

    val available = outgoing.available && incoming.available

    data class Part(val score: Double, val startChange: Double, val endChange: Double, val build: Double)

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
        return Part(score, entry, exit, 0.0)
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
        return Part(score, entry, exit, build)
    }

    fun evidence(out: Part, into: Part, bars: Int): MusicalCueEvidence {
        val length = lengthPreference(bars)
        return MusicalCueEvidence(
            incomingEndLift = into.endChange,
            incomingStartLift = into.startChange,
            outgoingStartRelease = -out.startChange,
            incomingBuild = into.build,
            lengthPreference = length,
            score = out.score + into.score + length,
        )
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
            sourceBlocks.size >= 4 &&
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
            val before = meanDb(time - window, time)
            val after = meanDb(time, time + window)
            val energy = ((after - before) / 6.0).coerceIn(-1.0, 1.0)
            val activity =
                if (meanActivity <= 1e-9) 0.0
                else
                    ((meanOnset(time, time + window) - meanOnset(time - window, time)) /
                            (2.0 * meanActivity))
                        .coerceIn(-1.0, 1.0)
            return (0.8 * energy + 0.2 * activity).coerceIn(-1.0, 1.0)
        }

        private fun meanDb(from: Double, to: Double): Double {
            val start = from.coerceIn(0.0, duration)
            val end = to.coerceIn(0.0, duration)
            if (end <= start || blocks.isEmpty()) return median
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
            return if (covered > 0) weighted / covered else median
        }

        private fun meanOnset(from: Double, to: Double): Double {
            if (envelope.isEmpty() || analysis.onsetHopSeconds <= 0) return 0.0
            fun frame(time: Double) =
                ceil((time - analysis.onsetTimeOffsetSeconds) / analysis.onsetHopSeconds)
                    .toInt()
                    .coerceIn(0, envelope.size)
            val start = frame(max(0.0, from))
            val end = frame(min(duration, to))
            return if (end > start) (prefix[end] - prefix[start]) / (end - start) else 0.0
        }
    }
}
