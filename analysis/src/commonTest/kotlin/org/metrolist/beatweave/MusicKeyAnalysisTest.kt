package org.metrolist.beatweave

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicKeyAnalysisTest {
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

    private fun chord(notes: IntArray): FloatArray =
        FloatArray(SAMPLE_RATE * 12) { sample ->
            val time = sample.toDouble() / SAMPLE_RATE
            (notes.sumOf { midi ->
                val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
                0.22 * sin(2.0 * PI * hz * time) + 0.08 * sin(4.0 * PI * hz * time)
            } * (0.75 + 0.25 * sin(2.0 * PI * 2.0 * time))).toFloat()
        }

    private companion object {
        const val SAMPLE_RATE = 11025
    }
}
