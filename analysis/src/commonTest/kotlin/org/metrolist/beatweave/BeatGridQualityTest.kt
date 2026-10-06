package org.metrolist.beatweave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatGridQualityTest {
    @Test
    fun unmeasuredReferenceGapCannotInterpolateQuietPulse() {
        val completeModel = List(17) { Beat(0.26 + it * 0.5, 1.0f) }
        val modelBeats =
            completeModel.filterIndexed { index, _ -> index !in setOf(3, 5, 6, 7) }
        val onsetEnvelope =
            FloatArray(900).also {
                it[175] = 1.0f
                it[325] = 1.0f
                it[375] = 1.0f
            }
        val referenceBeats =
            List(18) { Beat(0.25 + it * 0.5, 1.0f) }
                .filterIndexed { index, _ -> index != 5 }
        val reference =
            Analysis(
                durationSeconds = 9.0,
                bpm = 120.0,
                tempoConfidence = 1.0,
                beats = referenceBeats,
                rmsDb = -12.0,
                peakDb = -1.0,
                spectralCentroidHz = 500.0,
                keyEstimate = "unknown",
                keyConfidence = 0.0,
                energyBlocks = emptyList(),
                onsetEnvelope = onsetEnvelope,
                onsetHopSeconds = 0.01,
                warnings = emptyList(),
            )

        val result = PulseNormalizer.normalize(modelBeats, reference)

        assertEquals(5.0 / 6.0, result.canonicalAgreement, 1e-12)
        assertEquals(14.0 / 15.0, assertNotNull(result.quality.referenceCoverage), 1e-12)
        assertTrue(result.beats.none { kotlin.math.abs(it.seconds - 2.76) < 1e-12 })
        assertTrue(result.repairs.none { it.kind == PulseRepairKind.CADENCE_INTERPOLATION })
        assertFalse(result.quality.safeForAutomaticMix)
        assertTrue(result.quality.issues.any { it.code == "UNSUPPORTED_INSERTION" })
    }

    @Test
    fun unmeasuredReferenceEdgeCannotInsertModelPulse() {
        val modelBeats =
            buildList {
                add(Beat(0.0, 1.0f))
                repeat(14) { index -> add(Beat(1.0 + index * 0.5, 1.0f)) }
            }
        val onsetEnvelope = FloatArray(850).also { it[50] = 1.0f }
        val reference =
            Analysis(
                durationSeconds = 8.5,
                bpm = 120.0,
                tempoConfidence = 1.0,
                beats = List(15) { Beat(0.75 + it * 0.5, 1.0f) },
                rmsDb = -12.0,
                peakDb = -1.0,
                spectralCentroidHz = 500.0,
                keyEstimate = "unknown",
                keyConfidence = 0.0,
                energyBlocks = emptyList(),
                onsetEnvelope = onsetEnvelope,
                onsetHopSeconds = 0.01,
                warnings = emptyList(),
            )

        val result = PulseNormalizer.normalize(modelBeats, reference)

        assertEquals(1.0, result.canonicalAgreement, 1e-12)
        assertEquals(13.0 / 14.0, assertNotNull(result.quality.referenceCoverage), 1e-12)
        assertEquals(modelBeats, result.beats)
        assertTrue(result.repairs.isEmpty())
        assertFalse(result.quality.safeForAutomaticMix)
        assertTrue(result.quality.issues.any { it.code == "ABRUPT_PULSE_CHANGE" })
    }

    @Test
    fun unmeasuredReferenceEdgeCannotRelocateModelPulse() {
        val modelBeats =
            buildList {
                add(Beat(0.25, 1.0f))
                add(Beat(0.60, 0.30f))
                repeat(14) { index -> add(Beat(1.25 + index * 0.5, 1.0f)) }
            }
        val onsetEnvelope = FloatArray(850).also { it[75] = 1.0f }
        val reference =
            Analysis(
                durationSeconds = 8.5,
                bpm = 120.0,
                tempoConfidence = 1.0,
                beats = List(15) { Beat(0.75 + it * 0.5, 1.0f) },
                rmsDb = -12.0,
                peakDb = -1.0,
                spectralCentroidHz = 500.0,
                keyEstimate = "unknown",
                keyConfidence = 0.0,
                energyBlocks = emptyList(),
                onsetEnvelope = onsetEnvelope,
                onsetHopSeconds = 0.01,
                warnings = emptyList(),
            )

        val result = PulseNormalizer.normalize(modelBeats, reference)

        assertEquals(13.0 / 14.0, result.canonicalAgreement, 1e-12)
        assertEquals(14.0 / 15.0, assertNotNull(result.quality.referenceCoverage), 1e-12)
        assertEquals(modelBeats, result.beats)
        assertTrue(result.repairs.isEmpty())
        assertFalse(result.quality.safeForAutomaticMix)
        assertTrue(result.quality.issues.any { it.code == "ABRUPT_PULSE_CHANGE" })
    }

    @Test
    fun unmeasuredReferenceEdgeCannotRemoveModelPulse() {
        val modelBeats =
            buildList {
                add(Beat(0.0, 1.0f))
                add(Beat(0.25, 1.0f))
                repeat(14) { index -> add(Beat(0.75 + index * 0.5, 1.0f)) }
            }
        val reference =
            Analysis(
                durationSeconds = 8.5,
                bpm = 120.0,
                tempoConfidence = 1.0,
                beats = List(15) { Beat(0.75 + it * 0.5, 1.0f) },
                rmsDb = -12.0,
                peakDb = -1.0,
                spectralCentroidHz = 500.0,
                keyEstimate = "unknown",
                keyConfidence = 0.0,
                energyBlocks = emptyList(),
                onsetEnvelope = FloatArray(0),
                onsetHopSeconds = 0.01,
                warnings = emptyList(),
            )

        val result = PulseNormalizer.normalize(modelBeats, reference)

        assertEquals(1.0, result.canonicalAgreement, 1e-12)
        assertEquals(13.0 / 15.0, assertNotNull(result.quality.referenceCoverage), 1e-12)
        assertEquals(modelBeats, result.beats)
        assertTrue(result.repairs.isEmpty())
        assertFalse(result.quality.safeForAutomaticMix)
        assertTrue(result.quality.issues.any { it.code == "ABRUPT_PULSE_CHANGE" })
    }

    @Test
    fun unmeasuredReferenceGapCannotRaiseCanonicalAgreement() {
        val intervals =
            listOf(
                0.615,
                0.55,
                0.45,
                0.385,
                0.385,
                0.45,
                0.55,
                0.5,
                0.5,
                0.55,
                0.615,
                0.615,
                0.55,
                0.45,
                0.385,
            )
        val modelBeats = buildList {
            var seconds = 0.25
            add(Beat(seconds, 1.0f))
            for (interval in intervals) {
                seconds += interval
                add(Beat(seconds, 1.0f))
            }
        }
        val referenceBeats =
            List(16) { Beat(0.25 + it * 0.5, 1.0f) }
                .filterIndexed { index, _ -> index != 8 }
        val reference =
            Analysis(
                durationSeconds = 8.0,
                bpm = 120.0,
                tempoConfidence = 1.0,
                beats = referenceBeats,
                rmsDb = -12.0,
                peakDb = -1.0,
                spectralCentroidHz = 500.0,
                keyEstimate = "unknown",
                keyConfidence = 0.0,
                energyBlocks = emptyList(),
                onsetEnvelope = FloatArray(0),
                onsetHopSeconds = 0.01,
                warnings = emptyList(),
            )

        val result = PulseNormalizer.normalize(modelBeats, reference)

        assertEquals(7.0 / 13.0, result.canonicalAgreement, 1e-12)
        assertEquals(13.0 / 15.0, assertNotNull(result.quality.referenceCoverage), 1e-12)
        assertFalse(result.quality.safeForAutomaticMix)
        assertTrue(result.quality.issues.any { it.code == "UNCONFIRMED_CANONICAL_PULSE" })
    }

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
        assertEquals(13.0 / 15.0, assertNotNull(isolatedGap.referenceCoverage), 1e-12)

        assertNull(
            BeatGridQuality.audit(beats, durationSeconds = 8.0).referenceCoverage,
            "No independent reference means coverage was not measured",
        )

        val report =
            BeatGridQuality.audit(beats, referenceBeats = reference, durationSeconds = 8.0)

        assertFalse(report.safeForAutomaticMix)
        assertEquals(4.0 / 15.0, assertNotNull(report.referenceCoverage), 1e-12)
        assertTrue(report.issues.any { it.code == "INSUFFICIENT_REFERENCE_COVERAGE" })
    }
}
