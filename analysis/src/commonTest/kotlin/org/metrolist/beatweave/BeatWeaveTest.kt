package org.metrolist.beatweave

import kotlin.math.*
import kotlin.test.*

class BeatWeaveTest {
    @Test
    fun clickTrackAt156Bpm() {
        val rate = 11025
        val samples = FloatArray(rate * 24)
        for (beat in 0 until 62) {
            val pos = (beat * 60.0 / 156.0 * rate).roundToInt()
            for (i in 0..min(120, samples.size - pos - 1)) samples[pos + i] +=
                (exp(-i / 19.0) * sin(i * 0.6)).toFloat()
        }
        val result = MusicAnalyzer().analyze(samples, rate)
        assertTrue(abs(result.bpm - 156.0) < 2.0, "Estimated ${result.bpm}")
        assertTrue(result.beats.size > 40)
    }

    @Test
    fun everyMappedBeatHasZeroAccumulatedDrift() {
        val first = BeatGrid(DoubleArray(900) { it * 60.0 / 156.0 })
        val second =
            BeatGrid(DoubleArray(900) { it * 60.0 / 155.0 + 0.11 + 0.006 * sin(it / 17.0) })
        val plan = MixPlan(first, second, 100, 5)
        for (beat in 5..700) {
            val output = first.at(100 + beat - 5)
            assertEquals(second.at(beat), plan.secondSourceTime(output), 1e-8)
            assertEquals(output, plan.secondOutputTime(second.at(beat)), 1e-8)
        }
    }

    @Test
    fun unequalPulseCyclesAlignAtEveryBar() {
        val a = BeatGrid(DoubleArray(900) { it * 60.0 / 156.0 })
        val b = BeatGrid(DoubleArray(600) { it * 60.0 / 104.0 + 0.2 })
        val plan = MixPlan(a, b, firstBeat = 0, firstBeatsPerCycle = 3, secondBeatsPerCycle = 2)
        for (bar in 0..200) {
            val output = a.at(bar * 3)
            assertEquals(b.at(bar * 2), plan.secondSourceTime(output), 1e-8)
            assertEquals(output, plan.secondOutputTime(b.at(bar * 2)), 1e-8)
        }
    }
}
