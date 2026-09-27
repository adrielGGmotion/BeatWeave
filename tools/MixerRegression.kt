import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.rubberband.RubberBandEngine

/**
 * Independent analytic audio fixtures. No detector or renderer-derived positions are used as the
 * audio oracle: transient centres are known at signal construction time. Compile alongside the
 * common library, then run MixerRegressionKt OUTPUT_DIRECTORY. Validate the resulting
 * floating-point audio with tools/validate_audio.py fixtures DIR.
 */
private const val fixtureRate = 44100
private lateinit var fixtureMixer: BeatMixer

private class AnalyticPcm(
    override val durationSeconds: Double = 14.0,
    private val sample: (Double, Int) -> Double,
) : StereoPcm {
    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray =
        FloatArray(frames * 2) { index ->
            val seconds = startSeconds + (index / 2).toDouble() / outputSampleRate
            if (seconds < 0 || seconds >= durationSeconds) 0f
            else sample(seconds, index % 2).toFloat()
        }
}

private fun fixtureRender(
    directory: File,
    name: String,
    source: StereoPcm,
    plan: MixPlan,
    start: Double = 0.0,
    end: Double = 10.0,
) {
    val silence = AnalyticPcm { _, _ -> 0.0 }
    File(directory, "$name.f32").outputStream().buffered().use { stream ->
        fixtureMixer.renderRange(
            silence,
            source,
            plan,
            object : PcmSink {
                override fun write(interleavedStereo: FloatArray, frames: Int) {
                    val bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until frames * 2) bytes.putFloat(interleavedStereo[i])
                    stream.write(bytes.array())
                }
            },
            start,
            end,
            MixMode.OVERLAP,
        )
    }
}

fun main(args: Array<String>) {
    require(args.size in 1..2 && (args.size == 1 || args[1] == "--only-long"))
    val directory = File(args[0])
    directory.mkdirs()
    fixtureMixer = BeatMixer(RubberBandEngine(File(directory, "cache")))
    val aTimes = DoubleArray(48) { 0.35 + it * 60.0 / 156 }
    val manifest = mutableListOf<String>()
    if (args.getOrNull(1) != "--only-long") {
        for ((label, bpm) in
            listOf(
                "near" to 155.0,
                "fast" to 120.0,
                "slow" to 195.0,
                "drift" to 155.0,
                "release" to 120.0,
            )) {
            val bTimes =
                DoubleArray(48) {
                    0.41 + it * 60.0 / bpm + if (label == "drift") 0.060 * sin(it / 5.0) else 0.0
                }
            val plan =
                MixPlan(
                    BeatGrid(aTimes),
                    BeatGrid(bTimes),
                    0,
                    crossfadeBeats = if (label == "release") 8 else 16,
                    outputSampleRate = fixtureRate,
                    releaseAfterFade = label == "release",
                )
            // Band-limited Gaussian-modulated pulse: analytically centred on source beats.
            val pulse = AnalyticPcm { t, _ ->
                val approximate = ((t - bTimes[0]) * bpm / 60).roundToInt()
                val candidates = max(0, approximate - 1)..min(bTimes.lastIndex, approximate + 1)
                val index = candidates.minByOrNull { abs(t - bTimes[it]) }
                val d = if (index == null) Double.POSITIVE_INFINITY else t - bTimes[index]
                if (abs(d) > 0.012) 0.0
                else 0.7 * exp(-0.5 * (d / 0.0012).pow(2)) * cos(2 * PI * 1400 * d)
            }
            fixtureRender(directory, "pulse-$label", pulse, plan)
            val expected =
                (if (label == "release")
                        bTimes.map { source ->
                            // Independent analytic inverse of the specified two-second cubic
                            // release;
                            // deliberately does not call MixPlan.secondOutputTime to create the
                            // oracle.
                            val fadeOutput = 0.35 + 8 * 60.0 / 156
                            val fadeSource = 0.41 + 8 * 60.0 / 120
                            if (source <= fadeSource) 0.35 + (source - 0.41) / 1.3
                            else {
                                var lo = 0.0
                                var hi = 20.0
                                repeat(64) {
                                    val dt = (lo + hi) / 2
                                    val extra =
                                        if (dt >= 2.0) 0.2
                                        else 0.3 * (dt - dt * dt / 2 + dt * dt * dt / 12)
                                    if (fadeSource + dt + extra < source) lo = dt else hi = dt
                                }
                                fadeOutput + (lo + hi) / 2
                            }
                        }
                    else aTimes.toList())
                    .filter { it > 0.6 && it < 9.6 }
            manifest +=
                """{"name":"pulse-$label","type":"pulse","expected_seconds":[${expected.joinToString(",")}],"source_width_ms":2.176,"rate":$fixtureRate,"max_error_ms":25.0,"p95_error_ms":15.0}"""
            val tone = AnalyticPcm { t, channel ->
                val left = 0.35 * sin(2 * PI * 440 * t) + 0.12 * sin(2 * PI * 880 * t)
                if (channel == 0) left else -0.65 * left
            }
            fixtureRender(directory, "tone-$label", tone, plan)
            manifest +=
                """{"name":"tone-$label","type":"tone","expected_hz":440.0,"right_gain":-0.65,"rate":$fixtureRate,"max_pitch_cents":5.0}"""
            if (label == "fast") {
                fixtureRender(directory, "tone-seek", tone, plan, start = 3.0, end = 7.0)
                manifest +=
                    """{"name":"tone-seek","type":"seek","reference":"tone-fast","start_seconds":3.0,"rate":$fixtureRate,"exact_crop":true}"""
            }
        }
        // Perfect identity speed should be transparent, including stereo relationship.
        val identity =
            MixPlan(BeatGrid(aTimes), BeatGrid(aTimes), 0, outputSampleRate = fixtureRate)
        val stereo = AnalyticPcm { t, channel ->
            if (channel == 0) 0.30 * sin(2 * PI * 440 * t) + 0.17 * sin(2 * PI * 733 * t)
            else 0.29 * cos(2 * PI * 440 * t) + 0.19 * cos(2 * PI * 977 * t)
        }
        fixtureRender(directory, "identity", stereo, identity)
        manifest += """{"name":"identity","type":"identity","rate":$fixtureRate,"gain":0.46}"""
    }
    // Long variable-tempo material checks accumulated offset, not only one overlap.
    val longA = DoubleArray(430) { 0.35 + it * 60.0 / 156 }
    val longB =
        DoubleArray(430) { 0.41 + it * 60.0 / 155 + 0.060 * sin(it / 5.0) + 0.030 * sin(it / 17.0) }
    val longPlan = MixPlan(BeatGrid(longA), BeatGrid(longB), 0, outputSampleRate = fixtureRate)
    val longPulse =
        AnalyticPcm(150.0) { t, _ ->
            val approximate = ((t - longB[0]) * 155 / 60).roundToInt()
            val candidates = max(0, approximate - 1)..min(longB.lastIndex, approximate + 1)
            val index = candidates.minByOrNull { abs(t - longB[it]) }
            val d = if (index == null) Double.POSITIVE_INFINITY else t - longB[index]
            if (abs(d) > 0.012) 0.0
            else 0.7 * exp(-0.5 * (d / 0.0012).pow(2)) * cos(2 * PI * 1400 * d)
        }
    fixtureRender(directory, "pulse-long-drift", longPulse, longPlan, end = 120.0)
    val longExpected = longA.filter { it > 0.6 && it < 119.6 }
    manifest +=
        """{"name":"pulse-long-drift","type":"pulse","expected_seconds":[${longExpected.joinToString(",")}],"source_width_ms":2.176,"rate":$fixtureRate,"max_error_ms":25.0,"p95_error_ms":15.0}"""
    File(directory, "manifest.json").writeText("[\n" + manifest.joinToString(",\n") + "\n]\n")
    println("Rendered independent PCM fixtures to ${directory.absolutePath}")
}
