package org.metrolist.beatweave

import kotlin.math.*
import kotlin.test.*

class DetectorContractTest {
    @Test
    fun silentPcmDoesNotProduceABeatGrid() {
        val analysis = MusicAnalyzer().analyze(FloatArray(11025 * 5), 11025)
        assertTrue(analysis.beats.isEmpty())
        assertEquals(0.0, analysis.bpm)
        assertEquals(0.0, analysis.beatConfidence)
        assertEquals("unknown", analysis.keyEstimate)
    }

    @Test
    fun stationaryToneIsNotPeriodicPercussion() {
        val rate = 11025
        val analysis =
            MusicAnalyzer()
                .analyze(
                    FloatArray(rate * 12) { (0.4 * sin(2 * PI * 440 * it / rate)).toFloat() },
                    rate,
                )
        assertTrue(analysis.beats.isEmpty())
        assertEquals(0.0, analysis.beatConfidence)
    }

    @Test
    fun absoluteClickTimesAreCorrectAcrossSampleRates() {
        for (rate in listOf(11025, 44100, 48000)) {
            val reference = List(40) { 0.2 + it * 60.0 / 155.0 }
            val pcm = FloatArray(((reference.last() + 0.5) * rate).toInt())
            for (time in reference) {
                val sample = (time * rate).roundToInt()
                for (j in 0 until 220) pcm[sample + j] = (exp(-j / 30.0) * sin(j * 0.43)).toFloat()
            }
            val analysis = MusicAnalyzer().analyze(pcm, rate)
            assertEquals(reference.size, analysis.beats.size)
            assertTrue(abs(analysis.bpm - 155) < 0.2)
            reference.zip(analysis.beats).forEach { (expected, actual) ->
                assertEquals(expected, actual.seconds, 0.006)
            }
        }
    }

    @Test
    fun importedModelAnchorsAreNotSmoothedOrReplaced() {
        val times =
            listOf(Beat(0.153, 0.8f), Beat(0.732, 0.9f), Beat(1.304, 0.7f), Beat(1.90, 0.9f))
        val analysis =
            MusicAnalyzer()
                .analyzeWithBeats(FloatArray(11025 * 5), 11025, times, listOf(0.153), "test-model")
        assertEquals(times, analysis.beats)
        assertEquals(listOf(0.153), analysis.downbeatSeconds)
        assertEquals("test-model", analysis.detectorId)
    }

    @Test
    fun invalidAudioAndUnorderedObservationsFailAtTheBoundary() {
        assertFailsWith<IllegalArgumentException> {
            MusicAnalyzer().analyze(FloatArray(11025 * 5) { Float.NaN }, 11025)
        }
        assertFailsWith<IllegalArgumentException> {
            MusicAnalyzer()
                .analyzeWithBeats(
                    FloatArray(11025 * 5),
                    11025,
                    listOf(Beat(1.0, 0.8f), Beat(0.5, 0.9f)),
                    modelId = "test-model",
                )
        }
    }
}
