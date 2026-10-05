package org.metrolist.beatweave.learned

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audit actual source audio clocks; never import Spotify timing or relax gates. */
fun main(args: Array<String>) {
    val data = File(args[0])
    val output = File(args[2]).also { it.mkdirs() }
    OrtBeatThisBackend(File(args[1]), threads = 2).use { backend ->
        for (index in 0..5) {
            val file = File(data, "track-$index.mono.f32")
            val bytes = file.readBytes()
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            val analysis = LocalSongAnalyzer(backend).analyze(samples)
            File(output, "track-$index-canonical.csv").printWriter().use { writer ->
                writer.println("time,strength")
                analysis.pulse.beats.forEach { writer.println("${it.seconds},${it.strength}") }
            }
            File(output, "track-$index-bars.csv").printWriter().use { writer ->
                writer.println("time,beat_index,model_score,posterior")
                analysis.barTracking?.boundaries?.forEach {
                    writer.println("${it.seconds},${it.beatIndex},${it.modelDownbeatScore},${it.downbeatPosterior}")
                }
            }
            File(output, "track-$index-logits.csv").printWriter().use { writer ->
                writer.println("time,beat_logit,downbeat_logit")
                analysis.model.logits.beat.indices.forEach { i ->
                    writer.println("${i * 0.02},${analysis.model.logits.beat[i]},${analysis.model.logits.downbeat[i]}")
                }
            }
            File(output, "track-$index-audit.txt").writeText(
                "detector=${analysis.audio.detectorId}\n" +
                "bpm=${analysis.audio.bpm}\n" +
                "pulse=${analysis.pulse.quality}\n" +
                "bars=${analysis.barTracking?.regions}\n" +
                "warnings=${analysis.audio.warnings.joinToString("\n")}\n"
            )
            println("Exported source-clock audit for track $index")
        }
    }
}
