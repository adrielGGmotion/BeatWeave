import java.io.File
import java.nio.*
import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.*

fun main(args: Array<String>) {
    fun floats(path: String): FloatArray {
        val bytes = File(path).readBytes()
        return FloatArray(bytes.size / 4).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
    }
    if (args.size == 4) {
        val pcm = floats(args[0])
        val duration = pcm.size / 22050.0
        val lines = File(args[1]).readLines()
        val separator = if (lines[0].contains('\t')) '\t' else ','
        val headings = lines[0].split(separator)
        val timeColumn = headings.indexOf("seconds")
        val strengthColumn =
            if ("strength" in headings) headings.indexOf("strength")
            else headings.indexOf("model_score")
        val dbColumn = headings.indexOf("downbeat")
        val rows = lines.drop(1).map { it.split(separator) }
        val raw = rows.map { Beat(it[timeColumn].toDouble(), it[strengthColumn].toFloat()) }
        val db =
            if (dbColumn >= 0)
                rows.filter { it[dbColumn] == "true" }.map { it[timeColumn].toDouble() }
            else emptyList()
        val model =
            LearnedBeatAnalysis(
                duration,
                raw,
                db,
                "cached-fixture",
                BeatThisLogits(floats(args[2]), FloatArray(floats(args[2]).size)),
            )
        val initial = MusicAnalyzer().analyze(pcm, 22050)
        val result =
            AutomaticPulseSelector.select(
                model,
                initial,
                { bpm -> MusicAnalyzer().analyze(pcm, 22050, preferredBpm = bpm) },
            )
        val report = buildString {
            appendLine(
                "original=${initial.bpm} selected=${result.diagnostics.selectedBpm} global=${result.pulse.quality.safeForAutomaticMix} beats=${result.pulse.beats.size} raw=${raw.size} additionalPasses=${result.diagnostics.additionalSpectralPasses}"
            )
            appendLine(result.diagnostics.reason)
            result.diagnostics.candidates.forEach { appendLine(it) }
            appendLine("REGIONS")
            result.regions.accepted.forEach { appendLine(it) }
            appendLine("EXCLUDED")
            result.regions.excluded.forEach { appendLine(it) }
            appendLine("GLOBAL_ERRORS")
            result.pulse.quality.issues
                .filter { it.severity == BeatIssueSeverity.ERROR }
                .forEach { appendLine(it) }
        }
        File(args[3]).writeText(report)
        print(report)
        return
    }
    fun beats(bpm: Double, n: Int) = List(n) { Beat(.2 + it * 60 / bpm, .95f) }
    fun model(beats: List<Beat>, duration: Double): LearnedBeatAnalysis {
        val logits = FloatArray((duration * 50).toInt() + 1) { -8f }
        for (b in beats) logits[(b.seconds * 50).roundToInt()] = 5f
        return LearnedBeatAnalysis(
            duration,
            beats,
            beats.filterIndexed { i, _ -> i % 4 == 0 }.map { it.seconds },
            "fixture",
            BeatThisLogits(logits, logits.copyOf()),
        )
    }
    fun reference(
        beats: List<Beat>,
        duration: Double,
        candidates: List<TempoCandidate>,
        evidence: List<Beat> = beats,
    ): Analysis {
        val onset = FloatArray((duration / .01).toInt() + 1)
        for (b in evidence) onset[(b.seconds / .01).roundToInt()] = 1f
        val bpm = 60 / (beats[1].seconds - beats[0].seconds)
        return Analysis(
            duration,
            bpm,
            .8,
            beats,
            -12.0,
            -1.0,
            500.0,
            "unknown",
            0.0,
            emptyList(),
            onset,
            .01,
            emptyList(),
            candidates,
            .8,
            true,
        )
    }
    val slow = beats(80.0, 96)
    val fast = beats(160.0, 191)
    val duration = slow.last().seconds + 1
    val candidates = listOf(TempoCandidate(160.0, .8), TempoCandidate(80.0, .75))
    val initial = reference(fast, duration, candidates, slow)
    val slowReference = reference(slow, duration, candidates)
    var passes = 0
    val resolved =
        AutomaticPulseSelector.select(
            model(slow, duration),
            initial,
            { bpm ->
                passes++
                check(abs(bpm - 80) < .01)
                slowReference
            },
        )
    check(resolved.diagnostics.selectedBpm == 80.0 && passes == 1)
    check(resolved.pulse.beats == slow && resolved.pulse.quality.safeForAutomaticMix)
    check(
        resolved.diagnostics.candidates.any { it.bpm == 160.0 && it.rejectionReasons.isNotEmpty() }
    )
    val fastResolved =
        AutomaticPulseSelector.select(
            model(fast, duration),
            reference(slow, duration, candidates, fast),
            { reference(fast, duration, candidates) },
        )
    check(fastResolved.diagnostics.selectedBpm == 160.0 && fastResolved.pulse.beats == fast)
    val split = slow.take(48) + List(48) { Beat(slow[47].seconds + (it + 1) * 60 / 160.0, .95f) }
    val splitResult =
        AutomaticPulseSelector.select(
            model(split, duration),
            initial,
            { error("No dominant pulse should be imposed") },
        )
    check(splitResult.diagnostics.ambiguous && splitResult.regions.accepted.isEmpty())
    val unsupported =
        AutomaticPulseSelector.select(
            model(slow, duration),
            slowReference.copy(tempoCandidates = listOf(TempoCandidate(80.0, .01))),
            { error("Unsupported tempo must not trigger another analysis") },
        )
    check(!unsupported.pulse.quality.safeForAutomaticMix && unsupported.regions.accepted.isEmpty())
    val contradictory =
        AutomaticPulseSelector.select(
            model(slow, duration),
            reference(beats(55.0, 65), duration, listOf(TempoCandidate(55.0, .9)), slow),
            { error("Contradiction must not impose a tempo") },
        )
    check(contradictory.regions.accepted.isEmpty())
    // A strong unsupported internal event creates rejected context, not a silent repair.
    val disturbed =
        slow.toMutableList().also {
            it[48] = it[48].copy(seconds = it[48].seconds + .30, strength = .99f)
        }
    val badPulse = PulseNormalizer.normalize(disturbed, slowReference)
    check(!badPulse.quality.safeForAutomaticMix)
    val regions = PulseRegions.analyze(badPulse, slowReference, true)
    check(regions.accepted.size == 2 && regions.accepted.none { it.contains(47, 51) })
    check(badPulse.quality.issues.any { it.severity == BeatIssueSeverity.ERROR })
    val tail =
        slow.toMutableList().also {
            it[92] = it[92].copy(seconds = it[92].seconds + .30, strength = .99f)
        }
    val tailPulse = PulseNormalizer.normalize(tail, slowReference)
    val tailRegions = PulseRegions.analyze(tailPulse, slowReference, true)
    check(tailRegions.accepted.size == 1 && tailRegions.accepted[0].endBeatExclusive < 90)
    check(!tailPulse.quality.safeForAutomaticMix)
    val forgedGlobal =
        badPulse.copy(
            quality =
                badPulse.quality.copy(
                    issues =
                        badPulse.quality.issues +
                            BeatGridIssue(
                                "NO_SUPPORTED_AUTOMATIC_PULSE",
                                BeatIssueSeverity.ERROR,
                                0.0,
                                duration,
                                "missing evidence",
                            )
                )
        )
    check(PulseRegions.analyze(forgedGlobal, slowReference, true).accepted.isEmpty())
    class Cancelled : RuntimeException()
    var calls = 0
    try {
        AutomaticPulseSelector.select(
            model(slow, duration),
            initial,
            { slowReference },
            {
                calls++
                if (calls == 2) throw Cancelled()
            },
        )
        error("Cancellation ignored")
    } catch (_: Cancelled) {}
    try {
        PulseRegions.analyze(badPulse, slowReference, true) { throw Cancelled() }
        error("Region cancellation ignored")
    } catch (_: Cancelled) {}
    val song =
        LocalSongAnalysis(
            slowReference,
            model(slow, duration),
            badPulse,
            slowReference,
            pulseRegions = regions.accepted,
        )
    song.requirePulseRange(
        regions.accepted.first().startBeat,
        regions.accepted.first().endBeatExclusive,
    )
    try {
        song.requirePulseRange(0, slow.size)
        error("Unsafe range accepted")
    } catch (_: IllegalArgumentException) {}
    check(song.audio.durationSeconds == duration && song.model.beats == slow)
    val inspected =
        LocalSongAnalysis(
            slowReference,
            model(slow, duration),
            badPulse,
            slowReference,
            barTracking =
                BarTracker.track(
                    slow,
                    slow.filterIndexed { i, _ -> i % 4 == 0 }.map { it.seconds },
                    model(slow, duration).logits.downbeat,
                ),
            pulseRegions = regions.accepted,
        )
    check(inspected.bars().barCount > 0) {
        "Proposed bars should remain inspectable when an unrelated region rejects"
    }
    println(
        "Automatic pulse regression passed: independent candidate evidence, half/double levels, contradictions, interior and trailing exclusions, unchanged global rejection, original timestamps, bounded reanalysis, cancellation"
    )
}
