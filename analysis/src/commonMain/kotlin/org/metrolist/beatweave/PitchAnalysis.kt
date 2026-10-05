package org.metrolist.beatweave

import kotlin.math.*

/** Settings for local, monophonic fundamental-frequency analysis. */
data class PitchAnalysisOptions(
    /** Estimates within one cent of a frequency-range endpoint are clamped to that endpoint. */
    val minimumFrequencyHz: Double = 50.0,
    /** The analysis sample rate must provide at least five samples per period at this frequency. */
    val maximumFrequencyHz: Double = 2000.0,
    val hopSeconds: Double = 0.02,
    /** Expanded when necessary to contain at least four periods of the lowest frequency. */
    val windowSeconds: Double = 0.08,
    /** Maximum YIN normalized difference; lower values require stronger periodicity. */
    val periodicityThreshold: Double = 0.15,
    val silenceThresholdDb: Double = -60.0,
) {
    init {
        require(minimumFrequencyHz.isFinite() && minimumFrequencyHz in 20.0..10000.0)
        require(maximumFrequencyHz.isFinite() && maximumFrequencyHz > minimumFrequencyHz)
        require(hopSeconds.isFinite() && hopSeconds in 0.005..1.0)
        require(windowSeconds.isFinite() && windowSeconds in 0.01..0.5)
        require(periodicityThreshold.isFinite() && periodicityThreshold in 0.01..0.5)
        require(silenceThresholdDb.isFinite() && silenceThresholdDb in -160.0..0.0)
    }
}

/**
 * One window's pitch evidence, timestamped at its center on the source audio clock.
 * [confidence] measures periodicity, not the probability that a musical note is correct.
 * Null [frequencyHz] denotes silence, aperiodic audio, or a pitch outside the requested range.
 */
data class PitchFrame(
    val seconds: Double,
    val frequencyHz: Double?,
    val confidence: Double,
    val rmsDb: Double,
) {
    val isVoiced: Boolean
        get() = frequencyHz != null

    /** Nearest equal-tempered MIDI note, using A4 = 440 Hz; null for unvoiced frames. */
    val midiNote: Int?
        get() = frequencyHz?.let { (69.0 + 12.0 * log2(it / 440.0)).roundToInt() }

    /** Scientific pitch notation, e.g. A4 or C#3; null for unvoiced frames. */
    val noteName: String?
        get() = midiNote?.let {
            val pitchClass = ((it % 12) + 12) % 12
            val octave = (it - pitchClass) / 12 - 1
            "${PITCH_NOTE_NAMES[pitchClass]}$octave"
        }

    /** Signed cents relative to [midiNote], approximately in [-50, 50]. */
    val cents: Double?
        get() = frequencyHz?.let { 1200.0 * log2(it / 440.0) - 100.0 * (midiNote!! - 69) }
}

/** Pitch windows cover only complete source windows; no padded endpoints are treated as evidence. */
data class PitchAnalysis(
    val durationSeconds: Double,
    val frames: List<PitchFrame>,
    val hopSeconds: Double,
    val windowSeconds: Double,
)

/**
 * FFT-accelerated YIN fundamental-frequency estimation for isolated voices and instruments.
 * This is a monophonic periodicity detector, not melody extraction or polyphonic transcription.
 * Chords, percussion, strong harmonics and full mixes can produce ambiguous or octave-shifted
 * estimates; use [PitchFrame.confidence] as evidence rather than musical correctness.
 *
 * Scratch memory depends on sample rate and window length, not track length. Stereo decoding adds
 * a bounded sequential cache of at most 2 MiB. Results use one frame per hop. Inputs are limited to
 * four hours and one million result frames. The analyzer retains no caller PCM, source, callback or
 * native resource after a call. Each invocation has its own buffers.
 */
class PitchAnalyzer(private val options: PitchAnalysisOptions = PitchAnalysisOptions()) {
    /**
     * Analyze finite mono PCM without changing or resampling it. Requires one complete window,
     * a sample rate of 4000..384000 Hz, and at least five samples per maximum-frequency period.
     */
    fun analyze(
        pcm: FloatArray,
        sampleRate: Int,
        cancellationCheck: () -> Unit = {},
    ): PitchAnalysis {
        cancellationCheck()
        val layout = layout(sampleRate, pcm.size.toLong())
        for (i in pcm.indices) {
            if (i % 8192 == 0) cancellationCheck()
            require(pcm[i].isFinite()) { "PCM must contain only finite samples" }
        }
        return analyzeWindows(layout, sampleRate, pcm.size.toDouble() / sampleRate, cancellationCheck) {
            start, window ->
            for (i in window.indices) window[i] = pcm[(start + i).toInt()].toDouble()
        }
    }

    /**
     * Analyze a deterministic stereo decoder/cache at [analysisSampleRate]. The source must use
     * a band-limited resampler when rates differ, as required by [StereoPcm]. For each window the
     * channel with greater energy after DC removal is analyzed independently: averaging
     * antiphase stereo can erase a voice, while DC bias must not determine channel selection.
     * After the first window, the other channel must exceed the selected channel by one decibel
     * before selection changes. This prevents tiny stereo-balance differences from alternating
     * between panned instruments; a decisive level change can still select the other channel.
     * This is not stereo source separation.
     * No decoder or source ownership is transferred.
     */
    fun analyze(
        source: StereoPcm,
        analysisSampleRate: Int = 11025,
        cancellationCheck: () -> Unit = {},
    ): PitchAnalysis {
        cancellationCheck()
        val duration = source.durationSeconds
        require(duration.isFinite() && duration > 0.0 && duration <= MAX_PITCH_DURATION_SECONDS) {
            "Source duration must be in (0, 4 hours]"
        }
        val durationFrames = duration * analysisSampleRate
        val nearestFrame = round(durationFrames)
        // A duration computed from an integer frame count can round just below that count.
        // Snap only within four floating-point rounding units; genuine fractional tails remain
        // excluded so a padded endpoint never becomes evidence.
        val sampleCount =
            if (abs(durationFrames - nearestFrame) <= 4.0 * 2.220446049250313e-16 * max(1.0, abs(durationFrames)))
                nearestFrame.toLong()
            else floor(durationFrames).toLong()
        val layout = layout(analysisSampleRate, sampleCount)
        // Default windows overlap by roughly 75%. Decode bounded sequential chunks so a long
        // source is not reread and reallocated once per hop while retaining per-window channels.
        val cacheCapacity =
            max(
                layout.window,
                min(analysisSampleRate.toLong() * 2L, MAX_PITCH_WINDOW_FRAMES.toLong()).toInt(),
            )
        var cacheStart = -1L
        var cacheFrames = 0
        var stereoCache = FloatArray(0)
        var selectedChannel = -1
        return analyzeWindows(layout, analysisSampleRate, duration, cancellationCheck) { start, window ->
            if (
                cacheStart < 0L ||
                    start < cacheStart ||
                    start + window.size > cacheStart + cacheFrames
            ) {
                cacheStart = start
                cacheFrames = min(sampleCount - start, cacheCapacity.toLong()).toInt()
                stereoCache = source.readFrames(cacheStart, cacheFrames, analysisSampleRate)
                require(stereoCache.size == cacheFrames * 2) {
                    "Stereo source returned an incorrect frame count"
                }
            }
            val offset = (start - cacheStart).toInt()
            var leftMean = 0.0
            var rightMean = 0.0
            for (i in window.indices) {
                if (i % 8192 == 0) cancellationCheck()
                val left = stereoCache[(offset + i) * 2].toDouble()
                val right = stereoCache[(offset + i) * 2 + 1].toDouble()
                require(left.isFinite() && right.isFinite()) { "PCM must contain only finite samples" }
                leftMean += left
                rightMean += right
            }
            leftMean /= window.size
            rightMean /= window.size
            var leftPower = 0.0
            var rightPower = 0.0
            // Match the DC removal used by analyzeWindows before comparing channel energy.
            // Sum centered squares directly to avoid cancellation in E[x²] - E[x]².
            for (i in window.indices) {
                if (i % 8192 == 0) cancellationCheck()
                val left = stereoCache[(offset + i) * 2].toDouble() - leftMean
                val right = stereoCache[(offset + i) * 2 + 1].toDouble() - rightMean
                leftPower += left * left
                rightPower += right * right
            }
            if (selectedChannel < 0) {
                selectedChannel = if (rightPower > leftPower) 1 else 0
            } else {
                val selectedPower = if (selectedChannel == 0) leftPower else rightPower
                val otherPower = if (selectedChannel == 0) rightPower else leftPower
                if (otherPower > selectedPower * STEREO_CHANNEL_SWITCH_POWER_RATIO) {
                    selectedChannel = 1 - selectedChannel
                }
            }
            for (i in window.indices)
                window[i] = stereoCache[(offset + i) * 2 + selectedChannel].toDouble()
        }
    }

    private fun layout(sampleRate: Int, sampleCount: Long): Layout {
        require(sampleRate in 4000..384000) { "Sample rate must be in [4000, 384000] Hz" }
        require(options.maximumFrequencyHz <= sampleRate / 5.0) {
            "At least five samples per period of the maximum pitch frequency are required"
        }
        require(sampleCount.toDouble() / sampleRate <= MAX_PITCH_DURATION_SECONDS) {
            "Source duration must not exceed four hours"
        }
        val maximumLag = ceil(sampleRate / options.minimumFrequencyHz).toInt()
        val window = max(ceil(options.windowSeconds * sampleRate).toInt(), maximumLag * 4 + 1)
        require(window <= MAX_PITCH_WINDOW_FRAMES) { "Pitch analysis window exceeds the sample limit" }
        require(sampleCount >= window) { "At least one complete pitch analysis window is required" }
        val hop = max(1, (options.hopSeconds * sampleRate).roundToInt())
        val frames = 1L + (sampleCount - window) / hop
        require(frames <= 1_000_000) { "Pitch analysis exceeds one million result frames" }
        return Layout(window, hop, maximumLag, frames.toInt())
    }

    private fun analyzeWindows(
        layout: Layout,
        sampleRate: Int,
        duration: Double,
        cancellationCheck: () -> Unit,
        readWindow: (Long, DoubleArray) -> Unit,
    ): PitchAnalysis {
        var fftSize = 1
        while (fftSize < layout.window * 2) fftSize *= 2
        val samples = DoubleArray(layout.window)
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        val power = DoubleArray(layout.window + 1)
        val squaredDifference = DoubleArray(layout.maximumLag + 2)
        val difference = DoubleArray(layout.maximumLag + 2)
        val frames = ArrayList<PitchFrame>(layout.frames)
        for (frame in 0 until layout.frames) {
            cancellationCheck()
            val start = frame.toLong() * layout.hop
            readWindow(start, samples)
            val mean = samples.average()
            var totalPower = 0.0
            real.fill(0.0)
            imaginary.fill(0.0)
            for (i in samples.indices) {
                // Removing DC keeps microphone offsets from being reported as voiced silence.
                val sample = samples[i] - mean
                real[i] = sample
                totalPower += sample * sample
                power[i + 1] = totalPower
            }
            val rmsDb = 10.0 * log10(max(totalPower / layout.window, 1e-16))
            var frequency: Double? = null
            var confidence = 0.0
            if (rmsDb > options.silenceThresholdDb) {
                pitchFft(real, imaginary, inverse = false, cancellationCheck)
                for (i in real.indices) {
                    real[i] = real[i] * real[i] + imaginary[i] * imaginary[i]
                    imaginary[i] = 0.0
                }
                pitchFft(real, imaginary, inverse = true, cancellationCheck)
                var sumDifference = 0.0
                difference[0] = 1.0
                for (lag in 1..layout.maximumLag + 1) {
                    // A zero-padded FFT produces linear autocorrelation, not circular wrapping.
                    // Correct for the shrinking comparison count before cumulative normalization.
                    val squared =
                        max(0.0, power[layout.window - lag] + totalPower - power[lag] - 2.0 * real[lag]) /
                            (layout.window - lag)
                    squaredDifference[lag] = squared
                    sumDifference += squared
                    difference[lag] =
                        if (sumDifference > 0.0) squared * lag / sumDifference else 1.0
                }
                var selected = -1
                var selectedDifference = 1.0
                // Search the first accepted period before applying the frequency range. Otherwise
                // an above-range tone can be falsely accepted at one of its period multiples.
                var lag = 2
                while (lag <= layout.maximumLag) {
                    val left = difference[lag - 1]
                    val center = difference[lag]
                    val right = difference[lag + 1]
                    if (center <= left && center < right) {
                        val denominator = left - 2.0 * center + right
                        val offset =
                            if (denominator > 1e-12)
                                (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
                            else 0.0
                        val minimum = max(0.0, center - 0.25 * (left - right) * offset)
                        // Evaluate the interpolated dip before thresholding. High notes can
                        // have their true period between integer lags; thresholding only the
                        // integer samples incorrectly selects their next period (an octave low).
                        if (minimum < options.periodicityThreshold) {
                            selected = lag
                            selectedDifference = minimum
                            break
                        }
                    }
                    lag++
                }
                if (selected >= 0) {
                    // Refine the raw difference minimum. Interpolating the normalized curve
                    // biases short periods because its cumulative denominator varies with lag.
                    val left = squaredDifference[selected - 1]
                    val center = squaredDifference[selected]
                    val right = squaredDifference[selected + 1]
                    val denominator = left - 2.0 * center + right
                    val offset =
                        if (abs(denominator) > 1e-12)
                            (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
                        else 0.0
                    val candidate = sampleRate / (selected + offset)
                    confidence = (1.0 - selectedDifference).coerceIn(0.0, 1.0)
                    // Permit one cent of estimator rounding at an endpoint, then report that
                    // endpoint. Exact boundary tones must not flicker to unvoiced solely because
                    // fractional-period interpolation is imperfect.
                    val rangeTolerance = 2.0.pow(1.0 / 1200.0)
                    if (candidate in options.minimumFrequencyHz / rangeTolerance..options.maximumFrequencyHz * rangeTolerance)
                        frequency = candidate.coerceIn(options.minimumFrequencyHz, options.maximumFrequencyHz)
                } else {
                    var best = 1.0
                    for (i in 2..layout.maximumLag) best = min(best, difference[i])
                    confidence = (1.0 - best).coerceIn(0.0, 1.0)
                }
            }
            frames +=
                PitchFrame(
                    (start + (layout.window - 1) / 2.0) / sampleRate,
                    frequency,
                    confidence,
                    rmsDb,
                )
        }
        cancellationCheck()
        return PitchAnalysis(duration, frames.toList(), layout.hop.toDouble() / sampleRate, layout.window.toDouble() / sampleRate)
    }

    private data class Layout(val window: Int, val hop: Int, val maximumLag: Int, val frames: Int)
}

private const val MAX_PITCH_DURATION_SECONDS = 4.0 * 60.0 * 60.0
private const val MAX_PITCH_WINDOW_FRAMES = 262144
private const val PITCH_FFT_CANCELLATION_BUTTERFLIES = 32768
private val STEREO_CHANNEL_SWITCH_POWER_RATIO = 10.0.pow(1.0 / 10.0)
private val PITCH_NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** In-place radix-two FFT; the inverse includes its normalization. */
private fun pitchFft(
    real: DoubleArray,
    imaginary: DoubleArray,
    inverse: Boolean,
    cancellationCheck: () -> Unit,
) {
    val size = real.size
    cancellationCheck()
    var j = 0
    for (i in 1 until size) {
        var bit = size shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val re = real[i]
            real[i] = real[j]
            real[j] = re
            val im = imaginary[i]
            imaginary[i] = imaginary[j]
            imaginary[j] = im
        }
    }
    var length = 2
    while (length <= size) {
        cancellationCheck()
        val angle = (if (inverse) 2.0 else -2.0) * PI / length
        val stepReal = cos(angle)
        val stepImaginary = sin(angle)
        // Keep callback overhead bounded while ensuring a maximum-size transform cannot become
        // one uninterruptible operation. Both values are powers of two, so the mask replaces a
        // modulo in this hot loop.
        val cancellationStride = max(length, PITCH_FFT_CANCELLATION_BUTTERFLIES * 2)
        for (base in 0 until size step length) {
            if (base and (cancellationStride - 1) == 0) cancellationCheck()
            var twiddleReal = 1.0
            var twiddleImaginary = 0.0
            for (offset in 0 until length / 2) {
                val a = base + offset
                val b = a + length / 2
                val re = real[b] * twiddleReal - imaginary[b] * twiddleImaginary
                val im = real[b] * twiddleImaginary + imaginary[b] * twiddleReal
                real[b] = real[a] - re
                imaginary[b] = imaginary[a] - im
                real[a] += re
                imaginary[a] += im
                val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                twiddleReal = nextReal
            }
        }
        length *= 2
    }
    if (inverse) {
        for (i in real.indices) {
            real[i] /= size
            imaginary[i] /= size
        }
    }
}
