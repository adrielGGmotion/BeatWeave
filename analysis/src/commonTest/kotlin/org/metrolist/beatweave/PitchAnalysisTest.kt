package org.metrolist.beatweave

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class PitchAnalysisTest {
    private fun tone(hz: Double, rate: Int, seconds: Double = 0.4, cents: Double = 0.0): FloatArray =
        FloatArray((rate * seconds).roundToInt()) {
            (0.4 * sin(2.0 * PI * hz * 2.0.pow(cents / 1200.0) * it / rate + 0.73)).toFloat()
        }

    private fun centsError(actual: Double, expected: Double): Double =
        abs(1200.0 * log2(actual / expected))

    @Test
    fun detectsKnownFundamentalsAcrossSampleRatesWithoutOctaveErrors() {
        for (rate in listOf(11025, 44100, 48000)) {
            for (hz in listOf(50.0, 55.0, 82.4069, 220.0, 440.0, 1500.0, 1600.0, 1800.0, 1900.0, 1990.0, 2000.0)) {
                val analysis = PitchAnalyzer().analyze(tone(hz, rate), rate)
                assertTrue(analysis.frames.isNotEmpty())
                for (frame in analysis.frames) {
                    val frequency = assertNotNull(frame.frequencyHz, "$hz Hz at $rate Hz")
                    assertTrue(centsError(frequency, hz) < 8.0, "$hz Hz at $rate Hz measured $frequency Hz")
                    assertTrue(frame.confidence > 0.9)
                }
            }
        }
    }

    @Test
    fun reportsMidiNoteAndSignedCentsWithSourceWindowTimestamps() {
        val rate = 11025
        val analysis = PitchAnalyzer().analyze(tone(440.0, rate, cents = -23.0), rate)
        assertEquals(0.4, analysis.durationSeconds, 1.0 / rate)
        val first = analysis.frames.first()
        assertEquals(69, first.midiNote)
        assertEquals("A4", first.noteName)
        assertEquals(-23.0, assertNotNull(first.cents), 1.0)
        assertEquals((analysis.windowSeconds - 1.0 / rate) / 2.0, first.seconds, 1e-12)
        for ((a, b) in analysis.frames.zipWithNext()) {
            assertEquals(analysis.hopSeconds, b.seconds - a.seconds, 1e-12)
        }
        assertTrue(analysis.frames.last().seconds + analysis.windowSeconds / 2.0 <= analysis.durationSeconds)
    }

    @Test
    fun distinguishesSilenceDcOffsetAndAperiodicNoiseFromVoicedAudio() {
        val rate = 11025
        val random = Random(502)
        val noise = FloatArray(rate / 2) { random.nextDouble(-0.5, 0.5).toFloat() }
        for (pcm in listOf(FloatArray(rate / 2), FloatArray(rate / 2) { 0.25f }, noise)) {
            val analysis = PitchAnalyzer().analyze(pcm, rate)
            assertTrue(analysis.frames.all { !it.isVoiced })
            assertTrue(analysis.frames.all { it.frequencyHz == null && it.midiNote == null && it.cents == null && it.noteName == null })
            assertTrue(analysis.frames.all { it.confidence in 0.0..1.0 && it.rmsDb.isFinite() })
        }
        val shifted = tone(220.0, rate).map { it + 0.7f }.toFloatArray()
        assertTrue(PitchAnalyzer().analyze(shifted, rate).frames.all { it.isVoiced })
    }

    @Test
    fun recoversMissingFundamentalFromSecondAndThirdHarmonics() {
        val rate = 11025
        val hz = 130.8128
        val pcm = FloatArray(rate / 2) {
            val phase = 2.0 * PI * hz * it / rate
            (0.35 * sin(phase * 2.0) + 0.28 * sin(phase * 3.0 + 0.2)).toFloat()
        }
        for (frame in PitchAnalyzer().analyze(pcm, rate).frames) {
            assertTrue(centsError(assertNotNull(frame.frequencyHz), hz) < 3.0)
            assertEquals("C3", frame.noteName)
        }
    }

    @Test
    fun aboveRangeToneIsNotAcceptedAtAPeriodMultiple() {
        val options = PitchAnalysisOptions(maximumFrequencyHz = 600.0)
        val result = PitchAnalyzer(options).analyze(tone(1000.0, 11025), 11025)
        assertTrue(result.frames.all { !it.isVoiced })
    }

    @Test
    fun stereoAntiphaseAndSilentLeftChannelRetainPitch() {
        val rate = 11025
        val pcm = tone(440.0, rate)
        for (leftGain in listOf(-1.0f, 0.0f)) {
            val source = object : StereoPcm {
                override val durationSeconds = pcm.size.toDouble() / rate
                override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray =
                    readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)
                override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
                    assertEquals(rate, outputSampleRate)
                    return FloatArray(frames * 2) {
                        pcm[(startFrame + it / 2).toInt()] * if (it % 2 == 0) leftGain else 1.0f
                    }
                }
            }
            val analysis = PitchAnalyzer().analyze(source, rate)
            assertTrue(analysis.frames.all { centsError(assertNotNull(it.frequencyHz), 440.0) < 1.0 })
        }
    }

    @Test
    fun stereoChannelSelectionDoesNotFlickerOnSubDecibelLevelChanges() {
        val rate = 11025
        val durationSeconds = 1.0
        val totalFrames = (rate * durationSeconds).roundToInt()
        val source = object : StereoPcm {
            override val durationSeconds = durationSeconds

            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

            override fun readFrames(
                startFrame: Long,
                frames: Int,
                outputSampleRate: Int,
            ): FloatArray {
                assertEquals(rate, outputSampleRate)
                return FloatArray(frames * 2) {
                    val frame = startFrame + it / 2
                    val time = frame.toDouble() / rate
                    val balance = 0.04 * sin(2.0 * PI * time / 0.16)
                    val leftGain = if (time < 0.6) 0.4 * (1.0 + balance) else 0.04
                    val rightGain = if (time < 0.6) 0.4 * (1.0 - balance) else 0.4
                    val hz = if (it % 2 == 0) 220.0 else 440.0
                    val gain = if (it % 2 == 0) leftGain else rightGain
                    (gain * sin(2.0 * PI * hz * frame / rate)).toFloat()
                }
            }
        }

        val result = PitchAnalyzer().analyze(source, rate)
        val nearlyBalanced = result.frames.filter { it.seconds < 0.5 }
        val decisiveRight = result.frames.filter { it.seconds > 0.75 }

        assertEquals(totalFrames.toDouble() / rate, result.durationSeconds, 1.0 / rate)
        assertTrue(nearlyBalanced.isNotEmpty() && decisiveRight.isNotEmpty())
        assertTrue(
            nearlyBalanced.all { centsError(assertNotNull(it.frequencyHz), 220.0) < 3.0 },
            "Sub-decibel balance changes switched the selected pitch",
        )
        assertTrue(decisiveRight.all { centsError(assertNotNull(it.frequencyHz), 440.0) < 3.0 })
    }

    @Test
    fun sequentialStereoWindowsReuseBoundedDecoderReads() {
        val rate = 11025
        val pcm = tone(440.0, rate, seconds = 1.2)
        var reads = 0
        var framesRead = 0L
        val source = object : StereoPcm {
            override val durationSeconds = pcm.size.toDouble() / rate

            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

            override fun readFrames(
                startFrame: Long,
                frames: Int,
                outputSampleRate: Int,
            ): FloatArray {
                assertEquals(rate, outputSampleRate)
                assertTrue(startFrame >= 0L && startFrame + frames <= pcm.size)
                reads++
                framesRead += frames
                return FloatArray(frames * 2) { pcm[(startFrame + it / 2).toInt()] }
            }
        }
        val analyzer = PitchAnalyzer()

        assertEquals(analyzer.analyze(pcm, rate), analyzer.analyze(source, rate))
        assertEquals(1, reads)
        assertEquals(pcm.size.toLong(), framesRead)
    }

    @Test
    fun overlappingMaximumWindowsDecodeEverySourceFrameAtMostOnce() {
        val rate = 384000
        val sourceFrames = rate
        var reads = 0
        var framesRead = 0L
        var nextExpectedFrame = 0L
        val source = object : StereoPcm {
            override val durationSeconds = sourceFrames.toDouble() / rate

            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

            override fun readFrames(
                startFrame: Long,
                frames: Int,
                outputSampleRate: Int,
            ): FloatArray {
                assertEquals(rate, outputSampleRate)
                assertEquals(nextExpectedFrame, startFrame)
                assertTrue(startFrame >= 0L && startFrame + frames <= sourceFrames)
                reads++
                framesRead += frames
                nextExpectedFrame += frames
                return FloatArray(frames * 2)
            }
        }
        val analyzer =
            PitchAnalyzer(
                PitchAnalysisOptions(
                    minimumFrequencyHz = 20.0,
                    maximumFrequencyHz = 2000.0,
                    hopSeconds = 0.005,
                    windowSeconds = 0.5,
                ),
            )

        val result = analyzer.analyze(source, rate)

        assertEquals(101, result.frames.size)
        assertEquals(3, reads)
        assertEquals(sourceFrames.toLong(), framesRead)
        assertEquals(sourceFrames.toLong(), nextExpectedFrame)
        assertTrue(result.frames.all { !it.isVoiced })
    }

    @Test
    fun frameCountDerivedStereoDurationsMatchMonoWithoutLosingCompleteWindows() {
        val rate = 11025
        val options = PitchAnalysisOptions(windowSeconds = 889.0 / rate)
        val pcm = tone(440.0, rate).copyOf(889)
        val source = object : StereoPcm {
            override val durationSeconds = pcm.size.toDouble() / rate
            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray =
                FloatArray(frames * 2) { pcm[(startSeconds * rate).roundToInt() + it / 2] }
        }
        val analyzer = PitchAnalyzer(options)
        val mono = analyzer.analyze(pcm, rate)
        assertEquals(1, mono.frames.size)
        assertEquals(mono, analyzer.analyze(source, rate))
    }

    /** DC bias must not hide a stronger pitched channel or promote a weaker competing tone. */
    @Test
    fun stereoChannelSelectionIgnoresDcBias() {
        for (rate in listOf(11025, 48000)) {
            val strong = tone(440.0, rate)
            val weak = tone(220.0, rate)
            for (weakGain in listOf(0.0f, 0.25f)) {
                for (strongChannel in 0..1) {
                    val source = object : StereoPcm {
                        override val durationSeconds = strong.size.toDouble() / rate
                        override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                            readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

                        override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
                            assertEquals(rate, outputSampleRate)
                            return FloatArray(frames * 2) {
                                val index = (startFrame + it / 2).toInt()
                                if (it % 2 == strongChannel) strong[index]
                                else 0.6f + weak[index] * weakGain
                            }
                        }
                    }
                    val stereo = PitchAnalyzer().analyze(source, rate)
                    assertTrue(stereo.frames.isNotEmpty())
                    for (frame in stereo.frames) {
                        val frequency = assertNotNull(frame.frequencyHz,
                            "DC-biased channel hid the tone at $rate Hz; strong channel $strongChannel")
                        assertTrue(centsError(frequency, 440.0) < 1.0,
                            "Selected $frequency Hz with weak gain $weakGain at $rate Hz")
                    }
                }
            }
        }
    }

    @Test
    fun cancellationInvalidInputsAndDecoderContractRemainExplicit() {
        class Cancelled : RuntimeException()
        var checks = 0
        val pcm = tone(440.0, 11025)
        assertFailsWith<Cancelled> {
            PitchAnalyzer().analyze(pcm, 11025) { if (++checks == 4) throw Cancelled() }
        }
        assertEquals(4, checks)
        assertFailsWith<IllegalArgumentException> { PitchAnalyzer().analyze(FloatArray(8), 11025) }
        assertFailsWith<IllegalArgumentException> { PitchAnalyzer().analyze(pcm, 0) }
        assertFailsWith<IllegalArgumentException> { PitchAnalyzer().analyze(pcm, 4000) }
        assertFailsWith<IllegalArgumentException> { PitchAnalyzer().analyze(pcm.copyOf().also { it[1] = Float.NaN }, 11025) }
        assertFailsWith<IllegalArgumentException> { PitchAnalysisOptions(hopSeconds = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PitchAnalysisOptions(minimumFrequencyHz = -1.0) }
        val invalid = object : StereoPcm {
            override val durationSeconds = 1.0
            override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) = FloatArray(1)
        }
        assertFailsWith<IllegalArgumentException> { PitchAnalyzer().analyze(invalid) }
        assertTrue(PitchAnalyzer().analyze(pcm, 11025).frames.all { it.isVoiced })
    }

    @Test
    fun maximumWindowCancellationIsCheckedInsideTheFirstFft() {
        class Cancelled : RuntimeException()
        val rate = 384000
        val options =
            PitchAnalysisOptions(
                minimumFrequencyHz = 20.0,
                maximumFrequencyHz = 2000.0,
                hopSeconds = 0.5,
                windowSeconds = 0.5,
            )
        val pcm = tone(220.0, rate, seconds = 0.5)
        var checks = 0

        assertFailsWith<Cancelled> {
            PitchAnalyzer(options).analyze(pcm, rate) {
                if (++checks == 28) throw Cancelled()
            }
        }
        // Before in-transform polling, this one-window analysis made only 27 checks and returned.
        assertTrue(checks >= 28)
    }

    @Test
    fun maximumWindowCancellationCoversLinearFftPasses() {
        class Cancelled : RuntimeException()
        val rate = 384000
        val options =
            PitchAnalysisOptions(
                minimumFrequencyHz = 20.0,
                maximumFrequencyHz = 2000.0,
                hopSeconds = 0.5,
                windowSeconds = 0.5,
            )
        val pcm = tone(220.0, rate, seconds = 0.5)
        var checks = 0

        assertFailsWith<Cancelled> {
            PitchAnalyzer(options).analyze(pcm, rate) {
                if (++checks == 338) throw Cancelled()
            }
        }
        // Previously this maximum-size analysis completed after 337 polls: bit reversal,
        // spectrum conversion, inverse normalization and the final 262,144-butterfly stage
        // could not observe a cancellation request within their linear work.
        assertEquals(338, checks)
    }
}
