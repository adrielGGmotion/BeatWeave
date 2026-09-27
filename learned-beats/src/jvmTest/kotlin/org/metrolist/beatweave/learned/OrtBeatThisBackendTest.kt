package org.metrolist.beatweave.learned

import java.io.File
import kotlin.test.*

class OrtBeatThisBackendTest {
    private fun model(variant: BeatThisModelVariant): File {
        val candidates =
            listOf(
                File("models", variant.assetFileName),
                File("learned-beats/models", variant.assetFileName),
                File("BeatWeave/learned-beats/models", variant.assetFileName),
            )
        return candidates.firstOrNull { it.isFile }
            ?: error("Bundled model missing: ${variant.assetFileName}")
    }

    @Test
    fun everyModelChecksumIsEnforced() {
        for (variant in BeatThisModelVariant.entries) {
            val bytes = model(variant).readBytes()
            bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
            assertFailsWith<IllegalArgumentException>(variant.name) { OrtBeatThisBackend(bytes) }
        }
    }

    private fun checkModel(variant: BeatThisModelVariant) {
        val backend = OrtBeatThisBackend(model(variant))
        try {
            assertEquals(variant, backend.modelVariant)
            assertEquals(variant.modelId, backend.modelId)
            for (frames in listOf(1, 51, 151, 1500)) {
                val input = FloatArray(frames * 128) { (it % 97) / 97f }
                val result = backend.infer(input, frames)
                assertEquals(frames, result.beat.size)
                assertEquals(frames, result.downbeat.size)
                assertTrue(result.beat.all { it.isFinite() })
                assertTrue(result.downbeat.all { it.isFinite() })
            }
            assertFailsWith<IllegalArgumentException> { backend.infer(FloatArray(128), 0) }
            assertFailsWith<IllegalArgumentException> { backend.infer(FloatArray(128), 2) }
            assertFailsWith<IllegalArgumentException> {
                backend.infer(FloatArray(128) { Float.NaN }, 1)
            }
        } finally {
            backend.close()
        }
        backend.close()
        assertFailsWith<IllegalStateException> { backend.infer(FloatArray(151 * 128), 151) }
    }

    @Test fun smallModelRunsLocallyAndCloseIsIdempotent() = checkModel(BeatThisModelVariant.SMALL0)

    @Test fun finalModelRunsLocallyAndCloseIsIdempotent() = checkModel(BeatThisModelVariant.FINAL0)

    @Test
    fun modelIdentitySurvivesAnalysisAndDoesNotAliasAcrossVariants() {
        val identities =
            BeatThisModelVariant.entries.map { variant ->
                OrtBeatThisBackend(model(variant)).use { backend ->
                    // The identity travels through the public result, which hosts use in
                    // analysis-cache keys. No on-disk application cache is assumed here.
                    val result = BeatThisAnalyzer(backend).analyze(FloatArray(22050 * 3))
                    assertEquals(variant.modelId, result.modelId)
                    assertTrue(result.modelId.contains(variant.sha256))
                    assertTrue(result.modelId.contains(variant.checkpointSha256))
                    result.modelId
                }
            }
        assertEquals(BeatThisModelVariant.entries.size, identities.distinct().size)
    }
}
