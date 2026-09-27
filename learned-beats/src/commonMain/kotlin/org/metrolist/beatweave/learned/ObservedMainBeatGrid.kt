package org.metrolist.beatweave.learned

/**
 * Describes who chose the metrical interpretation; neither origin certifies musical correctness.
 */
enum class MainBeatDeclarationOrigin {
    CALLER_DECLARED,
    INFERRED_BY_PROVIDER,
}

/**
 * An immutable metrical declaration over an unchanged, named canonical source clock.
 *
 * Main beats are a strictly ordered subset of actual canonical observations. A provider may, for
 * example, declare every third observed tatum as a main beat and twelve tatums as a bar. No
 * timestamp is interpolated or relabeled. [supportedBars] is the provider's evidence decision, not
 * a calibrated probability or independent musical annotation. The planner additionally checks the
 * source's pulse and acoustic evidence and every rendering-clock safety limit.
 *
 * IDs identify the caller's source artifact and evidence provider. The complete copied clock is
 * checked against the supplied analysis at planning time; a matching ID alone is insufficient.
 */
class ObservedMainBeatGrid(
    val sourceClockId: String,
    canonicalSeconds: DoubleArray,
    canonicalBeatIndices: IntArray,
    barBoundaryMainBeatIndices: IntArray,
    supportedBars: BooleanArray,
    val origin: MainBeatDeclarationOrigin,
    val providerId: String,
    val hypothesisId: String,
    diagnostics: List<String> = emptyList(),
) {
    private val sourceTimes = canonicalSeconds.copyOf()
    private val mainIndices = canonicalBeatIndices.copyOf()
    private val boundaries = barBoundaryMainBeatIndices.copyOf()
    private val accepted = supportedBars.copyOf()
    private val diagnosticValues = diagnostics.toList()

    val mainBeatCount: Int
        get() = mainIndices.size

    val barCount: Int
        get() = boundaries.size - 1

    val canonicalSeconds: DoubleArray
        get() = sourceTimes.copyOf()

    val canonicalBeatIndices: IntArray
        get() = mainIndices.copyOf()

    val barBoundaryMainBeatIndices: IntArray
        get() = boundaries.copyOf()

    val supportedBars: BooleanArray
        get() = accepted.copyOf()

    val diagnostics: List<String>
        get() = diagnosticValues.toList()

    val seconds: DoubleArray
        get() = DoubleArray(mainIndices.size) { sourceTimes[mainIndices[it]] }

    init {
        require(sourceClockId.isNotBlank() && providerId.isNotBlank() && hypothesisId.isNotBlank())
        require(sourceTimes.size in 2..1_000_000 && mainIndices.size in 2..sourceTimes.size)
        require(sourceTimes.all { it.isFinite() && it >= 0.0 })
        require((1 until sourceTimes.size).all { sourceTimes[it] > sourceTimes[it - 1] })
        require(
            mainIndices.all { it in sourceTimes.indices } &&
                (1 until mainIndices.size).all { mainIndices[it] > mainIndices[it - 1] }
        )
        require(
            boundaries.size in 2..mainIndices.size &&
                boundaries.first() == 0 &&
                boundaries.last() == mainIndices.lastIndex &&
                (1 until boundaries.size).all { boundaries[it] > boundaries[it - 1] }
        )
        require(accepted.size == boundaries.size - 1)
    }

    fun canonicalIndex(mainBeat: Int): Int = mainIndices[mainBeat]

    fun at(mainBeat: Int): Double = sourceTimes[mainIndices[mainBeat]]

    fun boundaryMainIndex(barBoundary: Int): Int = boundaries[barBoundary]

    fun boundaryCanonicalIndex(barBoundary: Int): Int = mainIndices[boundaries[barBoundary]]

    fun mainBeatsInBar(bar: Int): Int = boundaries[bar + 1] - boundaries[bar]

    fun canonicalPulsesInBar(bar: Int): Int =
        boundaryCanonicalIndex(bar + 1) - boundaryCanonicalIndex(bar)

    fun isBarSupported(bar: Int): Boolean = accepted[bar]

    internal fun matchesClock(song: LocalSongAnalysis): Boolean =
        sourceTimes.size == song.pulse.beats.size &&
            sourceTimes.indices.all { sourceTimes[it] == song.pulse.beats[it].seconds }
}

enum class DeclaredTransitionFailureCode {
    SOURCE_CLOCK_MISMATCH,
    SOURCE_COVERAGE_INVALID,
    UNSUPPORTED_BAR_RANGE,
    INCOMPATIBLE_MAIN_METER,
    UNSUPPORTED_PULSE_RANGE,
    UNMAPPED_CANONICAL_PIN,
    CLOCK_REJECTED,
    SEARCH_LIMIT_REACHED,
    NO_COMPATIBLE_SUPPORTED_RANGE,
}

class DeclaredTransitionPlanningException(
    val code: DeclaredTransitionFailureCode,
    detail: String,
    val clockReport: org.metrolist.beatweave.ClockFitReport? = null,
) : IllegalArgumentException("Declared main-beat transition declined ($code): $detail")

/**
 * Original canonical indices stay explicit even though the render clock contains only main beats.
 */
data class MatchedMainBeat(
    val outgoingCanonicalBeat: Int,
    val incomingCanonicalBeat: Int,
    val outgoingSeconds: Double,
    val originalIncomingSeconds: Double,
    val preparedIncomingSeconds: Double,
    val originalOutputResidualSeconds: Double,
    val barBoundary: Boolean,
)

/**
 * The provider's declared bar numbering is separate from LocalSongAnalysis.barTracking numbering.
 */
class DeclaredMainBeatSelection
internal constructor(
    val outgoingGrid: ObservedMainBeatGrid,
    val incomingGrid: ObservedMainBeatGrid,
    val outgoingStartBar: Int,
    val incomingStartBar: Int,
    val barCount: Int,
    val automaticallySelectedCues: Boolean,
    matches: List<MatchedMainBeat>,
) {
    private val matchValues = matches.toList()
    val matchedMainBeats: List<MatchedMainBeat>
        get() = matchValues.toList()

    val outgoingCanonicalPulsesPerBar: List<Int>
        get() = (0 until barCount).map { outgoingGrid.canonicalPulsesInBar(outgoingStartBar + it) }

    val incomingCanonicalPulsesPerBar: List<Int>
        get() = (0 until barCount).map { incomingGrid.canonicalPulsesInBar(incomingStartBar + it) }

    val mainBeatsPerBar: List<Int>
        get() = (0 until barCount).map { outgoingGrid.mainBeatsInBar(outgoingStartBar + it) }
}
