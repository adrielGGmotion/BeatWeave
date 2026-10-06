package org.metrolist.beatweave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatGridQualityTest {
    @Test
    fun invalidRecordingDurationCannotCertifyAutomaticGrid() {
        val beats = List(16) { Beat(0.25 + it * 0.5, 1.0f) }

        for (duration in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            val report = BeatGridQuality.audit(beats, durationSeconds = duration)

            assertFalse(report.safeForAutomaticMix)
            assertTrue(report.issues.any { it.code == "INVALID_DURATION" })
        }
    }

    @Test
    fun malformedReferenceClockCannotCertifyAutomaticGrid() {
        val beats = List(16) { Beat(0.25 + it * 0.5, 1.0f) }
        val malformedReferences =
            listOf(
                beats.toMutableList().apply { this[4] = Beat(this[4].seconds, Float.NaN) },
                beats.toMutableList().apply { this[8] = Beat(this[7].seconds, 1.0f) },
                beats.toMutableList().apply { this[0] = Beat(-0.25, 1.0f) },
                beats.toMutableList().apply { this[lastIndex] = Beat(8.0, 1.0f) },
            )

        for (reference in malformedReferences) {
            val report =
                BeatGridQuality.audit(beats, referenceBeats = reference, durationSeconds = 8.0)

            assertFalse(report.safeForAutomaticMix)
            assertTrue(report.issues.any { it.code == "INVALID_REFERENCE_CLOCK" })
        }
    }

    @Test
    fun insufficientReferenceClockCannotDisableCadenceAudit() {
        val beats = List(16) { Beat(0.25 + it * 0.5, 1.0f) }

        for (reference in listOf(emptyList(), beats.take(1), beats.take(2))) {
            val report =
                BeatGridQuality.audit(beats, referenceBeats = reference, durationSeconds = 8.0)

            assertFalse(report.safeForAutomaticMix)
            assertTrue(report.issues.any { it.code == "INVALID_REFERENCE_CLOCK" })
        }
    }

    @Test
    fun partialReferenceClockCannotCorroborateUnmeasuredGrid() {
        val beats = List(16) { Beat(0.25 + it * 0.5, 1.0f) }

        assertTrue(BeatGridQuality.audit(beats, durationSeconds = 8.0).safeForAutomaticMix)
        assertTrue(
            BeatGridQuality.audit(beats, referenceBeats = beats, durationSeconds = 8.0)
                .safeForAutomaticMix
        )

        for (reference in listOf(beats.take(3), beats.takeLast(3))) {
            val report =
                BeatGridQuality.audit(beats, referenceBeats = reference, durationSeconds = 8.0)

            assertFalse(report.safeForAutomaticMix)
            assertTrue(report.issues.any { it.code == "INSUFFICIENT_REFERENCE_COVERAGE" })
        }
    }

    @Test
    fun internalReferenceGapCannotCountAsMeasuredCoverage() {
        val beats = List(16) { Beat(0.25 + it * 0.5, 1.0f) }
        val reference = beats.take(3) + beats.takeLast(3)

        val isolatedGap =
            BeatGridQuality.audit(
                beats,
                referenceBeats = beats.filterIndexed { index, _ -> index != 8 },
                durationSeconds = 8.0,
            )
        assertTrue(isolatedGap.safeForAutomaticMix)
        assertEquals(13.0 / 15.0, isolatedGap.referenceCoverage, 1e-12)

        assertNull(
            BeatGridQuality.audit(beats, durationSeconds = 8.0).referenceCoverage,
            "No independent reference means coverage was not measured",
        )

        val report =
            BeatGridQuality.audit(beats, referenceBeats = reference, durationSeconds = 8.0)

        assertFalse(report.safeForAutomaticMix)
        assertEquals(4.0 / 15.0, report.referenceCoverage, 1e-12)
        assertTrue(report.issues.any { it.code == "INSUFFICIENT_REFERENCE_COVERAGE" })
    }
}
