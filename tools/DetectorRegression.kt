import kotlin.math.*
import org.metrolist.beatweave.*

/** Independent sample-clock references exercise inference rather than supplied-grid mapping. */
fun main() {
    fun measure(
        name: String,
        bpm: Double,
        beats: List<Double>,
        missing: Set<Int> = emptySet(),
        guide: Double? = null,
        rate: Int = 11025,
        offbeats: Boolean = false,
    ) {
        val samples = FloatArray(((beats.last() + 0.5) * rate).toInt())
        for ((b, t) in beats.withIndex()) if (b !in missing) {
            val start = (t * rate).roundToInt()
            for (j in 0 until 220) if (start + j < samples.size)
                samples[start + j] += (exp(-j / 30.0) * sin(j * 0.43)).toFloat()
        }
        if (offbeats)
            for (b in 0 until beats.lastIndex) {
                val start = ((beats[b] + beats[b + 1]) * 0.5 * rate).roundToInt()
                for (j in 0 until 110) if (start + j < samples.size)
                    samples[start + j] += (0.20 * exp(-j / 15.0) * sin(j * 1.3)).toFloat()
            }
        val a = MusicAnalyzer().analyze(samples, rate, guide)
        val errors = a.beats.map { b -> beats.minOf { abs(it - b.seconds) } }.sorted()
        val coverage =
            beats.count { t -> a.beats.any { abs(t - it.seconds) < 0.030 } }.toDouble() / beats.size
        val p95 = errors[(errors.size * 0.95).toInt().coerceAtMost(errors.lastIndex)]
        println(
            "$name bpm=${a.bpm} expected=$bpm beats=${a.beats.size} coverage=$coverage p95ms=${p95*1000} confidence=${a.beatConfidence} candidates=${a.tempoCandidates.take(3)}"
        )
        check(abs(a.bpm - bpm) < 1.5) { "$name BPM ${a.bpm}" }
        check(p95 < 0.025) { "$name p95 timing error $p95" }
        check(coverage > 0.92) { "$name coverage $coverage" }
    }
    for (bpm in listOf(40.0, 60.0, 78.0, 104.0, 120.0, 155.0, 156.0, 180.0, 210.0, 240.0)) measure(
        "constant-$bpm",
        bpm,
        List(80) { 0.2 + it * 60.0 / bpm },
    )
    measure(
        "missing",
        156.0,
        List(80) { 0.2 + it * 60.0 / 156 },
        setOf(14, 15, 16, 28, 44, 45, 62),
        156.0,
    )
    measure(
        "tempo-drift",
        156.0,
        List(100) { 0.2 + it * 60.0 / 156 + 0.045 * sin(it / 10.0) },
        guide = 156.0,
    )
    measure("subdivisions", 156.0, List(100) { 0.2 + it * 60.0 / 156 }, offbeats = true)
    measure("sample-rate-44100", 155.0, List(80) { 0.2 + it * 60.0 / 155 }, rate = 44100)
    measure("sample-rate-48000", 155.0, List(80) { 0.2 + it * 60.0 / 155 }, rate = 48000)
    val ramp = mutableListOf(0.2)
    for (i in 1 until 120) ramp += ramp.last() + 60.0 / (145.0 + 22.0 * i / 119)
    measure("gradual-145-to-167", 156.0, ramp, guide = 156.0)
    val rate = 11025
    val silence = MusicAnalyzer().analyze(FloatArray(rate * 5), rate)
    check(silence.beats.isEmpty() && silence.bpm == 0.0 && silence.beatConfidence == 0.0)
    try {
        MusicAnalyzer().analyze(FloatArray(rate * 5) { Float.NaN }, rate)
        error("NaN input was accepted")
    } catch (_: IllegalArgumentException) {}
    for (frequency in listOf(110.0, 440.0, 1500.0)) {
        val tone =
            MusicAnalyzer()
                .analyze(
                    FloatArray(rate * 12) { (0.4 * sin(2 * PI * frequency * it / rate)).toFloat() },
                    rate,
                )
        check(tone.beatConfidence < 0.10 && tone.beats.isEmpty()) {
            "Stationary tone became confident beats"
        }
    }
    val observations =
        listOf(Beat(0.153, 0.8f), Beat(0.732, 0.9f), Beat(1.304, 0.7f), Beat(1.90, 0.9f))
    val imported =
        MusicAnalyzer()
            .analyzeWithBeats(FloatArray(rate * 5), rate, observations, listOf(0.153), "test-model")
    check(
        imported.beats == observations &&
            imported.downbeatSeconds == listOf(0.153) &&
            imported.detectorId == "test-model"
    )
    println("Detector regression passed")
}
