import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.*
import org.metrolist.beatweave.rubberband.RubberBandEngine

private const val autoRate = 48000

private class AutoPcm(file: File) : StereoPcm, AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    private val count = input.length() / 8

    init {
        require(input.length() % 8 == 0L)
    }

    override val durationSeconds = count / autoRate.toDouble()

    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
        readFrames((startSeconds * autoRate).roundToLong(), frames, outputSampleRate)

    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
        require(outputSampleRate == autoRate && frames in 0..65536)
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

private class AutoWav(file: File, private val expected: Long, private val gain: Double = 1.0) :
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
        le(autoRate.toLong(), 4)
        le(autoRate * 6L, 4)
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
private class AutoExportPeak : PcmSink {
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
 * Run the automatic public API with no song-specific pulse, cue or range overrides. Args: model
 * A.mono22050.f32 A.stereo48000.f32 B.mono22050.f32 B.stereo48000.f32 output-directory
 */
fun main(args: Array<String>) {
    require(args.size == 6)
    val directory = File(args[5])
    require(
        !directory.exists() || directory.isDirectory && directory.listFiles().orEmpty().isEmpty()
    )
    require(directory.isDirectory || directory.mkdirs())
    val cache = File(directory, "cache").apply { mkdirs() }
    val backend = OrtBeatThisBackend(File(args[0]))
    val songs =
        try {
            listOf(args[1], args[3]).mapIndexed { index, path ->
                val start = System.nanoTime()
                val song = LocalSongAnalyzer(backend).analyze(mono(File(path)))
                val stem = "track-${index + 1}"
                File(directory, "$stem-analysis.txt")
                    .writeText(
                        "bpm=${song.audio.bpm}\nduration=${song.audio.durationSeconds}\nseconds=${(System.nanoTime()-start)/1e9}\n" +
                            "globalQuality=${song.pulse.quality}\nselection=${song.pulseSelection}\nacceptedRegions=${song.pulseRegions}\nexcludedRegions=${song.excludedPulseRegions}\n" +
                            "barRegions=${song.barTracking?.regions}\nwarnings=${song.audio.warnings}\n"
                    )
                File(directory, "$stem-beats.csv").printWriter().use { out ->
                    out.println("beat,seconds,strength")
                    song.pulse.beats.forEachIndexed { i, beat ->
                        out.println("$i,${beat.seconds},${beat.strength}")
                    }
                }
                File(directory, "$stem-bars.csv").printWriter().use { out ->
                    out.println("bar,beat,seconds,usable")
                    val bars = song.barTracking!!
                    bars.boundaries.forEachIndexed { i, b ->
                        out.println(
                            "$i,${b.beatIndex},${b.seconds},${i<bars.barCount && bars.isBarUsable(i)}"
                        )
                    }
                }
                println(
                    "Track ${index+1}: ${song.audio.bpm} BPM; ${song.pulseRegions.size} accepted pulse regions"
                )
                song
            }
        } finally {
            backend.close()
        }
    try {
        val overlap = LocalMixPlanner.overlap(songs[0], songs[1], outputSampleRate = autoRate)
        File(directory, "automatic-overlap.txt")
            .writeText("ACCEPT ${overlap.automaticSelection}\n${overlap.clockFit.report}\n")
        File(directory, "overlap-bar-pairs.csv").printWriter().use { out ->
            out.println(
                "outgoing_source_seconds,incoming_source_seconds,original_output_residual_seconds"
            )
            overlap.barMatches.forEach { b ->
                out.println(
                    "${b.outgoingSeconds},${b.originalIncomingSeconds},${b.originalBoundaryOutputResidualSeconds}"
                )
            }
        }
    } catch (failure: AutoMixPlanningException) {
        File(directory, "automatic-overlap.txt").writeText("DECLINE ${failure.report}\n")
    }
    // Beat-only overlap audits the entire jointly available observed span. A
    // longer unpaired outro is retained but is not falsely claimed as matched.
    try {
        val overlap = LocalMixPlanner.beatOverlap(songs[0], songs[1], outputSampleRate = autoRate)
        File(directory, "automatic-beat-overlap.txt")
            .writeText("ACCEPT ${overlap.beatCoverage}\n${overlap.clockFit.report}\n")
        writeClockAudit(directory, "beat-overlap", overlap.clockFit)
    } catch (failure: BeatOverlapPlanningException) {
        File(directory, "automatic-beat-overlap.txt")
            .writeText("DECLINE ${failure.code}\n${failure.coverage}\n${failure.clockReport}\n")
    }
    val choicesReport = File(directory, "automatic-choices.txt")
    choicesReport.printWriter().use { out ->
        for (bars in TransitionPlanner.supportedBarCounts) {
            try {
                val plan =
                    AutoMixPlanner.transition(songs[0], songs[1], bars, outputSampleRate = autoRate)
                out.println("bars=$bars ACCEPT ${plan.automaticSelection}")
            } catch (failure: AutoMixPlanningException) {
                out.println("bars=$bars DECLINE ${failure.report}")
            }
        }
    }
    val selectionReport = File(directory, "automatic-selection.txt")
    val transition =
        try {
            AutoMixPlanner.bestTransition(songs[0], songs[1], outputSampleRate = autoRate)
        } catch (failure: AutoMixPlanningException) {
            selectionReport.writeText("DECLINE ${failure.report}\n")
            throw IllegalStateException(
                "No safe automatic transition; inspect ${selectionReport.path} and ${choicesReport.path}",
                failure,
            )
        }
    val selectedBars = checkNotNull(transition.automaticSelection).barCount
    val plan = transition.mixPlan
    selectionReport.writeText(transition.automaticSelection.toString())
    writeClockAudit(directory, "transition", transition.clockFit)
    File(directory, "selected-downbeat-pairs.csv").printWriter().use { out ->
        out.println(
            "outgoing_source_seconds,incoming_source_seconds,incoming_adjusted_source_seconds,original_output_residual_seconds"
        )
        transition.barMatches.forEach { b ->
            out.println(
                "${b.outgoingSeconds},${b.originalIncomingSeconds},${b.preparedIncomingSeconds},${b.originalBoundaryOutputResidualSeconds}"
            )
        }
    }
    AutoPcm(File(args[2])).use { first ->
        AutoPcm(File(args[4])).use { second ->
            val prepared = transition.prepare(first, second, RubberBandEngine(cache))
            try {
                val origin = prepared.startFrame(MixMode.TRANSITION)
                File(directory, "time-map.csv").printWriter().use { out ->
                    out.println("source_seconds,global_output_seconds,file_output_seconds")
                    var time = 0.0
                    while (time <= second.durationSeconds) {
                        val output = plan.secondOutputTime(time)
                        out.println("$time,$output,${output-origin/autoRate.toDouble()}")
                        time += 0.005
                    }
                }
                File(directory, "incoming-stem.f32").outputStream().buffered().use { stream ->
                    var frame = origin
                    while (frame < prepared.endFrame()) {
                        val count = min(4096L, prepared.endFrame() - frame).toInt()
                        val pcm = prepared.readIncoming(frame, count)
                        val buffer = ByteBuffer.allocate(count * 8).order(ByteOrder.LITTLE_ENDIAN)
                        pcm.forEach(buffer::putFloat)
                        stream.write(buffer.array())
                        frame += count
                    }
                }
                val from = floor(max(0.0, plan.startSeconds - 4.0) * autoRate).toLong()
                val to =
                    min(prepared.endFrame(), ceil((plan.fadeEndSeconds + 4.0) * autoRate).toLong())
                val meter = AutoExportPeak()
                prepared.renderFrames(meter, from, to)
                val sink =
                    AutoWav(File(directory, "automatic-transition.wav"), to - from, meter.gain)
                sink.use { prepared.renderFrames(it, from, to) }
                check(sink.clipped == 0L)
                File(directory, "render.json")
                    .writeText(
                        """
              {"automatic":true,"manual_bpm_or_cue_overrides":false,"sample_rate":$autoRate,
               "timeline_origin_frame":$origin,"bars":$selectedBars,"fade_start_seconds":${plan.startSeconds},
               "fade_end_seconds":${plan.fadeEndSeconds},"excerpt_start_frame":$from,"excerpt_end_frame":$to,
               "excerpt_duration_seconds":${(to-from)/autoRate.toDouble()},"pitch_scale":1.0,
               "unscaled_peak":${meter.peak},"export_gain":${meter.gain},"clipped_samples":${sink.clipped}}
            """
                            .trimIndent()
                    )
            } finally {
                prepared.close()
            }
        }
    }
    check(cache.listFiles().orEmpty().isEmpty())
    cache.delete()
    println(
        "Automatic $selectedBars-bar transition rendered with ${transition.barMatches.size} matched boundaries"
    )
}
