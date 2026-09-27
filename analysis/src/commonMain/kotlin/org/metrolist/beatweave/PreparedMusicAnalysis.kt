package org.metrolist.beatweave

/**
 * Reusable measured audio features. Created by [MusicAnalyzer.prepare]. Owns no decoder, file,
 * native resource or caller PCM; discard it when finished. Results have independent mutable arrays,
 * so editing one cannot affect another.
 */
class PreparedMusicAnalysis
internal constructor(
    private val flux: FloatArray,
    private val audio: BeatDetector.AudioContext,
    private val rmsDb: Double,
    private val peakDb: Double,
    private val centroidHz: Double,
    private val key: String,
    private val keyConfidence: Double,
    private val blocks: List<EnergyBlock>,
    /** Immutable measured attack profiles; contains no PCM or inferred beat clock. */
    val acousticAttacks: AcousticAttackFeatures,
) {
    val durationSeconds: Double
        get() = audio.sampleCount.toDouble() / audio.rate

    /** The hint chooses a supported pulse level; it never supplies beat positions. */
    fun analyze(preferredBpm: Double? = null, cancellationCheck: () -> Unit = {}): Analysis {
        cancellationCheck()
        require(preferredBpm == null || preferredBpm in 40.0..240.0)
        val detection = BeatDetector.detect(flux, audio, preferredBpm, cancellationCheck)
        return result(detection, preferredBpm, emptyList(), "spectral-periodicity-v2", false)
    }

    internal fun withObservedBeats(
        observedBeats: List<Beat>,
        downbeats: List<Double>,
        detectorId: String,
        cancellationCheck: () -> Unit,
    ): Analysis {
        cancellationCheck()
        // Match the source-clock behavior of MusicAnalyzer.analyzeWithBeats.
        val intervals =
            (if (observedBeats.size >= 9)
                    observedBeats.windowed(9).map { (it.last().seconds - it.first().seconds) / 8 }
                else observedBeats.zipWithNext { a, b -> b.seconds - a.seconds })
                .sorted()
        val bpm = if (intervals.isEmpty()) 0.0 else 60.0 / intervals[intervals.size / 2]
        val score =
            observedBeats
                .map { it.strength.toDouble() }
                .average()
                .let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
        val detection =
            BeatDetector.Result(
                bpm,
                observedBeats.toList(),
                flux.copyOf(),
                emptyList(),
                score,
                score,
                observedBeats.size < 4,
            )
        return result(detection, null, downbeats, detectorId, true)
    }

    private fun result(
        detection: BeatDetector.Result,
        preferredBpm: Double?,
        downbeats: List<Double>,
        detectorId: String,
        observed: Boolean,
    ): Analysis {
        val warnings = buildList {
            if (preferredBpm != null)
                add(
                    "Tempo pulse constrained near $preferredBpm BPM; this does not establish meter or downbeat phase."
                )
            if (detection.tempoConfidence < 0.2)
                add(
                    "Weak beat evidence; this beat grid is not reliable for automatic synchronization."
                )
            if (detection.ambiguous)
                add(
                    "Multiple tempo interpretations have comparable support; BPM alone does not establish the musical pulse."
                )
            if (detection.beatConfidence < 0.35)
                add("Weak beat evidence; automatic beat matching should be withheld.")
            if (detection.beats.size < 12) add("Too few beats for a reliable grid.")
            if (observed)
                add(
                    "Beat confidence contains uncalibrated detector scores; source-clock beat positions come from $detectorId."
                )
            add(
                if (downbeats.isEmpty())
                    "Key is a chroma estimate; meter and downbeats are not classified."
                else "Key is a chroma estimate; downbeat timestamps come from $detectorId."
            )
        }
        return Analysis(
            durationSeconds,
            detection.bpm,
            detection.tempoConfidence,
            detection.beats.toList(),
            rmsDb,
            peakDb,
            centroidHz,
            key,
            keyConfidence,
            blocks.toList(),
            detection.envelope,
            audio.hop.toDouble() / audio.rate,
            warnings,
            detection.candidates.toList(),
            detection.beatConfidence,
            detection.ambiguous,
            downbeats.toList(),
            detectorId,
            audio.window / (4.0 * audio.rate),
        )
    }
}
