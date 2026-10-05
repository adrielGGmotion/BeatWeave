package org.metrolist.beatweave

import kotlin.math.*

/** Time stamps describe audio samples, not UI callbacks. No network or platform APIs are used. */
data class Beat(val seconds: Double, val strength: Float)

/**
 * Relative periodicity evidence, not a calibrated probability. Multiple metrical levels may be
 * valid.
 */
data class TempoCandidate(val bpm: Double, val support: Double)

data class EnergyBlock(val startSeconds: Double, val endSeconds: Double, val rmsDb: Double)

data class Analysis(
    val durationSeconds: Double,
    val bpm: Double,
    val tempoConfidence: Double,
    val beats: List<Beat>,
    val rmsDb: Double,
    val peakDb: Double,
    val spectralCentroidHz: Double,
    val keyEstimate: String,
    /** Separation from the strongest harmonically incompatible key profile, not a probability. */
    val keyConfidence: Double,
    val energyBlocks: List<EnergyBlock>,
    val onsetEnvelope: FloatArray,
    val onsetHopSeconds: Double,
    val warnings: List<String>,
    val tempoCandidates: List<TempoCandidate> = emptyList(),
    val beatConfidence: Double = 0.0,
    val tempoAmbiguous: Boolean = true,
    val downbeatSeconds: List<Double> = emptyList(),
    val detectorId: String = "spectral-periodicity-v2",
    /** Add to onset frame * onsetHopSeconds to express novelty peaks on the audio clock. */
    val onsetTimeOffsetSeconds: Double = 0.0,
)

/** Accepts mono PCM, preferably downsampled to 11025 Hz; limits memory to ~1 float/sample. */
class MusicAnalyzer {
    /** preferredBpm selects a metrical pulse (e.g. 78 vs 156) but never supplies beat positions. */
    fun analyze(
        pcm: FloatArray,
        sampleRate: Int,
        preferredBpm: Double? = null,
        cancellationCheck: () -> Unit = {},
    ): Analysis {
        require(preferredBpm == null || preferredBpm in 40.0..240.0)
        return prepare(pcm, sampleRate, cancellationCheck).analyze(preferredBpm, cancellationCheck)
    }

    /**
     * Uses independently inferred source-clock beats unchanged; no heuristic grid can overwrite
     * them. Beat strength is the detector's evidence score, not a calibrated correctness
     * probability.
     */
    fun analyzeWithBeats(
        pcm: FloatArray,
        sampleRate: Int,
        observedBeats: List<Beat>,
        downbeatSeconds: List<Double> = emptyList(),
        modelId: String,
        cancellationCheck: () -> Unit = {},
    ): Analysis {
        require(modelId.isNotBlank()) { "A detector identity is required" }
        val duration = pcm.size.toDouble() / sampleRate
        require(
            observedBeats.all {
                it.seconds.isFinite() &&
                    it.seconds >= 0 &&
                    it.seconds < duration &&
                    it.strength.isFinite()
            }
        )
        require(observedBeats.zipWithNext().all { (a, b) -> b.seconds > a.seconds }) {
            "Beats must be strictly ordered"
        }
        require(downbeatSeconds.all { it.isFinite() && it >= 0 && it < duration })
        require(downbeatSeconds.zipWithNext().all { (a, b) -> b > a }) {
            "Downbeats must be strictly ordered"
        }
        return prepare(pcm, sampleRate, cancellationCheck)
            .withObservedBeats(observedBeats, downbeatSeconds, modelId, cancellationCheck)
    }

    /**
     * Measure spectral, energy and attack features once, then evaluate multiple tempo
     * interpretations without repeating the FFT pass. The returned snapshot retains derived
     * features only, not the caller's PCM or cancellation callback. Do not mutate PCM during this
     * call; subsequent mutation is safe.
     */
    fun prepare(
        pcm: FloatArray,
        sampleRate: Int,
        cancellationCheck: () -> Unit = {},
    ): PreparedMusicAnalysis {
        cancellationCheck()
        require(sampleRate in 4000..384000 && pcm.size >= sampleRate * 3) {
            "At least 3 seconds of mono PCM required"
        }
        var sumSq = 0.0
        var peak = 0.0
        for (i in pcm.indices) {
            if (i % PCM_CANCELLATION_INTERVAL == 0) cancellationCheck()
            val value = pcm[i]
            require(value.isFinite()) { "PCM must contain only finite samples" }
            val sample = value.toDouble()
            sumSq += sample * sample
            peak = max(peak, abs(sample))
        }
        // Constant temporal resolution at every sample rate. Centered windows include the
        // first attack instead of dropping the first half-window from the beat grid.
        var n = 256
        while (n < sampleRate * 0.046) n *= 2
        val hop = max(16, n / 8)
        val frames = 1 + (pcm.size - 1) / hop
        val flux = FloatArray(frames)
        val acousticAttacks =
            AcousticAttackFeatureCollector(
                frames,
                hop.toDouble() / sampleRate,
                pcm.size.toDouble() / sampleRate,
            )
        val previous = DoubleArray(n / 2)
        val previousMagnitude = DoubleArray(n / 2)
        val whitening = DoubleArray(n / 2)
        val whiteningDecay = exp(-hop.toDouble() / sampleRate / 0.8)
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
        var centroidSum = 0.0
        var spectralWeight = 0.0
        for (frame in 0 until frames) {
            if (frame % 32 == 0) cancellationCheck()
            val base = frame * hop - n / 2
            for (i in 0 until n) {
                real[i] = if (base + i in pcm.indices) pcm[base + i] * hann[i] else 0.0
                imag[i] = 0.0
            }
            fft(real, imag)
            var novelty = 0.0
            var magnitudeRise = 0.0
            var magnitudeTotal = 0.0
            for (bin in 2 until n / 2) {
                val hz = bin.toDouble() * sampleRate / n
                val mag = hypot(real[bin], imag[bin])
                // The independent pulse audit reuses these unwhitened magnitudes.
                // It never changes legacy novelty, beat timestamps, or model inputs.
                acousticAttacks.record(frame, hz, mag, previousMagnitude[bin])
                whitening[bin] = max(mag, whitening[bin] * whiteningDecay)
                val logMag = ln(1.0 + 10.0 * mag / max(1e-4, whitening[bin]))
                if (hz in 45.0..5000.0) {
                    magnitudeRise += max(0.0, mag - previousMagnitude[bin])
                    magnitudeTotal += mag
                    novelty += max(0.0, logMag - previous[bin]) * (if (hz < 180.0) 1.3 else 1.0)
                    val w = mag * mag
                    centroidSum += hz * w
                    spectralWeight += w
                }
                previous[bin] = logMag
                previousMagnitude[bin] = mag
            }
            // Adaptive whitening must not turn numerical leakage from a stationary tone
            // into loud attacks. Require measurable unwhitened spectral change first.
            val relativeRise = magnitudeRise / max(1e-9, magnitudeTotal)
            flux[frame] = if (relativeRise > 0.005) novelty.toFloat() else 0f
            acousticAttacks.finishFrame(frame)
        }
        val blocks = ArrayList<EnergyBlock>()
        val chunk = sampleRate * 4
        for (start in pcm.indices step chunk) {
            cancellationCheck()
            val end = min(pcm.size, start + chunk)
            var power = 0.0
            for (j in start until end) power += pcm[j].toDouble() * pcm[j]
            blocks +=
                EnergyBlock(
                    start.toDouble() / sampleRate,
                    end.toDouble() / sampleRate,
                    db(sqrt(power / (end - start))),
                )
        }
        val (key, keyConfidence) =
            estimateKey(highResolutionChroma(pcm, sampleRate, n, cancellationCheck))
        val audio = BeatDetector.prepareAudio(pcm, sampleRate, hop, n, peak, cancellationCheck)
        cancellationCheck()
        return PreparedMusicAnalysis(
            flux,
            audio,
            db(sqrt(sumSq / pcm.size)),
            db(peak),
            centroidSum / max(1.0, spectralWeight),
            key,
            keyConfidence,
            blocks.toList(),
            acousticAttacks.finish(),
        )
    }

    /**
     * Key profiles need substantially finer pitch resolution than the short onset window. A
     * 46 ms FFT at 11.025 kHz has bins more than 21 Hz apart, which can map unrelated songs and
     * even clean triads to the same pitch class. Sample sparse, eight-times-longer windows so the
     * added work remains bounded while semitone energy is resolved. Normalizing each window keeps
     * a few loud sections from erasing the rest of the track's harmonic evidence.
     */
    private fun highResolutionChroma(
        pcm: FloatArray,
        sampleRate: Int,
        onsetWindow: Int,
        cancellationCheck: () -> Unit,
    ): DoubleArray {
        val windowSize = onsetWindow * 8
        val hop = windowSize * 2
        val hann = DoubleArray(windowSize) { 0.5 - 0.5 * cos(2.0 * PI * it / (windowSize - 1)) }
        val real = DoubleArray(windowSize)
        val imag = DoubleArray(windowSize)
        // Retain sub-semitone position until the whole recording has supplied a tuning estimate.
        // Folding directly to twelve bins assumes A440 and can turn a globally detuned major
        // chord into its relative/neighboring minor key near the half-semitone boundary.
        val fineBinsPerSemitone = KEY_FINE_BINS_PER_SEMITONE
        val fineSize = 12 * fineBinsPerSemitone
        val frameChroma = DoubleArray(fineSize)
        val fineChroma = DoubleArray(fineSize)
        val frameConcertChroma = DoubleArray(12)
        val concertChroma = DoubleArray(12)
        for (base in -windowSize / 2 until pcm.size step hop) {
            cancellationCheck()
            frameChroma.fill(0.0)
            frameConcertChroma.fill(0.0)
            for (i in 0 until windowSize) {
                real[i] = if (base + i in pcm.indices) pcm[base + i] * hann[i] else 0.0
                imag[i] = 0.0
            }
            fft(real, imag)
            for (bin in 1 until windowSize / 2) {
                val hz = bin.toDouble() * sampleRate / windowSize
                if (hz < 55.0) continue
                if (hz > 2000.0) break
                val midi = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)
                val fine = midi * fineBinsPerSemitone
                val lower = floor(fine).toInt()
                val fraction = fine - lower
                val power = real[bin] * real[bin] + imag[bin] * imag[bin]
                frameChroma[((lower % fineSize) + fineSize) % fineSize] +=
                    power * (1.0 - fraction)
                frameChroma[(((lower + 1) % fineSize) + fineSize) % fineSize] +=
                    power * fraction
                val concertLower = floor(midi).toInt()
                val concertFraction = midi - concertLower
                frameConcertChroma[((concertLower % 12) + 12) % 12] +=
                    power * (1.0 - concertFraction)
                frameConcertChroma[(((concertLower + 1) % 12) + 12) % 12] +=
                    power * concertFraction
            }
            val norm = sqrt(frameChroma.sumOf { it * it })
            if (norm > 1e-12) {
                for (bin in fineChroma.indices) fineChroma[bin] += frameChroma[bin] / norm
            }
            val concertNorm = sqrt(frameConcertChroma.sumOf { it * it })
            if (concertNorm > 1e-12) {
                for (pitchClass in concertChroma.indices)
                    concertChroma[pitchClass] += frameConcertChroma[pitchClass] / concertNorm
            }
        }
        var tuningX = 0.0
        var tuningY = 0.0
        for (bin in fineChroma.indices) {
            val angle = 2.0 * PI * (bin % fineBinsPerSemitone) / fineBinsPerSemitone
            tuningX += fineChroma[bin] * cos(angle)
            tuningY += fineChroma[bin] * sin(angle)
        }
        val tuningConcentration =
            hypot(tuningX, tuningY) / max(1e-12, fineChroma.sum())
        // An incoherent spectrum must not manufacture an arbitrary global tuning correction.
        if (tuningConcentration < MINIMUM_KEY_TUNING_CONCENTRATION) return concertChroma
        val tuningBins = atan2(tuningY, tuningX) / (2.0 * PI) * fineBinsPerSemitone
        val chroma = DoubleArray(12)
        for (bin in fineChroma.indices) {
            val corrected = (bin - tuningBins) / fineBinsPerSemitone
            val lower = floor(corrected).toInt()
            val fraction = corrected - lower
            chroma[((lower % 12) + 12) % 12] += fineChroma[bin] * (1.0 - fraction)
            chroma[(((lower + 1) % 12) + 12) % 12] += fineChroma[bin] * fraction
        }
        return chroma
    }

    private fun estimateKey(chroma: DoubleArray): Pair<String, Double> {
        val major =
            doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor =
            doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val mean = chroma.average()
        val spread = sqrt(chroma.sumOf { (it - mean).pow(2) })
        if (spread < 1e-9) return "unknown" to 0.0
        data class KeyProfile(val root: Int, val minor: Boolean, val label: String, val score: Double)
        val candidates = ArrayList<KeyProfile>(24)
        for (root in 0..11) for ((profile, mode) in listOf(major to "major", minor to "minor")) {
            val pm = profile.average()
            var dot = 0.0
            var pn = 0.0
            for (j in 0..11) {
                dot += (chroma[(root + j) % 12] - mean) * (profile[j] - pm)
                pn += (profile[j] - pm).pow(2)
            }
            val corr = dot / max(1e-9, spread * sqrt(pn))
            candidates += KeyProfile(root, mode == "minor", "${names[root]} $mode", corr)
        }
        val best = candidates.maxBy { it.score }
        // Relative major/minor and same-mode fourth/fifth alternatives are acceptable for the
        // only confidence-sensitive consumer: harmonic transition compatibility. Their close
        // profile scores must not masquerade as uncertainty about that compatibility class.
        val incompatible =
            candidates
                .asSequence()
                .filterNot { harmonicallyCompatible(best.root, best.minor, it.root, it.minor) }
                .maxOf { it.score }
        return best.label to (best.score - incompatible).coerceIn(0.0, 1.0)
    }

    private fun harmonicallyCompatible(
        firstRoot: Int,
        firstMinor: Boolean,
        secondRoot: Int,
        secondMinor: Boolean,
    ): Boolean {
        val interval = (firstRoot - secondRoot + 12) % 12
        if (firstMinor == secondMinor) return interval == 0 || interval == 5 || interval == 7
        val majorRoot = if (firstMinor) secondRoot else firstRoot
        val minorRoot = if (firstMinor) firstRoot else secondRoot
        return (minorRoot - majorRoot + 12) % 12 == 9
    }

    private fun db(value: Double): Double = 20.0 * log10(max(value, 1e-8))

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val t = re[i]
                re[i] = re[j]
                re[j] = t
                val u = im[i]
                im[i] = im[j]
                im[j] = u
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            for (base in 0 until n step len) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = base + k
                    val b = a + len / 2
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
            }
            len = len shl 1
        }
    }
}

private const val KEY_FINE_BINS_PER_SEMITONE = 12
private const val MINIMUM_KEY_TUNING_CONCENTRATION = 0.10
private const val PCM_CANCELLATION_INTERVAL = 65536
