package org.metrolist.beatweave.learned

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.*
import org.metrolist.beatweave.*

class CandidateAcousticRangeTest {
    private fun song(quiet: Set<Int> = emptySet(), meter: Int = 4): LocalSongAnalysis {
        val rate = 4000
        val beats = List(65) { Beat(.25 + it * .5, 1f) }
        val pcm = FloatArray(33 * rate)
        for (i in beats.indices) if (i !in quiet) {
            val start = ((beats[i].seconds + .1) * rate).toInt()
            for (j in 0 until 80) if (start + j < pcm.size)
                pcm[start + j] = (.8 * sin(2 * PI * 670 * j / rate) * (1 - j / 80.0)).toFloat()
        }
        val raw = beats.filterIndexed { i, _ -> i % meter == 0 }.map { it.seconds }
        val logits = FloatArray(33 * 50) { -8f }
        raw.forEach { logits[(it * 50).roundToInt()] = 8f }
        val analyzer = MusicAnalyzer()
        val measured = analyzer.analyzeWithBeats(pcm, rate, beats, raw, "independent-generated-attacks")
        // Keep ranking irrelevant to these coverage tests, selecting deterministic ordered cues.
        val audio = measured.copy(energyBlocks = emptyList(), onsetEnvelope = FloatArray(0))
        val pulse = PulseNormalizationResult(beats, beats, raw, emptyList(),
            BeatGridQualityReport(beats.size, 120.0, emptyList()), 1.0)
        val evidence = AcousticPulseEvidence.assess(analyzer.prepare(pcm, rate).acousticAttacks, beats)
        val tracking = BarTracker.track(beats, raw, logits).withAcousticPulseSupport(evidence)
        return LocalSongAnalysis(audio,
            LearnedBeatAnalysis(33.0, beats, raw, "fixture", BeatThisLogits(FloatArray(logits.size), logits)),
            pulse, audio, tracking, acousticPulse = evidence)
    }

    @Test
    fun candidateCanStartAndEndAtObservedPhraseEdgesWithoutChangingTheClock() {
        val a = song()
        assertFalse(a.barTracking!!.isBarUsable(0)) // Old centered acoustic mask excludes it.
        val result = LocalMixPlanner.autoTransition(a, a, 2)
        val selection = assertNotNull(result.automaticSelection)
        assertEquals(0, selection.incomingStartBar)
        assertEquals(14, selection.outgoingStartBar)
        assertEquals(.25, selection.incomingStartSeconds, 1e-9)
        assertEquals(28.25, selection.outgoingStartSeconds, 1e-9)
        assertTrue(selection.search.tracks.all { it.acousticCheckDeferredToCandidate })
        assertTrue(result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds })
    }

    @Test
    fun earlierSilenceCannotPoisonTheFirstCompleteActivePhrase() {
        val a = song()
        val b = song((0..23).toSet())
        // Twelve seconds is beyond this short fixture's default opening window.
        assertFailsWith<AutoMixPlanningException> { LocalMixPlanner.autoTransition(a, b, 2) }
        val selection = LocalMixPlanner.autoTransition(a, b, 2,
            searchOptions = AutoMixSearchOptions(maximumIncomingStartFraction = 1.0)).automaticSelection!!
        assertEquals(6, selection.incomingStartBar)
        assertEquals(12.25, selection.incomingStartSeconds, 1e-9)
    }

    @Test
    fun anInteriorQuietBarStillRejectsAnOtherwiseRecurringFullSpan() {
        val a = song()
        val b = song((24..27).toSet())
        assertFailsWith<AutoMixPlanningException> { LocalMixPlanner.autoTransition(a, b, 16) }
    }

    @Test
    fun aWeakTernaryBarCannotBorrowTheNextBarsActivity() {
        val a = song(meter = 3)
        val b = song(quiet = setOf(24, 26), meter = 3)
        assertFailsWith<IllegalArgumentException> {
            AutoMixPlanner.scopedPlan(a, b, a.bars(), b.bars(), 8, 8, 4,
                MixMode.TRANSITION, 48000, ClockFitOptions(), WarpQualityLimits(), { false })
        }
    }

    @Test
    fun independentlySupportedAlternativeSurvivesARejectedPrimaryPool() {
        val good = song()
        val bad = good.copy(barTracking = null)
        val result = AutoMixAnalysisEnsemble.bestTransition(listOf(bad, good), listOf(good))
        assertEquals(1, result.outgoingAnalysis)
        assertEquals(0, result.incomingAnalysis)
        assertEquals(2, result.attempts.size)
        assertEquals(AutoMixFailureCode.NO_CONFIDENT_BAR_GRID, result.attempts[0].report.failure)
        assertNull(result.attempts[1].report.failure)
        assertTrue(result.plan.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds })
        assertFailsWith<AutoMixAnalysisException> {
            AutoMixAnalysisEnsemble.bestTransition(listOf(bad), listOf(bad))
        }
        assertFailsWith<IllegalArgumentException> {
            AutoMixAnalysisEnsemble.bestTransition(listOf(good, good.copy(audio = good.audio.copy(durationSeconds = 34.0))), listOf(good))
        }
        assertFailsWith<MixCancelledException> {
            AutoMixAnalysisEnsemble.bestTransition(listOf(good), listOf(good), isCancelled = { true })
        }
    }
}
