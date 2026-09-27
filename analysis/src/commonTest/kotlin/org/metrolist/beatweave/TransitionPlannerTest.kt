package org.metrolist.beatweave

import kotlin.test.*

class TransitionPlannerTest {
    @Test
    fun differentMetersMatchEveryBeatAndBothPhraseEndpoints() {
        val a =
            BarGrid(BeatGrid(DoubleArray(201) { 0.25 + it * 60.0 / 156 }), IntArray(101) { it * 2 })
        val b =
            BarGrid(BeatGrid(DoubleArray(201) { 0.11 + it * 60.0 / 155 }), IntArray(51) { it * 4 })
        val chosen = TransitionPlanner.transition(a, b, 4, 2, 16)
        assertEquals(8, chosen.incomingBars)
        for (i in 0..32) assertEquals(
            b.beats.at(8 + i),
            chosen.mixPlan.secondSourceTime(a.beats.at(8 + i)),
            1e-8,
        )
    }

    @Test
    fun tripleAndQuadrupleBarsDoNotInventMatchingDownbeats() {
        val beats = BeatGrid(DoubleArray(401) { it * 0.4 })
        val a = BarGrid(beats, IntArray(101) { it * 3 })
        val b = BarGrid(beats, IntArray(101) { it * 4 })
        assertFailsWith<IllegalArgumentException> { TransitionPlanner.transition(a, b, 0, 0, 2) }
        assertEquals(listOf(4, 8, 16, 32), TransitionPlanner.compatibleBarCounts(a, b, 0, 0))
        assertEquals(3, TransitionPlanner.transition(a, b, 0, 0, 4).incomingBars)
    }

    @Test
    fun downbeatsMustCorrespondToRealBeats() {
        val beats = BeatGrid(DoubleArray(20) { it * 0.5 })
        assertFailsWith<IllegalArgumentException> { BarGrid.from(beats, listOf(0.25, 2.25)) }
        assertFailsWith<IllegalArgumentException> { BarGrid.from(beats, emptyList()) }
        val bars = BarGrid.from(beats, listOf(0.01, 2.01, 4.01))
        assertEquals(4, bars.beatsInBar(0))
    }
}
