import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.metrolist.beatweave.*

fun main(args: Array<String>) {
    fun pcm(path: String): FloatArray {
        val bytes = File(path).readBytes()
        return FloatArray(bytes.size / 4).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
    }
    if (args.size in 4..5) {
        val fallback = MusicAnalyzer().analyze(pcm(args[0]), args.getOrNull(4)?.toInt() ?: 11025)
        val rows = File(args[1]).readLines().drop(1).map { it.split('\t') }
        val raw = rows.map { Beat(it[0].toDouble(), it[1].toFloat()) }
        val db = rows.filter { it[2] == "true" }.map { it[0].toDouble() }
        val scores = pcm(args[2]).map { (1 / (1 + exp(-it.toDouble()))).toFloat() }.toFloatArray()
        val result = PulseNormalizer.normalize(raw, fallback, db, scores)
        File(args[3] + ".tsv").printWriter().use { out ->
            out.println("seconds\tstrength\tdownbeat")
            for (b in result.beats) out.println(
                "${b.seconds}\t${b.strength}\t${b.seconds in result.downbeatSeconds}"
            )
        }
        File(args[3] + ".repairs.tsv").printWriter().use { out ->
            out.println("kind\tseconds\tscore\tdetail")
            for (r in result.repairs) out.println(
                "${r.kind}\t${r.seconds}\t${r.evidenceScore}\t${r.detail}"
            )
        }
        val message =
            "raw=${raw.size} canonical=${result.beats.size} agreement=${result.canonicalAgreement} safe=${result.quality.safeForAutomaticMix} repairs=${result.repairs.groupingBy { it.kind }.eachCount()}\n" +
                result.quality.issues.joinToString("\n")
        File(args[3] + ".quality.txt").writeText(message)
        println(message)
        return
    }
    fun reference(times: List<Double>, support: Boolean = true): Analysis {
        val hop = .01
        val env = FloatArray(((times.last() + 1) / hop).toInt())
        if (support) for (t in times) env[(t / hop).roundToInt()] = 1f
        return Analysis(
            times.last() + 1,
            155.0,
            .8,
            times.map { Beat(it, 1f) },
            -12.0,
            -1.0,
            500.0,
            "unknown",
            0.0,
            emptyList(),
            env,
            hop,
            emptyList(),
        )
    }
    val times = List(160) { .2 + it * 60 / 155.0 + .015 * sin(it / 12.0) }
    val fallback = reference(times)
    val good = times.map { Beat(it, .9f) }
    val unchanged = PulseNormalizer.normalize(good, fallback)
    check(
        unchanged.quality.safeForAutomaticMix &&
            unchanged.repairs.isEmpty() &&
            unchanged.beats == good
    )
    val dirty =
        good.filterIndexed { i, _ -> i != 22 && !(i in 70..110 && i % 2 == 1) }.toMutableList()
    dirty.add(31, Beat(dirty[31].seconds - .08, .55f))
    dirty.sortBy { it.seconds }
    val repaired = PulseNormalizer.normalize(dirty, fallback)
    check(repaired.quality.safeForAutomaticMix) { repaired.quality.toString() }
    check(repaired.beats.size == times.size)
    check(repaired.beats.zip(times).maxOf { (b, t) -> abs(b.seconds - t) } < .001)
    check(repaired.rawBeats == dirty)
    val unsupported = PulseNormalizer.normalize(dirty, reference(times, false))
    check(!unsupported.quality.safeForAutomaticMix)
    val wrongLevel = PulseNormalizer.normalize(good.filterIndexed { i, _ -> i % 2 == 0 }, fallback)
    check(!wrongLevel.quality.safeForAutomaticMix)
    val rawAudit = BeatGridQuality.audit(dirty, good, fallback.durationSeconds)
    check(!rawAudit.safeForAutomaticMix)
    val misplaced = good.toMutableList().also { it[50] = Beat(it[50].seconds + 0.12, 0.40f) }
    val relocated = PulseNormalizer.normalize(misplaced, fallback)
    check(
        relocated.quality.safeForAutomaticMix &&
            relocated.repairs.any { it.kind == PulseRepairKind.RELOCATED_WEAK_OBSERVATION }
    )
    check(abs(relocated.beats[50].seconds - times[50]) < 0.001)
    val strongConflict = misplaced.toMutableList().also { it[50] = it[50].copy(strength = 0.99f) }
    check(!PulseNormalizer.normalize(strongConflict, fallback).quality.safeForAutomaticMix)
    check(!PulseNormalizer.normalize(emptyList(), fallback).quality.safeForAutomaticMix)
    // Same audio onset evidence, two different zero-strength implied-grid ties.
    val gap = 70
    val observations = good.filterIndexed { index, _ -> index != gap }
    val onClock = reference(times)
    val shiftedClock =
        onClock.copy(
            onsetTimeOffsetSeconds = 0.03,
            onsetEnvelope =
                FloatArray(onClock.onsetEnvelope.size) { index ->
                    onClock.onsetEnvelope.getOrElse(index + 3) { 0f }
                },
        )
    fun tieReference(delta: Double) =
        shiftedClock.copy(
            beats = good.toMutableList().also { it[gap] = Beat(times[gap] + delta, 0f) }
        )
    val tieA = PulseNormalizer.normalize(observations, tieReference(-0.08))
    val tieB = PulseNormalizer.normalize(observations, tieReference(0.01))
    check(tieA.quality.safeForAutomaticMix && tieB.quality.safeForAutomaticMix)
    check(tieA.beats == tieB.beats) { "Implied-grid tie changed identical onset-supported repair" }
    check(abs(tieA.beats[gap].seconds - times[gap]) < 0.006) { "Onset clock offset was ignored" }
    val insufficient = BeatGridQuality.audit(good.take(5))
    check(!insufficient.safeForAutomaticMix)
    println(
        "Pulse normalization regression passed: variable tempo, duplicate, missed pulse, half-level switch, unsupported repairs, global metrical disagreement, raw preservation"
    )
}
