import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.metrolist.beatweave.learned.*

/**
 * Raw input: mono22050 f32 little endian. Output logits/feature binary and TSV are test evidence.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "model.onnx mono22050.f32 outputPrefix" }
    val bytes = File(args[1]).readBytes()
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val pcm = FloatArray(input.remaining()).also { input.get(it) }
    val spectrogram = BeatThisFrontend().transform(pcm)
    fun floats(path: String, values: FloatArray) {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        File(path).writeBytes(buffer.array())
    }
    floats(args[2] + ".mel.f32", spectrogram.values)
    OrtBeatThisBackend(File(args[0]).readBytes()).use { backend ->
        val started = System.nanoTime()
        val logits = BeatThisAnalyzer(backend).infer(spectrogram)
        val (beats, downbeats) = BeatThisAnalyzer.postprocess(logits)
        floats(args[2] + ".beat-logits.f32", logits.beat)
        floats(args[2] + ".downbeat-logits.f32", logits.downbeat)
        File(args[2] + ".beats.tsv").printWriter().use { out ->
            out.println("seconds\tstrength\tdownbeat")
            for (beat in beats) out.println(
                "${beat.seconds}\t${beat.strength}\t${beat.seconds in downbeats}"
            )
        }
        val periods =
            beats.windowed(9).map { (it.last().seconds - it.first().seconds) / 8 }.sorted()
        println(
            "frames=${spectrogram.frames} beats=${beats.size} downbeats=${downbeats.size} bpm=${if(periods.isEmpty()) 0.0 else 60.0 / periods[periods.size/2]} inferenceSeconds=${(System.nanoTime()-started)/1e9}"
        )
    }
}
