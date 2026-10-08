import java.io.File
import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.*
import org.metrolist.beatweave.rubberband.RubberBandEngine

private const val RATE = 48000

private fun annotatedSong(incoming: Boolean): LocalSongAnalysis {
    // Ground-truth bar boundaries stay fixed; deliberately jitter only interior
    // pulses to exercise bounded refinement without moving a declared downbeat.
    val beats =
        List(81) { i ->
            Beat(
                (if (incoming) 0.3 else 0.2) +
                    i * 0.4 +
                    (if (incoming && i in 1..79 && i % 4 != 0) 0.024 * sin(i * 1.9) else 0.0),
                1f,
            )
        }
    val downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }.map { it.seconds }
    val audio =
        Analysis(
            32.5,
            150.0,
            1.0,
            beats,
            -18.0,
            -6.0,
            800.0,
            "unknown",
            0.0,
            emptyList(),
            FloatArray(0),
            0.02,
            emptyList(),
            downbeatSeconds = downbeats,
        )
    val pulse =
        PulseNormalizationResult(
            beats,
            beats,
            downbeats,
            emptyList(),
            BeatGridQualityReport(beats.size, 150.0, emptyList()),
            1.0,
        )
    val beatLogits = FloatArray((audio.durationSeconds * 50).toInt()) { -8f }
    val downbeatLogits = FloatArray(beatLogits.size) { -8f }
    for (beat in beats) beatLogits[(beat.seconds * 50).roundToInt()] = 8f
    for (time in downbeats) downbeatLogits[(time * 50).roundToInt()] = 8f
    val tracked = BarTracker.track(beats, downbeats, downbeatLogits)
    tracked.requireUsable()
    return LocalSongAnalysis(
        audio,
        LearnedBeatAnalysis(
            32.5,
            beats,
            downbeats,
            "annotated-synthetic-consumer-fixture",
            BeatThisLogits(beatLogits, downbeatLogits),
        ),
        pulse,
        audio,
        barTracking = tracked,
    )
}

private fun unevenSubdivisionSong(mainSong: LocalSongAnalysis): LocalSongAnalysis {
    val beats = List(241) { i -> Beat(.2 + (i / 3) * .4 + doubleArrayOf(0.0, .08, .28)[i % 3], 1f) }
    val audio = mainSong.audio.copy(beats = beats)
    return mainSong.copy(
        audio = audio,
        pulse =
            mainSong.pulse.copy(
                rawBeats = beats,
                beats = beats,
                quality = BeatGridQualityReport(beats.size, 450.0, emptyList()),
            ),
        model = mainSong.model.copy(beats = beats),
        reference = audio,
        barTracking = null,
    )
}

private fun declaration(song: LocalSongAnalysis, stride: Int) =
    ObservedMainBeatGrid(
        "synthetic-consumer-clock",
        song.pulse.beats.map { it.seconds }.toDoubleArray(),
        IntArray(81) { it * stride },
        IntArray(21) { it * 4 },
        BooleanArray(20) { true },
        MainBeatDeclarationOrigin.CALLER_DECLARED,
        "synthetic-construction",
        "four-observed-main-beats",
    )

private class Tone(
    private val frequency: Double,
    override val durationSeconds: Double = 32.5,
) : StereoPcm {

    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
        readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray =
        FloatArray(frames * 2) { index ->
            val frame = startFrame + index / 2
            if (frame < 0 || frame >= (durationSeconds * outputSampleRate).roundToLong()) 0f
            else
                (0.2 *
                        sin(2 * PI * frequency * frame / outputSampleRate) *
                        if (index % 2 == 0) 1.0 else 0.5)
                    .toFloat()
        }
}

private class CountingSink : PcmSink {
    var framesWritten = 0L
    var peak = 0f

    override fun write(interleavedStereo: FloatArray, frames: Int) {
        check(interleavedStereo.size == frames * 2)
        check(interleavedStereo.all { it.isFinite() })
        framesWritten += frames
        peak = max(peak, interleavedStereo.maxOf { abs(it) })
    }
}

private fun pitchSmoke(engine: RubberBandEngine) {
    val source = Tone(440.0, durationSeconds = 2.0)
    val analyzer = PitchAnalyzer(PitchAnalysisOptions(hopSeconds = 0.1))
    val original = analyzer.analyze(source, analysisSampleRate = RATE)
    check(original.frames.isNotEmpty() && original.frames.all {
        it.frequencyHz?.let { frequency -> abs(frequency - 440.0) < 2.0 } == true
    })
    val shifted = engine.preparePitchShift(source, RATE, PitchShift(12.0, preserveFormants = false))
    try {
        check(shifted.durationSeconds == source.durationSeconds)
        val stereo = shifted.readFrames(RATE / 2L, RATE / 2, RATE)
        val pitch = analyzer.analyze(FloatArray(stereo.size / 2) { stereo[it * 2] }, RATE)
        check(pitch.frames.isNotEmpty() && pitch.frames.all {
            it.frequencyHz?.let { frequency -> abs(frequency - 880.0) < 2.0 } == true
        }) { "Published native pitch shift did not produce A5" }
    } finally {
        shifted.close()
    }
    println("PASS published pitch analysis and native +12-semitone shift: A4 to A5, unchanged duration")
}

fun main(args: Array<String>) {
    check(args.size == 2) { "model.onnx|--native-only cache-directory" }
    if (args[0] != "--native-only") OrtBeatThisBackend(File(args[0]), threads = 2).use { backend ->
        val logits = backend.infer(FloatArray(128 * 51), 51)
        check(logits.beat.size == 51 && logits.downbeat.size == 51)
        check(logits.beat.all { it.isFinite() } && logits.downbeat.all { it.isFinite() })
        val rate = 22050
        val mono =
            FloatArray(rate * 8) { index ->
                val t = index.toDouble() / rate
                val phase = t % 0.5
                (0.05 * sin(2 * PI * 220 * t) +
                        if (phase < 0.025) 0.5 * exp(-phase * 200) * sin(2 * PI * 1000 * phase)
                        else 0.0)
                    .toFloat()
            }
        val analysis = LocalSongAnalyzer(backend).analyze(mono)
        check(analysis.model.modelId == backend.modelId)
        check(backend.modelVariant.assetFileName == File(args[0]).name)
        check(abs(analysis.audio.durationSeconds - 8.0) < 1e-9)
        check(analysis.model.logits.beat.isNotEmpty())
        println(
            "PASS exported ONNX model and LocalSongAnalyzer pipeline: ${analysis.model.logits.beat.size} logit frames; automatic beat acceptance=${analysis.pulse.quality.safeForAutomaticMix}"
        )
        println(
            "SCOPE synthetic analyzer inference is an integration check, not an accuracy benchmark"
        )
    }
    val first = annotatedSong(false)
    val second = annotatedSong(true)
    val preparedPlan = LocalMixPlanner.overlap(first, second, outputSampleRate = RATE)
    check(preparedPlan.automaticSelection != null && preparedPlan.barMatches.size >= 3)
    check(preparedPlan.clockFit.report.accepted && preparedPlan.clockFit.report.sweeps > 0)
    check(!preparedPlan.clockFit.report.originalQuality.accepted)
    for (boundary in preparedPlan.barMatches) {
        check(boundary.preparedIncomingSeconds == boundary.originalIncomingSeconds)
        check(abs(boundary.originalBoundaryOutputResidualSeconds) <= 1.0 / RATE)
    }
    val transition =
        LocalMixPlanner.autoTransition(first, second, bars = 16, outputSampleRate = RATE)
    check(transition.mode == MixMode.TRANSITION && transition.automaticSelection?.barCount == 16)
    check(
        transition.outgoingBars == 16 &&
            transition.incomingBars == 16 &&
            transition.barMatches.size == 17
    )
    for (boundary in transition.barMatches) {
        check(boundary.preparedIncomingSeconds == boundary.originalIncomingSeconds)
        check(abs(boundary.originalBoundaryOutputResidualSeconds) <= 1.0 / RATE)
    }
    println(
        "PASS automatic cue selection: ${preparedPlan.automaticSelection!!.barCount}-bar overlap and exact 16-bar transition; all matched downbeats pinned"
    )
    val cache = File(args[1]).also { it.mkdirs() }
    check(cache.listFiles().orEmpty().isEmpty())
    pitchSmoke(RubberBandEngine(cache))
    check(cache.listFiles().orEmpty().isEmpty())
    val session = preparedPlan.prepare(Tone(220.0), Tone(440.0), RubberBandEngine(cache))
    try {
        check(session.startFrame(MixMode.OVERLAP) < 0)
        val firstRead = session.readIncoming(12345, 1024)
        val secondRead = session.readIncoming(12345, 1024)
        check(firstRead.contentEquals(secondRead))
        val sink = CountingSink()
        session.renderComplete(sink, MixMode.OVERLAP)
        check(sink.framesWritten == session.frameCount(MixMode.OVERLAP))
        check(sink.peak > 0.05 && sink.peak < 1f)
        println(
            "PASS LocalMixPlanner bounded fit: ${preparedPlan.clockFit.report.sweeps} sweeps; native prepare/seek/renderComplete frames=${sink.framesWritten}, origin=${session.startFrame(MixMode.OVERLAP)}, peak=${sink.peak}"
        )
    } finally {
        session.close()
        session.close()
    }
    val transitionSession =
        transition.prepare(
            Tone(220.0), Tone(440.0), RubberBandEngine(cache),
            incomingPitchShift = PitchShift(7.0, preserveFormants = false),
        )
    try {
        val sink = CountingSink()
        transitionSession.renderComplete(sink, transition.mode)
        check(sink.framesWritten == transitionSession.frameCount(transition.mode))
        check(sink.peak > 0.05 && sink.peak < 1f)
        println("PASS prepared automatic transition render with +7-semitone incoming pitch: ${sink.framesWritten} frames")
    } finally {
        transitionSession.close()
    }
    val subdivided = unevenSubdivisionSong(first)
    val declared =
        LocalMixPlanner.declaredAutoTransition(
            first,
            subdivided,
            declaration(first, 1),
            declaration(subdivided, 3),
            bars = 16,
            outputSampleRate = RATE,
        )
    val mainSelection = checkNotNull(declared.declaredMainSelection)
    check(mainSelection.automaticallySelectedCues && mainSelection.matchedMainBeats.size == 65)
    check(mainSelection.outgoingCanonicalPulsesPerBar.all { it == 4 })
    check(mainSelection.incomingCanonicalPulsesPerBar.all { it == 12 })
    check(declared.mixPlan.firstBeatsPerCycle == 1 && declared.mixPlan.secondBeatsPerCycle == 1)
    check(mainSelection.matchedMainBeats.all { abs(it.originalOutputResidualSeconds) < 1e-9 })
    val declaredSession = declared.prepare(Tone(220.0), Tone(440.0), RubberBandEngine(cache))
    try {
        check(declaredSession.schedule.isTranslationOnly)
        val sink = CountingSink()
        declaredSession.renderComplete(sink, declared.mode)
        check(sink.framesWritten == declaredSession.frameCount(declared.mode))
    } finally {
        declaredSession.close()
    }
    println(
        "PASS published declared main-beat transition: unequal canonical subdivisions, 1:1 observed-main clock, complete render"
    )
    check(cache.listFiles().orEmpty().isEmpty())
    println("PASS owned native cache cleanup and idempotent close")
    val extractedNativeLibraries =
        File(System.getProperty("java.io.tmpdir")).listFiles().orEmpty().filter {
            it.name.startsWith("beatweave-rubberband-") && it.name.endsWith(".so")
        }
    check(extractedNativeLibraries.isEmpty()) {
        "Bundled JVM native extraction remained until process exit: $extractedNativeLibraries"
    }
    println("PASS bundled JVM native extraction is unlinked after loading")
    println(
        "PASS published JVM integration: ${System.getProperty("beatweave.version", "unspecified")}, no repository source-module dependency"
    )
}
