package org.metrolist.beatweave.learned

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only comparison; fixed-length diagnostics never replace the automatic selection. */
fun main(args: Array<String>) {
    require(args.size == 5)
    val output = File(args[4]).also { it.mkdirs() }
    val rows = File(args[1]).readLines().map { it.split(',').map(String::toDouble).toDoubleArray() }
    val model = TrainedCueModel(rows[0], rows[1], rows[2], "beatweave-manual-pilot-v1")
    val songs = OrtBeatThisBackend(File(args[0]), threads = 2).use { backend ->
        args.slice(2..3).map { path ->
            val bytes = File(path).readBytes()
            require(bytes.size % 4 == 0)
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            LocalSongAnalyzer(backend).analyze(samples)
        }
    }
    File(output, "bar-count-comparison.tsv").printWriter().use { out ->
        out.println("requested\tstatus\tselected_bars\toutgoing_start\tincoming_start\tduration\tscore\tlong_blend_affinity")
        for (bars in listOf<Int?>(null, 8, 16)) {
            val label = bars?.toString() ?: "automatic"
            try {
                val options = AutoMixSearchOptions(cueModel = model)
                val chosen = if (bars == null) LocalMixPlanner.bestTransition(songs[0], songs[1], searchOptions = options)
                    else AutoMixPlanner.transition(songs[0], songs[1], bars = bars, searchOptions = options)
                val plan = chosen.mixPlan
                val selection = chosen.automaticSelection!!
                val evidence = selection.musicalCueEvidence!!
                out.println(listOf(label, "accepted", selection.barCount, plan.startSeconds,
                    plan.secondSourceTime(plan.startSeconds), plan.fadeEndSeconds - plan.startSeconds,
                    evidence.score, evidence.longBlendAffinity).joinToString("\t"))
                File(output, "$label.txt").writeText("$selection\n${chosen.clockFit.report}\n")
            } catch (e: AutoMixPlanningException) {
                out.println("$label\tdeclined\t\t\t\t\t\t")
                File(output, "$label.txt").writeText("${e.report}\n${e.message}\n")
            }
        }
    }
}
