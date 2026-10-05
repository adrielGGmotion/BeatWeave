package org.metrolist.beatweave.learned

import kotlin.math.ln

/** Opt-in musical ranking only. This cannot relax bar, pulse or clock acceptance gates. */
class TrainedCueModel(
    mean: DoubleArray,
    scale: DoubleArray,
    weights: DoubleArray,
    val modelId: String,
) {
    private val mean = mean.copyOf()
    private val scale = scale.copyOf()
    private val weights = weights.copyOf()

    init {
        require(modelId.isNotBlank())
        require(mean.size == FEATURE_COUNT && scale.size == FEATURE_COUNT)
        require(weights.size == FEATURE_COUNT + FEATURE_COUNT * (FEATURE_COUNT + 1) / 2)
        require(mean.all { it.isFinite() } && scale.all { it.isFinite() && it > 0 })
        require(weights.all { it.isFinite() })
    }

    /** Same order as tools/manual-automix/train.py. No track names, IDs or reference cues. */
    fun score(features: DoubleArray): Double {
        require(features.size == FEATURE_COUNT && features.all { it.isFinite() })
        val z = DoubleArray(FEATURE_COUNT) { (features[it] - mean[it]) / scale[it] }
        var index = 0
        var result = 0.0
        for (v in z) result += v * weights[index++]
        for (i in z.indices) for (j in i until z.size) result += z[i] * z[j] * weights[index++]
        require(result.isFinite()) { "Cue model produced a non-finite score" }
        return result
    }

    companion object {
        const val FEATURE_COUNT = 13

        internal fun features(
            out: MusicalCueRanking.Part,
            into: MusicalCueRanking.Part,
            evidence: MusicalCueEvidence,
            bars: Int,
            firstDuration: Double,
            secondDuration: Double,
        ): DoubleArray = doubleArrayOf(
            out.from / firstDuration, into.from / secondDuration, ln(bars.toDouble()) / ln(2.0) / 5.0,
            (out.to - out.from) / 60.0, out.startChange, out.endChange,
            into.startChange, into.endChange, into.build, evidence.overlapLevelBalance,
            evidence.longBlendAffinity, out.score, into.score,
        )
    }
}
