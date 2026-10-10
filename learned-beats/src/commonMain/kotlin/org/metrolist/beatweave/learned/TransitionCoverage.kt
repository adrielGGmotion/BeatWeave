package org.metrolist.beatweave.learned

import org.metrolist.beatweave.*

/** A contiguous region of complete bars accepted by strict transition evidence checks. */
data class SupportedTransitionRegion(
    val startBar: Int,
    val endBarExclusive: Int,
    val startBeat: Int,
    val endBeat: Int,
    val startSeconds: Double,
    val endSeconds: Double,
) {
    val barCount: Int get() = endBarExclusive - startBar
}

data class TransitionCoverage(
    val regions: List<SupportedTransitionRegion>,
    /** Last geometrically accepted pulse, before acoustic and meter checks; null if none. */
    val lastAcceptedPulseBeat: Int?,
    val lastAcceptedPulseSeconds: Double?,
    /** Actual strict bar coverage; use this endpoint to schedule a blend before a rejected outro. */
    val lastUsableBarEndSeconds: Double?,
    val declinedBars: Map<Int, String>,
)

/**
 * Reports usable transition coverage without extending or changing a rejected outro grid.
 * Times use this analysis's source clock (add the decode-window offset in a full-track caller).
 * A fade of N bars may end at region.endBarExclusive and start N bars earlier within that region.
 */
fun LocalSongAnalysis.transitionCoverage(isCancelled: () -> Boolean = { false }): TransitionCoverage {
    fun checkCancellation() { if (isCancelled()) throw MixCancelledException() }
    checkCancellation()
    val grid = barTracking?.grid() ?: BarGrid.from(
        BeatGrid(pulse.beats.map { it.seconds }.toDoubleArray()), pulse.downbeatSeconds,
    )
    require(grid.beats.times.contentEquals(pulse.beats.map { it.seconds }.toDoubleArray())) {
        "Bar evidence does not refer to the supplied canonical source clock"
    }
    require(audio.durationSeconds.isFinite() && audio.durationSeconds > 0.0 &&
        grid.beats.times.all { it >= 0.0 && it < audio.durationSeconds }) {
        "Selected pulse anchors must lie inside the unchanged source recording"
    }
    val regions = mutableListOf<SupportedTransitionRegion>()
    val declined = mutableMapOf<Int, String>()
    var runStart: Int? = null
    fun flush(end: Int) {
        val start = runStart ?: return
        regions += SupportedTransitionRegion(start, end, grid.boundary(start), grid.boundary(end),
            grid.beats.at(grid.boundary(start)), grid.beats.at(grid.boundary(end)))
        runStart = null
    }
    for (bar in 0 until grid.barCount) {
        checkCancellation()
        try {
            TransitionEvidence.requireBars(this, grid, bar, 1, emptySet(), isCancelled)
            // Adjacent locally accepted bars may still belong to different accepted pulse regions.
            val start = runStart
            if (start != null) {
                try {
                    requirePulseGeometryRange(grid.boundary(start), grid.boundary(bar + 1) + 1)
                    // Per-bar checks already cover every local window and activity fraction.
                    // Check the only additional acoustic condition: adjacent rests at the join.
                    // Avoid rescanning the entire growing region on every bar.
                    if (acousticPulse != null)
                        requirePulseRange(grid.boundary(bar) - 1, grid.boundary(bar) + 2)
                }
                catch (_: IllegalArgumentException) { flush(bar) }
            }
            if (runStart == null) runStart = bar
        } catch (e: UncertainBarsException) {
            declined[bar] = e.message.orEmpty()
            flush(bar)
        } catch (e: IllegalArgumentException) {
            declined[bar] = e.message.orEmpty()
            flush(bar)
        }
    }
    flush(grid.barCount)
    val lastPulse = if (pulse.quality.safeForAutomaticMix) pulse.beats.lastIndex else
        pulseRegions.filter { it.quality.safeForAutomaticMix && it.startBeat >= 0 &&
            it.endBeatExclusive <= pulse.beats.size && it.endBeatExclusive - it.startBeat >= 2 }
            .maxOfOrNull { it.endBeatExclusive - 1 }
    checkCancellation()
    return TransitionCoverage(regions, lastPulse, lastPulse?.let { pulse.beats[it].seconds },
        regions.lastOrNull()?.endSeconds, declined)
}
