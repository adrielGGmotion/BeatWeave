package org.metrolist.beatweave.learned

import kotlin.math.*

/**
 * The exact signal convention of CPJKU Beat This LogMelSpect (see licenses/provenance). Input:
 * decoded mono float PCM at 22050 Hz, without gain normalization. Output: frame-major 128-bin
 * logmel; frame i is centered at i * 20 ms. Uses reflect padding, a periodic Hann window, amplitude
 * (not power), FFT/sqrt(1024), Slaney-spaced triangular filters without area normalization, and
 * log1p(1000 * mel).
 */
class BeatThisFrontend {
    companion object {
        const val SAMPLE_RATE = 22050
        const val HOP = 441
        const val BINS = 128
        const val FFT_SIZE = 1024
    }

    private val window = DoubleArray(FFT_SIZE) { .5 - .5 * cos(2 * PI * it / FFT_SIZE) }
    private val filters: List<Pair<IntArray, DoubleArray>> = run {
        fun mel(hz: Double) =
            if (hz < 1000.0) hz / (200.0 / 3.0) else 15.0 + ln(hz / 1000.0) / (ln(6.4) / 27.0)
        fun hz(mel: Double) =
            if (mel < 15.0) mel * (200.0 / 3.0) else 1000.0 * exp((mel - 15.0) * ln(6.4) / 27.0)
        val low = mel(30.0)
        val high = mel(11000.0)
        val edges = DoubleArray(BINS + 2) { hz(low + (high - low) * it / (BINS + 1)) }
        List(BINS) { bin ->
            val indices = ArrayList<Int>()
            val weights = ArrayList<Double>()
            for (fftBin in 0..FFT_SIZE / 2) {
                val frequency = fftBin.toDouble() * SAMPLE_RATE / FFT_SIZE
                val weight =
                    max(
                        0.0,
                        min(
                            (frequency - edges[bin]) / (edges[bin + 1] - edges[bin]),
                            (edges[bin + 2] - frequency) / (edges[bin + 2] - edges[bin + 1]),
                        ),
                    )
                if (weight > 0.0) {
                    indices.add(fftBin)
                    weights.add(weight)
                }
            }
            indices.toIntArray() to weights.toDoubleArray()
        }
    }

    fun transform(mono22050: FloatArray, cancellationCheck: () -> Unit = {}): MelSpectrogram {
        require(mono22050.size > FFT_SIZE / 2) {
            "At least 513 samples of 22050 Hz PCM are required"
        }
        require(mono22050.all { it.isFinite() }) { "PCM contains non-finite samples" }
        val frames = 1 + mono22050.size / HOP
        require(frames <= Int.MAX_VALUE / BINS) { "Audio is too long" }
        val values = FloatArray(frames * BINS)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val magnitudes = DoubleArray(FFT_SIZE / 2 + 1)
        for (frame in 0 until frames) {
            if (frame % 64 == 0) cancellationCheck()
            val start = frame * HOP - FFT_SIZE / 2
            for (i in 0 until FFT_SIZE) {
                var source = start + i
                if (source < 0) source = -source
                if (source >= mono22050.size) source = 2 * mono22050.size - 2 - source
                real[i] = mono22050[source] * window[i]
                imag[i] = 0.0
            }
            fft(real, imag)
            for (i in magnitudes.indices) magnitudes[i] =
                hypot(real[i], imag[i]) / sqrt(FFT_SIZE.toDouble())
            for (bin in 0 until BINS) {
                val (indices, weights) = filters[bin]
                var sum = 0.0
                for (i in indices.indices) sum += magnitudes[indices[i]] * weights[i]
                values[frame * BINS + bin] = ln1p(1000.0 * sum).toFloat()
            }
        }
        cancellationCheck()
        return MelSpectrogram(frames, values)
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]
                real[i] = real[j]
                real[j] = r
                val im = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = im
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImag = sin(angle)
            for (offset in 0 until size step length) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val first = offset + k
                    val second = first + length / 2
                    val tr = wr * real[second] - wi * imaginary[second]
                    val ti = wr * imaginary[second] + wi * real[second]
                    real[second] = real[first] - tr
                    imaginary[second] = imaginary[first] - ti
                    real[first] += tr
                    imaginary[first] += ti
                    val next = wr * stepReal - wi * stepImag
                    wi = wr * stepImag + wi * stepReal
                    wr = next
                }
            }
            length *= 2
        }
    }
}

class MelSpectrogram(val frames: Int, val values: FloatArray) {
    init {
        require(
            frames > 0 &&
                frames <= Int.MAX_VALUE / BeatThisFrontend.BINS &&
                values.size == frames * BeatThisFrontend.BINS
        )
    }
}
