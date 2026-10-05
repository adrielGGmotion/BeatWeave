package org.metrolist.beatweave.learned

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.*
import org.metrolist.beatweave.*

class AutoMixPlannerTest {
    private fun song(
        meters: List<Int>,
        period: Double = 0.4,
        periods: List<Double>? = null,
    ): LocalSongAnalysis {
        val starts = ArrayList<Int>()
        var count = 0
        meters.forEach {
            starts += count
            count += it
        }
        starts += count
        val intervals =
            meters.flatMapIndexed { bar, pulses -> List(pulses) { periods?.get(bar) ?: period } }
        var seconds = 0.2
        val beats =
            List(count + 1) {
                if (it > 0) seconds += intervals[it - 1]
                Beat(if (periods == null) 0.2 + it * period else seconds, 0.95f)
            }
        val raw = starts.map { beats[it].seconds }
        val duration = beats.last().seconds + 1.0
        val logits = FloatArray((duration * 50).roundToInt()) { -8f }
        raw.forEach { logits[(it * 50).roundToInt()] = 8f }
        val audio =
            Analysis(
                duration,
                60 / period,
                1.0,
                beats,
                -18.0,
                -6.0,
                800.0,
                "unknown",
                0.0,
                emptyList(),
                FloatArray(0),
                0.02,
                emptyList(),
                downbeatSeconds = raw,
            )
        val pulse =
            PulseNormalizationResult(
                beats,
                beats,
                raw,
                emptyList(),
                BeatGridQualityReport(beats.size, audio.bpm, emptyList()),
                1.0,
            )
        val tracking =
            BarTracker.track(
                beats,
                raw,
                logits,
                BarTrackingOptions(
                    pulsesPerBar = (listOf(2, 3, 4, 6, 8) + meters).distinct().sorted()
                ),
            )
        return LocalSongAnalysis(
            audio,
            LearnedBeatAnalysis(
                duration,
                beats,
                raw,
                "synthetic-annotated",
                BeatThisLogits(FloatArray(logits.size), logits),
            ),
            pulse,
            audio,
            tracking,
        )
    }

    private fun withBarEnergy(song: LocalSongAnalysis, levels: List<Double>): LocalSongAnalysis {
        val grid = song.barTracking!!.grid()
        require(levels.size == grid.barCount)
        val blocks =
            levels.mapIndexed { bar, db ->
                EnergyBlock(
                    grid.beats.at(grid.boundary(bar)),
                    grid.beats.at(grid.boundary(bar + 1)),
                    db,
                )
            }
        return song.copy(audio = song.audio.copy(energyBlocks = blocks))
    }

    @Test
    fun automaticTransitionsChooseEarlyIncomingAndLateOutgoingForEachRequestedLength() {
        val a = song(List(40) { 4 })
        val b = song(List(40) { 4 }, 0.41)
        for (bars in listOf(2, 4, 8, 16, 32)) {
            val result = LocalMixPlanner.autoTransition(a, b, bars)
            val selection = assertNotNull(result.automaticSelection)
            assertEquals(40 - bars, selection.outgoingStartBar)
            assertEquals(0, selection.incomingStartBar)
            assertEquals(bars, selection.barCount)
            assertEquals(bars + 1, result.barMatches.size)
            assertTrue(
                result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
            )
            assertTrue(
                result.barMatches.all {
                    kotlin.math.abs(it.originalBoundaryOutputResidualSeconds) < 1e-8
                }
            )
            assertEquals(
                a.audio.beats[selection.outgoingStartBeat].seconds,
                result.mixPlan.first.at(0),
            )
            assertTrue(result.mixPlan.releaseAfterFade)
        }
    }

    @Test
    fun trainedRankingChangesMusicalPreferenceWithoutOverridingClockRejection() {
        val a=withBarEnergy(song(List(20) { 4 }),List(20) { -18.0 })
        val b=withBarEnergy(song(List(20) { 4 }),List(20) { -18.0 })
        val old=LocalMixPlanner.autoTransition(a,b,4).automaticSelection!!
        val weights=DoubleArray(104).also { it[0]=-100.0;it[1]=100.0 }
        val model=TrainedCueModel(DoubleArray(13),DoubleArray(13) { 1.0 },weights,"test-preference")
        val learned=LocalMixPlanner.autoTransition(a,b,4,
            searchOptions=AutoMixSearchOptions(cueModel=model)).automaticSelection!!
        assertTrue(learned.outgoingStartBar < old.outgoingStartBar)
        assertTrue(learned.incomingStartBar > old.incomingStartBar)
        assertNull(old.cueModelId)
        assertEquals("test-preference", learned.cueModelId)
        val featureFree = LocalMixPlanner.bestTransition(song(List(20) { 4 }),song(List(20) { 4 }),
            searchOptions=AutoMixSearchOptions(cueModel=model)).automaticSelection!!
        assertNull(featureFree.cueModelId)
        val unsafe=withBarEnergy(song(List(20) { 4 },.6),List(20) { -18.0 })
        val failure=assertFailsWith<AutoMixPlanningException> {
            LocalMixPlanner.autoTransition(a,unsafe,4,
                qualityLimits=WarpQualityLimits(maximumPlaybackSpeed=1.01),
                searchOptions=AutoMixSearchOptions(cueModel=model,maximumClockFits=1024))
        }
        assertEquals(AutoMixFailureCode.CLOCK_REJECTED,failure.report.failure)
    }

    @Test
    fun automaticBarSearchChoosesARangeContainingOriginalCanonicalPins() {
        val first = song(List(8) { 4 })
        val second = song(List(8) { 4 }, 0.41)
        val result = AutoMixPlanner.transition(
            first, second, bars = 4,
            fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(25)),
        )
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(4, selected.outgoingStartBar)
        assertEquals(3, selected.incomingStartBar)
        val pin = result.clockFit.adjustments[13]
        assertTrue(pin.pinned)
        assertEquals(second.pulse.beats[25].seconds, pin.originalSourceSeconds)
        assertEquals(0.0, pin.allowedDisplacementSeconds)

        assertFailsWith<IllegalArgumentException> {
            AutoMixPlanner.transition(
                first, second, bars = 4,
                fitOptions = ClockFitOptions(pinnedIncomingBeats = setOf(second.pulse.beats.size)),
            )
        }
    }

    @Test
    fun measuredEnergyRanksAnIncomingLiftAndOutgoingReleaseOverTheOldCueOrder() {
        val outgoing = withBarEnergy(song(List(20) { 4 }, period = 0.6), List(16) { -12.0 } + List(4) { -24.0 })
        val incoming = withBarEnergy(song(List(20) { 4 }, period = 0.6), List(12) { -24.0 } + List(8) { -8.0 })
        val plan = LocalMixPlanner.autoTransition(outgoing, incoming, bars = 4)
        val selected = assertNotNull(plan.automaticSelection)
        assertEquals(AutoMixSelectionPolicy.AUDIO_AWARE_RANKING, selected.policy)
        assertEquals(16, selected.outgoingStartBar)
        assertEquals(8, selected.incomingStartBar)
        assertTrue(assertNotNull(selected.musicalCueEvidence).incomingEndLift > 0.5)
        assertEquals(5, plan.barMatches.size)
        assertTrue(plan.barMatches.all { kotlin.math.abs(it.originalBoundaryOutputResidualSeconds) < 1e-8 })
    }

    @Test
    fun fullyAutomaticModeSelectsLengthAsWellAsTimingSafeCues() {
        val outgoing = withBarEnergy(song(List(20) { 4 }, period = 0.6), List(16) { -12.0 } + List(4) { -24.0 })
        val incoming = withBarEnergy(song(List(20) { 4 }, period = 0.6), List(12) { -24.0 } + List(8) { -8.0 })
        val selected = assertNotNull(LocalMixPlanner.bestTransition(outgoing, incoming).automaticSelection)
        assertEquals(4, selected.barCount)
        assertEquals(16, selected.outgoingStartBar)
        assertEquals(8, selected.incomingStartBar)
        assertEquals(null, selected.search.requestedBars)
    }

    @Test
    fun automaticLengthDoesNotDoubleCountOverlappingBoundaryWindows() {
        // With unweighted four-second endpoint windows, the same changes at both ends made this
        // 150 BPM fixture select two bars (3.2 s) instead of the supported eight-bar phrase.
        val outgoing =
            withBarEnergy(
                song(List(20) { 4 }),
                listOf(
                    -18.0, -12.0, -18.0, -18.0, -24.0, -24.0, -18.0, -12.0,
                    -12.0, -24.0, -18.0, -12.0, -24.0, -12.0, -24.0, -12.0,
                    -12.0, -24.0, -18.0, -6.0,
                ),
            )
        val incoming =
            withBarEnergy(
                song(List(20) { 4 }),
                listOf(
                    -18.0, -12.0, -18.0, -12.0, -12.0, -18.0, -24.0, -18.0,
                    -6.0, -18.0, -18.0, -18.0, -24.0, -12.0, -18.0, -24.0,
                    -24.0, -18.0, -18.0, -18.0,
                ),
            )

        val selected =
            assertNotNull(LocalMixPlanner.bestTransition(outgoing, incoming).automaticSelection)

        assertEquals(8, selected.barCount)
        assertTrue(selected.outgoingEndSeconds - selected.outgoingStartSeconds >= 8.0)
    }

    @Test
    fun similarSongsWithBalancedOverlapsCanSelectSixteenBarsAutomatically() {
        fun compatibleSong(): LocalSongAnalysis {
            val measured = withBarEnergy(song(List(40) { 4 }), List(40) { -12.0 })
            return measured.copy(
                audio = measured.audio.copy(keyEstimate = "F major", keyConfidence = 0.2)
            )
        }
        val selected =
            assertNotNull(LocalMixPlanner.bestTransition(compatibleSong(), compatibleSong()).automaticSelection)
        assertEquals(16, selected.barCount)
        val evidence = assertNotNull(selected.musicalCueEvidence)
        assertTrue(evidence.longBlendAffinity > 0.9)
        assertTrue(evidence.overlapLevelBalance > 0.9)
    }

    @Test
    fun highConfidenceHarmonicNeighborsAndEnharmonicNamesCanUseLongBlends() {
        val measured = withBarEnergy(song(List(40) { 4 }), List(40) { -12.0 })
        fun keyed(name: String) =
            measured.copy(audio = measured.audio.copy(keyEstimate = name, keyConfidence = 0.2))

        for (
            (first, second) in
                listOf("B minor" to "E minor", "A# major" to "Bb major", "C major" to "A minor")
        ) {
            val selection =
                assertNotNull(
                    LocalMixPlanner.bestTransition(keyed(first), keyed(second)).automaticSelection
                )
            assertEquals(16, selection.barCount)
            assertTrue(assertNotNull(selection.musicalCueEvidence).longBlendAffinity > 0.9)
        }
        val lowConfidence =
            measured.copy(
                audio = measured.audio.copy(keyEstimate = "B minor", keyConfidence = 0.1)
            )
        val guarded =
            assertNotNull(
                LocalMixPlanner.bestTransition(lowConfidence, keyed("E minor")).automaticSelection
            )
        assertTrue(guarded.barCount < 16)
        assertEquals(0.0, assertNotNull(guarded.musicalCueEvidence).longBlendAffinity)
    }

    @Test
    fun aMatchingTempoAloneDoesNotTriggerAnExtendedBlend() {
        val first = withBarEnergy(song(List(40) { 4 }), List(40) { -12.0 })
        val second = withBarEnergy(song(List(40) { 4 }), List(40) { -20.0 })
        val matchingKeys =
            first.copy(audio = first.audio.copy(keyEstimate = "F major", keyConfidence = 0.2))
        val differentKey =
            first.copy(audio = first.audio.copy(keyEstimate = "G major", keyConfidence = 0.2))
        val poorBalance =
            second.copy(audio = second.audio.copy(keyEstimate = "F major", keyConfidence = 0.2))

        for (incoming in listOf(differentKey, poorBalance)) {
            val selection =
                assertNotNull(LocalMixPlanner.bestTransition(matchingKeys, incoming).automaticSelection)
            assertTrue(selection.barCount < 16)
            val evidence = assertNotNull(selection.musicalCueEvidence)
            if (incoming === differentKey) assertEquals(0.0, evidence.longBlendAffinity)
            else assertEquals(0.0, evidence.overlapLevelBalance)
        }
    }

    @Test
    fun longBlendBonusDoesNotRewardAnIncomingSectionThatWindsDown() {
        val measured = withBarEnergy(song(List(40) { 4 }), List(40) { -12.0 })
        val audio = measured.audio.copy(keyEstimate = "F major", keyConfidence = 0.2)
        val ranking = MusicalCueRanking(audio, audio)
        val grid = measured.barTracking!!.grid()
        val outgoing = ranking.outgoing(grid, 20, 16)
        val incoming = ranking.incoming(grid, 0, 16)
        val steady = ranking.evidence(outgoing, incoming, 16)
        val fading = ranking.evidence(
            outgoing,
            incoming.copy(endChange = -0.1, build = -0.4),
            16,
        )
        assertTrue(steady.lengthPreference > 0.15)
        assertEquals(0.15, fading.lengthPreference)
    }

    @Test
    fun cueRankingPenalizesOnlyLevelMismatchBeyondTwoDecibels() {
        val first = withBarEnergy(song(List(20) { 4 }), List(20) { -12.0 })
        val near = withBarEnergy(song(List(20) { 4 }), List(20) { -14.0 })
        val far = withBarEnergy(song(List(20) { 4 }), List(20) { -17.0 })
        val veryFar = withBarEnergy(song(List(20) { 4 }), List(20) { -20.0 })
        val firstGrid = first.barTracking!!.grid()

        fun evidence(second: LocalSongAnalysis): Pair<MusicalCueEvidence, Double> {
            val ranking = MusicalCueRanking(first.audio, second.audio)
            val outgoing = ranking.outgoing(firstGrid, start = 4, bars = 4)
            val incoming = ranking.incoming(second.barTracking!!.grid(), start = 4, bars = 4)
            return Pair(
                ranking.evidence(outgoing, incoming, bars = 4),
                outgoing.score + incoming.score + MusicalCueRanking.lengthPreference(4),
            )
        }

        val (tolerated, toleratedUnpenalizedScore) = evidence(near)
        val (penalized, penalizedUnpenalizedScore) = evidence(far)
        val (stronglyPenalized, stronglyPenalizedUnpenalizedScore) = evidence(veryFar)
        assertEquals(toleratedUnpenalizedScore, tolerated.score, 1e-9)
        assertEquals(penalizedUnpenalizedScore - 1.5, penalized.score, 1e-9)
        assertEquals(stronglyPenalizedUnpenalizedScore - 3.0, stronglyPenalized.score, 1e-9)
        assertEquals(2.0 / 3.0, tolerated.overlapLevelBalance, 1e-9)
        assertEquals(1.0 / 6.0, penalized.overlapLevelBalance, 1e-9)
        assertEquals(0.0, stronglyPenalized.overlapLevelBalance, 1e-9)
    }

    @Test
    fun cueRankingDoesNotTreatMissingOverlapLevelsAsBalanced() {
        val measured = withBarEnergy(song(List(40) { 4 }), List(40) { -12.0 })
        val keyed =
            measured.copy(
                audio = measured.audio.copy(keyEstimate = "F major", keyConfidence = 0.2)
            )
        val gapped =
            keyed.copy(
                audio =
                    keyed.audio.copy(
                        energyBlocks =
                            keyed.audio.energyBlocks.filterIndexed { bar, _ ->
                                bar < 4 || bar >= 36
                            }
                    )
            )
        val grid = keyed.barTracking!!.grid()
        val ranking = MusicalCueRanking(keyed.audio, gapped.audio)
        val outgoing = ranking.outgoing(grid, start = 8, bars = 16)
        val incoming = ranking.incoming(grid, start = 8, bars = 16)
        val evidence =
            ranking.evidence(
                outgoing,
                incoming,
                bars = 16,
            )

        assertEquals(0.0, evidence.overlapLevelBalance)
        assertEquals(MusicalCueRanking.lengthPreference(16), evidence.lengthPreference)
        assertEquals(
            outgoing.score + incoming.score + MusicalCueRanking.lengthPreference(16) - 2.0,
            evidence.score,
            1e-9,
        )
    }

    /** Missing audio outside the recording must not be treated as a measured dynamics change. */
    @Test
    fun cueRankingDoesNotFabricateLiftsAtTrackBoundaries() {
        val base = song(List(8) { 4 })
        val measured =
            withBarEnergy(
                base,
                listOf(-6.0) + List(6) { -24.0 } + listOf(-6.0),
            )
        val ranking = MusicalCueRanking(measured.audio, measured.audio)
        val grid = measured.barTracking!!.grid()

        val incomingAtStart = ranking.incoming(grid, start = 0, bars = 2)
        val outgoingAtEnd = ranking.outgoing(grid, start = 6, bars = 2)

        assertEquals(0.0, incomingAtStart.startChange)
        assertEquals(0.0, outgoingAtEnd.endChange)

        val boundary = grid.beats.at(grid.boundary(3))
        fun rankingWithBeforeCoverage(fraction: Double): MusicalCueRanking {
            val window = 4.0
            val blocks =
                listOf(
                    EnergyBlock(boundary - window * fraction, boundary, -24.0),
                    EnergyBlock(boundary, boundary + 1.0, -6.0),
                    EnergyBlock(boundary + 1.0, boundary + 2.0, -6.0),
                    EnergyBlock(boundary + 2.0, boundary + window, -6.0),
                )
            val audio = base.audio.copy(energyBlocks = blocks)
            return MusicalCueRanking(audio, audio)
        }

        val belowCutoff = rankingWithBeforeCoverage(0.49).incoming(grid, start = 3, bars = 2)
        val atCutoff = rankingWithBeforeCoverage(0.50).incoming(grid, start = 3, bars = 2)
        assertEquals(0.0, belowCutoff.startChange)
        assertEquals(0.8, atCutoff.startChange, absoluteTolerance = 1e-12)
    }

    /** A truncated onset envelope must not turn missing frames into a measured activity drop. */
    @Test
    fun cueRankingIgnoresUncoveredOnsetComparisons() {
        val measured = withBarEnergy(song(List(8) { 4 }), List(8) { -12.0 })
        val grid = measured.barTracking!!.grid()
        val boundary = grid.beats.at(grid.boundary(3))
        val onsetHop = 0.02
        val onsetOffset = 0.011
        val cutoffFrame = ceil((boundary + 4.0 * 0.50 - onsetOffset) / onsetHop).toInt()
        val fullEnvelope =
            FloatArray(ceil(measured.audio.durationSeconds / onsetHop).toInt()) {
                if (onsetOffset + it * onsetHop < boundary) 1.0f else 0.0f
            }
        val fullAudio =
            measured.audio.copy(
                onsetEnvelope = fullEnvelope,
                onsetHopSeconds = onsetHop,
                onsetTimeOffsetSeconds = onsetOffset,
            )
        val belowCutoffAudio =
            fullAudio.copy(
                onsetEnvelope = fullEnvelope.copyOf(cutoffFrame - 1)
            )
        val atCutoffAudio =
            fullAudio.copy(
                onsetEnvelope = fullEnvelope.copyOf(cutoffFrame)
            )

        val belowCutoff =
            MusicalCueRanking(belowCutoffAudio, belowCutoffAudio)
                .incoming(grid, start = 3, bars = 2)
        val atCutoff =
            MusicalCueRanking(atCutoffAudio, atCutoffAudio)
                .incoming(grid, start = 3, bars = 2)

        assertEquals(0.0, belowCutoff.startChange)
        assertTrue(atCutoff.startChange < 0.0)
    }

    @Test
    fun earlyBreakDoesNotDiscardMostOfTheOutgoingSong() {
        val outgoing =
            withBarEnergy(
                song(List(20) { 4 }, period = 0.6),
                List(8) { -10.0 } + List(4) { -30.0 } + List(4) { -10.0 } + List(4) { -20.0 },
            )
        val incoming = withBarEnergy(song(List(20) { 4 }, period = 0.6), List(12) { -24.0 } + List(8) { -8.0 })
        val selected = assertNotNull(LocalMixPlanner.autoTransition(outgoing, incoming, bars = 4).automaticSelection)
        assertEquals(16, selected.outgoingStartBar)
        assertEquals(8, selected.incomingStartBar)
    }

    @Test
    fun missingAudioFeaturesKeepExplicitLengthPolicyAndChooseAConservativeAutomaticLength() {
        val a = song(List(20) { 4 })
        val explicit = assertNotNull(LocalMixPlanner.autoTransition(a, a, bars = 4).automaticSelection)
        assertEquals(AutoMixSelectionPolicy.EARLY_INCOMING_LATE_OUTGOING, explicit.policy)
        val automatic = assertNotNull(LocalMixPlanner.bestTransition(a, a).automaticSelection)
        assertEquals(AutoMixSelectionPolicy.AUTOMATIC_LENGTH_FALLBACK, automatic.policy)
        assertEquals(8, automatic.barCount)
        assertEquals(null, automatic.musicalCueEvidence)
    }

    /** Budgets below the length count must still fit collected pairs, with or without energy data. */
    @Test
    fun automaticLengthSearchFitsCollectedCandidatesBeforeExhaustingSmallPairBudgets() {
        val plain = song(List(40) { 4 })
        val measured = withBarEnergy(plain, List(40) { -18.0 })
        for (track in listOf(plain, measured)) {
            for (budget in 1..4) {
                val plan = AutoMixPlanner.bestTransition(
                    track, track,
                    searchOptions = AutoMixSearchOptions(maximumCandidatePairs = budget),
                )
                val selected = assertNotNull(plan.automaticSelection)
                assertEquals(budget, selected.search.inspectedPairs)
                assertEquals(budget.toLong(), selected.search.compatibleCandidates)
                assertEquals(0, selected.search.rejectedClocks)
                assertEquals(AutoMixSearchStrategy.RANKED_TRANSITION_SCAN, selected.search.strategy)
                assertEquals(selected.barCount + 1, plan.barMatches.size)
                assertTrue(plan.barMatches.all {
                    it.originalIncomingSeconds == it.preparedIncomingSeconds &&
                        kotlin.math.abs(it.originalBoundaryOutputResidualSeconds) < 1e-8
                })
            }
        }
    }

    /** Retaining ranked candidates must preserve speed limits and the independent one-fit cap. */
    @Test
    fun truncatedRankedSearchStillRejectsUnsafeClocks() {
        for (budget in 1..4) {
            val failure = assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.bestTransition(
                    song(List(40) { 4 }), song(List(40) { 4 }, 0.6),
                    qualityLimits = WarpQualityLimits(maximumPlaybackSpeed = 1.01),
                    searchOptions = AutoMixSearchOptions(
                        maximumCandidatePairs = budget, maximumClockFits = 1,
                    ),
                )
            }
            assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, failure.report.failure)
            assertEquals(AutoMixSearchStrategy.RANKED_TRANSITION_SCAN, failure.report.strategy)
            assertEquals(budget, failure.report.inspectedPairs)
            assertEquals(budget.toLong(), failure.report.compatibleCandidates)
            assertEquals(1, failure.report.rejectedClocks)
        }
    }

    @Test
    fun fixedLengthSearchRecoversCompatibleBarsOutsideTheRankedShortlist() {
        val outgoing = withBarEnergy(
            song(List(8) { 4 } + List(300) { 3 }), List(308) { -18.0 },
        )
        val incoming = withBarEnergy(song(List(148) { 4 }), List(148) { -18.0 })
        val plan = AutoMixPlanner.transition(outgoing, incoming, bars = 4)
        val selected = assertNotNull(plan.automaticSelection)
        assertEquals(AutoMixSelectionPolicy.EARLY_INCOMING_LATE_OUTGOING, selected.policy)
        assertEquals(AutoMixSearchStrategy.ORDERED_TRANSITION_SCAN, selected.search.strategy)
        assertEquals(4, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
        assertEquals(List(4) { 4 }, selected.pulsesPerBar)
        assertNull(selected.musicalCueEvidence)
        // The ranked Cartesian scan used 128 starts on each side before the ordered retry.
        assertTrue(selected.search.inspectedPairs > 128 * 128)
        assertTrue(selected.search.inspectedPairs <= AutoMixSearchOptions().maximumCandidatePairs)
        assertEquals(5, plan.barMatches.size)
        assertTrue(plan.barMatches.all {
            it.originalIncomingSeconds == it.preparedIncomingSeconds &&
                kotlin.math.abs(it.originalBoundaryOutputResidualSeconds) < 1e-8
        })

        val limited = assertFailsWith<AutoMixPlanningException> {
            AutoMixPlanner.transition(
                outgoing, incoming, bars = 4,
                searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 128 * 128),
            )
        }
        assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, limited.report.failure)
        assertEquals(128 * 128, limited.report.inspectedPairs)
        assertEquals(0, limited.report.rejectedClocks)
    }

    @Test
    fun automaticLengthSearchRecoversCompatibleMeterOutsideEveryRankedShortlist() {
        val outgoing = withBarEnergy(
            song(List(8) { 4 } + List(300) { 3 }), List(308) { -18.0 },
        )
        val incoming = withBarEnergy(song(List(200) { 4 }), List(200) { -18.0 })
        val selected = assertNotNull(
            AutoMixPlanner.bestTransition(outgoing, incoming).automaticSelection,
        )
        assertEquals(AutoMixSelectionPolicy.AUTOMATIC_LENGTH_FALLBACK, selected.policy)
        assertEquals(AutoMixSearchStrategy.ORDERED_TRANSITION_SCAN, selected.search.strategy)
        assertEquals(8, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
        assertEquals(List(8) { 4 }, selected.pulsesPerBar)
        assertNull(selected.search.requestedBars)
        assertNull(selected.musicalCueEvidence)
        assertTrue(selected.search.inspectedPairs > 4 * 128 * 128)
        assertTrue(selected.search.inspectedPairs <= AutoMixSearchOptions().maximumCandidatePairs)

        // Ranked and ordered attempts share the same pair and clock-fit budgets.
        val limited = assertFailsWith<AutoMixPlanningException> {
            AutoMixPlanner.bestTransition(
                outgoing, incoming,
                searchOptions = AutoMixSearchOptions(
                    maximumCandidatePairs = selected.search.inspectedPairs - 1,
                ),
            )
        }
        assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, limited.report.failure)
        assertEquals(AutoMixSearchStrategy.ORDERED_TRANSITION_SCAN, limited.report.strategy)
        assertEquals(selected.search.inspectedPairs - 1, limited.report.inspectedPairs)
        assertEquals(0, limited.report.rejectedClocks)
    }

    @Test
    fun orderedRetryRetainsTheRankedClockFitBudgetAndQualityLimits() {
        val outgoing = withBarEnergy(song(List(4) { 4 }), List(4) { -18.0 })
        val incoming = withBarEnergy(song(List(4) { 4 }, 0.6), List(4) { -18.0 })
        val quality = WarpQualityLimits(maximumPlaybackSpeed = 1.01)
        val limited = assertFailsWith<AutoMixPlanningException> {
            AutoMixPlanner.transition(
                outgoing, incoming, bars = 4, qualityLimits = quality,
                searchOptions = AutoMixSearchOptions(maximumClockFits = 1),
            )
        }
        assertEquals(AutoMixSearchStrategy.ORDERED_TRANSITION_SCAN, limited.report.strategy)
        assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, limited.report.failure)
        assertEquals(1, limited.report.rejectedClocks)
        val rejected = assertFailsWith<AutoMixPlanningException> {
            AutoMixPlanner.transition(outgoing, incoming, bars = 4, qualityLimits = quality)
        }
        assertEquals(AutoMixFailureCode.CLOCK_REJECTED, rejected.report.failure)
        assertEquals(2, rejected.report.rejectedClocks)
    }

    @Test
    fun musicalCueRankingRequiresPositiveFiniteDurationsForBothTracks() {
        val audio = withBarEnergy(song(List(4) { 4 }), List(4) { -18.0 }).audio
        assertTrue(MusicalCueRanking(audio, audio).available)
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val invalid = audio.copy(durationSeconds = duration)
            assertFalse(MusicalCueRanking(invalid, audio).available)
            assertFalse(MusicalCueRanking(audio, invalid).available)
        }
    }

    @Test
    fun musicalCueRankingCanCancelWhilePreparingLongFeatureTimelines() {
        val base = withBarEnergy(song(List(40) { 4 }), List(40) { -18.0 }).audio
        val longFeatures =
            base.copy(
                onsetEnvelope = FloatArray(1_000_000) { 1.0f },
                onsetHopSeconds = 0.02,
                onsetTimeOffsetSeconds = 0.0,
            )
        var checks = 0

        assertFailsWith<MixCancelledException> {
            MusicalCueRanking(longFeatures, longFeatures) {
                if (++checks == 8) throw MixCancelledException()
            }
        }
        assertEquals(8, checks)
    }

    @Test
    fun allInteriorBarBoundariesMustMatchEvenWhenTotalPulsesMatch() {
        val a = song(List(4) { 3 } + List(4) { 5 }).copy(barTracking = null)
        val b = song(List(8) { 4 }).copy(barTracking = null)
        assertEquals(a.pulse.beats.size, b.pulse.beats.size)
        assertFailsWith<IllegalArgumentException> { LocalMixPlanner.transition(a, b, 0, 0, 8) }
        val mismatch =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(song(List(12) { 3 }), song(List(12) { 4 }), 4)
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, mismatch.report.failure)
    }

    @Test
    fun automaticOverlapMatchesSupportedMeterChangesAndPinsEveryBoundary() {
        val meters = List(4) { 8 } + List(10) { 6 }
        val a = song(meters)
        val b = song(meters, 0.41)
        val result = LocalMixPlanner.overlap(a, b)
        val selection = assertNotNull(result.automaticSelection)
        assertEquals(14, selection.barCount)
        assertEquals(meters, selection.pulsesPerBar)
        assertEquals(15, result.barMatches.size)
        assertFalse(result.mixPlan.releaseAfterFade)
        assertTrue(
            result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
        )
        assertEquals(AutoMixSelectionPolicy.LONGEST_SUPPORTED_OVERLAP, selection.policy)
    }

    /** A partial prefix scan must fit its supported 3/4 candidate and preserve original boundaries. */
    @Test
    fun variableMeterOverlapFitsCollectedCandidatesBeforeExhaustingPairBudget() {
        val meters = List(4) { 4 } + List(4) { 3 }
        val track = song(meters)
        val plan =
            AutoMixPlanner.overlap(
                track,
                track,
                searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 10),
            )
        val selected = assertNotNull(plan.automaticSelection)
        assertEquals(AutoMixSearchStrategy.GENERAL_PREFIX_SCAN, selected.search.strategy)
        assertEquals(10, selected.search.inspectedPairs)
        assertEquals(1L, selected.search.compatibleCandidates)
        assertEquals(2, selected.barCount)
        assertEquals(6, selected.outgoingStartBar)
        assertEquals(6, selected.incomingStartBar)
        assertEquals(listOf(3, 3), selected.pulsesPerBar)
        assertEquals(3, plan.barMatches.size)
        assertTrue(plan.barMatches.all {
            it.originalIncomingSeconds == it.preparedIncomingSeconds &&
                kotlin.math.abs(it.originalBoundaryOutputResidualSeconds) < 1e-8
        })
    }

    /** A retained variable-meter candidate must still be rejected when its clock exceeds limits. */
    @Test
    fun truncatedVariableMeterOverlapStillRejectsUnsafeClocks() {
        val meters = List(4) { 4 } + List(4) { 3 }
        val failure =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(
                    song(meters),
                    song(meters, period = 0.6),
                    qualityLimits = WarpQualityLimits(maximumPlaybackSpeed = 1.01),
                    searchOptions = AutoMixSearchOptions(
                        maximumCandidatePairs = 10,
                        maximumClockFits = 1,
                    ),
                )
            }
        assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, failure.report.failure)
        assertEquals(AutoMixSearchStrategy.GENERAL_PREFIX_SCAN, failure.report.strategy)
        assertEquals(10, failure.report.inspectedPairs)
        assertEquals(1L, failure.report.compatibleCandidates)
        assertEquals(1, failure.report.rejectedClocks)
    }

    @Test
    fun equivalentDurationTieDoesNotMoveTheAutomaticCueBecauseOfFloatingPointNoise() {
        val result = AutoMixPlanner.overlap(song(List(20) { 4 }), song(List(4) { 4 }))
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(4, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
    }

    @Test
    fun longConstantMeterSongsAvoidQuadraticCandidateMaterialization() {
        val a = song(List(900) { 4 })
        val b = song(List(900) { 4 }, 0.41)
        val result = AutoMixPlanner.overlap(a, b)
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(900, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
        assertEquals(AutoMixSearchStrategy.CONSTANT_METER_MERGE, selected.search.strategy)
        assertEquals(899L * 899L, selected.search.compatibleCandidates)
        assertEquals(899, selected.search.inspectedPairs)
        assertEquals(901, result.barMatches.size)
        assertTrue(
            result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
        )
    }

    @Test
    fun incompatibleConstantMetersAreDeclinedWithoutExhaustingPairBudget() {
        val failure =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(
                    song(List(30) { 3 }),
                    song(List(30) { 4 }),
                    searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 1),
                )
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, failure.report.failure)
        assertEquals(0, failure.report.inspectedPairs)
        assertEquals(0L, failure.report.compatibleCandidates)
    }

    @Test
    fun lazyOverlapAdvancesRejectedStreamsToTheHighestRankedShorterSpan() {
        val a = song(List(4) { 4 })
        val b = song(List(4) { 4 }, periods = listOf(0.6, 0.4, 0.4, 0.4))
        val result =
            AutoMixPlanner.overlap(
                a,
                b,
                qualityLimits = WarpQualityLimits(maximumPlaybackSpeed = 1.1),
            )
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(3, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(1, selected.incomingStartBar)
        assertTrue(selected.search.rejectedClocks > 0)
        assertTrue(
            result.barMatches.all { it.originalIncomingSeconds == it.preparedIncomingSeconds }
        )
    }

    @Test
    fun lazyOverlapKeepsDurationRankingWhenOutgoingTempoVaries() {
        val a = song(List(8) { 4 }, periods = List(4) { 0.4 } + List(4) { 0.6 })
        val b = song(List(4) { 4 })
        val result =
            AutoMixPlanner.overlap(
                a,
                b,
                qualityLimits = WarpQualityLimits(minimumPlaybackSpeed = 0.95),
            )
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(4, selected.barCount)
        assertEquals(0, selected.outgoingStartBar)
        assertEquals(0, selected.incomingStartBar)
        assertTrue(selected.search.rejectedClocks > 0)
    }

    @Test
    fun rejectionReportSeparatesUsablePhaseFromRejectedPulseEvidence() {
        val good = song(List(10) { 4 })
        val issue =
            BeatGridIssue(
                "UNSUPPORTED_TEST_PULSES",
                BeatIssueSeverity.ERROR,
                0.0,
                good.audio.durationSeconds,
                "deliberately unsupported clock",
            )
        val bad =
            good.copy(
                pulse = good.pulse.copy(quality = good.pulse.quality.copy(issues = listOf(issue))),
                barTracking = good.barTracking!!.withPulseSupport(emptyList()),
            )
        val failure =
            assertFailsWith<AutoMixPlanningException> { AutoMixPlanner.transition(bad, good, 4) }
        assertEquals(AutoMixFailureCode.NO_CONFIDENT_BAR_GRID, failure.report.failure)
        val diagnostics = failure.report.tracks
        assertEquals(2, diagnostics.size)
        val outgoing = diagnostics.first { it.role == AutoMixTrackRole.OUTGOING }
        assertEquals(10, outgoing.phaseUsableBars)
        assertEquals(0, outgoing.pulseSupportedBars)
        assertEquals(0, outgoing.jointUsableBars)
        assertEquals(0, outgoing.longestSupportedBars)
        assertEquals(
            mapOf(BarUsabilityIssue.UNSUPPORTED_PULSE to 10),
            outgoing.unusableReasonCounts,
        )
        assertEquals(listOf("UNSUPPORTED_TEST_PULSES"), outgoing.sourcePulseErrorCodes)
        assertEquals(10, diagnostics.first { it.role == AutoMixTrackRole.INCOMING }.jointUsableBars)
    }

    @Test
    fun unrelatedRejectedOutroDoesNotBlockAConfidentScopedOverlap() {
        val base = song(List(16) { 4 })
        val cutoff = 32
        val issue =
            BeatGridIssue(
                "UNSUPPORTED_OUTRO",
                BeatIssueSeverity.ERROR,
                base.pulse.beats[40].seconds,
                base.audio.durationSeconds,
                "adversarial unsupported tail",
            )
        val subset = base.pulse.beats.take(cutoff + 1)
        val accepted =
            AcceptedPulseRegion(
                0,
                cutoff + 1,
                subset.first().seconds,
                subset.last().seconds,
                BeatGridQuality.audit(subset),
            )
        val a =
            base.copy(
                pulse = base.pulse.copy(quality = base.pulse.quality.copy(issues = listOf(issue))),
                pulseRegions = listOf(accepted),
            )
        assertFailsWith<IllegalArgumentException> { a.matchingGrid() }
        val result = AutoMixPlanner.overlap(a, base)
        val selected = assertNotNull(result.automaticSelection)
        assertEquals(8, selected.barCount)
        assertEquals(cutoff, selected.outgoingEndBeat)
        assertEquals(base.audio.durationSeconds, a.audio.durationSeconds)
        assertEquals(base.pulse.beats, a.pulse.beats)
        assertTrue(result.mixPlan.observedCoverage.outputEndSeconds < a.audio.durationSeconds)
        assertFalse(a.pulse.quality.safeForAutomaticMix)
    }

    @Test
    fun noCompatibleLengthDoesNotSilentlyShrinkOrForceClockGates() {
        val short = song(List(12) { 4 })
        val missing =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(short, short, 16)
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, missing.report.failure)
        val unsafe =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(
                    song(List(5) { 4 }),
                    song(List(5) { 4 }, 0.6),
                    2,
                    qualityLimits = WarpQualityLimits(maximumPlaybackSpeed = 1.01),
                )
            }
        assertEquals(AutoMixFailureCode.CLOCK_REJECTED, unsafe.report.failure)
        assertTrue(unsafe.report.rejectedClocks > 0)
    }

    @Test
    fun scopedAutomaticPlanStillPreparesTheEntireUntrimmedSourceAndPreservesOutro() {
        val a = song(List(12) { 4 })
        val b = song(List(12) { 4 }, 0.41)
        fun source(duration: Double) =
            object : StereoPcm {
                override val durationSeconds = duration

                override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                    FloatArray(frames * 2)
            }
        val first = source(a.audio.durationSeconds)
        val second = source(b.audio.durationSeconds)
        var receivedFullSource = false
        var closed = false
        val engine =
            object : PitchStretchEngine {
                override fun prepare(
                    source: StereoPcm,
                    schedule: WarpSchedule,
                    progress: (Double) -> Unit,
                ): PreparedStereoPcm {
                    receivedFullSource = source === second
                    assertEquals(
                        kotlin.math.ceil(b.audio.durationSeconds * schedule.sampleRate).toLong(),
                        schedule.sourceFrames,
                    )
                    return object : PreparedStereoPcm {
                        override val durationSeconds =
                            schedule.outputFrames.toDouble() / schedule.sampleRate

                        override fun read(
                            startSeconds: Double,
                            frames: Int,
                            outputSampleRate: Int,
                        ) = FloatArray(frames * 2)

                        override fun close() {
                            closed = true
                        }
                    }
                }
            }
        val plan = AutoMixPlanner.overlap(a, b)
        val prepared = plan.prepare(first, second, engine)
        assertTrue(receivedFullSource)
        assertTrue(
            prepared.endFrame(MixMode.OVERLAP) >=
                kotlin.math.ceil(a.audio.durationSeconds * 48000).toLong()
        )
        assertTrue(
            prepared.endFrame(MixMode.OVERLAP) >
                plan.mixPlan.observedCoverage.outputEndSeconds * 48000
        )
        prepared.close()
        assertTrue(closed)
    }

    @Test
    fun boundedSearchAndCancellationReturnExplicitOutcomes() {
        val a = song(List(12) { 4 })
        val limited =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(
                    a,
                    a,
                    searchOptions = AutoMixSearchOptions(maximumCandidatePairs = 1),
                )
            }
        assertEquals(AutoMixFailureCode.SEARCH_LIMIT_REACHED, limited.report.failure)
        assertFailsWith<MixCancelledException> {
            AutoMixPlanner.transition(a, a, isCancelled = { true })
        }
        val absent =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.overlap(a.copy(barTracking = null), a)
            }
        assertEquals(AutoMixFailureCode.NO_CONFIDENT_BAR_GRID, absent.report.failure)
    }

    @Test
    fun unsupportedFadeSpanAndInvalidSourceCoverageHaveExplicitOutcomes() {
        val longMeter = song(List(36) { 32 })
        val unsupported =
            assertFailsWith<AutoMixPlanningException> {
                AutoMixPlanner.transition(longMeter, longMeter, 32)
            }
        assertEquals(AutoMixFailureCode.NO_COMPATIBLE_BAR_RANGE, unsupported.report.failure)
        val a = song(List(12) { 4 })
        val truncated =
            a.copy(audio = a.audio.copy(durationSeconds = a.pulse.beats.last().seconds - 0.1))
        val missing =
            assertFailsWith<AutoMixPlanningException> { AutoMixPlanner.overlap(truncated, a) }
        assertEquals(AutoMixFailureCode.SOURCE_COVERAGE_INVALID, missing.report.failure)
        assertFailsWith<IllegalArgumentException> {
            AutoMixPlanner.overlap(a, a, outputSampleRate = 0)
        }
    }
}
