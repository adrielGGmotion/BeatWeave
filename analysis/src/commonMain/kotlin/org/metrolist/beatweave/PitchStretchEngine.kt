package org.metrolist.beatweave

import kotlin.math.*

/** One exact frame correspondence. Output frames are local to the prepared stem. */
data class WarpAnchor(val sourceFrame: Long, val outputFrame: Long)

/**
 * Monotone, sample-quantized correspondence for an offline, pitch-preserving stretcher.
 * [outputOriginFrame] places the prepared stem on the mix's global timeline; it may be negative
 * when the selected incoming cue is later than the outgoing cue. Engines process the whole source
 * once, then serve arbitrary ranges of that result.
 */
class WarpSchedule
private constructor(
    val sampleRate: Int,
    val sourceFrames: Long,
    val outputOriginFrame: Long,
    val outputFrames: Long,
    anchors: List<WarpAnchor>,
) {
    private val anchorSnapshot = anchors.toList()
    val anchors: List<WarpAnchor>
        get() = anchorSnapshot.toList()

    val isTranslationOnly: Boolean =
        sourceFrames == outputFrames && anchorSnapshot.all { it.sourceFrame == it.outputFrame }

    init {
        require(sourceFrames > 0 && outputFrames > 0)
        require(anchorSnapshot.first() == WarpAnchor(0, 0))
        require(anchorSnapshot.last() == WarpAnchor(sourceFrames, outputFrames))
        require(
            anchorSnapshot.zipWithNext().all { (a, b) ->
                b.sourceFrame > a.sourceFrame && b.outputFrame > a.outputFrame
            }
        ) {
            "Warp anchors must advance on both clocks"
        }
    }

    companion object {
        /** Beat anchors are exact; extra knots follow continuous tempo changes between them. */
        fun from(plan: MixPlan, sourceDurationSeconds: Double): WarpSchedule {
            require(
                sourceDurationSeconds.isFinite() &&
                    sourceDurationSeconds > 0.0 &&
                    sourceDurationSeconds <= 4 * 60 * 60
            ) {
                "Source duration must be in (0, 4 hours]"
            }
            val rate = plan.outputSampleRate
            val sourceFrames = ceil(sourceDurationSeconds * rate).toLong()
            val sourceEnd = sourceFrames.toDouble() / rate
            val origin = plan.secondOutputTime(0.0)
            val end = plan.secondOutputTime(sourceEnd)
            require(
                origin.isFinite() &&
                    end.isFinite() &&
                    end > origin &&
                    abs(origin) <= 4 * 60 * 60 &&
                    end - origin <= 4 * 60 * 60
            ) {
                "Warped duration or cue offset exceeds four hours"
            }
            val originFrame = (origin * rate).roundToLong()
            // Rounding on the global clock avoids cue-position-dependent one-frame drift.
            val endFrame = (end * rate).roundToLong()
            val outputFrames = endFrame - originFrame
            val candidates = mutableListOf(Pair(0.0, origin), Pair(sourceEnd, end))
            // At every A beat boundary the interpolation crosses its exact paired B beat.
            val startBeat = floor(plan.first.position(origin)).toInt()
            val endBeat = ceil(plan.first.position(end)).toInt()
            require(endBeat.toLong() - startBeat <= 1_000_000) { "Unreasonable beat density" }
            for (beat in startBeat..endBeat) {
                val out = plan.first.at(beat)
                if (out > origin && out < end) {
                    val src = plan.secondSourceTime(out)
                    if (src > 0.0 && src < sourceEnd) candidates += Pair(src, out)
                }
            }
            // Subdivide curved clock segments only when a straight map would miss by
            // more than one millisecond. Depth limits bound memory for corrupt grids.
            val exactKnots = candidates.sortedBy { it.first }
            candidates.clear()
            fun subdivide(s0: Double, t0: Double, s1: Double, t1: Double, depth: Int) {
                if (depth >= 12 || (s1 - s0) * rate < 4) return
                var error = 0.0
                for (fraction in doubleArrayOf(0.25, 0.5, 0.75)) {
                    val s = s0 + (s1 - s0) * fraction
                    val t = plan.secondOutputTime(s)
                    error = max(error, abs(t - (t0 + (t1 - t0) * fraction)) * rate)
                }
                if (error <= rate * 0.001) return
                val sm = (s0 + s1) / 2
                val tm = plan.secondOutputTime(sm)
                subdivide(s0, t0, sm, tm, depth + 1)
                candidates += Pair(sm, tm)
                subdivide(sm, tm, s1, t1, depth + 1)
            }
            for (i in 0 until exactKnots.lastIndex) {
                val (s0, t0) = exactKnots[i]
                val (s1, t1) = exactKnots[i + 1]
                candidates += exactKnots[i]
                subdivide(s0, t0, s1, t1, 0)
            }
            candidates += exactKnots.last()
            val anchors = ArrayList<WarpAnchor>(candidates.size)
            anchors += WarpAnchor(0, 0)
            for ((src, out) in candidates.sortedBy { it.first }) {
                val sourceFrame = (src * rate).roundToLong()
                val targetFrame = (out * rate).roundToLong() - originFrame
                val last = anchors.last()
                if (
                    sourceFrame > last.sourceFrame &&
                        sourceFrame < sourceFrames &&
                        targetFrame > last.outputFrame &&
                        targetFrame < outputFrames
                ) {
                    anchors += WarpAnchor(sourceFrame, targetFrame)
                }
            }
            anchors += WarpAnchor(sourceFrames, outputFrames)
            return WarpSchedule(rate, sourceFrames, originFrame, outputFrames, anchors)
        }
    }
}

/** Owned random-access result. close() must be idempotent and release any cache files. */
interface PreparedStereoPcm : StereoPcm {
    fun close()
}

/**
 * A platform engine with stereo-linked, pitch-preserving offline processing. Implementations may
 * spill to disk; never hold entire long recordings on the heap. The progress callback may throw
 * (e.g. cancellation); implementations must then close native handles, streams and temporary files.
 * Returned PCM has exactly schedule.outputFrames.
 */
interface PitchStretchEngine {
    fun prepare(
        source: StereoPcm,
        schedule: WarpSchedule,
        progress: (Double) -> Unit = {},
    ): PreparedStereoPcm
}

class MissingPitchStretchEngineException :
    IllegalStateException(
        "Pitch-preserving beat matching requires an installed PitchStretchEngine. " +
            "Pass RubberBandEngine (optional GPL module) or another verified engine to BeatMixer."
    )

class MixCancelledException : IllegalStateException("Mix operation cancelled")
