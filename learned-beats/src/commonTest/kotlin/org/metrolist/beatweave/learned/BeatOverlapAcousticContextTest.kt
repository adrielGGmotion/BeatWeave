package org.metrolist.beatweave.learned

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*
import org.metrolist.beatweave.*

class BeatOverlapAcousticContextTest {
    private fun song(quietIntervals: Set<Int> = emptySet()): LocalSongAnalysis {
        val sampleRate = 4000
        val period = .5
        val beats = List(65) { Beat(.25 + it * period, 1f) }
        val duration = 33.0
        val pcm = FloatArray((duration * sampleRate).toInt())
        for (beat in beats.indices) if (beat !in quietIntervals) {
            val start = ((beats[beat].seconds + .1) * sampleRate).toInt()
            for (j in 0 until 80) if (start + j < pcm.size) {
                pcm[start + j] =
                    (.8 * sin(2 * PI * 670 * j / sampleRate) * (1 - j / 80.0)).toFloat()
            }
        }
        val prepared = MusicAnalyzer().prepare(pcm, sampleRate)
        val audio =
            MusicAnalyzer()
                .analyzeWithBeats(
                    pcm,
                    sampleRate,
                    beats,
                    beats.filterIndexed { index, _ -> index % 4 == 0 }.map { it.seconds },
                    "known-generated-attacks",
                )
        val pulse =
            PulseNormalizationResult(
                beats,
                beats,
                audio.downbeatSeconds,
                emptyList(),
                BeatGridQualityReport(beats.size, 120.0, emptyList()),
                1.0,
            )
        return LocalSongAnalysis(
            audio,
            LearnedBeatAnalysis(
                duration,
                beats,
                audio.downbeatSeconds,
                "generated",
                BeatThisLogits(FloatArray(0), FloatArray(0)),
            ),
            pulse,
            audio,
            acousticPulse = AcousticPulseEvidence.assess(prepared.acousticAttacks, beats),
        )
    }

    @Test
    fun onlyUnassessedEdgesAreExcludedFromClaimAndEntireSourcePcmRemains() {
        val a = song()
        val acoustic = assertNotNull(a.acousticPulse)
        assertTrue(acoustic.supportsRange(4, 62))
        assertFalse(acoustic.supportsRange(0, 65))
        val result = LocalMixPlanner.beatOverlap(a, a)
        val coverage = assertNotNull(result.beatCoverage)
        val matched = assertNotNull(coverage.matchedSpan)
        assertEquals(0, coverage.outgoingStartBeat)
        assertEquals(64, coverage.outgoingEndBeat)
        assertEquals(65, coverage.pairedBeatCount)
        assertEquals(4, matched.outgoingStartBeat)
        assertEquals(61, matched.outgoingEndBeat)
        assertEquals(4, matched.incomingStartBeat)
        assertEquals(61, matched.incomingEndBeat)
        assertEquals(matched.pairedBeatCount, result.mixPlan.first.size)
        assertEquals(0, result.mixPlan.firstBeat)
        assertEquals(0, result.mixPlan.secondBeat)
        assertEquals(a.audio.durationSeconds, coverage.incomingSourceDurationSeconds)
        assertEquals(
            (a.audio.durationSeconds * 48000).toLong(),
            WarpSchedule.from(result.mixPlan, a.audio.durationSeconds).sourceFrames,
        )
        assertTrue(WarpSchedule.from(result.mixPlan, a.audio.durationSeconds).isTranslationOnly)
        assertEquals(0.0, result.mixPlan.secondOutputTime(0.0), 1e-9)
    }

    @Test
    fun unequalCuesRetainTheirOriginalOffsetAfterSymmetricPairClipping() {
        val a = song()
        val result =
            LocalMixPlanner.beatOverlap(
                a,
                a,
                outgoingCueBeat = 12,
                incomingCueBeat = 8,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(12)),
            )
        val coverage = assertNotNull(result.beatCoverage)
        val matched = assertNotNull(coverage.matchedSpan)
        assertEquals(4, coverage.outgoingStartBeat)
        assertEquals(0, coverage.incomingStartBeat)
        assertEquals(8, matched.outgoingStartBeat)
        assertEquals(4, matched.incomingStartBeat)
        assertEquals(
            a.pulse.beats[12].seconds,
            result.mixPlan.secondOutputTime(a.pulse.beats[8].seconds),
            1e-9,
        )
        assertEquals(2.0, result.mixPlan.secondOutputTime(0.0), 1e-9)
        assertTrue(result.clockFit.adjustments[8].pinned)
    }

    @Test
    fun unsupportedInteriorAttacksCannotBecomeAnEarlyCutoff() {
        val good = song()
        val silentMiddle = song((24..27).toSet())
        val failure =
            assertFailsWith<BeatOverlapPlanningException> {
                LocalMixPlanner.beatOverlap(good, silentMiddle)
            }
        assertEquals(BeatOverlapFailureCode.UNSUPPORTED_SHARED_COVERAGE, failure.code)
        val coverage = assertNotNull(failure.coverage)
        assertEquals(64, coverage.incomingEndBeat)
        assertEquals(61, coverage.matchedSpan!!.incomingEndBeat)
    }

    @Test
    fun declaredWeakBarCannotBorrowAcousticActivityFromAnAdjacentActiveBar() {
        val good = song()
        val missing = song(setOf(4, 6))
        assertTrue(missing.acousticPulse!!.supportsRange(4, 11))
        assertFalse(missing.acousticPulse!!.supportsRange(4, 8))
        fun declaration(source: LocalSongAnalysis) =
            ObservedMainBeatGrid(
                "fixture-clock",
                source.pulse.beats.map { it.seconds }.toDoubleArray(),
                (4..10).toList().toIntArray(),
                intArrayOf(0, 3, 6),
                booleanArrayOf(true, true),
                MainBeatDeclarationOrigin.CALLER_DECLARED,
                "fixture-provider",
                "two-ternary-bars",
            )
        val failure =
            assertFailsWith<DeclaredTransitionPlanningException> {
                LocalMixPlanner.declaredTransition(
                    good,
                    missing,
                    declaration(good),
                    declaration(missing),
                    0,
                    0,
                    2,
                )
            }
        assertEquals(DeclaredTransitionFailureCode.UNSUPPORTED_PULSE_RANGE, failure.code)
    }

    @Test
    fun pinnedEdgeIsNotSilentlyDroppedAndCancellationStillStopsPlanning() {
        val a = song()
        assertFailsWith<IllegalArgumentException> {
            LocalMixPlanner.beatOverlap(
                a,
                a,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(1)),
            )
        }
        assertFailsWith<MixCancelledException> {
            LocalMixPlanner.beatOverlap(a, a, isCancelled = { true })
        }
    }
}
