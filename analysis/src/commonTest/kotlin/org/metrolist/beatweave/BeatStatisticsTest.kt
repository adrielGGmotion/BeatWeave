package org.metrolist.beatweave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BeatStatisticsTest {
    @Test
    fun primitiveMedianPreservesAdjacentAndNineBeatSemantics() {
        val short = listOf(Beat(0.0, 1f), Beat(0.4, 1f), Beat(1.0, 1f), Beat(1.5, 1f))
        assertEquals(0.5, robustBeatInterval(short) {})

        val long =
            List(12) { index ->
                Beat(index * 0.5 + if (index == 4) 0.08 else 0.0, 1f)
            }
        val expected =
            long.windowed(9).map { (it.last().seconds - it.first().seconds) / 8 }.sorted()
        assertEquals(expected[expected.size / 2], robustBeatInterval(long) {})
    }

    @Test
    fun cancellationInterruptsLongMedianSorting() {
        class Cancelled : RuntimeException()
        val beats = List(50_000) { Beat(it * 0.001, 1f) }
        val materializationPolls = (beats.size - 8 + 255) / 256
        var polls = 0

        assertFailsWith<Cancelled> {
            robustBeatInterval(beats) {
                if (++polls == materializationPolls + 2) throw Cancelled()
            }
        }

        // The threshold is after span materialization and the sort-entry poll. Cancellation must
        // therefore be observed during merge sorting, before the full median sort completes.
        assertEquals(materializationPolls + 2, polls)
    }
}
