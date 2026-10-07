package org.metrolist.beatweave

import kotlin.math.min

/**
 * Returns the existing upper-median beat interval without materializing overlapping beat windows
 * or boxed doubles. Nine-beat spans suppress isolated jitter when enough observations exist;
 * shorter clocks retain their adjacent-interval behavior.
 */
internal fun robustBeatInterval(
    beats: List<Beat>,
    cancellationCheck: () -> Unit,
): Double? {
    val count = if (beats.size >= 9) beats.size - 8 else (beats.size - 1).coerceAtLeast(0)
    if (count == 0) return null
    var source = DoubleArray(count)
    var observed = 0
    if (beats.size >= 9) {
        val ring = DoubleArray(9)
        for (beat in beats) {
            if (observed % BEAT_STATISTICS_CANCELLATION_INTERVAL == 0) cancellationCheck()
            val position = observed % ring.size
            ring[position] = beat.seconds
            if (observed >= 8)
                source[observed - 8] =
                    (beat.seconds - ring[(position + 1) % ring.size]) / 8
            observed++
        }
    } else {
        var previous: Beat? = null
        for (beat in beats) {
            if (observed % BEAT_STATISTICS_CANCELLATION_INTERVAL == 0) cancellationCheck()
            previous?.let { source[observed - 1] = beat.seconds - it.seconds }
            previous = beat
            observed++
        }
    }

    cancellationCheck()
    var destination = DoubleArray(count)
    var width = 1
    var work = 0
    while (width < count) {
        var start = 0
        while (start < count) {
            val middle = min(start + width, count)
            val end = min(start + width * 2, count)
            var left = start
            var right = middle
            var output = start
            while (left < middle && right < end) {
                destination[output++] =
                    if (source[left].compareTo(source[right]) <= 0) source[left++]
                    else source[right++]
                if (++work % BEAT_STATISTICS_SORT_CANCELLATION_INTERVAL == 0)
                    cancellationCheck()
            }
            while (left < middle) {
                destination[output++] = source[left++]
                if (++work % BEAT_STATISTICS_SORT_CANCELLATION_INTERVAL == 0)
                    cancellationCheck()
            }
            while (right < end) {
                destination[output++] = source[right++]
                if (++work % BEAT_STATISTICS_SORT_CANCELLATION_INTERVAL == 0)
                    cancellationCheck()
            }
            start = end
        }
        val swap = source
        source = destination
        destination = swap
        width = if (width > count / 2) count else width * 2
    }
    cancellationCheck()
    return source[count / 2]
}

private const val BEAT_STATISTICS_CANCELLATION_INTERVAL = 256
private const val BEAT_STATISTICS_SORT_CANCELLATION_INTERVAL = 1024
