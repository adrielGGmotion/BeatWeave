import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.rubberband.RubberBandEngine

private const val profileSampleRate = 48000

private class AnalyticProfilePcm(
    override val durationSeconds: Double,
    private val signal: (Double, Int) -> Double,
) : StereoPcm {
    var largestRead = 0
        private set

    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
        readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
        require(outputSampleRate == profileSampleRate)
        largestRead = max(largestRead, frames)
        return FloatArray(frames * 2) { k ->
            val t = (startFrame + k / 2).toDouble() / outputSampleRate
            if (t < 0 || t >= durationSeconds) 0f else signal(t, k % 2).toFloat()
        }
    }
}

/**
 * Reproduces the entire supplied-recording clock with independent analytic PCM. Pulses always use
 * ORIGINAL incoming events. The expected output always uses ORIGINAL outgoing events, including
 * when a fitted incoming grid is supplied. This verifies the render clock, not correctness of the
 * music annotations.
 */
fun main(args: Array<String>) {
    require(args.size in 3..4) {
        "output-directory original-outgoing.csv original-incoming.csv [--regularize|fitted-incoming.csv-or-tsv]"
    }
    val directory = File(args[0]).apply { mkdirs() }
    fun read(path: String): DoubleArray {
        val lines = File(path).readLines().filter { it.isNotBlank() }
        val csv = lines.first().contains(',')
        val columns = lines.first().split(if (csv) ',' else '\t')
        val second = columns.indexOf("seconds").let { if (it < 0) 0 else it }
        val data = if (columns[second].toDoubleOrNull() == null) lines.drop(1) else lines
        return data.map { it.split(if (csv) ',' else '\t')[second].toDouble() }.toDoubleArray()
    }
    val a = read(args[1])
    val originalB = read(args[2])
    val regularize = args.size == 4 && args[3] == "--regularize"
    val planB = if (args.size == 4 && !regularize) read(args[3]) else originalB
    require(originalB.size == planB.size) { "The fitted grid must preserve event identity" }
    fun firstDownbeat(path: String): Int {
        val rows = File(path).readLines().drop(1)
        return rows.indexOfFirst { it.substringAfterLast(',') == "true" }.also { require(it >= 0) }
    }
    val first = firstDownbeat(args[1])
    val second = firstDownbeat(args[2])
    val duration = 209.51442176870748
    val originalPlan =
        MixPlan(BeatGrid(a), BeatGrid(planB), first, second, outputSampleRate = profileSampleRate)
    val fit = if (regularize) BeatClockRegularizer.regularize(originalPlan, duration) else null
    val plan = fit?.requireAccepted() ?: originalPlan
    if (fit != null) {
        File(directory, "fit-report.txt").writeText(fit.report.toString())
        File(directory, "fitted-incoming.csv")
            .writeText("seconds\n" + plan.second.times.joinToString("\n") + "\n")
        println("Fit accepted: ${fit.report}")
    }
    val schedule = WarpSchedule.from(plan, duration)
    val endFrame = schedule.outputOriginFrame + schedule.outputFrames
    val endSeconds = endFrame.toDouble() / profileSampleRate
    val cache = File(directory, "cache")
    val mixer = BeatMixer(RubberBandEngine(cache))
    val silence = AnalyticProfilePcm(289.53333333333336) { _, _ -> 0.0 }
    val readSizes = mutableListOf<Int>()
    var exactSeek = true
    fun render(name: String, pcm: AnalyticProfilePcm) {
        val prepared = mixer.prepare(silence, pcm, plan)
        try {
            val target = File(directory, "$name.f32")
            target.outputStream().buffered().use { stream ->
                var frame = 0L
                while (frame < endFrame) {
                    val count = min(4096L, endFrame - frame).toInt()
                    val block = prepared.readIncoming(frame, count)
                    val bytes = ByteBuffer.allocate(count * 8).order(ByteOrder.LITTLE_ENDIAN)
                    block.forEach(bytes::putFloat)
                    stream.write(bytes.array())
                    frame += count
                }
            }
            RandomAccessFile(target, "r").use { stream ->
                val offsets =
                    listOf(0L, 12345L, endFrame / 2, endFrame - 2048, profileSampleRate * 101L)
                for (frame in offsets.reversed()) {
                    val count = min(2048L, endFrame - frame).toInt()
                    val bytes = ByteArray(count * 8)
                    stream.seek(frame * 8)
                    stream.readFully(bytes)
                    val expected = FloatArray(count * 2)
                    ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(expected)
                    exactSeek =
                        exactSeek && expected.contentEquals(prepared.readIncoming(frame, count))
                }
            }
        } finally {
            prepared.close()
            prepared.close()
        }
        readSizes += pcm.largestRead
        check(cache.listFiles().orEmpty().isEmpty()) { "Prepared cache was not cleaned up" }
        println(
            "Rendered $name; source largest block ${pcm.largestRead} frames; exact seeks=$exactSeek"
        )
    }
    render(
        "pulse-full-song",
        AnalyticProfilePcm(duration) { t, _ ->
            val found = originalB.binarySearch(t)
            val insertion = if (found >= 0) found else -found - 1
            val k =
                (max(0, insertion - 1)..min(originalB.lastIndex, insertion + 1)).minByOrNull {
                    abs(t - originalB[it])
                }
            val delta = if (k == null) Double.POSITIVE_INFINITY else t - originalB[k]
            if (abs(delta) > .012) 0.0
            else .7 * exp(-.5 * (delta / .0012).pow(2)) * cos(2 * PI * 1400 * delta)
        },
    )
    render(
        "tone-full-song",
        AnalyticProfilePcm(duration) { t, ch ->
            val v = .35 * sin(2 * PI * 440 * t) + .12 * sin(2 * PI * 880 * t)
            if (ch == 0) v else -.65 * v
        },
    )
    val expected =
        (second..originalB.lastIndex)
            .map { a[first + it - second] }
            .filter { it > .6 && it < endSeconds - .25 }
    File(directory, "manifest.json")
        .writeText(
            """[
      {"name":"pulse-full-song","type":"pulse","expected_seconds":[${expected.joinToString(",")}],"source_width_ms":2.176,"rate":$profileSampleRate,"max_error_ms":25.0,"p95_error_ms":15.0},
      {"name":"tone-full-song","type":"tone","expected_hz":440.0,"harmonics_hz":[440.0,880.0],"right_gain":-0.65,"rate":$profileSampleRate,"max_pitch_cents":5.0,"rolling_pitch":true}
    ]"""
                .trimIndent()
        )
    File(directory, "execution.json")
        .writeText(
            """{
      "original_outgoing_grid":"${File(args[1]).name}","original_incoming_grid":"${File(args[2]).name}",
      "first_beat":$first,"second_beat":$second,"original_event_oracle":true,
      "fitted_incoming_grid":${args.size == 4},"library_regularizer":$regularize,"duration_seconds":$endSeconds,
      "frames":$endFrame,"cache_empty_after_close":${cache.listFiles().orEmpty().isEmpty()},
      "seeks_bit_exact":$exactSeek,"largest_source_read_frames":${readSizes.maxOrNull()},
      "warp_quality":"${WarpQuality.assess(plan, 0.0, endSeconds)}"
    }"""
                .trimIndent()
        )
    check(exactSeek) { "Seeking did not reproduce exported samples" }
    println("Full-song original clock oracle: ${expected.size} transients over $endSeconds seconds")
}
