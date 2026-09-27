package org.metrolist.beatweave.consumer

import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.*
import org.metrolist.beatweave.rubberband.RubberBandEngine

object SmokeChecks {
    private fun annotatedBars(incoming: Boolean): LocalSongAnalysis {
        val beats =
            List(25) { Beat((if (incoming) .31 else .2) + it * (if (incoming) .405 else .4), 1f) }
        val downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 }.map { it.seconds }
        val audio =
            Analysis(
                10.7,
                if (incoming) 60.0 / .405 else 150.0,
                1.0,
                beats,
                -18.0,
                -6.0,
                800.0,
                "unknown",
                0.0,
                emptyList(),
                FloatArray(0),
                .02,
                emptyList(),
                downbeatSeconds = downbeats,
            )
        val beatLogits = FloatArray(535) { -8f }
        val downbeatLogits = FloatArray(535) { -8f }
        beats.forEach { beatLogits[(it.seconds * 50).roundToInt()] = 8f }
        downbeats.forEach { downbeatLogits[(it * 50).roundToInt()] = 8f }
        val tracking = BarTracker.track(beats, downbeats, downbeatLogits).requireUsable()
        val pulse =
            PulseNormalizationResult(
                beats,
                beats,
                downbeats,
                emptyList(),
                BeatGridQualityReport(beats.size, audio.bpm, emptyList()),
                1.0,
            )
        return LocalSongAnalysis(
            audio,
            LearnedBeatAnalysis(
                audio.durationSeconds,
                beats,
                downbeats,
                "annotated-android-consumer-fixture",
                BeatThisLogits(beatLogits, downbeatLogits),
            ),
            pulse,
            audio,
            barTracking = tracking,
        )
    }

    private fun declaredGrid(song: LocalSongAnalysis, stride: Int) =
        ObservedMainBeatGrid(
            "android-consumer-generated-clock",
            song.pulse.beats.map { it.seconds }.toDoubleArray(),
            IntArray(25) { it * stride },
            IntArray(7) { it * 4 },
            BooleanArray(6) { true },
            MainBeatDeclarationOrigin.CALLER_DECLARED,
            "generated-fixture",
            "four-main-beats",
        )

    fun run(context: Context): String {
        val cache = File(context.cacheDir, "smoke-${System.nanoTime()}")
        check(cache.mkdirs())
        try {
            val rate = 48000
            val source =
                object : StereoPcm {
                    override val durationSeconds = 2.0

                    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
                        readFrames((startSeconds * rate).toLong(), frames, outputSampleRate)

                    override fun readFrames(
                        startFrame: Long,
                        frames: Int,
                        outputSampleRate: Int,
                    ): FloatArray {
                        require(outputSampleRate == rate)
                        return FloatArray(frames * 2) { i ->
                            (sin(2 * PI * 440 * (startFrame + i / 2) / rate) *
                                    if (i % 2 == 0) .4 else -.2)
                                .toFloat()
                        }
                    }
                }
            val plan =
                MixPlan(
                    BeatGrid(DoubleArray(20) { it * .5 * 1.031 }),
                    BeatGrid(DoubleArray(20) { it * .5 }),
                    0,
                    0,
                    4,
                    outputSampleRate = rate,
                )
            val schedule = WarpSchedule.from(plan, source.durationSeconds)
            val engine = RubberBandEngine(cache)
            val prepared = engine.prepare(source, schedule) {}
            try {
                check(prepared.durationSeconds == schedule.outputFrames.toDouble() / rate)
                val first = prepared.readFrames(12000, 4096, rate)
                check(first.all { it.isFinite() } && first.any { it != 0f })
                prepared.readFrames(16, 128, rate)
                check(first.contentEquals(prepared.readFrames(12000, 4096, rate)))
                for (i in 0 until first.size / 2) check(
                    kotlin.math.abs(first[2 * i + 1] + .5f * first[2 * i]) < .002f
                )
                val crossings = mutableListOf<Double>()
                for (i in 1 until first.size / 2) {
                    val previous = first[2 * (i - 1)]
                    val current = first[2 * i]
                    if (previous <= 0f && current > 0f)
                        crossings += i - 1 - previous.toDouble() / (current - previous)
                }
                check(crossings.size > 20)
                val measuredPitch =
                    (crossings.size - 1) * rate / (crossings.last() - crossings.first())
                check(kotlin.math.abs(measuredPitch - 440.0) < 1.0) {
                    "Pitch changed: $measuredPitch Hz"
                }
            } finally {
                prepared.close()
                prepared.close()
            }
            check(cache.listFiles()!!.isEmpty())
            for (stopAt in listOf(.15, .65)) {
                var cancelled = false
                try {
                    engine
                        .prepare(source, schedule) {
                            if (it > stopAt) throw MixCancelledException()
                        }
                        .close()
                } catch (_: MixCancelledException) {
                    cancelled = true
                }
                check(cancelled && cache.listFiles()!!.isEmpty())
            }
            val model = context.assets.open(BuildConfig.MODEL_ASSET).use { it.readBytes() }
            OrtBeatThisBackend(model, threads = 1).use { backend ->
                check(backend.modelVariant.assetFileName == BuildConfig.MODEL_ASSET)
                val result = backend.infer(FloatArray(51 * 128), 51)
                check(result.beat.size == 51 && result.downbeat.size == 51)
                check(result.beat.all { it.isFinite() } && result.downbeat.all { it.isFinite() })
            }
            val outgoing = annotatedBars(false)
            val incoming = annotatedBars(true)
            val transition =
                LocalMixPlanner.autoTransition(
                    outgoing,
                    incoming,
                    bars = 2,
                    outputSampleRate = rate,
                )
            check(transition.automaticSelection?.barCount == 2 && transition.barMatches.size == 3)
            check(
                transition.outgoingBars == 2 &&
                    transition.incomingBars == 2 &&
                    transition.mode == MixMode.TRANSITION
            )
            transition.barMatches.forEach {
                check(it.preparedIncomingSeconds == it.originalIncomingSeconds)
                check(abs(it.originalBoundaryOutputResidualSeconds) <= 1.0 / rate)
            }
            val overlap = LocalMixPlanner.overlap(outgoing, incoming, outputSampleRate = rate)
            check(overlap.automaticSelection != null && overlap.barMatches.size >= 3)
            val unevenBeats =
                List(73) { i -> Beat(.2 + (i / 3) * .4 + doubleArrayOf(0.0, .08, .28)[i % 3], 1f) }
            val unevenAudio = outgoing.audio.copy(beats = unevenBeats)
            val uneven =
                outgoing.copy(
                    audio = unevenAudio,
                    reference = unevenAudio,
                    barTracking = null,
                    model = outgoing.model.copy(beats = unevenBeats),
                    pulse =
                        outgoing.pulse.copy(
                            rawBeats = unevenBeats,
                            beats = unevenBeats,
                            quality = BeatGridQualityReport(unevenBeats.size, 450.0, emptyList()),
                        ),
                )
            val declared =
                LocalMixPlanner.declaredAutoTransition(
                    outgoing,
                    uneven,
                    declaredGrid(outgoing, 1),
                    declaredGrid(uneven, 3),
                    bars = 2,
                    outputSampleRate = rate,
                )
            val mainSelection = checkNotNull(declared.declaredMainSelection)
            check(mainSelection.matchedMainBeats.size == 9)
            check(mainSelection.outgoingCanonicalPulsesPerBar == listOf(4, 4))
            check(mainSelection.incomingCanonicalPulsesPerBar == listOf(12, 12))
            check(
                declared.mixPlan.firstBeatsPerCycle == 1 &&
                    declared.mixPlan.secondBeatsPerCycle == 1
            )
            val musicSource =
                object : StereoPcm {
                    override val durationSeconds = outgoing.audio.durationSeconds

                    override fun read(
                        startSeconds: Double,
                        frames: Int,
                        outputSampleRate: Int,
                    ): FloatArray =
                        FloatArray(frames * 2) { i ->
                            val time = startSeconds + (i / 2).toDouble() / outputSampleRate
                            if (time < 0 || time >= durationSeconds) 0f
                            else (0.2 * sin(2 * PI * 440 * time)).toFloat()
                        }
                }
            val transitionSession = transition.prepare(musicSource, musicSource, engine)
            try {
                var rendered = 0L
                transitionSession.renderComplete(
                    object : PcmSink {
                        override fun write(interleavedStereo: FloatArray, frames: Int) {
                            check(
                                interleavedStereo.size == frames * 2 &&
                                    interleavedStereo.all { it.isFinite() }
                            )
                            rendered += frames
                        }
                    },
                    transition.mode,
                )
                check(rendered > 0 && rendered == transitionSession.frameCount(transition.mode))
            } finally {
                transitionSession.close()
            }
            check(cache.listFiles()!!.isEmpty())
            return "PASS: native pitch-preserving preparation, exact duration, stereo, seek, close, cancellation cleanup, offline learned-model inference, automatic overlap selection, automatic bar-transition render with pinned downbeats, and declared main-beat planning across unequal subdivisions."
        } finally {
            check(cache.deleteRecursively())
        }
    }
}
