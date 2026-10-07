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
    // The private factories transfer an owned list; callers only receive defensive snapshots.
    private val anchorSnapshot = anchors
    val anchors: List<WarpAnchor>
        get() = anchorSnapshot.toList()
    internal val anchorCount: Int
        get() = anchorSnapshot.size

    internal fun anchorAt(index: Int): WarpAnchor = anchorSnapshot[index]

    val isTranslationOnly: Boolean =
        sourceFrames == outputFrames && anchorSnapshot.all { it.sourceFrame == it.outputFrame }

    init {
        require(sourceFrames > 0 && outputFrames > 0)
        require(anchorSnapshot.first() == WarpAnchor(0, 0))
        require(anchorSnapshot.last() == WarpAnchor(sourceFrames, outputFrames))
        for (index in 1 until anchorSnapshot.size) {
            val previous = anchorSnapshot[index - 1]
            val current = anchorSnapshot[index]
            require(
                current.sourceFrame > previous.sourceFrame &&
                    current.outputFrame > previous.outputFrame
            ) {
                "Warp anchors must advance on both clocks"
            }
        }
    }

    companion object {
        /** Unchanged duration and timeline, for pitch-only processing at the given PCM rate. */
        fun identity(sourceFrames: Long, sampleRate: Int): WarpSchedule {
            require(sampleRate in 8000..192000) { "Sample rate must be in 8000..192000 Hz" }
            require(sourceFrames in 1L..sampleRate.toLong() * 4 * 60 * 60) {
                "Source length must be in (0, 4 hours]"
            }
            return WarpSchedule(
                sampleRate, sourceFrames, 0L, sourceFrames,
                listOf(WarpAnchor(0L, 0L), WarpAnchor(sourceFrames, sourceFrames)),
            )
        }

        fun identity(sourceDurationSeconds: Double, sampleRate: Int): WarpSchedule {
            require(sourceDurationSeconds.isFinite() && sourceDurationSeconds > 0.0 &&
                sourceDurationSeconds <= 4 * 60 * 60) { "Source duration must be in (0, 4 hours]" }
            return identity(sourceFrameCount(sourceDurationSeconds, sampleRate), sampleRate)
        }

        /** Beat anchors are exact; extra knots follow continuous tempo changes between them. */
        fun from(plan: MixPlan, sourceDurationSeconds: Double): WarpSchedule =
            from(plan, sourceDurationSeconds) {}

        /** Internal cancellable path for potentially dense, long-running schedules. */
        internal fun from(
            plan: MixPlan,
            sourceDurationSeconds: Double,
            cancellationCheck: () -> Unit,
        ): WarpSchedule {
            require(
                sourceDurationSeconds.isFinite() &&
                    sourceDurationSeconds > 0.0 &&
                    sourceDurationSeconds <= 4 * 60 * 60
            ) {
                "Source duration must be in (0, 4 hours]"
            }
            cancellationCheck()
            var workSinceCancellationCheck = 0
            fun checkCancellationPeriodically() {
                workSinceCancellationCheck++
                if (workSinceCancellationCheck >= 4096) {
                    cancellationCheck()
                    workSinceCancellationCheck = 0
                }
            }
            val rate = plan.outputSampleRate
            val sourceFrames = sourceFrameCount(sourceDurationSeconds, rate)
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
            // At every A beat boundary the interpolation crosses its exact paired B beat.
            val startBeat = floor(plan.first.position(origin)).toInt()
            val endBeat = ceil(plan.first.position(end)).toInt()
            require(endBeat.toLong() - startBeat <= 1_000_000) { "Unreasonable beat density" }
            // Exact knots are generated in output-clock order. The source clock is monotone too,
            // so stream each section instead of boxing and sorting a second full knot list.
            val anchors =
                ArrayList<WarpAnchor>((endBeat.toLong() - startBeat + 3).toInt())
            anchors += WarpAnchor(0, 0)
            fun addCandidate(src: Double, out: Double) {
                checkCancellationPeriodically()
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
            // Subdivide curved clock segments only when a straight map would miss by
            // more than one millisecond. Depth limits bound memory for corrupt grids.
            fun subdivide(s0: Double, t0: Double, s1: Double, t1: Double, depth: Int) {
                checkCancellationPeriodically()
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
                addCandidate(sm, tm)
                subdivide(sm, tm, s1, t1, depth + 1)
            }
            var previousSource = 0.0
            var previousOutput = origin
            fun addExact(source: Double, output: Double) {
                addCandidate(previousSource, previousOutput)
                subdivide(previousSource, previousOutput, source, output, 0)
                previousSource = source
                previousOutput = output
            }
            for (beat in startBeat..endBeat) {
                checkCancellationPeriodically()
                val output = plan.first.at(beat)
                if (output > origin && output < end) {
                    val source = plan.secondSourceTimeAtFirstBeat(beat)
                    if (source > 0.0 && source < sourceEnd) addExact(source, output)
                }
            }
            addExact(sourceEnd, end)
            addCandidate(previousSource, previousOutput)
            anchors += WarpAnchor(sourceFrames, outputFrames)
            cancellationCheck()
            return WarpSchedule(rate, sourceFrames, originFrame, outputFrames, anchors)
        }
    }
}

/** A duration obtained from N/rate must not acquire a phantom frame on multiplication. */
private fun sourceFrameCount(durationSeconds: Double, sampleRate: Int): Long {
    val frames = durationSeconds * sampleRate
    val nearest = frames.roundToLong()
    // Below one millionth of a sample, within round-trip error at the supported four-hour limit.
    return if (abs(frames - nearest) <= 1e-6) nearest else ceil(frames).toLong()
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
