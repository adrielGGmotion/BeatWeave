package org.metrolist.beatweave.learned

import kotlin.math.min
import org.metrolist.beatweave.Beat

/** Robust model pulse: malformed spans remain unsupported instead of entering the median. */
internal fun robustModelPulseBpm(
    beats: List<Beat>,
    cancellationCheck: () -> Unit,
): Double? = nineBeatBpm(beats, finitePositiveOnly = true, cancellationCheck)

/** Canonical clocks are already validated, so retain every span exactly as the summary did. */
internal fun canonicalPulseBpm(
    beats: List<Beat>,
    cancellationCheck: () -> Unit,
): Double? = nineBeatBpm(beats, finitePositiveOnly = false, cancellationCheck)

internal fun meanBeatStrength(
    beats: List<Beat>,
    cancellationCheck: () -> Unit,
): Double {
    cancellationCheck()
    val iterator = beats.iterator()
    var count = 0
    var sum = 0.0
    while (iterator.hasNext()) {
        if (count > 0 && count % STATISTICS_CANCELLATION_INTERVAL == 0) cancellationCheck()
        sum += iterator.next().strength.toDouble().coerceIn(0.0, 1.0)
        count++
    }
    val average = sum / count
    return if (average.isFinite()) average else 0.0
}

private fun nineBeatBpm(
    beats: List<Beat>,
    finitePositiveOnly: Boolean,
    cancellationCheck: () -> Unit,
): Double? {
    cancellationCheck()
    if (beats.size < 9) return null
    var periods = DoubleArray(beats.size - 8)
    val ring = DoubleArray(9)
    val iterator = beats.iterator()
    var observed = 0
    var accepted = 0
    while (iterator.hasNext()) {
        if (observed > 0 && observed % STATISTICS_CANCELLATION_INTERVAL == 0)
            cancellationCheck()
        val seconds = iterator.next().seconds
        val position = observed % ring.size
        ring[position] = seconds
        if (observed >= 8) {
            val period = (seconds - ring[(position + 1) % ring.size]) / 8
            if (!finitePositiveOnly || (period > 0 && period.isFinite()))
                periods[accepted++] = period
        }
        observed++
    }
    if (accepted == 0) {
        cancellationCheck()
        return null
    }

    cancellationCheck()
    var destination = DoubleArray(accepted)
    var width = 1
    var work = 0
    while (width < accepted) {
        var start = 0
        while (start < accepted) {
            val middle = min(start + width, accepted)
            val end = min(start + width * 2, accepted)
            var left = start
            var right = middle
            var output = start
            while (left < middle && right < end) {
                destination[output++] =
                    if (periods[left].compareTo(periods[right]) <= 0) periods[left++]
                    else periods[right++]
                if (++work % STATISTICS_SORT_CANCELLATION_INTERVAL == 0) cancellationCheck()
            }
            while (left < middle) {
                destination[output++] = periods[left++]
                if (++work % STATISTICS_SORT_CANCELLATION_INTERVAL == 0) cancellationCheck()
            }
            while (right < end) {
                destination[output++] = periods[right++]
                if (++work % STATISTICS_SORT_CANCELLATION_INTERVAL == 0) cancellationCheck()
            }
            start = end
        }
        val swap = periods
        periods = destination
        destination = swap
        width = if (width > accepted / 2) accepted else width * 2
    }
    cancellationCheck()
    return 60.0 / periods[accepted / 2]
}

private const val STATISTICS_CANCELLATION_INTERVAL = 256
private const val STATISTICS_SORT_CANCELLATION_INTERVAL = 1024
