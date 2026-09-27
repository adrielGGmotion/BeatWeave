package org.metrolist.beatweave

import kotlin.math.*
import kotlin.test.*

class PreparedMusicAnalysisTest {
    private val rate = 11025

    private fun audio(): FloatArray =
        FloatArray(rate * 12).also { pcm ->
            for (beat in 0 until 35) {
                val start = ((0.13 + beat * 60.0 / 173.0) * rate).roundToInt()
                for (j in 0 until 180) if (start + j in pcm.indices)
                    pcm[start + j] +=
                        (exp(-j / 35.0) * sin(j * .91) * if (beat % 4 == 0) .9 else .5).toFloat()
            }
        }

    private fun assertSameAnalysis(expected: Analysis, actual: Analysis) {
        assertContentEquals(expected.onsetEnvelope, actual.onsetEnvelope)
        assertEquals(expected, actual.copy(onsetEnvelope = expected.onsetEnvelope))
    }

    @Test
    fun repeatedTempoSearchMatchesFreshMeasurementsExactly() {
        val pcm = audio()
        val analyzer = MusicAnalyzer()
        val prepared = analyzer.prepare(pcm, rate)
        for (bpm in listOf(null, 86.5, 173.0, null)) {
            assertSameAnalysis(analyzer.analyze(pcm, rate, bpm), prepared.analyze(bpm))
        }
    }

    @Test
    fun snapshotAndSubsequentResultsAreIsolatedFromCallerMutation() {
        val pcm = audio()
        val snapshot = MusicAnalyzer().prepare(pcm, rate)
        val baseline = snapshot.analyze()
        pcm.fill(Float.NaN)
        val edited = snapshot.analyze()
        edited.onsetEnvelope.fill(Float.NaN)
        (edited.energyBlocks as? MutableList<EnergyBlock>)?.clear()
        (edited.beats as? MutableList<Beat>)?.clear()
        (edited.tempoCandidates as? MutableList<TempoCandidate>)?.clear()
        assertSameAnalysis(baseline, snapshot.analyze())
    }

    @Test
    fun cancellationRemainsActiveDuringPreparationAndEverySearch() {
        class Cancelled : RuntimeException()
        var checks = 0
        assertFailsWith<Cancelled> {
            MusicAnalyzer().prepare(audio(), rate) { if (++checks == 4) throw Cancelled() }
        }
        assertEquals(4, checks)
        val prepared = MusicAnalyzer().prepare(audio(), rate)
        assertFailsWith<Cancelled> { prepared.analyze { throw Cancelled() } }
        assertFailsWith<IllegalArgumentException> { prepared.analyze(Double.NaN) }
        assertTrue(prepared.analyze().beats.isNotEmpty())
    }
}
