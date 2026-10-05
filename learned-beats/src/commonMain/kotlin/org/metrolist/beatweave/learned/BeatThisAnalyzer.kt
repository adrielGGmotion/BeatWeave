package org.metrolist.beatweave.learned

import kotlin.math.*
import org.metrolist.beatweave.Analysis
import org.metrolist.beatweave.Beat
import org.metrolist.beatweave.MusicAnalyzer

/** Implementable on any KMP target; shipped ORT adapter supports JVM and Android. */
interface BeatThisBackend {
    val modelId: String

    fun infer(logMel: FloatArray, frames: Int): BeatThisLogits
}

/** Optional capability for backends that can interrupt an inference already in progress. */
interface CancellableBeatThisBackend : BeatThisBackend {
    fun infer(
        logMel: FloatArray,
        frames: Int,
        cancellationCheck: () -> Unit,
    ): BeatThisLogits
}

data class BeatThisLogits(val beat: FloatArray, val downbeat: FloatArray) {
    init {
        require(beat.size == downbeat.size)
    }
}

/** Strength is a model sigmoid score, not a calibrated probability of rhythmic correctness. */
data class LearnedBeatAnalysis(
    val durationSeconds: Double,
    val beats: List<Beat>,
    val downbeatSeconds: List<Double>,
    val modelId: String,
    val logits: BeatThisLogits,
) {
    fun toAnalysis(mono22050: FloatArray): Analysis {
        require(abs(mono22050.size / 22050.0 - durationSeconds) < 1.0 / 22050) {
            "Audio does not match the analyzed duration"
        }
        return MusicAnalyzer().analyzeWithBeats(mono22050, 22050, beats, downbeatSeconds, modelId)
    }
}

/**
 * Offline beat/downbeat analysis with exactly the upstream 1500-frame, 6-frame-border, keep-first
 * overlap convention. Call on a worker thread. No model download or network occurs. Cancellation
 * may throw from cancellationCheck during preprocessing and between chunks. The shipped ORT
 * backend also polls it while a model chunk is running and terminates that run on cancellation.
 */
class BeatThisAnalyzer(
    private val backend: BeatThisBackend,
    private val frontend: BeatThisFrontend = BeatThisFrontend(),
) {
    fun analyze(
        mono22050: FloatArray,
        cancellationCheck: () -> Unit = {},
        onProgress: (Double) -> Unit = {},
    ): LearnedBeatAnalysis {
        require(mono22050.size >= 22050 * 3) { "At least 3 seconds of 22050 Hz mono PCM required" }
        cancellationCheck()
        onProgress(0.0)
        val spectrogram = frontend.transform(mono22050, cancellationCheck)
        onProgress(0.25)
        val logits = infer(spectrogram, cancellationCheck) { onProgress(0.25 + 0.75 * it) }
        val (beats, downbeats) = postprocess(logits, cancellationCheck)
        // Reflect padding may produce a final frame at the exact audio end. It is not playable.
        val duration = mono22050.size / 22050.0
        return LearnedBeatAnalysis(
            duration,
            beats.filter { it.seconds < duration },
            downbeats.filter { it < duration },
            backend.modelId,
            logits,
        )
    }

    fun infer(
        spectrogram: MelSpectrogram,
        cancellationCheck: () -> Unit = {},
        onProgress: (Double) -> Unit = {},
    ): BeatThisLogits {
        val border = 6
        val chunkSize = 1500
        val starts = ArrayList<Int>()
        var s = -border
        while (s < spectrogram.frames - border) {
            starts.add(s)
            s += chunkSize - 2 * border
        }
        if (spectrogram.frames > chunkSize - 2 * border)
            starts[starts.lastIndex] = spectrogram.frames - (chunkSize - border)
        val beat = FloatArray(spectrogram.frames) { -1000f }
        val downbeat = FloatArray(spectrogram.frames) { -1000f }
        val assigned = BooleanArray(spectrogram.frames)
        for ((chunkIndex, start) in starts.withIndex()) {
            cancellationCheck()
            val sourceStart = max(0, start)
            val sourceEnd = min(start + chunkSize, spectrogram.frames)
            val leftPad = max(0, -start)
            val rightPad = max(0, min(border, start + chunkSize - spectrogram.frames))
            val frames = sourceEnd - sourceStart + leftPad + rightPad
            val chunk = FloatArray(frames * BeatThisFrontend.BINS)
            spectrogram.values.copyInto(
                chunk,
                leftPad * BeatThisFrontend.BINS,
                sourceStart * BeatThisFrontend.BINS,
                sourceEnd * BeatThisFrontend.BINS,
            )
            val output =
                if (backend is CancellableBeatThisBackend) {
                    backend.infer(chunk, frames, cancellationCheck)
                } else {
                    backend.infer(chunk, frames)
                }
            require(output.beat.size == frames) {
                "Model returned ${output.beat.size} frames for $frames inputs"
            }
            require(output.beat.all { it.isFinite() } && output.downbeat.all { it.isFinite() }) {
                "Model returned non-finite logits"
            }
            for (local in border until frames - border) {
                val target = start + local
                if (target in beat.indices && !assigned[target]) {
                    beat[target] = output.beat[local]
                    downbeat[target] = output.downbeat[local]
                    assigned[target] = true
                }
            }
            cancellationCheck()
            onProgress((chunkIndex + 1).toDouble() / starts.size)
        }
        check(assigned.all { it }) { "Internal error: incomplete inference coverage" }
        return BeatThisLogits(beat, downbeat)
    }

    companion object {
        /**
         * Upstream minimal postprocessing: maxima +/-3 frames, logit >0, adjacent plateau
         * deduplication, then each detected downbeat attaches to its nearest observed beat. No BPM
         * preference, straight-line grid, meter assumption, or fabricated beats. A downbeat is
         * attached only when an observed beat lies within the model's +/-3-frame tolerance;
         * distant peaks remain unsupported rather than being moved onto an unrelated pulse.
         */
        fun postprocess(logits: BeatThisLogits): Pair<List<Beat>, List<Double>> =
            postprocess(logits) {}

        fun postprocess(
            logits: BeatThisLogits,
            cancellationCheck: () -> Unit,
        ): Pair<List<Beat>, List<Double>> {
            cancellationCheck()
            for (i in logits.beat.indices) {
                if (i % POSTPROCESS_CANCELLATION_INTERVAL == 0) cancellationCheck()
                require(logits.beat[i].isFinite() && logits.downbeat[i].isFinite())
            }
            fun peaks(values: FloatArray): List<Double> {
                val selected = ArrayList<Int>()
                for (i in values.indices) {
                    if (i % POSTPROCESS_CANCELLATION_INTERVAL == 0) cancellationCheck()
                    if (values[i] <= 0f) continue
                    var maximum = true
                    for (j in max(0, i - 3)..min(values.lastIndex, i + 3)) {
                        if (values[j] > values[i]) {
                            maximum = false
                            break
                        }
                    }
                    if (maximum) selected.add(i)
                }
                if (selected.isEmpty()) return emptyList()
                val result = ArrayList<Double>()
                var p = selected.first().toDouble()
                var count = 1
                var previous = selected.first()
                for (p2 in selected.drop(1)) {
                    if (p2 - previous <= 1) {
                        count++
                        p += (p2 - p) / count
                    } else {
                        result.add(p)
                        p = p2.toDouble()
                        count = 1
                    }
                    previous = p2
                }
                result.add(p)
                return result
            }
            val beatFrames = peaks(logits.beat)
            val beats =
                beatFrames.map { frame ->
                    val score = 1.0 / (1.0 + exp(-logits.beat[frame.roundToInt()].toDouble()))
                    Beat(frame / 50.0, score.toFloat())
                }
            if (beats.isEmpty()) return beats to emptyList()
            val downbeatFrames = peaks(logits.downbeat)
            var nearestBeat = 0
            val snappedDownbeats = ArrayList<Double>(downbeatFrames.size)
            for ((downbeatIndex, frame) in downbeatFrames.withIndex()) {
                if (downbeatIndex % POSTPROCESS_CANCELLATION_INTERVAL == 0) cancellationCheck()
                while (nearestBeat < beatFrames.lastIndex) {
                    val currentDistance = abs(beatFrames[nearestBeat] - frame)
                    val nextDistance = abs(beatFrames[nearestBeat + 1] - frame)
                    // minBy retained the earlier beat on an exact tie; preserve that behavior.
                    if (nextDistance >= currentDistance) break
                    nearestBeat++
                    if (nearestBeat % POSTPROCESS_CANCELLATION_INTERVAL == 0)
                        cancellationCheck()
                }
                beatFrames[nearestBeat].takeIf {
                    abs(it - frame) <= MAX_DOWNBEAT_BEAT_DISTANCE_FRAMES
                }?.let(snappedDownbeats::add)
            }
            val downbeats =
                snappedDownbeats
                    .map { it / 50.0 }
                    .distinct()
                    .sorted()
            cancellationCheck()
            return beats to downbeats
        }
    }
}

private const val MAX_DOWNBEAT_BEAT_DISTANCE_FRAMES = 3.0
private const val POSTPROCESS_CANCELLATION_INTERVAL = 1024
