import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.*
import org.metrolist.beatweave.rubberband.RubberBandEngine

private const val demoRate = 48000

private class DemoPcm(file: File) : StereoPcm, AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    private val count = input.length() / 8

    init {
        require(input.length() % 8 == 0L)
    }

    override val durationSeconds = count / demoRate.toDouble()

    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
        readFrames((startSeconds * demoRate).roundToLong(), frames, outputSampleRate)

    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
        require(outputSampleRate == demoRate && frames in 0..65536)
        val out = FloatArray(frames * 2)
        val begin = max(0L, startFrame)
        val end = min(count, startFrame + frames)
        if (end <= begin) return out
        val bytes = ByteArray(((end - begin) * 8).toInt())
        input.seek(begin * 8)
        input.readFully(bytes)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        floats.get(out, ((begin - startFrame) * 2).toInt(), ((end - begin) * 2).toInt())
        return out
    }

    override fun close() = input.close()
}

private class DemoWav(file: File, private val expected: Long, private val gain: Double = 1.0) :
    PcmSink, AutoCloseable {
    private val stream = FileOutputStream(file)
    private var written = 0L
    var peak = 0.0
        private set

    var clipped = 0L
        private set

    init {
        require(expected > 0 && expected * 6 + 36 <= 0xffffffffL)
        fun text(s: String) = stream.write(s.toByteArray(Charsets.US_ASCII))
        fun le(v: Long, bytes: Int) {
            repeat(bytes) { stream.write((v ushr (it * 8)).toInt() and 255) }
        }
        text("RIFF")
        le(expected * 6 + 36, 4)
        text("WAVEfmt ")
        le(16, 4)
        le(1, 2)
        le(2, 2)
        le(demoRate.toLong(), 4)
        le(demoRate * 6L, 4)
        le(6, 2)
        le(24, 2)
        text("data")
        le(expected * 6, 4)
    }

    override fun write(interleavedStereo: FloatArray, frames: Int) {
        require(interleavedStereo.size == frames * 2 && written + frames <= expected)
        val bytes = ByteArray(frames * 6)
        interleavedStereo.forEachIndexed { i, original ->
            val value = (original * gain).toFloat()
            require(value.isFinite())
            peak = max(peak, abs(value.toDouble()))
            if (abs(value) > 1f) clipped++
            val sample = (value.coerceIn(-1f, 1f) * 8388607).roundToInt()
            for (j in 0..2) bytes[3 * i + j] = (sample shr (8 * j)).toByte()
        }
        stream.write(bytes)
        written += frames
    }

    override fun close() {
        stream.close()
        check(written == expected) { "WAV frame count mismatch: $written != $expected" }
    }
}

/**
 * Float PCM may exceed full scale after stretching; measure before integer encoding. A single
 * constant gain preserves dynamics and does not alter the timing/pitch path.
 */
private class ExportPeak : PcmSink {
    var peak = 0.0
        private set

    val gain: Double
        get() = if (peak == 0.0) 1.0 else min(1.0, 0.891250938 / peak)

    override fun write(interleavedStereo: FloatArray, frames: Int) {
        require(interleavedStereo.size == frames * 2)
        for (sample in interleavedStereo) {
            require(sample.isFinite())
            peak = max(peak, abs(sample.toDouble()))
        }
    }
}

private fun mono(file: File): FloatArray {
    val bytes = file.readBytes()
    require(bytes.size % 4 == 0)
    return FloatArray(bytes.size / 4).also {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
    }
}

private fun writeClockAudit(directory: File, name: String, fit: BeatClockFitResult) {
    File(directory, "$name-clock-fit.txt").writeText(fit.report.toString())
    File(directory, "$name-clock-adjustments.csv").printWriter().use { out ->
        out.println(
            "beat,original_source_seconds,prepared_source_seconds,displacement_seconds,allowed_seconds,pinned,original_anchor_output_residual_seconds"
        )
        fit.adjustments.forEach { a ->
            out.println(
                "${a.incomingBeat},${a.originalSourceSeconds},${a.adjustedSourceSeconds},${a.displacementSeconds},${a.allowedDisplacementSeconds},${a.pinned},${a.originalAnchorOutputResidualSeconds ?: ""}"
            )
        }
    }
}

/**
 * Local example: decode/resample with ffmpeg first (see README), then invoke: LibraryDemoKt
 * model.onnx A.mono22050.f32 A.stereo48000.f32 B.mono22050.f32 B.stereo48000.f32 output-directory
 * No song-specific BPM, phase offset, smoothing radius, or meter is supplied.
 */
fun main(args: Array<String>) {
    require(args.size == 6) {
        "model.onnx A.mono22050.f32 A.stereo48000.f32 B.mono22050.f32 B.stereo48000.f32 output-directory"
    }
    val directory = File(args[5])
    require(
        !directory.exists() || directory.isDirectory && directory.listFiles().orEmpty().isEmpty()
    ) {
        "Use an empty output directory so a rejected render cannot leave an older mix looking current"
    }
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create output directory" }
    val cache = File(directory, "cache").apply { mkdirs() }
    val model = OrtBeatThisBackend(File(args[0]))
    val results =
        try {
            listOf(args[1], args[3]).mapIndexed { index, path ->
                val signal = mono(File(path))
                val started = System.nanoTime()
                val song = LocalSongAnalyzer(model).analyze(signal)
                val analysis = song.audio
                val learned = song.model
                val downbeats = analysis.downbeatSeconds.toSet()
                File(directory, "track-${index + 1}-beats.csv").printWriter().use { out ->
                    out.println("beat,seconds,model_score,downbeat")
                    analysis.beats.forEachIndexed { i, beat ->
                        out.println(
                            "$i,${beat.seconds},${beat.strength},${beat.seconds in downbeats}"
                        )
                    }
                }
                File(directory, "track-${index + 1}-raw-model-beats.csv").printWriter().use { out ->
                    out.println("beat,seconds,model_score,downbeat")
                    learned.beats.forEachIndexed { i, beat ->
                        out.println(
                            "$i,${beat.seconds},${beat.strength},${beat.seconds in learned.downbeatSeconds}"
                        )
                    }
                }
                File(directory, "track-${index + 1}-pulse-audit.txt").printWriter().use { out ->
                    out.println("Structural acceptance: ${song.pulse.quality.safeForAutomaticMix}")
                    out.println("Independent pulse agreement: ${song.pulse.canonicalAgreement}")
                    song.pulse.quality.issues.forEach(out::println)
                    song.pulse.repairs.forEach(out::println)
                }
                song.barTracking?.let { bars ->
                    File(directory, "track-${index + 1}-bar-audit.txt").printWriter().use { out ->
                        out.println("All bars accepted: ${bars.safeForAutomaticBars}")
                        bars.regions.forEach(out::println)
                        bars.edits.forEach(out::println)
                        for (bar in 0 until bars.barCount) out.println(
                            "bar=$bar usable=${bars.isBarUsable(bar)} minimum_state_posterior=${bars.barMinimumStatePosteriors[bar]}"
                        )
                    }
                }
                val elapsed = (System.nanoTime() - started) / 1e9
                File(directory, "track-${index + 1}-analysis.json")
                    .writeText(
                        """
                {"detector":"${analysis.detectorId}","bpm":${analysis.bpm},"beat_count":${analysis.beats.size},
                 "downbeat_count":${analysis.downbeatSeconds.size},"duration_seconds":${analysis.durationSeconds},
                 "analysis_seconds":$elapsed,"rms_db":${analysis.rmsDb},"peak_db":${analysis.peakDb},
                 "raw_model_beats":${learned.beats.size},"canonical_pulse_repairs":${song.pulse.repairs.size},
                 "structural_acceptance":${song.pulse.quality.safeForAutomaticMix},
                 "key_estimate":"${analysis.keyEstimate}","key_evidence":${analysis.keyConfidence},
                 "note":"Model predictions, not human-annotated ground truth; key is a heuristic estimate."}
            """
                            .trimIndent()
                    )
                println(
                    "Track ${index + 1}: ${analysis.bpm} BPM; ${analysis.beats.size} canonical beats; ${downbeats.size} downbeats; ${elapsed}s"
                )
                song
            }
        } finally {
            model.close()
        }
    DemoPcm(File(args[2])).use { first ->
        DemoPcm(File(args[4])).use { second ->
            val engine = RubberBandEngine(cache)
            // Complete overlap: both tracks remain audible together, no seven-minute concatenation.
            val overlap =
                LocalMixPlanner.overlap(results[0], results[1], outputSampleRate = demoRate)
            val overlapPlan = overlap.mixPlan
            val overlapQuality = overlap.clockFit.report.adjustedQuality
            File(directory, "overlap-warp-audit.txt").writeText(overlapQuality.toString())
            writeClockAudit(directory, "overlap", overlap.clockFit)
            val prepared = overlap.prepare(first, second, engine)
            try {
                val end = prepared.frameCount(MixMode.OVERLAP)
                val meter = ExportPeak()
                prepared.renderComplete(meter, MixMode.OVERLAP)
                val sink = DemoWav(File(directory, "overlap.wav"), end, meter.gain)
                sink.use { prepared.renderComplete(it, MixMode.OVERLAP) }
                check(sink.clipped == 0L) { "Overlap clipped ${sink.clipped} samples" }
                File(directory, "overlap-plan.json")
                    .writeText(
                        """
                {"sample_rate":$demoRate,"frames":$end,"duration_seconds":${end.toDouble() / demoRate},
                 "first_beat":${overlapPlan.firstBeat},"second_beat":${overlapPlan.secondBeat},
                 "timeline_origin_frame":${prepared.startFrame(MixMode.OVERLAP)},
                 "observed_matching_source_end":${overlapPlan.observedCoverage.sourceEndSeconds},
                 "clock_refined":${overlap.clockFit.report.sweeps > 0},
                 "maximum_incoming_anchor_adjustment_seconds":${overlap.clockFit.report.maximumSourceDisplacementSeconds},
                 "pitch_scale":1.0,"source_origin_frame":${prepared.schedule.outputOriginFrame},
                 "warp_anchors":${prepared.schedule.anchors.size},"unscaled_peak":${meter.peak},"export_gain":${meter.gain},"peak":${sink.peak},"clipped_samples":${sink.clipped}}
            """
                            .trimIndent()
                    )
            } finally {
                prepared.close()
            }
            println(
                "Rendered complete overlap with ${overlap.clockFit.report.sweeps} clock-fit sweeps"
            )
            // All cue/range selection belongs to the library; request exactly sixteen bars.
            val transition =
                LocalMixPlanner.autoTransition(
                    results[0],
                    results[1],
                    bars = 16,
                    outputSampleRate = demoRate,
                )
            File(directory, "automatic-overlap-selection.txt")
                .writeText(overlap.automaticSelection.toString())
            File(directory, "automatic-transition-selection.txt")
                .writeText(transition.automaticSelection.toString())
            writeClockAudit(directory, "transition", transition.clockFit)
            val join = transition.prepare(first, second, engine)
            try {
                val barDuration =
                    (transition.mixPlan.fadeEndSeconds - transition.mixPlan.startSeconds) /
                        transition.outgoingBars!!
                val from =
                    floor(max(0.0, transition.mixPlan.startSeconds - 2 * barDuration) * demoRate)
                        .toLong()
                val to =
                    min(
                        join.endFrame(),
                        ceil((transition.mixPlan.fadeEndSeconds + 2 * barDuration) * demoRate)
                            .toLong(),
                    )
                val meter = ExportPeak()
                join.renderFrames(meter, from, to)
                val sink = DemoWav(File(directory, "transition.wav"), to - from, meter.gain)
                sink.use { join.renderFrames(it, from, to) }
                check(sink.clipped == 0L)
                File(directory, "transition-plan.json")
                    .writeText(
                        """
                {"outgoing_start_bar":${transition.outgoingStartBar},"incoming_start_bar":${transition.incomingStartBar},
                 "outgoing_bars":${transition.outgoingBars},"incoming_bars":${transition.incomingBars},
                 "first_beat":${transition.mixPlan.firstBeat},"second_beat":${transition.mixPlan.secondBeat},
                 "crossfade_beats":${transition.mixPlan.crossfadeBeats},"release_after_fade":true,
                 "fade_start":${transition.mixPlan.startSeconds},"fade_end":${transition.mixPlan.fadeEndSeconds},
                 "excerpt_from_frame":$from,"excerpt_to_frame":$to,"sample_rate":$demoRate,
                 "peak":${sink.peak},"clipped_samples":${sink.clipped}}
            """
                            .trimIndent()
                    )
                File(directory, "transition-warp-audit.txt")
                    .writeText(transition.clockFit.report.adjustedQuality.toString())
                println(
                    "Rendered ${transition.outgoingBars}-bar transition from outgoing bar ${transition.outgoingStartBar} " +
                        "to incoming bar ${transition.incomingStartBar}"
                )
            } finally {
                join.close()
            }
        }
    }
    check(cache.listFiles().orEmpty().isEmpty()) { "Prepared audio leaked cache files" }
    cache.delete()
}
