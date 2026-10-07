package org.metrolist.beatweave

import kotlin.math.*

/** Offline, deterministic beat inference. All times are in the source sample clock. */
internal object BeatDetector {
    /** Small immutable-by-ownership audio features; no full PCM retained. */
    internal class AudioContext(
        val peak: Double,
        val attacks: DoubleArray,
        val attackBlock: Int,
        val sampleCount: Int,
        val rate: Int,
        val hop: Int,
        val window: Int,
    )

    fun prepareAudio(
        pcm: FloatArray,
        rate: Int,
        hop: Int,
        window: Int,
        peak: Double,
        cancellationCheck: () -> Unit,
    ): AudioContext {
        val block = max(1, (rate * 0.002).roundToInt())
        val energy = DoubleArray((pcm.size + block - 1) / block)
        for (i in pcm.indices) {
            if (i % 65536 == 0) cancellationCheck()
            energy[i / block] += pcm[i].toDouble().pow(2)
        }
        val attacks =
            DoubleArray(energy.size) { i ->
                max(0.0, sqrt(energy[i]) - if (i > 0) sqrt(energy[i - 1]) else 0.0)
            }
        return AudioContext(peak, attacks, block, pcm.size, rate, hop, window)
    }

    internal data class Result(
        val bpm: Double,
        val beats: List<Beat>,
        val envelope: FloatArray,
        val candidates: List<TempoCandidate>,
        val tempoConfidence: Double,
        val beatConfidence: Double,
        val ambiguous: Boolean,
    )

    fun detect(
        flux: FloatArray,
        audio: AudioContext,
        preferred: Double?,
        cancellationCheck: () -> Unit = {},
    ): Result {
        cancellationCheck()
        val rate = audio.rate
        val hop = audio.hop
        val step = hop.toDouble() / rate
        val envelope = normalize(flux, step, cancellationCheck)
        if (audio.peak < 1e-7f || envelope.maxOrNull()!! < 1e-5f) {
            return Result(0.0, emptyList(), envelope, emptyList(), 0.0, 0.0, true)
        }
        // Smooth by 10 ms before periodicity scoring; integer-bin autocorrelation without
        // this step penalizes non-integer BPMs differently according to FFT hop size.
        val evidence = gaussian(envelope, max(0.7, 0.010 / step), cancellationCheck)
        val minPeriod = 60.0 / 240.0 / step
        val maxPeriod = min(evidence.size / 3.0, 60.0 / 40.0 / step)
        if (maxPeriod <= minPeriod)
            return Result(0.0, emptyList(), envelope, emptyList(), 0.0, 0.0, true)
        val correlation = correlation(evidence, maxPeriod.toInt() * 4 + 4, cancellationCheck)
        fun support(period: Double): Double =
            (at(correlation, period) +
                0.5 * at(correlation, period * 2) +
                0.25 * at(correlation, period * 3) +
                0.125 * at(correlation, period * 4)) / 1.875
        val candidates = ArrayList<TempoCandidate>()
        val samples = ((maxPeriod - minPeriod) * 8).toInt()
        var prev = support(minPeriod)
        var current = support(minPeriod + 0.125)
        if (prev > current)
            candidates += TempoCandidate(60.0 / (minPeriod * step), prev.coerceIn(0.0, 1.0))
        for (i in 2..samples) {
            val period = minPeriod + i * 0.125
            val next = support(period)
            if (current >= prev && current > next) {
                val p = period - 0.125
                candidates += TempoCandidate(60.0 / (p * step), current.coerceIn(0.0, 1.0))
            }
            prev = current
            current = next
        }
        if (current > prev)
            candidates +=
                TempoCandidate(
                    60.0 / ((minPeriod + samples * 0.125) * step),
                    current.coerceIn(0.0, 1.0),
                )
        // A weak pulse-density preference avoids discarding every other equally strong beat.
        // Competing metrical levels remain exposed; this preference is not proof of meter.
        fun ranked(c: TempoCandidate) = c.support * (1.0 + 0.12 * ln(c.bpm / 40.0))
        val sorted = candidates.sortedByDescending(::ranked).take(8)
        var selected =
            if (preferred != null) {
                val near = sorted.filter { abs(ln(it.bpm / preferred)) < 0.055 }
                near.maxByOrNull { it.support }
                    ?: TempoCandidate(preferred, support(60.0 / preferred / step))
            } else sorted.firstOrNull() ?: TempoCandidate(120.0, 0.0)
        if (selected.support < 0.03)
            return Result(0.0, emptyList(), envelope, sorted, 0.0, 0.0, true)
        data class TrackedCandidate(
            val reference: DoubleArray,
            val frames: List<Int>,
        )
        val trackedCandidates = mutableMapOf<Double, TrackedCandidate>()
        fun trackCandidate(candidate: TempoCandidate): TrackedCandidate =
            trackedCandidates.getOrPut(candidate.bpm) {
                val period = 60.0 / candidate.bpm / step
                val reference = localPeriods(evidence, period, step, cancellationCheck)
                TrackedCandidate(reference, track(evidence, reference, period, cancellationCheck))
            }
        fun octaveCandidate(candidate: TempoCandidate, multiplier: Double): TempoCandidate? {
            val target = candidate.bpm * multiplier
            return sorted
                .filter { it.support >= 0.03 && abs(ln(it.bpm / target)) < 0.025 }
                .maxByOrNull(::ranked)
        }
        fun alternatingPulseBalance(candidate: TempoCandidate): Double? {
            val frames = trackCandidate(candidate).frames
            if (frames.size < 8) return null
            var even = 0.0
            var odd = 0.0
            var evenCount = 0
            var oddCount = 0
            for ((index, frame) in frames.withIndex()) {
                if (index % 2 == 0) {
                    even += envelope[frame]
                    evenCount++
                } else {
                    odd += envelope[frame]
                    oddCount++
                }
            }
            if (evenCount == 0 || oddCount == 0) return null
            val evenMean = even / evenCount
            val oddMean = odd / oddCount
            val strongest = max(evenMean, oddMean)
            return if (strongest > 1e-9) min(evenMean, oddMean) / strongest else null
        }
        if (preferred == null) {
            // Autocorrelation exposes several metrical levels. A fast candidate whose
            // tracked pulses alternate strongly is subdivision evidence, while balanced
            // alternating pulses support retaining (or promoting to) the faster level.
            // This avoids a fixed dance-tempo range that would break genuine slow/fast music.
            val balancedPulseThreshold = 0.85
            if (selected.bpm > 160.0) {
                val half = octaveCandidate(selected, 0.5)
                if (half != null) {
                    val balance = alternatingPulseBalance(selected)
                    if (balance != null && balance < balancedPulseThreshold) selected = half
                }
            }
            while (selected.bpm * 2.0 <= 240.0) {
                val doubled = octaveCandidate(selected, 2.0) ?: break
                val balance = alternatingPulseBalance(doubled) ?: break
                if (balance < balancedPulseThreshold) break
                selected = doubled
            }
        }
        val tracked = trackCandidate(selected)
        val reference = tracked.reference
        val frameBeats = tracked.frames
        val corrected = refine(frameBeats, envelope, audio)
        val beats =
            corrected
                .map { (frame, strength) -> Beat(frame * step, strength) }
                .filter { it.seconds >= 0 && it.seconds < audio.sampleCount.toDouble() / rate }
        val spans = beats.windowed(9).map { (it.last().seconds - it.first().seconds) / 8 }.sorted()
        val bpm = if (spans.isNotEmpty()) 60.0 / spans[spans.size / 2] else selected.bpm
        val medianStrength =
            frameBeats
                .map { at(evidence, it.toDouble()) }
                .sorted()
                .let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val competitors = sorted.filter { abs(ln(it.bpm / selected.bpm)) > 0.07 }
        val ambiguous = competitors.any { ranked(it) >= ranked(selected) * 0.82 }
        val periodicity = selected.support.coerceIn(0.0, 1.0)
        // These are conservative evidence scores, not calibrated confidence probabilities.
        val beatConfidence = (periodicity * min(1.0, medianStrength / 0.35)).coerceIn(0.0, 1.0)
        return Result(bpm, beats, envelope, sorted, periodicity, beatConfidence, ambiguous)
    }

    private fun normalize(
        flux: FloatArray,
        step: Double,
        cancellationCheck: () -> Unit,
    ): FloatArray {
        val prefix = DoubleArray(flux.size + 1)
        for (i in flux.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            prefix[i + 1] = prefix[i] + flux[i]
        }
        val radius = max(1, (0.2 / step).roundToInt())
        val onset = FloatArray(flux.size)
        for (i in flux.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            val lo = max(0, i - radius)
            val hi = min(flux.size, i + radius + 1)
            onset[i] =
                max(0.0, flux[i] - (prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
        }
        val power = DoubleArray(flux.size + 1)
        for (i in onset.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            power[i + 1] = power[i] + onset[i].toDouble().pow(2)
        }
        val longRadius = (3.0 / step).roundToInt()
        val floor = sqrt(power.last() / max(1, onset.size)) * 0.15 + 1e-7
        for (i in onset.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            val lo = max(0, i - longRadius)
            val hi = min(onset.size, i + longRadius + 1)
            onset[i] =
                min(5.0, onset[i] / max(floor, sqrt((power[hi] - power[lo]) / (hi - lo)))).toFloat()
        }
        return onset
    }

    private fun gaussian(
        input: FloatArray,
        sigma: Double,
        cancellationCheck: () -> Unit,
    ): DoubleArray {
        val radius = ceil(3 * sigma).toInt()
        val weights = DoubleArray(radius * 2 + 1) { exp(-0.5 * ((it - radius) / sigma).pow(2)) }
        val total = weights.sum()
        return DoubleArray(input.size) { i ->
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            var sum = 0.0
            for (j in -radius..radius) if (i + j in input.indices)
                sum += input[i + j] * weights[j + radius]
            sum / total
        }
    }

    private fun correlation(
        x: DoubleArray,
        maxLag: Int,
        cancellationCheck: () -> Unit,
    ): DoubleArray {
        var total = 0.0
        for (i in x.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            total += x[i]
        }
        val mean = total / x.size
        val centered = DoubleArray(x.size)
        for (i in x.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            centered[i] = x[i] - mean
        }
        var energy = 0.0
        for (i in centered.indices) {
            if (i % DETECTOR_CANCELLATION_INTERVAL == 0) cancellationCheck()
            energy += centered[i] * centered[i]
        }
        energy = energy.coerceAtLeast(1e-12)
        return DoubleArray(min(x.size, maxLag + 1)) { lag ->
            var sum = 0.0
            var start = lag
            while (start < x.size) {
                cancellationCheck()
                val end = min(x.size, start + DETECTOR_CANCELLATION_INTERVAL)
                for (i in start until end) sum += centered[i] * centered[i - lag]
                start = end
            }
            sum / energy * x.size / max(1, x.size - lag)
        }
    }

    private fun localPeriods(
        x: DoubleArray,
        period: Double,
        step: Double,
        cancellationCheck: () -> Unit,
    ): DoubleArray {
        val stride = max(1, (2.0 / step).roundToInt())
        val radius = max(1, (5.0 / step).roundToInt())
        val centres = (x.indices step stride).toList()
        val periods = DoubleArray(centres.size)
        val initializationSeconds = 30.0
        val initializationTightness = 2.0
        var previous = period
        for ((index, centre) in centres.withIndex()) {
            cancellationCheck()
            val lo = max(0, centre - radius)
            val hi = min(x.size, centre + radius)
            var best = previous
            var bestScore = Double.NEGATIVE_INFINITY
            // Local adaptation is evidence driven but cannot silently switch metrical levels.
            val minLag = max(2, floor(period * 0.85).toInt())
            val maxLag = ceil(period * 1.15).toInt()
            for (lag in minLag..maxLag) {
                var cross = 0.0
                var energy = 0.0
                for (i in max(lo, lag)..<hi) {
                    cross += x[i] * x[i - lag]
                    energy += x[i] * x[i]
                }
                // A rhythmically misleading intro can ratchet the first local estimates away
                // before a reliable preceding window exists. Anchor tracker initialization to
                // the full-track pulse, then leave established mid-song tempo adaptation alone.
                val initializationAnchor =
                    if (centre * step < initializationSeconds) initializationTightness else 0.0
                val score =
                    cross / max(1e-9, energy) -
                        6.0 * ln(lag / previous).pow(2) -
                        initializationAnchor * ln(lag / period).pow(2)
                if (score > bestScore) {
                    bestScore = score
                    best = lag.toDouble()
                }
            }
            previous = previous * 0.65 + best * 0.35
            periods[index] = previous
        }
        return DoubleArray(x.size) { i ->
            val index = min(i / stride, periods.lastIndex)
            val next = min(index + 1, periods.lastIndex)
            val f = (i % stride).toDouble() / stride
            periods[index] * (1 - f) + periods[next] * f
        }
    }

    private fun track(
        onset: DoubleArray,
        reference: DoubleArray,
        period: Double,
        cancellationCheck: () -> Unit,
    ): List<Int> {
        val score = DoubleArray(onset.size)
        val back = IntArray(onset.size) { -1 }
        // The old 0.9 penalty was negligible against an onset reward up to 4, and a
        // positive per-beat bonus rewarded the shortest interval. Tightness now scales
        // the log-period deviation materially; no synthetic beat-count reward exists.
        for (i in onset.indices) {
            if (i % 512 == 0) cancellationCheck()
            val localPeriod = reference[i]
            val minLag = max(2, floor(localPeriod * 0.72).toInt())
            val maxLag = ceil(localPeriod * 1.35).toInt()
            var best = 0.0
            var predecessor = -1
            for (lag in minLag..maxLag) {
                val previous = i - lag
                if (previous < 0) continue
                val candidate = score[previous] - 160.0 * ln(lag / localPeriod).pow(2)
                if (candidate > best) {
                    best = candidate
                    predecessor = previous
                }
            }
            score[i] = onset[i] + best
            back[i] = predecessor
        }
        var last = onset.indexOfLast { it > 0.05 }
        if (last < 0) return emptyList()
        val firstEnd = max(0, last - ceil(period * 2).toInt())
        var index = (firstEnd..last).maxByOrNull { score[it] } ?: return emptyList()
        val out = ArrayList<Int>()
        while (index >= 0) {
            out += index
            index = back[index]
        }
        out.reverse()
        // Remove unsupported leading/trailing extrapolation, retaining gaps inside a track.
        while (out.isNotEmpty() && onset[out.first()] < 0.05) out.removeAt(0)
        while (out.isNotEmpty() && onset[out.last()] < 0.05) out.removeAt(out.lastIndex)
        return out
    }

    private fun refine(
        frames: List<Int>,
        onset: FloatArray,
        audio: AudioContext,
    ): List<Pair<Double, Float>> {
        // First align the spectral-flux rise with the sample-clock attack. The energy
        // derivative is calculated independently; adding half an FFT window is wrong.
        val rate = audio.rate
        val hop = audio.hop
        val window = audio.window
        val block = audio.attackBlock
        val attack = audio.attacks
        return frames.map { frame ->
            val predicted = frame * hop.toDouble() + window * 0.25
            val radius = rate * 0.025
            val lo = max(0, ((predicted - radius) / block).toInt())
            val hi = min(attack.lastIndex, ((predicted + radius) / block).toInt())
            var best = -1
            var bestValue = 0.0
            for (i in lo..hi) {
                val distance = (i * block - predicted) / max(1.0, radius)
                val value = attack[i] * exp(-0.5 * distance * distance)
                if (value > bestValue) {
                    bestValue = value
                    best = i
                }
            }
            val sample = if (best >= 0 && bestValue > 1e-8) best * block.toDouble() else predicted
            sample / hop to onset[frame]
        }
    }

    private fun at(x: DoubleArray, i: Double): Double {
        if (i < 0 || i >= x.lastIndex) return 0.0
        val left = i.toInt()
        val f = i - left
        return x[left] * (1 - f) + x[left + 1] * f
    }
}

private const val DETECTOR_CANCELLATION_INTERVAL = 8192
