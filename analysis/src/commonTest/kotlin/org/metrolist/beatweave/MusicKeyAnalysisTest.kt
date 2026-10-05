package org.metrolist.beatweave

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicKeyAnalysisTest {
    /** Concert-pitch triads retain their expected labels with the longer chroma window. */
    @Test
    fun highResolutionChromaAvoidsShortFftBinKeyBias() {
        val cases =
            listOf(
                "B minor" to intArrayOf(59, 62, 66),
                "E minor" to intArrayOf(52, 55, 59),
                "G# major" to intArrayOf(56, 60, 63),
            )
        for ((expected, notes) in cases) {
            val actual = MusicAnalyzer().analyze(chord(notes), SAMPLE_RATE).keyEstimate
            assertEquals(expected, actual)
        }
    }

    /** A consistent tuning offset must not change the chord's root or major/minor mode. */
    @Test
    fun keyEstimateCompensatesForGlobalTuningOffset() {
        val cases =
            listOf(
                Triple("C major", intArrayOf(48, 52, 55), -45.0),
                Triple("E minor", intArrayOf(52, 55, 59), -45.0),
                Triple("F# major", intArrayOf(54, 58, 61), 45.0),
                Triple("A minor", intArrayOf(57, 60, 64), 45.0),
            )
        for ((expected, notes, cents) in cases) {
            val actual = MusicAnalyzer().analyze(chord(notes, cents), SAMPLE_RATE).keyEstimate
            assertEquals(expected, actual)
        }
    }

    /** Synthesizes a modulated triad with all partials shifted by the same number of cents. */
    private fun chord(notes: IntArray, cents: Double = 0.0): FloatArray =
        FloatArray(SAMPLE_RATE * 12) { sample ->
            val time = sample.toDouble() / SAMPLE_RATE
            (notes.sumOf { midi ->
                val hz = 440.0 * 2.0.pow((midi - 69 + cents / 100.0) / 12.0)
                0.22 * sin(2.0 * PI * hz * time) + 0.08 * sin(4.0 * PI * hz * time)
            } * (0.75 + 0.25 * sin(2.0 * PI * 2.0 * time))).toFloat()
        }

    private companion object {
        const val SAMPLE_RATE = 11025
    }
}
