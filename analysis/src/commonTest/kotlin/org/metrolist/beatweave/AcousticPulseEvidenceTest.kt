package org.metrolist.beatweave

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcousticPulseEvidenceTest {
    private fun regularClock(count: Int = 65): List<Beat> = List(count) { Beat(it.toDouble(), 1f) }

    private fun features(
        intervals: Int = 65,
        quietIntervals: Set<Int> = emptySet(),
        irregular: Boolean = false,
    ): AcousticAttackFeatures {
        val frames = intervals * 32
        val bands = Array(4) { FloatArray(frames) }
        for (frame in 0 until frames) {
            val interval = frame / 32
            val peak = if (irregular) (interval * interval * 11 + interval * 17) % 32 else 5
            if (interval !in quietIntervals && frame % 32 == peak) bands[0][frame] = 0.08f
        }
        return AcousticAttackFeatures(bands, 1.0 / 32, intervals.toDouble())
    }

    @Test
    fun recurringEvidenceHasCompleteContextAndImmutableClock() {
        val beats = regularClock()
        val report = AcousticPulseEvidence.assess(features(), beats)
        assertTrue(report.windows.all { it.supported })
        assertEquals(4..60, report.evaluatedIntervalRange)
        assertTrue(report.supportsRange(8, 13))
        assertFalse(report.supportsRange(0, 5))
        assertFalse(report.supportsRange(60, 65))
        assertEquals(
            setOf(AcousticPulseIssue.INSUFFICIENT_CONTEXT),
            report.assessRange(0, 5).issues,
        )
        val clock = beats.map { it.seconds }.toDoubleArray()
        report.requireClock(clock)
        clock[10] += 0.001
        assertFailsWith<IllegalArgumentException> { report.requireClock(clock) }
        assertFailsWith<IllegalArgumentException> { report.requireClock(clock.copyOf(12)) }
        report.intervalContrasts.fill(0.0)
        report.activeIntervals.fill(false)
        assertTrue(report.supportsRange(8, 13))
    }

    @Test
    fun silentBarCannotBorrowRecurringNeighborAttacks() {
        val report =
            AcousticPulseEvidence.assess(
                features(quietIntervals = (24..27).toSet()),
                regularClock(),
            )
        // Four active context intervals still make the original recurrence test significant.
        assertTrue(report.windows.filter { it.centerInterval in 24..27 }.all { it.supported })
        val assessment = report.assessRange(24, 29)
        assertFalse(assessment.supported)
        assertEquals(0.0, assessment.activeIntervalFraction)
        assertTrue(AcousticPulseIssue.INSUFFICIENT_LOCAL_ATTACKS in assessment.issues)
        assertTrue(report.supportsRange(8, 13))
    }

    @Test
    fun isolatedRestsCanBeSupportedButAdjacentUnobservedIntervalsCannot() {
        val isolated =
            AcousticPulseEvidence.assess(features(quietIntervals = setOf(25)), regularClock())
        assertTrue(isolated.supportsRange(24, 29))
        val pair =
            AcousticPulseEvidence.assess(features(quietIntervals = setOf(25, 26)), regularClock())
        assertFalse(pair.supportsRange(24, 29))
        assertTrue(AcousticPulseIssue.INSUFFICIENT_LOCAL_ATTACKS in pair.assessRange(24, 29).issues)
    }

    @Test
    fun missingBoundaryContextNeverCertifiesTail() {
        val report =
            AcousticPulseEvidence.assess(
                features(quietIntervals = (60..64).toSet()),
                regularClock(),
            )
        val range = report.assessRange(60, 65)
        assertFalse(range.supported)
        assertTrue(AcousticPulseIssue.INSUFFICIENT_CONTEXT in range.issues)
        assertTrue(AcousticPulseIssue.INSUFFICIENT_LOCAL_ATTACKS in range.issues)
    }

    @Test
    fun invalidClocksAndFeaturesFailBeforeNullComparison() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 70.0)) {
            val beats = regularClock().toMutableList()
            beats[20] = Beat(bad, 1f)
            assertFailsWith<IllegalArgumentException> {
                AcousticPulseEvidence.assess(features(), beats)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AcousticPulseEvidence.assess(features(), regularClock().reversed())
        }
        val duplicate = regularClock().toMutableList()
        duplicate[20] = duplicate[19]
        assertFailsWith<IllegalArgumentException> {
            AcousticPulseEvidence.assess(features(), duplicate)
        }
        val invalid = Array(4) { FloatArray(32) }
        invalid[1][10] = Float.NaN
        assertFailsWith<IllegalArgumentException> { AcousticAttackFeatures(invalid, 1.0 / 32, 1.0) }
        val report = AcousticPulseEvidence.assess(features(), regularClock())
        for ((a, b) in
            listOf(-1 to 4, 3 to 3, 4 to 3, 0 to 100, 1 to Int.MIN_VALUE, Int.MAX_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException> { report.assessRange(a, b) }
        }
        assertTrue(AcousticPulseEvidence.assess(features(), emptyList()).windows.isEmpty())
        assertEquals(
            null,
            AcousticPulseEvidence.assess(features(), emptyList()).evaluatedIntervalRange,
        )
    }

    @Test
    fun recurringAcousticsDoNotProveBeatPhaseOrMetricalLevel() {
        val attacks = features()
        val shifted = List(64) { Beat(it + 0.2, 1f) }
        val slower = List(33) { Beat(it * 2.0, 1f) }
        val faster = List(129) { Beat(it * 0.5, 1f) }
        assertTrue(AcousticPulseEvidence.assess(attacks, shifted).windows.all { it.supported })
        assertTrue(AcousticPulseEvidence.assess(attacks, slower).windows.all { it.supported })
        assertTrue(AcousticPulseEvidence.assess(attacks, faster).windows.all { it.supported })
    }

    @Test
    fun inconsistentProfilesLackRecurrence() {
        val report = AcousticPulseEvidence.assess(features(irregular = true), regularClock())
        assertTrue(report.windows.count { it.supported } < report.windows.size / 4)
        assertFalse(report.supportsRange(20, 25))
    }

    @Test
    fun cancellationIsPolledDuringAssessmentAndMeasurement() {
        var calls = 0
        assertFailsWith<InterruptedFixture> {
            AcousticPulseEvidence.assess(features(), regularClock()) {
                if (++calls == 8) throw InterruptedFixture()
            }
        }
        assertEquals(8, calls)
        calls = 0
        assertFailsWith<InterruptedFixture> {
            MusicAnalyzer().prepare(FloatArray(11025 * 4), 11025) {
                if (++calls == 3) throw InterruptedFixture()
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun measuredFeatureGainInvarianceAndStationaryToneRejection() {
        val sampleRate = 11025
        val beats = List(41) { Beat(0.25 + it * 0.4, 1f) }
        val pcm = FloatArray(sampleRate * 17)
        for (beat in beats) {
            val base = (beat.seconds * sampleRate).toInt()
            for (j in 0 until sampleRate / 10) {
                val t = j.toDouble() / sampleRate
                if (base + j < pcm.size)
                    pcm[base + j] += (sin(2 * PI * 73 * t) * exp(-t / 0.015)).toFloat()
            }
        }
        val prepared = MusicAnalyzer().prepare(pcm, sampleRate).acousticAttacks
        val quiet =
            MusicAnalyzer()
                .prepare(FloatArray(pcm.size) { pcm[it] * 1e-12f }, sampleRate)
                .acousticAttacks
        assertEquals(prepared.frameCount, quiet.frameCount)
        for (band in 0..3) {
            val a = prepared.band(band)
            val b = quiet.band(band)
            assertEquals(0f, a[0])
            assertTrue(a.indices.maxOf { abs(a[it] - b[it]) } < 1e-5f)
        }
        val normalReport = AcousticPulseEvidence.assess(prepared, beats)
        val quietReport = AcousticPulseEvidence.assess(quiet, beats)
        assertEquals(
            normalReport.windows.map { it.supported },
            quietReport.windows.map { it.supported },
        )
        assertTrue(normalReport.windows.all { it.supported })
        val tone = FloatArray(pcm.size) { sin(2 * PI * 220 * it / sampleRate).toFloat() }
        val toneReport =
            AcousticPulseEvidence.assess(
                MusicAnalyzer().prepare(tone, sampleRate).acousticAttacks,
                beats,
            )
        assertTrue(toneReport.windows.none { it.supported })
        val copy = prepared.band(0)
        copy.fill(100f)
        assertContentEquals(
            normalReport.activeIntervals,
            AcousticPulseEvidence.assess(prepared, beats).activeIntervals,
        )
    }

    private class InterruptedFixture : RuntimeException()
}
