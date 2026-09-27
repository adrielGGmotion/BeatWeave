package org.metrolist.beatweave.learned

import kotlin.math.*
import kotlin.test.*

class BeatThisPipelineTest {
    private val identityBackend =
        object : BeatThisBackend {
            override val modelId = "test/source-clock-identity"

            override fun infer(logMel: FloatArray, frames: Int): BeatThisLogits {
                val values = FloatArray(frames) { logMel[it * 128] }
                return BeatThisLogits(values, values.copyOf())
            }
        }

    @Test
    fun preservesEverySourceFrameAcrossChunkBoundaries() {
        for (frames in listOf(151, 1487, 1488, 1489, 1500, 1501, 2976, 2977, 4501, 15000)) {
            val values = FloatArray(frames * 128)
            for (i in 0 until frames) values[i * 128] = i + 1f
            val output = BeatThisAnalyzer(identityBackend).infer(MelSpectrogram(frames, values))
            assertContentEquals(FloatArray(frames) { it + 1f }, output.beat, "frames=$frames")
            assertContentEquals(output.beat, output.downbeat)
        }
    }

    @Test
    fun plateauProducesOneCenteredBeatAndDownbeatsUseBeatClock() {
        val beats = FloatArray(100) { -5f }
        for (i in 8..12) beats[i] = 4f
        beats[40] = 4f
        beats[70] = 4f
        val downbeats =
            FloatArray(100) { -5f }
                .also {
                    it[11] = 4f
                    it[71] = 4f
                }
        val result = BeatThisAnalyzer.postprocess(BeatThisLogits(beats, downbeats))
        assertEquals(listOf(.2, .8, 1.4), result.first.map { it.seconds })
        assertEquals(listOf(.2, 1.4), result.second)
    }

    @Test
    fun flatZeroActivationsDoNotInventBeats() {
        val result = BeatThisAnalyzer.postprocess(BeatThisLogits(FloatArray(100), FloatArray(100)))
        assertTrue(result.first.isEmpty())
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun cancellationPropagatesBetweenChunks() {
        var checks = 0
        assertFailsWith<IllegalStateException> {
            BeatThisAnalyzer(identityBackend)
                .infer(
                    MelSpectrogram(5000, FloatArray(5000 * 128)),
                    { if (++checks == 2) error("cancel") },
                )
        }
        assertEquals(2, checks)
    }

    @Test
    fun centeredSilenceAndNonFiniteValidation() {
        val result = BeatThisFrontend().transform(FloatArray(22050))
        assertEquals(51, result.frames)
        assertTrue(result.values.all { it == 0f })
        assertFailsWith<IllegalArgumentException> {
            BeatThisFrontend().transform(FloatArray(22050) { Float.NaN })
        }
    }

    @Test
    fun frontendMatchesIndependentTorchGoldenValues() {
        // Oracle: torchaudio LogMelSpect at the provenance commit, not this implementation.
        // Boundary frames exercise centered reflection, interior frames FFT/mel normalization.
        val pcm =
            FloatArray(22050) { i ->
                (.3 * sin(2 * PI * 220 * i / 22050) +
                        .1 * sin(2 * PI * 997 * i / 22050) +
                        if (i == 280) .6 else 0.0)
                    .toFloat()
            }
        val mel = BeatThisFrontend().transform(pcm)
        val expected =
            listOf(
                Triple(0, 1, 6.096230984),
                Triple(0, 9, 6.225745678),
                Triple(0, 32, 4.591330051),
                Triple(1, 12, 3.167810917),
                Triple(1, 60, 3.514110804),
                Triple(2, 32, .843467593),
                Triple(25, 9, 3.206781864),
                Triple(25, 60, .004238185),
                Triple(25, 100, .000225090),
                Triple(50, 9, 6.210006237),
                Triple(50, 32, 4.429759979),
                Triple(50, 127, 2.350139141),
            )
        for ((frame, bin, value) in expected) {
            assertEquals(
                value,
                mel.values[frame * 128 + bin].toDouble(),
                .0003,
                "frame=$frame bin=$bin",
            )
        }
    }

    @Test
    fun invalidOutputDoesNotBecomeSilentSuccess() {
        val broken =
            object : BeatThisBackend {
                override val modelId = "broken"

                override fun infer(logMel: FloatArray, frames: Int) =
                    BeatThisLogits(FloatArray(frames) { Float.NaN }, FloatArray(frames))
            }
        assertFailsWith<IllegalArgumentException> {
            BeatThisAnalyzer(broken).infer(MelSpectrogram(151, FloatArray(151 * 128)))
        }
    }
}
