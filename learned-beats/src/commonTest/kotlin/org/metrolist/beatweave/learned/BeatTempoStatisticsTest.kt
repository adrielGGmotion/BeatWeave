package org.metrolist.beatweave.learned

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.metrolist.beatweave.Beat

class BeatTempoStatisticsTest {
    @Test
    fun primitiveStatisticsPreserveExistingSummaryAndModelSemantics() {
        val beats =
            List(12) { index ->
                Beat(index * 0.5 + if (index == 4) 0.08 else 0.0, (index / 11f))
            }
        val periods =
            beats.windowed(9).map { (it.last().seconds - it.first().seconds) / 8 }.sorted()
        val expectedBpm = 60.0 / periods[periods.size / 2]
        val expectedStrength =
            beats.map { it.strength.toDouble().coerceIn(0.0, 1.0) }.average()

        assertEquals(expectedBpm, canonicalPulseBpm(beats) {})
        assertEquals(expectedBpm, robustModelPulseBpm(beats) {})
        assertEquals(expectedStrength, meanBeatStrength(beats) {})
        assertNull(canonicalPulseBpm(beats.take(8)) {})

        val malformed = List(9) { Beat(if (it == 8) -1.0 else it * 0.5, 1f) }
        assertNull(robustModelPulseBpm(malformed) {})
    }

    @Test
    fun cancellationStopsBeforeReadingBeyondAStatisticsChunk() {
        class Cancelled : RuntimeException()
        var reads = 0
        val beats =
            object : AbstractList<Beat>() {
                override val size = 50_000

                override fun get(index: Int): Beat {
                    reads++
                    return Beat(index * 0.001, 0.5f)
                }
            }
        var polls = 0

        assertFailsWith<Cancelled> {
            canonicalPulseBpm(beats) {
                if (++polls == 2) throw Cancelled()
            }
        }

        assertEquals(256, reads)
        assertEquals(2, polls)

        reads = 0
        polls = 0
        assertFailsWith<Cancelled> {
            meanBeatStrength(beats) {
                if (++polls == 2) throw Cancelled()
            }
        }
        assertEquals(256, reads)
        assertEquals(2, polls)
    }

    @Test
    fun cancellationInterruptsPrimitiveMedianSorting() {
        class Cancelled : RuntimeException()
        val beats = List(50_000) { Beat(it * 0.001, 0.5f) }
        val scanPolls = 1 + (beats.size - 1) / 256
        var polls = 0

        assertFailsWith<Cancelled> {
            canonicalPulseBpm(beats) {
                if (++polls == scanPolls + 2) throw Cancelled()
            }
        }

        // The threshold follows span extraction and sort entry, so this callback is inside merge.
        assertEquals(scanPolls + 2, polls)
    }
}
