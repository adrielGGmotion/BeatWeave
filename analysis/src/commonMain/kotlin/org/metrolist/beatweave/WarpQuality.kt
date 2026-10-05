package org.metrolist.beatweave

import kotlin.math.*

/**
 * Acceptance policy for automatic musical matching, not limits of the low-level stretcher. Wider
 * limits are an explicit caller decision. Playback speed is source seconds per output second: 1.25
 * plays the source 25% faster.
 */
data class WarpQualityLimits(
    val minimumPlaybackSpeed: Double = 0.5,
    val maximumPlaybackSpeed: Double = 2.0,
    /** Absolute d(log(speed))/dt. 0.8 permits a 1.08x change over 100 ms. */
    val maximumLogSpeedChangePerSecond: Double = 0.8,
) {
    init {
        require(minimumPlaybackSpeed.isFinite() && minimumPlaybackSpeed > 0)
        require(maximumPlaybackSpeed.isFinite() && maximumPlaybackSpeed >= minimumPlaybackSpeed)
        require(maximumLogSpeedChangePerSecond.isFinite() && maximumLogSpeedChangePerSecond > 0)
    }
}

enum class WarpQualityIssueCode {
    SPEED_TOO_LOW,
    SPEED_TOO_HIGH,
    TEMPO_CHANGE_TOO_ABRUPT,
    INVALID_CLOCK,
}

data class WarpQualityIssue(
    val code: WarpQualityIssueCode,
    val outputSeconds: Double,
    val measuredValue: Double,
    val acceptedLimit: Double,
)

/**
 * Reports whether the requested timing deformation is plausible. An accepted report does NOT
 * establish that detected beats are musically correct: that requires independent beat evidence or
 * an annotated grid. No audio is processed.
 */
data class WarpQualityReport(
    val fromSeconds: Double,
    val toSeconds: Double,
    val minimumPlaybackSpeed: Double,
    val maximumPlaybackSpeed: Double,
    val maximumLogSpeedChangePerSecond: Double,
    val issues: List<WarpQualityIssue>,
) {
    val accepted: Boolean
        get() = issues.isEmpty()

    fun requireAccepted(): WarpQualityReport {
        if (!accepted) throw UnsafeWarpException(this)
        return this
    }
}

class UnsafeWarpException(val report: WarpQualityReport) :
    IllegalArgumentException(
        "Automatic beat matching declined: " +
            report.issues.joinToString { issue ->
                when (issue.code) {
                    WarpQualityIssueCode.SPEED_TOO_LOW ->
                        "local playback speed is below the accepted range"
                    WarpQualityIssueCode.SPEED_TOO_HIGH ->
                        "local playback speed is above the accepted range"
                    WarpQualityIssueCode.TEMPO_CHANGE_TOO_ABRUPT ->
                        "beat grid implies an abrupt tempo change"
                    WarpQualityIssueCode.INVALID_CLOCK ->
                        "beat grid produces an invalid playback clock"
                }
            }
    )

object WarpQuality {
    /**
     * Check the audible transition before preparing any PCM. The default includes the two-second
     * return to original tempo when releaseAfterFade is enabled. This does not inspect inaudible
     * incoming material before the selected cue.
     */
    fun assess(
        plan: MixPlan,
        fromSeconds: Double = plan.startSeconds,
        toSeconds: Double = plan.fadeEndSeconds + if (plan.releaseAfterFade) 2.0 else 0.0,
        limits: WarpQualityLimits = WarpQualityLimits(),
    ): WarpQualityReport = assess(plan, fromSeconds, toSeconds, limits) {}

    /** Internal cancellable path used while automatic planning owns the operation. */
    internal fun assess(
        plan: MixPlan,
        fromSeconds: Double,
        toSeconds: Double,
        limits: WarpQualityLimits,
        cancellationCheck: () -> Unit,
    ): WarpQualityReport {
        cancellationCheck()
        require(
            fromSeconds.isFinite() &&
                toSeconds.isFinite() &&
                toSeconds > fromSeconds &&
                abs(fromSeconds) <= 4 * 60 * 60 &&
                abs(toSeconds) <= 4 * 60 * 60
        )
        val metrics = Metrics(fromSeconds, toSeconds, limits)
        // Five-millisecond sampling resolves an 80 ms duplicate detection rather
        // than concealing it inside one average-BPM statistic. Include every beat
        // boundary so even very short malformed intervals are inspected.
        val firstBeat = floor(plan.first.position(fromSeconds)).toInt()
        val lastBeat = ceil(plan.first.position(toSeconds)).toInt()
        require(lastBeat.toLong() - firstBeat <= 1_000_000) { "Unreasonable beat density" }
        var work = 0
        fun checkWork() {
            if (work and (WARP_QUALITY_CANCELLATION_INTERVAL - 1) == 0) cancellationCheck()
            work++
        }
        fun inspect(t: Double) {
            checkWork()
            val (speed, acceleration) = plan.secondClockRates(t)
            metrics.speed(speed, t)
            metrics.change(abs(acceleration / speed), t)
        }
        fun inspectSection(start: Double, end: Double) {
            val count = max(8, ceil((end - start) / 0.005).toInt())
            for (i in 0 until count) {
                val t = start + (end - start) * i / count
                inspect(t)
            }
        }
        // BeatGrid.at is ordered for ordered indices, so stream sections directly instead of
        // materializing and sorting up to one million knots before cancellation can be observed.
        var sectionStart = fromSeconds
        for (beat in firstBeat..lastBeat) {
            checkWork()
            val boundary = plan.first.at(beat)
            if (boundary > fromSeconds && boundary < toSeconds) {
                inspectSection(sectionStart, boundary)
                sectionStart = boundary
            }
        }
        inspectSection(sectionStart, toSeconds)
        inspect(toSeconds)
        cancellationCheck()
        return metrics.report()
    }

    /** Inspect the actual quantized map across the whole prepared source. */
    fun assess(
        schedule: WarpSchedule,
        limits: WarpQualityLimits = WarpQualityLimits(),
    ): WarpQualityReport {
        val rate = schedule.sampleRate.toDouble()
        val from = schedule.outputOriginFrame / rate
        val to = (schedule.outputOriginFrame + schedule.outputFrames) / rate
        val metrics = Metrics(from, to, limits)
        var previousSpeed = Double.NaN
        var previousTime = Double.NaN
        for ((left, right) in schedule.anchors.zipWithNext()) {
            val speed =
                (right.sourceFrame - left.sourceFrame).toDouble() /
                    (right.outputFrame - left.outputFrame)
            val t =
                (schedule.outputOriginFrame + (left.outputFrame + right.outputFrame) / 2.0) / rate
            metrics.speed(speed, t)
            if (previousSpeed.isFinite())
                metrics.change(abs(ln(speed / previousSpeed)) / (t - previousTime), t)
            previousSpeed = speed
            previousTime = t
        }
        return metrics.report()
    }

    private class Metrics(val from: Double, val to: Double, val limits: WarpQualityLimits) {
        private var minimum = Double.POSITIVE_INFINITY
        private var maximum = Double.NEGATIVE_INFINITY
        private var maximumChange = 0.0
        private var minimumTime = from
        private var maximumTime = from
        private var changeTime = from
        private var invalidTime: Double? = null

        fun speed(speed: Double, time: Double) {
            if (!speed.isFinite() || speed <= 0.0) {
                invalidTime = time
                return
            }
            if (speed < minimum) {
                minimum = speed
                minimumTime = time
            }
            if (speed > maximum) {
                maximum = speed
                maximumTime = time
            }
        }

        fun change(value: Double, time: Double) {
            if (!value.isFinite()) {
                invalidTime = time
                return
            }
            if (value > maximumChange) {
                maximumChange = value
                changeTime = time
            }
        }

        fun report(): WarpQualityReport {
            val issues = mutableListOf<WarpQualityIssue>()
            if (invalidTime != null || !minimum.isFinite()) {
                issues +=
                    WarpQualityIssue(
                        WarpQualityIssueCode.INVALID_CLOCK,
                        invalidTime ?: from,
                        Double.NaN,
                        0.0,
                    )
            }
            // Floating-point arithmetic and integer-frame quantization must not make
            // an exact policy boundary fail by a handful of floating-point ulps.
            if (minimum < limits.minimumPlaybackSpeed - 1e-6)
                issues +=
                    WarpQualityIssue(
                        WarpQualityIssueCode.SPEED_TOO_LOW,
                        minimumTime,
                        minimum,
                        limits.minimumPlaybackSpeed,
                    )
            if (maximum > limits.maximumPlaybackSpeed + 1e-6)
                issues +=
                    WarpQualityIssue(
                        WarpQualityIssueCode.SPEED_TOO_HIGH,
                        maximumTime,
                        maximum,
                        limits.maximumPlaybackSpeed,
                    )
            if (maximumChange > limits.maximumLogSpeedChangePerSecond + 1e-6)
                issues +=
                    WarpQualityIssue(
                        WarpQualityIssueCode.TEMPO_CHANGE_TOO_ABRUPT,
                        changeTime,
                        maximumChange,
                        limits.maximumLogSpeedChangePerSecond,
                    )
            return WarpQualityReport(from, to, minimum, maximum, maximumChange, issues.toList())
        }
    }
}

private const val WARP_QUALITY_CANCELLATION_INTERVAL = 4096
