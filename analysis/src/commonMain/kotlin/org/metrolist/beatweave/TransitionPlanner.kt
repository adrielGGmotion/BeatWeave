package org.metrolist.beatweave

import kotlin.math.abs

/**
 * Musical bars require audited downbeat boundaries. A BPM or a list of unlabelled beats is
 * deliberately insufficient: inventing "beat one" makes a numerically aligned mix enter on the
 * wrong part of the phrase.
 *
 * Consecutive entries in [barStartBeats] delimit complete bars. Meter may change. The final entry
 * is a boundary, not an additional complete bar.
 */
class BarGrid(val beats: BeatGrid, barStartBeats: IntArray) {
    private val boundaries = barStartBeats.copyOf()
    val barCount: Int
        get() = boundaries.size - 1

    val barStartBeats: IntArray
        get() = boundaries.copyOf()

    init {
        require(boundaries.size >= 2) { "At least two bar boundaries are required" }
        require(boundaries.all { it in 0 until beats.size }) {
            "A downbeat lies outside the detected beat grid"
        }
        require(boundaries.asList().zipWithNext().all { (a, b) -> b > a }) {
            "Downbeat indices must increase"
        }
    }

    fun boundary(bar: Int): Int {
        require(bar in boundaries.indices) { "Bar boundary $bar is outside the bar grid" }
        return boundaries[bar]
    }

    fun beatsInBar(bar: Int): Int {
        require(bar in 0 until barCount)
        return boundaries[bar + 1] - boundaries[bar]
    }

    internal fun boundaryIndex(beat: Int): Int = boundaries.indexOf(beat)

    companion object {
        /** Associates model/annotator downbeats with detected beats; never silently drops one. */
        fun from(
            beatGrid: BeatGrid,
            downbeatSeconds: List<Double>,
            toleranceSeconds: Double = 0.07,
        ): BarGrid {
            require(toleranceSeconds.isFinite() && toleranceSeconds > 0.0)
            require(downbeatSeconds.size >= 2) {
                "Downbeats were not detected reliably enough to choose bars"
            }
            val times = beatGrid.times
            val indices = IntArray(downbeatSeconds.size)
            var cursor = 0
            downbeatSeconds.forEachIndexed { index, time ->
                require(time.isFinite() && (index == 0 || time > downbeatSeconds[index - 1]))
                while (
                    cursor + 1 < times.size &&
                        abs(times[cursor + 1] - time) < abs(times[cursor] - time)
                ) cursor++
                require(abs(times[cursor] - time) <= toleranceSeconds) {
                    "Downbeat $time has no corresponding beat"
                }
                indices[index] = cursor
            }
            return BarGrid(beatGrid, indices)
        }
    }
}

data class BarTransition(
    val mixPlan: MixPlan,
    val outgoingStartBar: Int,
    val incomingStartBar: Int,
    val outgoingBars: Int,
    val incomingBars: Int,
    val quality: WarpQualityReport,
)

/** Bar geometry before clock validation or bounded refinement. */
data class BarTransitionSelection(
    val mixPlan: MixPlan,
    val outgoingStartBar: Int,
    val incomingStartBar: Int,
    val outgoingBars: Int,
    val incomingBars: Int,
)

/**
 * Matches every beat 1:1 and starts/finishes on selected downbeat boundaries. Bar lengths need not
 * match: sixteen 2-beat bars align with eight 4-beat bars. No automatic 3:2 subdivision change or
 * arbitrary phase offset is applied.
 */
object TransitionPlanner {
    val supportedBarCounts: List<Int> = listOf(2, 4, 8, 16, 32)

    fun transition(
        outgoing: BarGrid,
        incoming: BarGrid,
        outgoingStartBar: Int,
        incomingStartBar: Int,
        outgoingBars: Int = 16,
        outputSampleRate: Int = 48000,
        releaseAfterFade: Boolean = true,
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
    ): BarTransition {
        val selection =
            select(
                outgoing,
                incoming,
                outgoingStartBar,
                incomingStartBar,
                outgoingBars,
                outputSampleRate,
                releaseAfterFade,
            )
        val quality =
            WarpQuality.assess(selection.mixPlan, limits = qualityLimits).requireAccepted()
        return BarTransition(
            selection.mixPlan,
            outgoingStartBar,
            incomingStartBar,
            outgoingBars,
            selection.incomingBars,
            quality,
        )
    }

    /**
     * Selects complete bars from the supplied audited grid. Validate/refine the returned clock
     * before rendering.
     */
    fun select(
        outgoing: BarGrid,
        incoming: BarGrid,
        outgoingStartBar: Int,
        incomingStartBar: Int,
        outgoingBars: Int = 16,
        outputSampleRate: Int = 48000,
        releaseAfterFade: Boolean = true,
    ): BarTransitionSelection {
        require(outgoingBars in supportedBarCounts) { "Choose 2, 4, 8, 16 or 32 outgoing bars" }
        require(outgoingStartBar in 0 until outgoing.barCount)
        require(incomingStartBar in 0 until incoming.barCount)
        require(outgoingStartBar + outgoingBars <= outgoing.barCount) {
            "Not enough complete outgoing bars"
        }
        val firstBeat = outgoing.boundary(outgoingStartBar)
        val beatCount = outgoing.boundary(outgoingStartBar + outgoingBars) - firstBeat
        val secondBeat = incoming.boundary(incomingStartBar)
        val incomingEndBar = incoming.boundaryIndex(secondBeat + beatCount)
        require(incomingEndBar > incomingStartBar) {
            "This overlap does not finish on an incoming downbeat; choose a compatible bar count"
        }
        val plan =
            MixPlan(
                outgoing.beats,
                incoming.beats,
                firstBeat,
                secondBeat,
                beatCount,
                outputSampleRate = outputSampleRate,
                releaseAfterFade = releaseAfterFade,
            )
        return BarTransitionSelection(
            plan,
            outgoingStartBar,
            incomingStartBar,
            outgoingBars,
            incomingEndBar - incomingStartBar,
        )
    }

    /**
     * Suitable UI choices; incompatible phrase lengths never require a failed render to discover.
     */
    fun compatibleBarCounts(
        outgoing: BarGrid,
        incoming: BarGrid,
        outgoingStartBar: Int,
        incomingStartBar: Int,
    ): List<Int> {
        require(outgoingStartBar in 0 until outgoing.barCount)
        require(incomingStartBar in 0 until incoming.barCount)
        return supportedBarCounts.filter { count ->
            val endBar = outgoingStartBar + count
            endBar <= outgoing.barCount &&
                incoming.boundaryIndex(
                    incoming.boundary(incomingStartBar) + outgoing.boundary(endBar) -
                        outgoing.boundary(outgoingStartBar)
                ) > incomingStartBar
        }
    }
}
