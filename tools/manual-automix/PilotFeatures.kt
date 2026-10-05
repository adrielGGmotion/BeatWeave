package org.metrolist.beatweave.learned

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.metrolist.beatweave.*

private fun pcm(path: String): FloatArray {
    val bytes = File(path).readBytes()
    return FloatArray(bytes.size / 4).also {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
    }
}

/** Provider grids isolate musical choice from beat detection; they are NOT independent beat truth. */
fun main(args: Array<String>) {
    val root = File(args[0])
    val trained = if (args.size > 2) {
        val rows = File(args[2]).readLines().map { line -> line.split(',').map(String::toDouble).toDoubleArray() }
        TrainedCueModel(rows[0], rows[1], rows[2], "beatweave-manual-pilot-v1")
    } else null
    if (trained != null) {
        File(root,"score-parity.csv").readLines().forEach { line ->
            val values=line.split(',').map(String::toDouble)
            check(kotlin.math.abs(trained.score(values.take(13).toDoubleArray())-values[13])<1e-7)
        }
        println("Python/Kotlin score parity passed on 36 independent candidate vectors")
    }
    val tracks = File(root, "tracks.tsv").readLines().map { it.split('\t') }
    val analyses = tracks.map { t ->
        val beats = File(t[2]).readLines().map { it.split(',') }
        MusicAnalyzer().analyzeWithBeats(
            pcm(t[1]), 22050, beats.map { Beat(it[0].toDouble(), 1f) },
            beats.filter { it[1] == "1" }.map { it[0].toDouble() }, "provided-reference-grid-v1",
        )
    }
    for (p in 0..2) {
        val a = analyses[2*p]; val b = analyses[2*p+1]
        val ga = BarGrid.from(BeatGrid(a.beats.map { it.seconds }.toDoubleArray()), a.downbeatSeconds)
        val gb = BarGrid.from(BeatGrid(b.beats.map { it.seconds }.toDoubleArray()), b.downbeatSeconds)
        val ranking = MusicalCueRanking(a, b)
        File(root, "pair-${p+1}-candidates.csv").printWriter().use { out ->
            out.println("a,b,duration,speed,bars,baseline," + (0..12).joinToString(",") { "x$it" })
            for (bars in TransitionPlanner.supportedBarCounts) {
                // Identical per-track score shortlist to default production search (128 cap).
                val startsA = (0..ga.barCount-bars).map { ranking.outgoing(ga,it,bars) }
                    .sortedByDescending { it.score }.take(128)
                val startsB = (0..gb.barCount-bars).map { ranking.incoming(gb,it,bars) }
                    .sortedByDescending { it.score }.take(128)
                for (into in startsB) for (exit in startsA) {
                    val duration = exit.to - exit.from
                    val speed = (into.to - into.from) / duration
                    if (speed !in 0.80..1.25) continue
                    val e = ranking.evidence(exit,into,bars)
                    val f = TrainedCueModel.features(exit,into,e,bars,a.durationSeconds,b.durationSeconds)
                    out.println(listOf(exit.from,into.from,duration,speed,bars.toDouble(),e.score)
                        .plus(f.toList()).joinToString(","))
                }
            }
        }
        println("Extracted candidate features for pair ${p+1}")
    }
    // Audit the real automatic entry point separately; never turn a rejection into a render.
    if (args.size > 1) OrtBeatThisBackend(File(args[1]), threads=2).use { backend ->
        for (p in 0..2) {
            val songs = (0..1).map { side ->
                LocalSongAnalyzer(backend).analyze(pcm(tracks[2*p+side][1]))
            }
            val result = try {
                val plan = LocalMixPlanner.bestTransition(songs[0], songs[1])
                "ACCEPT\n${plan.automaticSelection}\n${plan.clockFit.report}"
            } catch (e: AutoMixPlanningException) {
                "DECLINE\n${e.report}\n${e.message}"
            }
            File(root,"pair-${p+1}-production-baseline.txt").writeText(result)
            println("Production baseline pair ${p+1}: ${result.lineSequence().first()}")
            if (trained != null) {
                val learnedResult = try {
                    val plan = LocalMixPlanner.bestTransition(songs[0],songs[1],
                        searchOptions=AutoMixSearchOptions(cueModel=trained))
                    "ACCEPT\n${plan.automaticSelection}\n${plan.clockFit.report}"
                } catch (e: AutoMixPlanningException) { "DECLINE\n${e.report}\n${e.message}" }
                File(root,"pair-${p+1}-production-learned.txt").writeText(learnedResult)
                println("Production learned pair ${p+1}: ${learnedResult.lineSequence().first()}")
            }
        }
    }
}
