package org.metrolist.beatweave.verification

import kotlin.math.*
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.*

/**
 * Production grid, clock and prepared-render regression using deterministic synthetic PCM.
 * High-level acoustic interpretation is evaluated separately; these inputs explicitly identify the
 * observed main events and retain their original canonical indices.
 */
object MainBeatProjectionRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val main = BeatGrid(DoubleArray(33) { .2 + it * .5 })
        val phases = doubleArrayOf(0.0, .10, .35)
        val canonical = BeatGrid(DoubleArray(97) { .2 + (it / 3) * .5 + phases[it % 3] })
        val forward =
            MixPlan(
                canonical,
                main,
                0,
                0,
                96,
                outputSampleRate = 48000,
                firstBeatsPerCycle = 3,
                secondBeatsPerCycle = 1,
            )
        val reverse =
            MixPlan(
                main,
                canonical,
                0,
                0,
                32,
                outputSampleRate = 48000,
                firstBeatsPerCycle = 1,
                secondBeatsPerCycle = 3,
            )
        check(!BeatClockRegularizer.regularize(forward, 17.0).report.accepted)
        check(!BeatClockRegularizer.regularize(reverse, 17.0).report.accepted)
        val canonicalSnapshot = canonical.times
        val explicitMainIndices = IntArray(33) { it * 3 }
        val projected =
            BeatGrid(
                DoubleArray(explicitMainIndices.size) { canonical.at(explicitMainIndices[it]) }
            )
        check(projected.times.contentEquals(main.times))
        var samplesChecked = 0L
        for (direction in 0..1) for (offsetFrames in intArrayOf(-4800, 0, 4800)) {
            val a = if (direction == 0) projected else main
            val b = if (direction == 0) main else projected
            val shifted = BeatGrid(a.times.map { it + offsetFrames / 48000.0 }.toDoubleArray())
            val originalA = if (direction == 0) canonical else main
            val originalB = if (direction == 0) main else canonical
            val firstSong =
                declaredSong(
                    BeatGrid(originalA.times.map { it + offsetFrames / 48000.0 }.toDoubleArray())
                )
            val secondSong = declaredSong(originalB)
            val outgoing = declaration(firstSong, if (direction == 0) 3 else 1)
            val incomingStride = if (direction == 0) 1 else 3
            val incoming = declaration(secondSong, incomingStride)
            val planned =
                LocalMixPlanner.declaredTransition(
                    firstSong,
                    secondSong,
                    outgoing,
                    incoming,
                    0,
                    0,
                    8,
                    outputSampleRate = 48000,
                    fitOptions =
                        ClockFitOptions(
                            pinnedIncomingBeats = (0..32 step 4).map { it * incomingStride }.toSet()
                        ),
                )
            val selection = checkNotNull(planned.declaredMainSelection)
            val fit = planned.clockFit
            val plan = planned.mixPlan
            check(
                plan.first.times.contentEquals(shifted.times) &&
                    plan.second.times.contentEquals(b.times)
            )
            check(plan.firstBeatsPerCycle == 1 && plan.secondBeatsPerCycle == 1)
            check(selection.matchedMainBeats.size == 33)
            check(
                selection.matchedMainBeats.withIndex().all { (i, match) ->
                    match.outgoingCanonicalBeat == (if (direction == 0) 3 else 1) * i &&
                        match.incomingCanonicalBeat == incomingStride * i
                }
            )
            // Inverse clock lookup is numerically bounded; sample-domain render checks below remain
            // bit exact.
            check(selection.matchedMainBeats.all { abs(it.originalOutputResidualSeconds) < 1e-9 }) {
                "Observed residuals: ${selection.matchedMainBeats.map { it.originalOutputResidualSeconds }}"
            }
            check(fit.report.accepted && fit.report.maximumSourceDisplacementSeconds == 0.0)
            val schedule = WarpSchedule.from(fit.requireAccepted(), 17.0)
            check(schedule.isTranslationOnly && schedule.outputOriginFrame == offsetFrames.toLong())
            check(
                schedule.outputFrames == 17L * 48000 &&
                    schedule.sourceFrames == schedule.outputFrames
            )
            val first = AuditPcm(220.0)
            val second = AuditPcm(330.0)
            val session = BeatMixer().prepare(first, second, fit.requireAccepted())
            try {
                for (position in longArrayOf(0, 777, 48000, 500000, 815990)) {
                    val global = position + offsetFrames
                    val actual = session.readIncoming(global, 32)
                    check(actual.contentEquals(second.readFrames(position, 32, 48000)))
                    samplesChecked += actual.size
                }
                for (mode in MixMode.entries) {
                    var global = session.startFrame(mode)
                    var count = 0L
                    val fullRenderCrop = FloatArray(2000)
                    session.renderComplete(
                        object : PcmSink {
                            override fun write(interleavedStereo: FloatArray, frames: Int) {
                                val left = first.readFrames(global, frames, 48000)
                                val right = second.readFrames(global - offsetFrames, frames, 48000)
                                for (i in interleavedStereo.indices) {
                                    val time = (global + i / 2) / 48000.0
                                    val fade =
                                        ((time - plan.startSeconds) /
                                                (plan.fadeEndSeconds - plan.startSeconds))
                                            .coerceIn(0.0, 1.0)
                                    val gainA =
                                        if (mode == MixMode.OVERLAP) .46
                                        else cos(fade * PI / 2) / sqrt(2.0)
                                    val gainB =
                                        if (mode == MixMode.OVERLAP) .46
                                        else sin(fade * PI / 2) / sqrt(2.0)
                                    val expected = (left[i] * gainA + right[i] * gainB).toFloat()
                                    check(interleavedStereo[i] == expected)
                                    val absolute = global + i / 2
                                    if (absolute in 32000L until 33000L)
                                        fullRenderCrop[(absolute - 32000).toInt() * 2 + i % 2] =
                                            interleavedStereo[i]
                                }
                                global += frames
                                count += frames
                                samplesChecked += interleavedStereo.size
                            }
                        },
                        mode,
                    )
                    check(count == session.frameCount(mode))
                    if (mode == MixMode.OVERLAP) check(count == 17L * 48000 + abs(offsetFrames))
                    val seekCrop = FloatArray(2000)
                    var cropSamples = 0
                    session.renderFrames(
                        object : PcmSink {
                            override fun write(interleavedStereo: FloatArray, frames: Int) {
                                interleavedStereo.copyInto(seekCrop, cropSamples)
                                cropSamples += interleavedStereo.size
                            }
                        },
                        32000,
                        33000,
                        mode,
                    )
                    check(cropSamples == seekCrop.size && seekCrop.contentEquals(fullRenderCrop))
                    val beforeFailure = session.readIncoming(32000, 128)
                    val sentinel = IllegalStateException("caller sink failed")
                    var observed: Throwable? = null
                    try {
                        session.renderFrames(
                            object : PcmSink {
                                override fun write(interleavedStereo: FloatArray, frames: Int) {
                                    throw sentinel
                                }
                            },
                            32000,
                            33000,
                            mode,
                        )
                    } catch (failure: Throwable) {
                        observed = failure
                    }
                    check(observed === sentinel)
                    check(beforeFailure.contentEquals(session.readIncoming(32000, 128)))
                }
            } finally {
                session.close()
                session.close()
            }
            check(canonical.times.contentEquals(canonicalSnapshot))
        }
        println(
            "PASS public declared main transition: both subdivision directions, signed/zero cue offsets, original-index pins and metadata, translation-only schedule, exact prepared reads/full transition+overlap, sink failure identity and session reuse, unchanged canonical metadata; $samplesChecked samples checked"
        )
    }
}

private fun declaredSong(grid: BeatGrid): LocalSongAnalysis {
    val beats = grid.times.map { Beat(it, .95f) }
    val audio =
        Analysis(
            17.0,
            120.0,
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
        )
    val pulse =
        PulseNormalizationResult(
            beats,
            beats,
            emptyList(),
            emptyList(),
            BeatGridQualityReport(beats.size, 120.0, emptyList()),
            1.0,
        )
    return LocalSongAnalysis(
        audio,
        LearnedBeatAnalysis(
            17.0,
            beats,
            emptyList(),
            "independently-generated-observations",
            BeatThisLogits(FloatArray(0), FloatArray(0)),
        ),
        pulse,
        audio,
    )
}

private fun declaration(song: LocalSongAnalysis, stride: Int): ObservedMainBeatGrid =
    ObservedMainBeatGrid(
        "generated-source-clock",
        song.pulse.beats.map { it.seconds }.toDoubleArray(),
        IntArray(33) { it * stride },
        IntArray(9) { it * 4 },
        BooleanArray(8) { true },
        MainBeatDeclarationOrigin.CALLER_DECLARED,
        "generated-audio-construction",
        "four-observed-main-beats",
        listOf(
            "Test fixture has independently specified main positions; unmatched subdivisions are deliberately uneven"
        ),
    )

private class AuditPcm(private val frequency: Double) : StereoPcm {
    override val durationSeconds = 17.0

    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
        readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int) =
        FloatArray(frames * 2) { index ->
            val frame = startFrame + index / 2
            if (frame < 0 || frame >= 17L * outputSampleRate) 0f
            else
                (.15 *
                        sin(2 * PI * frequency * frame / outputSampleRate) *
                        (if (index % 2 == 0) 1.0 else -1.0))
                    .toFloat()
        }
}
