package org.metrolist.beatweave

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
