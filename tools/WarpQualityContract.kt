import kotlin.math.*
import org.metrolist.beatweave.*

fun main() {
    val a = BeatGrid(DoubleArray(120) { it * 60.0 / 156 })
    val b = BeatGrid(DoubleArray(120) { it * 60.0 / 155 })
    val safe = WarpQuality.assess(MixPlan(a, b, 0, crossfadeBeats = 32, releaseAfterFade = true))
    check(safe.accepted) { "Normal155/156 match rejected: $safe" }
    val wide =
        WarpQuality.assess(
            MixPlan(
                a,
                BeatGrid(DoubleArray(120) { it * 0.5 }),
                0,
                crossfadeBeats = 32,
                releaseAfterFade = true,
            )
        )
    check(wide.accepted) { "Stable1.3x plus release rejected: $wide" }
    val drift = BeatGrid(DoubleArray(120) { it * 60.0 / 155 + 0.007 * sin(it * 0.4) })
    check(WarpQuality.assess(MixPlan(a, drift, 0, crossfadeBeats = 32)).accepted)

    val duplicate = b.times.toMutableList().apply { add(9, get(8) + 0.080) }.toDoubleArray()
    val duplicateReport =
        WarpQuality.assess(MixPlan(a, BeatGrid(duplicate), 0, crossfadeBeats = 32))
    check(
        !duplicateReport.accepted &&
            duplicateReport.issues.any {
                it.code == WarpQualityIssueCode.SPEED_TOO_LOW ||
                    it.code == WarpQualityIssueCode.TEMPO_CHANGE_TOO_ABRUPT
            }
    ) {
        "An80ms duplicate was silently accepted: $duplicateReport"
    }
    val half =
        BeatGrid(
            DoubleArray(120) {
                if (it <= 8) it * 60.0 / 155 else 8 * 60.0 / 155 + (it - 8) * 60.0 / 82
            }
        )
    val halfReport = WarpQuality.assess(MixPlan(a, half, 0, crossfadeBeats = 32))
    check(
        !halfReport.accepted &&
            halfReport.issues.any { it.code == WarpQualityIssueCode.TEMPO_CHANGE_TOO_ABRUPT }
    ) {
        "Half-tempo switching was accepted: $halfReport"
    }
    val extreme = MixPlan(a, BeatGrid(DoubleArray(120) { it * 60.0 / 45 }), 0, crossfadeBeats = 16)
    check(!WarpQuality.assess(extreme).accepted)
    check(
        WarpQuality.assess(extreme, limits = WarpQualityLimits(maximumPlaybackSpeed = 4.0)).accepted
    ) {
        "An explicit wider caller policy was ignored"
    }
    val full = WarpQuality.assess(WarpSchedule.from(MixPlan(a, b, 0), 20.0))
    check(full.accepted)
    var threw = false
    try {
        duplicateReport.requireAccepted()
    } catch (e: UnsafeWarpException) {
        threw = e.report == duplicateReport
    }
    check(threw)
    println(
        "Warp quality contract: normal tempo, variable tempo, release, duplicate, half-tempo switch, extreme ratio, explicit policy, quantized map, exception gates passed"
    )
    println(
        "duplicate min=${duplicateReport.minimumPlaybackSpeed}, max acceleration=${duplicateReport.maximumLogSpeedChangePerSecond}"
    )
    println("half-tempo max acceleration=${halfReport.maximumLogSpeedChangePerSecond}")
}
