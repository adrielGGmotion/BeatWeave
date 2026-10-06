package org.metrolist.beatweave.learned

import org.metrolist.beatweave.*

data class AutoMixAnalysisAttempt(
    val outgoingAnalysis: Int,
    val incomingAnalysis: Int,
    val outgoingDetector: String,
    val incomingDetector: String,
    val report: AutoMixSearchReport,
    val musicalScore: Double?,
)

data class AutoMixAnalysisSelection(
    val plan: LocalMixPlan,
    val outgoingAnalysis: Int,
    val incomingAnalysis: Int,
    val attempts: List<AutoMixAnalysisAttempt>,
)

class AutoMixAnalysisException(val attempts: List<AutoMixAnalysisAttempt>) :
    IllegalArgumentException("All independently analyzed candidate pools declined")

/**
 * Compare at most two independently measured interpretations per recording. Each pair goes
 * through the normal bar, acoustic, pulse and clock checks; clocks are never spliced together.
 * Per-pool search limits apply, so two alternatives per track cost at most four normal searches.
 * Provide the same recording/decoder and a common cue model for meaningful score comparison.
 * Score ties prefer the earlier analysis pair. This does not make an uncertain grid acceptable.
 */
object AutoMixAnalysisEnsemble {
    fun bestTransition(
        first: List<LocalSongAnalysis>,
        second: List<LocalSongAnalysis>,
        outputSampleRate: Int = 48000,
        fitOptions: ClockFitOptions = ClockFitOptions(),
        qualityLimits: WarpQualityLimits = WarpQualityLimits(),
        searchOptions: AutoMixSearchOptions = AutoMixSearchOptions(),
        onComparison: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): AutoMixAnalysisSelection {
        require(first.size in 1..2 && second.size in 1..2)
        for (options in listOf(first, second)) {
            require(options.all { it.audio.durationSeconds == options[0].audio.durationSeconds }) {
                "Alternative analyses must describe the same complete decoded recording"
            }
        }
        // Pin indices are interpretation-specific and cannot be transferred to another grid.
        require(fitOptions.pinnedIncomingBeats.isEmpty() || second.size == 1) {
            "Pinned incoming indices require one incoming analysis"
        }
        val attempts = ArrayList<AutoMixAnalysisAttempt>()
        var best: LocalMixPlan? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestA = -1
        var bestB = -1
        for (i in first.indices) for (j in second.indices) {
            if (isCancelled()) throw MixCancelledException()
            onComparison(i, j)
            try {
                val plan = AutoMixPlanner.bestTransition(first[i], second[j], outputSampleRate,
                    fitOptions, qualityLimits, searchOptions, isCancelled)
                val choice = plan.automaticSelection!!
                val score = choice.musicalCueEvidence?.score ?: 0.0
                attempts += AutoMixAnalysisAttempt(i, j, first[i].model.modelId,
                    second[j].model.modelId, choice.search, score)
                if (best == null || score > bestScore) {
                    best = plan; bestScore = score; bestA = i; bestB = j
                }
            } catch (e: AutoMixPlanningException) {
                attempts += AutoMixAnalysisAttempt(i, j, first[i].model.modelId,
                    second[j].model.modelId, e.report, null)
            }
        }
        return AutoMixAnalysisSelection(best ?: throw AutoMixAnalysisException(attempts.toList()),
            bestA, bestB, attempts.toList())
    }
}
