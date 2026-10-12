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

    @Test
    fun cancellationIsPolledWhileValidatingLongPcm() {
        class Cancelled : RuntimeException()
        val pcm = FloatArray(65536 * 2 + 1)
        pcm[pcm.lastIndex] = Float.NaN
        var checks = 0

        assertFailsWith<Cancelled> {
            MusicAnalyzer().prepare(pcm, 4000) {
                if (++checks == 3) throw Cancelled()
            }
        }
        // Before validation-loop polling, the trailing NaN failed validation before check 3.
        assertEquals(3, checks)
    }

    @Test
    fun cancellationInterruptsInsideOneEnergyBlock() {
        class Cancelled : RuntimeException()
        // This is shorter than one four-second block at 20 kHz, but crosses the 65,536-sample
        // cancellation boundary inside that block.
        val pcm = FloatArray(65_537) { 0.25f }
        var checks = 0

        assertFailsWith<Cancelled> {
            MusicAnalyzer().measureEnergyBlocks(pcm, 20_000) {
                if (++checks == 2) throw Cancelled()
            }
        }

        // Previously the whole block completed after its single entry poll.
        assertEquals(2, checks)
    }

    @Test
    fun cancellationInterruptsMaximumKeyAnalysisFft() {
        class Cancelled : RuntimeException()
        val size = 262144
        var checks = 0

        assertFailsWith<Cancelled> {
            analysisFft(DoubleArray(size), DoubleArray(size)) {
                if (++checks == 10) throw Cancelled()
            }
        }

        // This transform contains 2,359,296 butterflies plus bit reversal. Before polling was
        // added inside the FFT, the entire operation ran between two preparation callbacks.
        assertEquals(10, checks)
    }

    @Test
    fun cancellationInterruptsMaximumKeyWindowConstruction() {
        class Cancelled : RuntimeException()
        var checks = 0

        assertFailsWith<Cancelled> {
            keyAnalysisHann(262144) {
                if (++checks == 3) throw Cancelled()
            }
        }

        // Previously all 262,144 cosine evaluations ran between preparation callbacks.
        assertEquals(3, checks)
    }

    @Test
    fun cancellationInterruptsMaximumKeyWindowCopyWithoutChangingValues() {
        class Cancelled : RuntimeException()
        val size = 262144
        val pcm = floatArrayOf(2f, 3f, 5f)
        val hann = DoubleArray(size) { 0.25 }
        val real = DoubleArray(size) { Double.NaN }
        val imaginary = DoubleArray(size) { Double.NaN }
        var checks = 0

        assertFailsWith<Cancelled> {
            fillKeyAnalysisWindow(pcm, -1, hann, real, imaginary) {
                if (++checks == 3) throw Cancelled()
            }
        }

        // Previously the complete 262,144-sample copy ran after the frame-entry callback.
        assertEquals(3, checks)

        fillKeyAnalysisWindow(pcm, -1, hann, real, imaginary) {}
        assertContentEquals(doubleArrayOf(0.0, 0.5, 0.75, 1.25), real.copyOf(4))
        assertTrue(real.drop(4).all { it == 0.0 })
        assertTrue(imaginary.all { it == 0.0 })
    }

    @Test
    fun cancellationInterruptsSpectralNormalizationBeforeSilentReturn() {
        class Cancelled : RuntimeException()
        val prepared = MusicAnalyzer().prepare(FloatArray(rate * 12), rate)
        var checks = 0

        assertFailsWith<Cancelled> {
            prepared.analyze(cancellationCheck = {
                if (++checks == 3) throw Cancelled()
            })
        }

        // Before normalization polling, a silent search returned after only two callbacks.
        assertEquals(3, checks)
    }

    @Test
    fun cancellationInterruptsInsideAutocorrelationScan() {
        class Cancelled : RuntimeException()
        val prepared = MusicAnalyzer().prepare(audio(), rate)
        var checks = 0

        assertFailsWith<Cancelled> {
            prepared.analyze(cancellationCheck = {
                if (++checks == 100) throw Cancelled()
            })
        }

        // The old outer-lag polling completed this search after only 78 callbacks.
        assertEquals(100, checks)
    }

    @Test
    fun cancellableFftPreservesForwardTransform() {
        val real = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val imaginary = DoubleArray(real.size)

        analysisFft(real, imaginary) {}

        assertContentEquals(DoubleArray(real.size) { 1.0 }, real)
        assertContentEquals(DoubleArray(real.size), imaginary)
    }
}
