package org.metrolist.beatweave

import kotlin.math.*

/** Corrected musical grid; beat 0 is a known beat, not necessarily a downbeat. */
class BeatGrid(times: DoubleArray) {
    private val beatTimes = times.copyOf()
    /** A defensive snapshot. Mutating it cannot invalidate an existing plan. */
    val times: DoubleArray
        get() = beatTimes.copyOf()

    val size: Int
        get() = beatTimes.size

    init {
        require(beatTimes.size >= 4) { "At least four beat anchors are required" }
        require(
            beatTimes.all { it.isFinite() } &&
                (1 until beatTimes.size).all {
                    val interval = beatTimes[it] - beatTimes[it - 1]
                    interval.isFinite() && interval > 0.0
                }
        ) {
            "Beat anchors must be finite and strictly increasing"
        }
    }

    /**
     * Fit a local beat clock to reduce one-off onset jitter without flattening gradual tempo
     * changes.
     */
    fun smoothed(radius: Int = 4): BeatGrid {
        require(radius in 1..16)
        val result = DoubleArray(beatTimes.size)
        for (i in beatTimes.indices) {
            val left = max(0, i - radius)
            val right = min(beatTimes.lastIndex, i + radius)
            val middle = (left + right) / 2.0
            var mean = 0.0
            var numerator = 0.0
            var denominator = 0.0
            for (j in left..right) mean += beatTimes[j]
            mean /= right - left + 1
            for (j in left..right) {
                numerator += (j - middle) * (beatTimes[j] - mean)
                denominator += (j - middle) * (j - middle)
            }
            result[i] = mean + (i - middle) * numerator / denominator
        }
        return BeatGrid(result)
    }

    fun at(beat: Int): Double {
        if (beat < 0) return beatTimes[0] + beat * (beatTimes[1] - beatTimes[0])
        if (beat >= beatTimes.size)
            return beatTimes.last() +
                (beat - beatTimes.lastIndex) *
                    (beatTimes.last() - beatTimes[beatTimes.lastIndex - 1])
        return beatTimes[beat]
    }

    /** Fractional beat coordinate, including extrapolation into the outro. */
    fun position(seconds: Double): Double {
        require(seconds.isFinite()) { "Time must be finite" }
        if (seconds < beatTimes[0]) return (seconds - beatTimes[0]) / (beatTimes[1] - beatTimes[0])
        if (seconds >= beatTimes.last())
            return beatTimes.lastIndex +
                (seconds - beatTimes.last()) /
                    (beatTimes.last() - beatTimes[beatTimes.lastIndex - 1])
        var lo = 0
        var hi = beatTimes.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (beatTimes[mid] <= seconds) lo = mid else hi = mid
        }
        return lo + (seconds - beatTimes[lo]) / (beatTimes[hi] - beatTimes[lo])
    }

    fun at(position: Double): Double {
        require(
            position.isFinite() && position > Int.MIN_VALUE + 1.0 && position < Int.MAX_VALUE - 1.0
        ) {
            "Beat position is outside the supported range"
        }
        val left = floor(position).toInt()
        return at(left) + (position - left) * (at(left + 1) - at(left))
    }

    companion object {
        fun from(
            analysis: Analysis,
            offsetSeconds: Double = 0.0,
            fixedBpm: Double? = null,
        ): BeatGrid {
            require(offsetSeconds.isFinite())
            require(fixedBpm == null || fixedBpm in 40.0..240.0)
            val beats = analysis.beats.map { it.seconds }
            require(beats.size >= 4) { "Beat tracking was inconclusive; choose another recording." }
            val times =
                if (fixedBpm != null)
                    DoubleArray(beats.size) { beats[0] + offsetSeconds + it * 60.0 / fixedBpm }
                else DoubleArray(beats.size) { beats[it] + offsetSeconds }
            return BeatGrid(times)
        }
    }
}

/**
 * Read interleaved stereo PCM, resampled at [outputSampleRate]. Every read returns exactly frames *
 * 2 finite values and zero-pads outside the audio. Reads must be deterministic and independent;
 * ownership stays with the caller. A production decoder should use a band-limited resampler when
 * rates differ.
 */
interface StereoPcm {
    val durationSeconds: Double

    fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray

    /** Override for exact integer-frame access when the source or cache supports it. */
    fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray =
        read(startFrame.toDouble() / outputSampleRate, frames, outputSampleRate)
}

interface PcmSink {
    fun write(interleavedStereo: FloatArray, frames: Int)
}

enum class MixMode {
    TRANSITION,
    OVERLAP,
}

/**
 * Complete paired beat intervals supported by observations in both grids. Beyond these endpoints
 * the clock continues at its endpoint speed; it does not claim to have found additional beats. A
 * release may end matching earlier.
 */
data class ObservedBeatCoverage(
    val firstStartBeat: Int,
    val firstEndBeat: Int,
    val outputStartSeconds: Double,
    val outputEndSeconds: Double,
    val sourceStartSeconds: Double,
    val sourceEndSeconds: Double,
)

data class MixPlan(
    val first: BeatGrid,
    val second: BeatGrid,
    val firstBeat: Int,
    val secondBeat: Int = 0,
    val crossfadeBeats: Int = 16,
    val outputSampleRate: Int = 44100,
    val firstBeatsPerCycle: Int = 1,
    val secondBeatsPerCycle: Int = 1,
    val releaseAfterFade: Boolean = false,
) {
    init {
        require(firstBeat in 0 until first.size)
        require(secondBeat in 0 until second.size)
        require(crossfadeBeats in 1..512)
        require(outputSampleRate in 22050..96000)
        require(firstBeatsPerCycle in 1..16 && secondBeatsPerCycle in 1..16)
    }

    val startSeconds: Double
        get() = first.at(firstBeat)

    val fadeEndSeconds: Double
        get() = first.at(firstBeat + crossfadeBeats)

    private val observedStartBeat =
        max(
            0,
            ceil(firstBeat - secondBeat.toDouble() * firstBeatsPerCycle / secondBeatsPerCycle)
                .toInt(),
        )
    private val observedEndBeat =
        min(
            first.size - 1,
            floor(
                    firstBeat +
                        (second.size - 1 - secondBeat).toDouble() * firstBeatsPerCycle /
                            secondBeatsPerCycle
                )
                .toInt(),
        )

    init {
        require(observedEndBeat > observedStartBeat) {
            "Matching needs at least one complete interval observed in both beat grids"
        }
    }

    val observedCoverage: ObservedBeatCoverage by lazy {
        ObservedBeatCoverage(
            observedStartBeat,
            observedEndBeat,
            first.at(observedStartBeat),
            first.at(observedEndBeat),
            pairedSource(observedStartBeat),
            pairedSource(observedEndBeat),
        )
    }
    private val releaseTime = 2.0
    private val releaseSourceSeconds by lazy { onGrid(fadeEndSeconds) }
    private val releaseSpeed by lazy { slopeAt(firstBeat + crossfadeBeats) }

    private fun pairedSource(beat: Int) =
        second.at(
            secondBeat + (beat - firstBeat).toDouble() * secondBeatsPerCycle / firstBeatsPerCycle
        )

    private fun secant(beat: Int): Double =
        (pairedSource(beat + 1) - pairedSource(beat)) / (first.at(beat + 1) - first.at(beat))

    /** Positive harmonic mean of neighbouring speeds: continuous at each matched beat. */
    private fun slopeAt(beat: Int): Double {
        // An extrapolated beat is not new tempo evidence. At an endpoint use
        // the final observed secant on both sides, including the continuation.
        val before = secant((beat - 1).coerceIn(observedStartBeat, observedEndBeat - 1))
        val after = secant(beat.coerceIn(observedStartBeat, observedEndBeat - 1))
        require(before.isFinite() && after.isFinite() && before > 0 && after > 0) {
            "Beat grids produce an invalid playback speed"
        }
        // Reciprocal form avoids overflowing the product of two finite speeds.
        return 2.0 / (1.0 / before + 1.0 / after)
    }

    private fun onGrid(outputSeconds: Double): Double {
        val coverage = observedCoverage
        if (outputSeconds <= coverage.outputStartSeconds)
            return coverage.sourceStartSeconds +
                (outputSeconds - coverage.outputStartSeconds) * slopeAt(observedStartBeat)
        if (outputSeconds >= coverage.outputEndSeconds)
            return coverage.sourceEndSeconds +
                (outputSeconds - coverage.outputEndSeconds) * slopeAt(observedEndBeat)
        val position = first.position(outputSeconds)
        require(
            position.isFinite() && position > Int.MIN_VALUE + 2.0 && position < Int.MAX_VALUE - 2.0
        ) {
            "Output time exceeds the supported beat coordinate range"
        }
        val beat = floor(position).toInt()
        val t0 = first.at(beat)
        val length = first.at(beat + 1) - t0
        val u = ((outputSeconds - t0) / length).coerceIn(0.0, 1.0)
        val s0 = pairedSource(beat)
        val s1 = pairedSource(beat + 1)
        val m0 = slopeAt(beat) * length
        val m1 = slopeAt(beat + 1) * length
        val square = u * u
        val cube = square * u
        return (2 * cube - 3 * square + 1) * s0 +
            (cube - 2 * square + u) * m0 +
            (-2 * cube + 3 * square) * s1 +
            (cube - square) * m1
    }

    /** Maps corresponding *cycles* (e.g. three A pulses to two B pulses). */
    fun secondSourceTime(outputSeconds: Double): Double {
        require(outputSeconds.isFinite()) { "Output time must be finite" }
        if (!releaseAfterFade || outputSeconds <= fadeEndSeconds) return onGrid(outputSeconds)
        val dt = outputSeconds - fadeEndSeconds
        val c = releaseSpeed - 1.0
        val extra =
            if (dt >= releaseTime) c * releaseTime / 3
            else c * (dt - dt * dt / releaseTime + dt * dt * dt / (3 * releaseTime * releaseTime))
        return releaseSourceSeconds + dt + extra
    }

    /** Analytic derivatives of the same playback clock, without subtracting nearby source times. */
    internal fun secondClockRates(outputSeconds: Double): Pair<Double, Double> {
        require(outputSeconds.isFinite()) { "Output time must be finite" }
        if (releaseAfterFade && outputSeconds > fadeEndSeconds) {
            val dt = outputSeconds - fadeEndSeconds
            if (dt >= releaseTime) return 1.0 to 0.0
            val remaining = 1.0 - dt / releaseTime
            val c = releaseSpeed - 1.0
            return (1.0 + c * remaining * remaining) to (-2.0 * c * remaining / releaseTime)
        }
        val coverage = observedCoverage
        if (outputSeconds <= coverage.outputStartSeconds) return slopeAt(observedStartBeat) to 0.0
        if (outputSeconds >= coverage.outputEndSeconds) return slopeAt(observedEndBeat) to 0.0
        val beat = floor(first.position(outputSeconds)).toInt()
        val t0 = first.at(beat)
        val length = first.at(beat + 1) - t0
        val u = ((outputSeconds - t0) / length).coerceIn(0.0, 1.0)
        val m0 = slopeAt(beat)
        val m1 = slopeAt(beat + 1)
        val d = secant(beat)
        // Express the Hermite coefficients in rates rather than absolute times:
        // an identity clock stays exactly constant even at a late source cue.
        val quadratic = 3.0 * d - 2.0 * m0 - m1
        val cubic = m0 + m1 - 2.0 * d
        return (m0 + 2.0 * quadratic * u + 3.0 * cubic * u * u) to
            ((2.0 * quadratic + 6.0 * cubic * u) / length)
    }

    fun secondOutputTime(sourceSeconds: Double): Double {
        require(sourceSeconds.isFinite()) { "Source time must be finite" }
        if (releaseAfterFade && sourceSeconds > releaseSourceSeconds) {
            var lo = 0.0
            var hi = max(releaseTime, sourceSeconds - releaseSourceSeconds + releaseTime)
            repeat(36) {
                val mid = (lo + hi) / 2
                if (secondSourceTime(fadeEndSeconds + mid) < sourceSeconds) lo = mid else hi = mid
            }
            return fadeEndSeconds + (lo + hi) / 2
        }
        val coverage = observedCoverage
        if (sourceSeconds <= coverage.sourceStartSeconds)
            return coverage.outputStartSeconds +
                (sourceSeconds - coverage.sourceStartSeconds) / slopeAt(observedStartBeat)
        if (sourceSeconds >= coverage.sourceEndSeconds)
            return coverage.outputEndSeconds +
                (sourceSeconds - coverage.sourceEndSeconds) / slopeAt(observedEndBeat)
        val approximateBeat =
            (second.position(sourceSeconds) - secondBeat) * firstBeatsPerCycle /
                secondBeatsPerCycle + firstBeat
        require(
            approximateBeat.isFinite() &&
                approximateBeat > Int.MIN_VALUE + 2.0 &&
                approximateBeat < Int.MAX_VALUE - 2.0
        ) {
            "Source time exceeds the supported beat coordinate range"
        }
        val beat = floor(approximateBeat).toInt()
        var lo = first.at(beat)
        var hi = first.at(beat + 1)
        repeat(32) {
            val mid = (lo + hi) / 2
            if (onGrid(mid) < sourceSeconds) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}

/**
 * Prepares the incoming stem once through an explicit production stretcher. The common library
 * deliberately has no lower-quality phase-vocoder fallback. Source PCM is borrowed, never closed by
 * the mixer; prepared stems are owned. Source duration and content must remain stable until the
 * prepared session closes.
 */
class BeatMixer(private val engine: PitchStretchEngine? = null) {
    fun prepare(
        first: StereoPcm,
        second: StereoPcm,
        plan: MixPlan,
        isCancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {},
    ): PreparedMix = prepare(first, second, plan, PitchShift.None, isCancelled, progress)

    /** Shift the incoming stem in the same processing pass as its beat-clock warp. */
    fun prepare(
        first: StereoPcm,
        second: StereoPcm,
        plan: MixPlan,
        incomingPitchShift: PitchShift,
        isCancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {},
    ): PreparedMix {
        require(
            first.durationSeconds.isFinite() &&
                first.durationSeconds > 0 &&
                first.durationSeconds <= 4 * 60 * 60
        )
        if (isCancelled()) throw MixCancelledException()
        val schedule = WarpSchedule.from(plan, second.durationSeconds)
        val reportProgress: (Double) -> Unit = {
            if (isCancelled()) throw MixCancelledException()
            progress(it.coerceIn(0.0, 1.0))
        }
        val prepared =
            if (schedule.isTranslationOnly && incomingPitchShift.isIdentity)
                object : PreparedStereoPcm {
                    override val durationSeconds =
                        schedule.outputFrames.toDouble() / schedule.sampleRate

                    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                        second.read(startSeconds, frames, outputSampleRate)

                    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int) =
                        second.readFrames(startFrame, frames, outputSampleRate)

                    override fun close() = Unit // The caller owns the original source.
                }
            else if (!incomingPitchShift.isIdentity)
                (engine as? PitchShiftEngine ?: throw MissingPitchShiftEngineException())
                    .prepare(second, schedule, incomingPitchShift, reportProgress)
            else
                (engine ?: throw MissingPitchStretchEngineException())
                    .prepare(second, schedule, reportProgress)
        try {
            require(
                prepared.durationSeconds.isFinite() &&
                    abs(prepared.durationSeconds * schedule.sampleRate - schedule.outputFrames) <
                        0.51
            ) {
                "Stretcher returned an incorrect duration"
            }
            if (isCancelled()) throw MixCancelledException()
            progress(1.0)
            return PreparedMix(first, prepared, plan, schedule, isCancelled)
        } catch (failure: Throwable) {
            closeAfterFailure(failure) { prepared.close() }
            throw failure
        }
    }

    fun render(
        first: StereoPcm,
        second: StereoPcm,
        plan: MixPlan,
        sink: PcmSink,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) = render(first, second, plan, PitchShift.None, sink, mode, progress)

    fun render(
        first: StereoPcm,
        second: StereoPcm,
        plan: MixPlan,
        incomingPitchShift: PitchShift,
        sink: PcmSink,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) {
        val prepared =
            prepare(first, second, plan, incomingPitchShift, progress = { progress(it * 0.9) })
        prepared.use {
            prepared.render(sink, mode) { progress(0.9 + it * 0.1) }
        }
    }

    /** Convenience for one range; repeated playback should reuse prepare()'s result. */
    fun renderRange(
        first: StereoPcm,
        second: StereoPcm,
        plan: MixPlan,
        sink: PcmSink,
        fromSeconds: Double,
        toSeconds: Double,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) =
        renderRange(
            first, second, plan, PitchShift.None, sink, fromSeconds, toSeconds, mode, progress,
        )

    fun renderRange(
        first: StereoPcm,
        second: StereoPcm,
        plan: MixPlan,
        incomingPitchShift: PitchShift,
        sink: PcmSink,
        fromSeconds: Double,
        toSeconds: Double,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) {
        requireRange(fromSeconds, toSeconds)
        val prepared =
            prepare(first, second, plan, incomingPitchShift, progress = { progress(it * 0.9) })
        prepared.use {
            prepared.renderRange(sink, fromSeconds, toSeconds, mode) { progress(0.9 + it * 0.1) }
        }
    }
}

/**
 * Reusable, deterministic mix session. All playback, seeks and exports read the same prepared PCM.
 * Not thread-safe: serialize reads and close(), or use separate sessions.
 */
class PreparedMix
internal constructor(
    private val first: StereoPcm,
    private val second: PreparedStereoPcm,
    val plan: MixPlan,
    val schedule: WarpSchedule,
    private val isCancelled: () -> Boolean,
) {
    private var closed = false
    private val rate = plan.outputSampleRate

    /**
     * Global frame of the complete mix's first sample. An overlap can begin before the outgoing
     * song's zero when the incoming cue is later in its file. Add this offset to a complete
     * export's file frame to recover global time.
     */
    fun startFrame(mode: MixMode = MixMode.TRANSITION): Long {
        checkOpen()
        return if (mode == MixMode.OVERLAP) min(0L, schedule.outputOriginFrame) else 0L
    }

    fun endFrame(mode: MixMode = MixMode.TRANSITION): Long {
        checkOpen()
        val incomingEnd = schedule.outputOriginFrame + schedule.outputFrames
        // After A's fade ends its remaining PCM is inaudible, so do not append its tail.
        val outgoingEnd =
            if (mode == MixMode.OVERLAP) ceil(first.durationSeconds * rate).toLong()
            else
                min(
                    ceil(first.durationSeconds * rate).toLong(),
                    ceil(plan.fadeEndSeconds * rate).toLong(),
                )
        return max(0L, max(outgoingEnd, incomingEnd))
    }

    /** Number of frames written by [renderComplete], including overlap pre-roll. */
    fun frameCount(mode: MixMode = MixMode.TRANSITION): Long = endFrame(mode) - startFrame(mode)

    /** Legacy timeline export beginning at outgoing frame zero. */
    fun render(sink: PcmSink, mode: MixMode = MixMode.TRANSITION, progress: (Double) -> Unit = {}) =
        renderFrames(sink, 0, endFrame(mode), mode, progress)

    /**
     * Exports the entire selected mix. For OVERLAP this preserves incoming audio before global
     * zero; file frame zero corresponds to [startFrame]. TRANSITION retains its cue-controlled,
     * zero-origin playback semantics.
     */
    fun renderComplete(
        sink: PcmSink,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) = renderTimelineFrames(sink, startFrame(mode), endFrame(mode), mode, progress)

    fun renderRange(
        sink: PcmSink,
        fromSeconds: Double,
        toSeconds: Double,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) {
        requireRange(fromSeconds, toSeconds)
        renderFrames(
            sink,
            floor(fromSeconds * rate).toLong(),
            ceil(toSeconds * rate).toLong(),
            mode,
            progress,
        )
    }

    /** Half-open frame interval; exact frame counts are suitable for WAV headers. */
    fun renderFrames(
        sink: PcmSink,
        fromFrame: Long,
        toFrame: Long,
        mode: MixMode = MixMode.TRANSITION,
        progress: (Double) -> Unit = {},
    ) {
        checkOpen()
        require(fromFrame >= 0) { "Frame-range rendering starts at or after outgoing frame zero" }
        renderTimelineFrames(sink, fromFrame, toFrame, mode, progress)
    }

    private fun renderTimelineFrames(
        sink: PcmSink,
        fromFrame: Long,
        toFrame: Long,
        mode: MixMode,
        progress: (Double) -> Unit,
    ) {
        checkOpen()
        require(
            fromFrame >= MIN_MIX_SECONDS.toLong() * rate &&
                toFrame > fromFrame &&
                toFrame <= MAX_MIX_SECONDS.toLong() * rate
        )
        val total = toFrame - fromFrame
        var written = 0L
        progress(0.0)
        while (written < total) {
            checkOpen()
            if (isCancelled()) throw MixCancelledException()
            val global = fromFrame + written
            val frames = min(1024L, total - written).toInt()
            val output = checkedRead(first, global, frames, rate)
            val incoming =
                if (
                    mode == MixMode.OVERLAP ||
                        (global + frames).toDouble() / rate > plan.startSeconds
                )
                    readIncoming(global, frames)
                else FloatArray(frames * 2)
            for (i in 0 until frames) {
                val t = (global + i).toDouble() / rate
                val fade =
                    ((t - plan.startSeconds) / (plan.fadeEndSeconds - plan.startSeconds)).coerceIn(
                        0.0,
                        1.0,
                    )
                // Equal-power curve with enough headroom for correlated normalized tracks.
                val gainA = if (mode == MixMode.OVERLAP) 0.46 else cos(fade * PI / 2) / sqrt(2.0)
                val gainB = if (mode == MixMode.OVERLAP) 0.46 else sin(fade * PI / 2) / sqrt(2.0)
                output[2 * i] = (output[2 * i] * gainA + incoming[2 * i] * gainB).toFloat()
                output[2 * i + 1] =
                    (output[2 * i + 1] * gainA + incoming[2 * i + 1] * gainB).toFloat()
            }
            sink.write(output, frames)
            written += frames
            progress(written.toDouble() / total)
        }
    }

    /** Read only the aligned incoming stem, with no crossfade or gain applied. */
    fun readIncoming(outputFrame: Long, frames: Int): FloatArray {
        checkOpen()
        require(frames in 0..65536)
        require(outputFrame in (MIN_MIX_SECONDS.toLong() * rate)..(MAX_MIX_SECONDS.toLong() * rate)) {
            "Incoming read is outside the supported timeline"
        }
        if (isCancelled()) throw MixCancelledException()
        return checkedRead(second, outputFrame - schedule.outputOriginFrame, frames, rate)
    }

    fun close() {
        if (!closed) {
            closed = true
            second.close()
        }
    }

    private fun checkOpen() = check(!closed) { "Prepared mix is closed" }
}

// A four-hour prepared stem may begin up to four hours after outgoing frame zero.
private const val MIN_MIX_SECONDS = -4 * 60 * 60
private const val MAX_MIX_SECONDS = 8 * 60 * 60

private fun requireRange(fromSeconds: Double, toSeconds: Double) {
    require(
        fromSeconds.isFinite() &&
            toSeconds.isFinite() &&
            fromSeconds >= 0 &&
            toSeconds > fromSeconds &&
            toSeconds <= MAX_MIX_SECONDS
    ) {
        "Invalid mix time range"
    }
}

private fun checkedRead(source: StereoPcm, start: Long, frames: Int, rate: Int): FloatArray {
    val result = source.readFrames(start, frames, rate)
    require(result.size == frames * 2) { "StereoPcm.read must return exactly frames * 2 samples" }
    require(result.all { it.isFinite() }) { "StereoPcm returned a non-finite sample" }
    return result
}

private inline fun PreparedMix.use(block: () -> Unit) {
    try {
        block()
    } catch (failure: Throwable) {
        closeAfterFailure(failure) { close() }
        throw failure
    }
    close()
}

/** Cleanup must not replace a decoder, sink or cancellation error. */
private inline fun closeAfterFailure(failure: Throwable, close: () -> Unit) {
    try {
        close()
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
}
