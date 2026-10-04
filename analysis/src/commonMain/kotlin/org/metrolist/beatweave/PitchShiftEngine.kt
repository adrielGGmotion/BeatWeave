package org.metrolist.beatweave

import kotlin.math.pow

/**
 * Constant transposition, independent of the beat clock. Positive semitones raise pitch;
 * fractional values permit fine tuning (one cent is 0.01 semitones). Formant preservation
 * retains the original spectral envelope where the backend supports it. Processing can
 * introduce artifacts, particularly for large shifts; it is not a lossless transform.
 */
data class PitchShift(val semitones: Double = 0.0, val preserveFormants: Boolean = true) {
    init {
        require(semitones.isFinite() && semitones in -24.0..24.0) {
            "Pitch shift must be finite and in -24..24 semitones"
        }
    }

    val ratio: Double
        get() = 2.0.pow(semitones / 12.0)

    val isIdentity: Boolean
        get() = semitones == 0.0

    companion object {
        val None = PitchShift()
    }
}

/**
 * An offline backend supporting independent pitch and duration changes in one processing
 * pass. Ownership, cancellation, exact frame counts and disk-cache requirements match
 * [PitchStretchEngine]. Engines must not silently ignore a requested pitch shift.
 */
interface PitchShiftEngine : PitchStretchEngine {
    fun prepare(
        source: StereoPcm,
        schedule: WarpSchedule,
        pitchShift: PitchShift,
        progress: (Double) -> Unit = {},
    ): PreparedStereoPcm

    /** Pitch-only processing with the same duration, at the caller's decoded PCM sample rate. */
    fun preparePitchShift(
        source: StereoPcm,
        sampleRate: Int,
        pitchShift: PitchShift,
        progress: (Double) -> Unit = {},
    ): PreparedStereoPcm =
        prepare(source, WarpSchedule.identity(source.durationSeconds, sampleRate), pitchShift, progress)
}

class MissingPitchShiftEngineException : IllegalStateException(
    "Pitch shifting requires a PitchShiftEngine. Pass RubberBandEngine or another verified backend."
)
