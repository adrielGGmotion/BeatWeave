import kotlin.math.*
import org.metrolist.beatweave.*

/** Contract regressions independent of any platform or stretcher implementation. */
fun main() {
    var assertions = 0
    fun verify(ok: Boolean, message: String) {
        check(ok) { message }
        assertions++
    }
    fun mustFail(message: String, body: () -> Unit) {
        var failed = false
        try {
            body()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        verify(failed, message)
    }
    val original = DoubleArray(40) { it * 0.5 }
    val grid = BeatGrid(original)
    original[1] = -1.0
    grid.times[2] = Double.NaN
    verify(grid.at(1) == 0.5 && grid.at(2) == 1.0, "A caller mutated an existing grid")
    mustFail("NaN grid was accepted") { BeatGrid(doubleArrayOf(0.0, 1.0, Double.NaN, 3.0)) }
    mustFail("Infinite grid was accepted") {
        BeatGrid(doubleArrayOf(0.0, 1.0, 2.0, Double.POSITIVE_INFINITY))
    }
    mustFail("Repeated anchors were accepted") { BeatGrid(doubleArrayOf(0.0, 1.0, 1.0, 3.0)) }
    mustFail("NaN time was accepted") { grid.position(Double.NaN) }

    val b = BeatGrid(DoubleArray(100) { 0.17 + it * 60.0 / 155 + 0.018 * sin(it * 0.7) })
    val a = BeatGrid(DoubleArray(100) { 0.09 + it * 60.0 / 156 + 0.009 * sin(it * 0.5) })
    for (release in listOf(false, true)) {
        for (ratio in listOf(1 to 1, 3 to 2)) {
            val plan = MixPlan(a, b, 8, 12, 16, 44100, ratio.first, ratio.second, release)
            val schedule = WarpSchedule.from(plan, 20.0)
            verify(schedule.anchors.first() == WarpAnchor(0, 0), "Missing first anchor")
            verify(
                schedule.anchors.last() == WarpAnchor(schedule.sourceFrames, schedule.outputFrames),
                "Missing final anchor",
            )
            for ((left, right) in schedule.anchors.zipWithNext()) {
                verify(
                    left.sourceFrame < right.sourceFrame && left.outputFrame < right.outputFrame,
                    "Nonmonotone quantized map",
                )
                val midSource = (left.sourceFrame + right.sourceFrame) / 2.0
                val mapped =
                    plan.secondOutputTime(midSource / schedule.sampleRate) * schedule.sampleRate
                val interpolated =
                    schedule.outputOriginFrame +
                        left.outputFrame +
                        (midSource - left.sourceFrame) * (right.outputFrame - left.outputFrame) /
                            (right.sourceFrame - left.sourceFrame)
                verify(
                    abs(mapped - interpolated) / schedule.sampleRate < 0.0011,
                    "Stretcher map deviates from clock by more than 1.1 ms",
                )
            }
            for (i in 0..2000) {
                val t = i * 0.015
                val inverse = plan.secondOutputTime(plan.secondSourceTime(t))
                verify(abs(t - inverse) < 1e-6, "Warp inverse mismatch at $t")
            }
            for (beat in 8 until 24) {
                val src = plan.secondSourceTime(a.at(beat))
                if (src <= 0 || src >= 20) continue
                val sourceFrame = (src * schedule.sampleRate).roundToLong()
                val anchor = schedule.anchors.minBy { abs(it.sourceFrame - sourceFrame) }
                verify(anchor.sourceFrame == sourceFrame, "Exact beat key was omitted")
                verify(
                    abs(
                        anchor.outputFrame + schedule.outputOriginFrame -
                            a.at(beat) * schedule.sampleRate
                    ) <= 0.501,
                    "Quantized beat anchor missed its output sample",
                )
            }
        }
    }

    class Tone(override val durationSeconds: Double = 8.0) : StereoPcm {
        override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int): FloatArray =
            FloatArray(frames * 2) {
                val t =
                    ((startSeconds * outputSampleRate).roundToLong() + it / 2).toDouble() /
                        outputSampleRate
                if (t < 0 || t >= durationSeconds) 0f else sin(2 * PI * 220 * t).toFloat()
            }
    }
    val identity = MixPlan(grid, grid, 2, 4, 4)
    val source = Tone()
    val session = BeatMixer().prepare(source, source, identity)
    verify(session.schedule.isTranslationOnly, "Unit-speed translation unnecessarily stretches PCM")
    val whole = ArrayList<Float>()
    val sink =
        object : PcmSink {
            override fun write(interleavedStereo: FloatArray, frames: Int) {
                whole.addAll(interleavedStereo.take(frames * 2))
            }
        }
    session.renderFrames(sink, 0, 10000, MixMode.OVERLAP)
    val range = ArrayList<Float>()
    session.renderFrames(
        object : PcmSink {
            override fun write(interleavedStereo: FloatArray, frames: Int) {
                range.addAll(interleavedStereo.take(frames * 2))
            }
        },
        4321,
        8765,
        MixMode.OVERLAP,
    )
    verify(range == whole.subList(4321 * 2, 8765 * 2), "Seeking changes the exact prepared samples")
    mustFail("Huge frame offset was accepted") { session.readIncoming(Long.MIN_VALUE, 1) }
    mustFail("Huge positive frame offset was accepted") { session.readIncoming(Long.MAX_VALUE, 1) }
    session.close()
    session.close()
    var closedCheck = false
    try {
        session.readIncoming(0, 1)
    } catch (_: IllegalStateException) {
        closedCheck = true
    }
    verify(closedCheck, "Closed session remained readable")

    var missing = false
    try {
        BeatMixer().prepare(source, source, MixPlan(a, b, 0))
    } catch (_: MissingPitchStretchEngineException) {
        missing = true
    }
    verify(missing, "Unsupported stretch silently used a lower-quality fallback")
    var preparedClosed = 0
    val engine =
        object : PitchStretchEngine {
            override fun prepare(
                source: StereoPcm,
                schedule: WarpSchedule,
                progress: (Double) -> Unit,
            ): PreparedStereoPcm =
                object : PreparedStereoPcm {
                    override val durationSeconds =
                        schedule.outputFrames.toDouble() / schedule.sampleRate

                    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                        FloatArray(frames * 2)

                    override fun close() {
                        preparedClosed++
                    }
                }
        }
    var cancel = false
    val cancelSession = BeatMixer(engine).prepare(source, source, MixPlan(a, b, 0), { cancel })
    cancel = true
    var cancelled = false
    try {
        cancelSession.renderFrames(sink, 0, 100)
    } catch (_: MixCancelledException) {
        cancelled = true
    }
    cancelSession.close()
    cancelSession.close()
    verify(cancelled, "Cancellation was ignored")
    verify(preparedClosed == 1, "Prepared resources were leaked or closed twice")

    var errorSinkClosed = 0
    val owned =
        object : PitchStretchEngine {
            override fun prepare(
                source: StereoPcm,
                schedule: WarpSchedule,
                progress: (Double) -> Unit,
            ): PreparedStereoPcm =
                object : PreparedStereoPcm {
                    override val durationSeconds =
                        schedule.outputFrames.toDouble() / schedule.sampleRate

                    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                        FloatArray(frames * 2)

                    override fun close() {
                        errorSinkClosed++
                    }
                }
        }
    try {
        BeatMixer(owned)
            .renderRange(
                source,
                source,
                MixPlan(a, b, 0),
                object : PcmSink {
                    override fun write(interleavedStereo: FloatArray, frames: Int) {
                        throw UnsupportedOperationException("disk full")
                    }
                },
                0.0,
                1.0,
            )
    } catch (_: UnsupportedOperationException) {}
    verify(errorSinkClosed == 1, "Sink failure leaked prepared resources")
    val shorterB = Tone(2.0)
    val longerA = Tone(15.0)
    val transition = BeatMixer().prepare(longerA, shorterB, MixPlan(grid, grid, 2, 0, 2))
    verify(
        transition.endFrame(MixMode.TRANSITION) == 3L * 44100,
        "Transition appended an inaudible A tail",
    )
    verify(
        transition.endFrame(MixMode.OVERLAP) == 15L * 44100,
        "Overlap truncated the outgoing song",
    )
    transition.close()
    println("Mixer contract: $assertions assertions passed")
}
