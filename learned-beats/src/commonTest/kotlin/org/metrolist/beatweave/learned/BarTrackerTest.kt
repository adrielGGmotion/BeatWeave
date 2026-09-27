package org.metrolist.beatweave.learned

import kotlin.math.roundToInt
import kotlin.test.*
import org.metrolist.beatweave.Beat

class BarTrackerTest {
    private data class Fixture(
        val beats: List<Beat>,
        val raw: List<Double>,
        val logits: FloatArray,
    )

    private fun fixture(
        meters: List<Int>,
        missing: Set<Int> = emptySet(),
        extra: Set<Int> = emptySet(),
    ): Fixture {
        val indices = ArrayList<Int>()
        var beat = 0
        meters.forEach {
            indices.add(beat)
            beat += it
        }
        indices.add(beat)
        val beats = List(beat + 1) { Beat(0.2 + it * 0.4, 0.9f) }
        val logits = FloatArray(((beats.last().seconds + 0.2) * 50).roundToInt()) { -8f }
        val observed = ((indices.toSet() - missing) + extra).sorted()
        for (i in observed) logits[(beats[i].seconds * 50).roundToInt()] = 8f
        return Fixture(beats, observed.map { beats[it].seconds }, logits)
    }

    private fun track(f: Fixture, options: BarTrackingOptions = BarTrackingOptions()) =
        BarTracker.track(f.beats, f.raw, f.logits, options)

    @Test
    fun tracksMetersWithoutAssumingFourAndSupportsConfiguredOddMeter() {
        for (meter in listOf(2, 3, 4, 6, 8)) {
            val result = track(fixture(List(12) { meter }))
            assertEquals(listOf(meter), result.regions.map { it.pulsesPerBar })
            assertEquals(12, result.barCount)
            result.requireUsable()
            assertTrue((0 until result.barCount).all { result.grid().beatsInBar(it) == meter })
        }
        val odd =
            track(fixture(List(10) { 7 }), BarTrackingOptions(pulsesPerBar = listOf(3, 4, 5, 7)))
        assertEquals(listOf(7), odd.regions.map { it.pulsesPerBar })
        odd.requireUsable()
    }

    @Test
    fun preservesRawObservationsAndReportsInsertedAndRemovedBoundaries() {
        val f = fixture(List(16) { 4 }, missing = setOf(20), extra = setOf(30))
        val result = track(f)
        assertEquals(f.raw, result.rawDownbeatSeconds)
        assertEquals((0..16).map { it * 4 }, result.boundaries.map { it.beatIndex })
        assertEquals(
            listOf(20),
            result.edits
                .filter { it.kind == BarBoundaryEditKind.INFERRED }
                .map { it.nearestBeatIndex },
        )
        assertEquals(
            listOf(30),
            result.edits
                .filter { it.kind == BarBoundaryEditKind.REMOVED }
                .map { it.nearestBeatIndex },
        )
        assertEquals(f.beats.map { it.seconds }, result.grid().beats.times.toList())
    }

    @Test
    fun removedStrongInteriorDownbeatCannotBeHiddenByConfidentMeterPersistence() {
        val f = fixture(List(16) { 4 }, extra = setOf(30))
        val result = track(f)
        assertEquals((0..16).map { it * 4 }, result.boundaries.map { it.beatIndex })
        val removed = result.edits.single { it.kind == BarBoundaryEditKind.REMOVED }
        assertEquals(30, removed.nearestBeatIndex)
        assertTrue(removed.modelDownbeatScore >= 0.5)
        assertTrue(result.barMinimumStatePosteriors[7] >= result.options.minimumStatePosterior)
        assertEquals(
            listOf(BarUsabilityIssue.CONFLICTING_DOWNBEAT_EVIDENCE),
            result.issuesForBar(7),
        )
        assertFalse(result.isBarUsable(7))
        assertFailsWith<UncertainBarsException> { result.requireUsable(7, 1) }
        result.requireUsable(0, 7)
        result.requireUsable(8, 8)
        assertEquals(f.raw, result.rawDownbeatSeconds)
    }

    @Test
    fun removedWeakInteriorObservationDoesNotVetoOtherwiseSupportedBars() {
        val f = fixture(List(16) { 4 }, extra = setOf(30))
        val logits = f.logits.copyOf()
        logits[(f.beats[30].seconds * 50).roundToInt()] = -2f
        val result = BarTracker.track(f.beats, f.raw, logits)
        val removed = result.edits.single { it.kind == BarBoundaryEditKind.REMOVED }
        assertTrue(removed.modelDownbeatScore < 0.5)
        assertEquals(emptyList(), result.issuesForBar(7))
        result.requireUsable()
        assertEquals(f.raw, result.rawDownbeatSeconds)
    }

    @Test
    fun removedDuplicateNearRetainedEndpointDoesNotBecomeAnInteriorConflict() {
        val f = fixture(List(16) { 4 })
        val duplicate = f.beats[28].seconds + 0.04
        val raw = (f.raw + duplicate).sorted()
        val result = BarTracker.track(f.beats, raw, f.logits)
        val removed = result.edits.single { it.kind == BarBoundaryEditKind.REMOVED }
        assertEquals(duplicate, removed.seconds)
        assertEquals(28, removed.nearestBeatIndex)
        assertTrue(removed.modelDownbeatScore >= 0.5)
        result.requireUsable()
        assertTrue(
            (0 until result.barCount).all {
                BarUsabilityIssue.CONFLICTING_DOWNBEAT_EVIDENCE !in result.issuesForBar(it)
            }
        )
    }

    @Test
    fun repeatedEvidenceAllowsRealMeterChanges() {
        val result = track(fixture(List(10) { 3 } + List(10) { 4 } + List(10) { 6 }))
        assertEquals(listOf(3, 4, 6), result.regions.map { it.pulsesPerBar })
        assertEquals(30, result.barCount)
        result.requireUsable(2, 4)
        result.requireUsable(13, 4)
        result.requireUsable(23, 4)
    }

    @Test
    fun unknownPhaseAbstainsWithoutChangingBeatMatchingData() {
        val f = fixture(List(12) { 4 })
        val result = BarTracker.track(f.beats, emptyList(), FloatArray(f.logits.size))
        assertFalse(result.safeForAutomaticBars)
        assertFailsWith<UncertainBarsException> { result.requireUsable() }
        assertEquals(f.beats.map { it.seconds }, result.grid().beats.times.toList())
        val none = BarTracker.track(emptyList(), emptyList(), FloatArray(0))
        assertEquals(0, none.barCount)
        assertFailsWith<UncertainBarsException> { none.grid() }
    }

    @Test
    fun constantEvidenceCannotBecomeCertainBarsFromSequenceEdgeBias() {
        val beats = List(47) { Beat(0.2 + it * 0.4, 1f) }
        for (score in listOf(-1000f, 1000f)) {
            val raw = if (score > 0f) beats.map { it.seconds } else emptyList()
            val result = BarTracker.track(beats, raw, FloatArray(1000) { score })
            assertFalse(result.safeForAutomaticBars)
            assertTrue((0 until result.barCount).none { result.isBarUsable(it) })
        }
    }

    @Test
    fun confidentMeterPersistenceCannotCertifyWeakInferredHalfBarBoundaries() {
        val f = fixture(List(24) { 2 }, missing = setOf(42, 46))
        val logits = f.logits.copyOf()
        for (beat in listOf(42, 46)) logits[(f.beats[beat].seconds * 50).roundToInt()] = -2f
        val result = BarTracker.track(f.beats, f.raw, logits)
        val weak = result.boundaries.withIndex().filter { it.value.beatIndex in setOf(42, 46) }
        assertEquals(2, weak.size)
        for ((bar, boundary) in weak) {
            assertTrue(boundary.downbeatPosterior > 0.9)
            assertFalse(boundary.observed)
            assertTrue(boundary.modelDownbeatScore < 0.5)
            assertFalse(result.isBarUsable(bar - 1))
            assertFalse(result.isBarUsable(bar))
        }
        result.requireUsable(2, 8)
    }

    @Test
    fun pulseMaskPreservesProposalsWhileRejectingBarsOutsideAcceptedClockRegions() {
        val f = fixture(List(12) { 4 })
        val phaseOnly = track(f)
        assertTrue(phaseOnly.safeForAutomaticBars)
        assertFalse(phaseOnly.hasPulseSupportAudit)
        val region =
            AcceptedPulseRegion(
                0,
                25,
                f.beats.first().seconds,
                f.beats[24].seconds,
                org.metrolist.beatweave.BeatGridQuality.audit(f.beats.take(25)),
            )
        val scoped = phaseOnly.withPulseSupport(listOf(region))
        assertTrue(scoped.hasPulseSupportAudit)
        assertEquals(phaseOnly.boundaries, scoped.boundaries)
        assertEquals(phaseOnly.regions, scoped.regions)
        assertEquals(phaseOnly.edits, scoped.edits)
        assertEquals(phaseOnly.rawDownbeatSeconds, scoped.rawDownbeatSeconds)
        scoped.requireUsable(0, 6)
        assertFalse(scoped.isBarUsable(6))
        assertFailsWith<UncertainBarsException> { scoped.requireUsable(6, 2) }
        assertTrue(phaseOnly.isBarUsable(6))
        val rejected = phaseOnly.withPulseSupport(emptyList())
        assertTrue((0 until rejected.barCount).none { rejected.isBarUsable(it) })
        val snapshot = assertNotNull(scoped.barPulseSupported)
        snapshot[0] = false
        assertTrue(scoped.isBarUsable(0))
    }

    @Test
    fun diagnosticsRetainConcurrentPhaseAndPulseFailuresWithoutChangingDecisions() {
        val f = fixture(List(24) { 2 }, missing = setOf(42, 46))
        val phase = track(f)
        val result = phase.withPulseSupport(emptyList())
        val weakBar = result.boundaries.indexOfFirst { it.beatIndex == 42 }
        assertTrue(weakBar > 0)
        assertEquals(
            listOf(
                BarUsabilityIssue.UNSUPPORTED_PULSE,
                BarUsabilityIssue.WEAK_BOUNDARY_EVIDENCE,
                BarUsabilityIssue.UNCERTAIN_METER_PHASE,
            ),
            result.issuesForBar(weakBar),
        )
        for (bar in 0 until result.barCount) assertEquals(
            result.isBarUsable(bar),
            result.issuesForBar(bar).isEmpty(),
        )
        for (bar in 0 until phase.barCount) assertEquals(
            phase.isBarUsable(bar),
            phase.issuesForBar(bar).isEmpty(),
        )
        assertEquals(phase.boundaries, result.boundaries)
        assertEquals(phase.regions, result.regions)
    }

    @Test
    fun clippedFinalObservationIsVisibleButNotAcceptedAsACompleteBarEndpoint() {
        val f = fixture(List(12) { 4 })
        val endFrame = (f.beats.last().seconds * 50).roundToInt()
        val result = BarTracker.track(f.beats, f.raw, f.logits.copyOf(endFrame + 1))
        assertEquals(f.raw, result.downbeatSeconds)
        assertFalse(result.boundaries.last().completeObservationWindow)
        assertFalse(result.isBarUsable(result.barCount - 1))
        result.requireUsable(2, 8)
        assertFailsWith<UncertainBarsException> { result.requireUsable(result.barCount - 1, 1) }
    }

    @Test
    fun clippedInitialObservationIsVisibleButNotAcceptedAsACompleteBarEndpoint() {
        val f = fixture(List(12) { 4 })
        val shiftedBeats = f.beats.map { it.copy(seconds = it.seconds - 0.2) }
        val shiftedRaw = f.raw.map { it - 0.2 }
        val result =
            BarTracker.track(shiftedBeats, shiftedRaw, f.logits.copyOfRange(10, f.logits.size))
        assertEquals(shiftedRaw, result.downbeatSeconds)
        assertFalse(result.boundaries.first().completeObservationWindow)
        assertFalse(result.isBarUsable(0))
        result.requireUsable(2, 8)
        assertFailsWith<UncertainBarsException> { result.requireUsable(0, 1) }
    }

    @Test
    fun uncertainLocalRangeDoesNotInvalidateOtherReliableBars() {
        val f = fixture(List(12) { 4 } + listOf(3) + List(12) { 4 })
        val result = track(f)
        assertTrue(result.regions.any { it.completeBars < 3 })
        assertFalse(result.safeForAutomaticBars)
        result.requireUsable(2, 4)
        assertFailsWith<UncertainBarsException> { result.requireUsable() }
    }

    @Test
    fun rejectsInvalidEvidenceAndHonorsCancellation() {
        val f = fixture(List(8) { 4 })
        assertFailsWith<IllegalArgumentException> {
            BarTracker.track(f.beats.reversed(), f.raw, f.logits)
        }
        assertFailsWith<IllegalArgumentException> {
            BarTracker.track(f.beats, f.raw, floatArrayOf(Float.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            BarTracker.track(f.beats, f.raw, floatArrayOf(0f))
        }
        assertFailsWith<IllegalArgumentException> {
            BarTrackingOptions(pulsesPerBar = listOf(4, 4))
        }
        class Cancelled : RuntimeException()
        assertFailsWith<Cancelled> {
            BarTracker.track(f.beats, f.raw, f.logits, cancellationCheck = { throw Cancelled() })
        }
    }
}
