/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.metrolist.beatweave.rubberband

import java.io.File
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*
import org.metrolist.beatweave.*

class RubberBandPitchShiftTest {
    private val rate = 44100
    private val sourceFrames = 88201L

    private fun audio(
        frameCount: Long = sourceFrames,
        sample: (Long) -> Double = { sin(2 * PI * 440 * it / rate) * .4 },
    ) = object : StereoPcm {
        override val durationSeconds = frameCount.toDouble() / rate

        override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
            readFrames((startSeconds * rate).roundToLong(), frames, outputSampleRate)

        override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int) =
            FloatArray(frames * 2) { i ->
                val frame = startFrame + i / 2
                if (frame !in 0 until frameCount) 0f
                else (sample(frame) * if (i % 2 == 0) 1.0 else -.5).toFloat()
            }
    }

    private fun withCache(block: (File) -> Unit) {
        val cache = Files.createTempDirectory("beatweave-pitch-test-").toFile()
        try {
            block(cache)
        } finally {
            cache.deleteRecursively()
        }
    }

    /** A projection onto the expected tone detects pitch drift and spurious harmonics. */
    private fun toneEnergyFraction(pcm: FloatArray, frequency: Double): Double {
        val count = pcm.size / 2
        var cosEnergy = 0.0
        var sinEnergy = 0.0
        var cosSin = 0.0
        var cosProjection = 0.0
        var sinProjection = 0.0
        var energy = 0.0
        for (i in 0 until count) {
            val phase = 2 * PI * frequency * i / rate
            val c = cos(phase)
            val s = sin(phase)
            val value = pcm[2 * i].toDouble()
            cosEnergy += c * c
            sinEnergy += s * s
            cosSin += c * s
            cosProjection += value * c
            sinProjection += value * s
            energy += value * value
        }
        val determinant = cosEnergy * sinEnergy - cosSin * cosSin
        val c = (cosProjection * sinEnergy - sinProjection * cosSin) / determinant
        val s = (sinProjection * cosEnergy - cosProjection * cosSin) / determinant
        return (c * cosProjection + s * sinProjection) / energy
    }

    private fun assertTone(pcm: FloatArray, frequency: Double, checkGain: Boolean = true) {
        assertTrue(pcm.all { it.isFinite() })
        assertTrue(toneEnergyFraction(pcm, frequency) > .99, "Shifted tone lost spectral coherence")
        for (i in 0 until pcm.size / 2) {
            assertEquals(-.5f * pcm[2 * i], pcm[2 * i + 1], .002f)
        }
        if (checkGain) {
            val rms = sqrt((0 until pcm.size / 2).sumOf { pcm[2 * it].toDouble().pow(2) } / (pcm.size / 2))
            assertEquals(.4 / sqrt(2.0), rms, .006)
        }
    }

    @Test
    fun octaveAndFractionalShiftsPreserveDurationToneAndStereo() = withCache { cache ->
        val source = audio()
        val engine: PitchShiftEngine = RubberBandEngine(cache)
        for (semitones in listOf(-24.0, -12.0, -.25, .01, 3.5, 12.0, 24.0)) {
            val shift = PitchShift(semitones, preserveFormants = false)
            val prepared = engine.preparePitchShift(source, rate, shift)
            try {
                assertEquals(source.durationSeconds, prepared.durationSeconds)
                assertTone(prepared.readFrames(11025, 22050, rate), 440 * shift.ratio)
                assertTrue(prepared.readFrames(sourceFrames, 32, rate).all { it == 0f })
                assertContentEquals(
                    prepared.readFrames(19000, 4096, rate),
                    prepared.readFrames(19000, 4096, rate),
                )
            } finally {
                prepared.close()
            }
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun zeroPitchConvenienceIsBitExact() = withCache { cache ->
        val source = audio()
        val prepared = RubberBandEngine(cache).preparePitchShift(source, rate, PitchShift.None)
        try {
            assertEquals(source.durationSeconds, prepared.durationSeconds)
            assertContentEquals(source.readFrames(8000, 10000, rate), prepared.readFrames(8000, 10000, rate))
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun simultaneousWarpAndPitchHaveIndependentClocks() = withCache { cache ->
        val source = audio()
        val variableBeats = DoubleArray(20)
        for (i in 1 until variableBeats.size) {
            variableBeats[i] = variableBeats[i - 1] + .5 * (1 + .035 * sin(i * .6))
        }
        val plan = MixPlan(
            BeatGrid(variableBeats),
            BeatGrid(DoubleArray(20) { it * .5 }),
            0,
            0,
            4,
            outputSampleRate = rate,
        )
        val schedule = WarpSchedule.from(plan, source.durationSeconds)
        val shift = PitchShift(3.5, preserveFormants = false)
        val prepared = RubberBandEngine(cache).prepare(source, schedule, shift)
        try {
            assertEquals(schedule.outputFrames.toDouble() / rate, prepared.durationSeconds)
            assertTone(prepared.readFrames(11025, 22050, rate), 440 * shift.ratio)
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun nonuniformWarpWithPitchKeepsPairedTransientEventsOnTheirBeatAnchors() = withCache { cache ->
        val sourceBeats = DoubleArray(16) { .35 + it * .5 }
        val outputBeats = DoubleArray(16) { .35 }
        for (i in 1 until outputBeats.size) {
            outputBeats[i] = outputBeats[i - 1] + .48 + .015 * sin(i * .6)
        }
        val source = audio(frameCount = 8L * rate) { frame ->
            val t = frame.toDouble() / rate
            val event = ((t - .35) / .5).roundToInt()
            if (event !in sourceBeats.indices) 0.0
            else {
                val delta = t - sourceBeats[event]
                if (abs(delta) > .012) 0.0
                else .7 * exp(-.5 * (delta / .0012).pow(2)) * cos(2 * PI * 1400 * delta)
            }
        }
        val plan = MixPlan(
            BeatGrid(outputBeats), BeatGrid(sourceBeats), 0, 0, 12,
            outputSampleRate = rate,
        )
        val schedule = WarpSchedule.from(plan, source.durationSeconds)
        val prepared = RubberBandEngine(cache).prepare(source, schedule, PitchShift(3.5, false))
        try {
            assertEquals(schedule.outputFrames.toDouble() / rate, prepared.durationSeconds)
            // Event positions come from the constructed paired grids, independently of
            // rendered peaks or the plan's continuous time-conversion implementation.
            // R3 may smear the Gaussian pulse slightly; bound the peak to 10 ms.
            for (event in 1 until outputBeats.lastIndex) {
                val expectedFrame = (outputBeats[event] * rate).roundToLong() - schedule.outputOriginFrame
                val radius = (rate * .03).roundToInt()
                val pcm = prepared.readFrames(expectedFrame - radius, radius * 2 + 1, rate)
                val peak = (0 until pcm.size / 2).maxBy { abs(pcm[2 * it]) }
                assertTrue(abs(pcm[2 * peak]) > .08f, "Pitch shift lost transient $event")
                assertTrue(abs(peak - radius) <= rate * .010, "Pitch shift moved beat event $event by ${1000.0 * (peak - radius) / rate} ms")
            }
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun silenceStaysSilentWithFormantPreservation() = withCache { cache ->
        val source = audio(sample = { 0.0 })
        val prepared = RubberBandEngine(cache).preparePitchShift(source, rate, PitchShift(-7.0))
        try {
            assertEquals(source.durationSeconds, prepared.durationSeconds)
            assertTrue(prepared.readFrames(0, sourceFrames.toInt(), rate).all { it == 0f })
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun formantOptionChangesSpectralEnvelopeWithoutChangingShiftOrDuration() = withCache { cache ->
        // A voiced harmonic stack with two formant-like peaks, rather than a bare sine.
        val source = audio { frame ->
            (1..30).sumOf { harmonic ->
                val frequency = harmonic * 110.0
                val envelope = exp(-.5 * ((frequency - 700) / 140).pow(2)) +
                    .6 * exp(-.5 * ((frequency - 1500) / 240).pow(2))
                envelope * sin(2 * PI * frequency * frame / rate)
            } * .03
        }
        val engine = RubberBandEngine(cache)
        val shifted = engine.preparePitchShift(source, rate, PitchShift(5.0, preserveFormants = false))
        val preserved = engine.preparePitchShift(source, rate, PitchShift(5.0, preserveFormants = true))
        try {
            assertEquals(source.durationSeconds, shifted.durationSeconds)
            assertEquals(source.durationSeconds, preserved.durationSeconds)
            val a = shifted.readFrames(22050, 22050, rate)
            val b = preserved.readFrames(22050, 22050, rate)
            assertTrue(a.all { it.isFinite() } && b.all { it.isFinite() })
            val difference = sqrt(a.indices.sumOf { (a[it] - b[it]).toDouble().pow(2) } / a.size)
            assertTrue(difference > .005, "Native engine ignored the formant option")
        } finally {
            shifted.close()
            preserved.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun cancellingPitchStudyAndRenderDeletesPartialFiles() = withCache { cache ->
        for (threshold in listOf(.15, .65)) {
            assertFailsWith<MixCancelledException> {
                RubberBandEngine(cache).preparePitchShift(audio(), rate, PitchShift(3.0)) {
                    if (it > threshold) throw MixCancelledException()
                }
            }
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun shortNonzeroShiftFailsExplicitlyWithoutCacheFiles() = withCache { cache ->
        for (frames in listOf(1L, 10L, rate / 10L - 1)) {
            val failure = assertFailsWith<IllegalArgumentException> {
                RubberBandEngine(cache).preparePitchShift(audio(frames), rate, PitchShift(12.0))
            }
            assertTrue(failure.message.orEmpty().contains("100 ms"))
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        val source = audio(frameCount = 10)
        val prepared = RubberBandEngine(cache).preparePitchShift(source, rate, PitchShift.None)
        try {
            assertContentEquals(source.readFrames(0, 10, rate), prepared.readFrames(0, 10, rate))
        } finally {
            prepared.close()
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun minimumDurationAtSupportedRateBoundariesProducesCompleteAudio() = withCache { cache ->
        for (sampleRate in listOf(8000, 11025, 44100, 48000, 192000)) {
            val frameCount = (sampleRate + 9) / 10
            val source = object : StereoPcm {
                override val durationSeconds = frameCount.toDouble() / sampleRate
                override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                    readFrames((startSeconds * sampleRate).roundToLong(), frames, outputSampleRate)
                override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int) =
                    FloatArray(frames * 2) { i ->
                        (.2 * sin(2 * PI * 440 * (startFrame + i / 2) / sampleRate)).toFloat()
                    }
            }
            for (semitones in listOf(-24.0, 24.0)) {
                val prepared = RubberBandEngine(cache).preparePitchShift(
                    source, sampleRate, PitchShift(semitones, preserveFormants = false),
                )
                try {
                    assertEquals(source.durationSeconds, prepared.durationSeconds)
                    val output = prepared.readFrames(0, frameCount, sampleRate)
                    assertTrue(output.all { it.isFinite() })
                    assertTrue(output.any { abs(it) > .001f })
                    assertTrue(prepared.readFrames(frameCount.toLong(), 10, sampleRate).all { it == 0f })
                } finally {
                    prepared.close()
                }
            }
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun nativeEntryRejectsUnsupportedPitchBeforeAllocatingSession() {
        for (ratio in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, .249, 4.001)) {
            assertFailsWith<IllegalStateException> {
                RubberBandBridge.createWithPitch(rate, sourceFrames, sourceFrames, ratio, true)
            }
        }
        assertFailsWith<IllegalStateException> {
            RubberBandBridge.createWithPitch(rate, 1, 1, 2.0, false)
        }
    }
}
