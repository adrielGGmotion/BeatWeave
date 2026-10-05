package org.metrolist.beatweave.learned

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.metrolist.beatweave.*

private fun samples(path: String): FloatArray {
    val bytes=File(path).readBytes();require(bytes.size%4==0)
    return FloatArray(bytes.size/4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}
private fun exportCandidates(a:LocalSongAnalysis,b:LocalSongAnalysis,file:File) {
    val ga=a.bars();val gb=b.bars();val ranking=MusicalCueRanking(a.audio,b.audio)
    file.printWriter().use { out ->
        out.println("a,b,duration,speed,bars,baseline,"+(0..12).joinToString(","){"x$it"})
        for(bars in TransitionPlanner.supportedBarCounts) {
            val startsA=(0..ga.barCount-bars).map{ranking.outgoing(ga,it,bars)}.sortedByDescending{it.score}.take(128)
            val startsB=(0..gb.barCount-bars).map{ranking.incoming(gb,it,bars)}.sortedByDescending{it.score}.take(128)
            for(into in startsB) for(exit in startsA) {
                val duration=exit.to-exit.from;val speed=(into.to-into.from)/duration
                if(speed !in .8..1.25)continue
                val e=ranking.evidence(exit,into,bars)
                val f=TrainedCueModel.features(exit,into,e,bars,a.audio.durationSeconds,b.audio.durationSeconds)
                out.println(listOf(exit.from,into.from,duration,speed,bars.toDouble(),e.score).plus(f.toList()).joinToString(","))
            }
        }
    }
}
/** Inputs are paths only. No external cues, tempo, meter, or clock-limit override exists. */
fun main(args:Array<String>) {
    require(args.size>=5)
    val mode=args[0];require(mode=="export" || mode=="plan")
    val output=File(args[2]).also{it.mkdirs()}
    val songPaths=if(mode=="plan") args.drop(4) else args.drop(3)
    require(songPaths.size%2==0 && (mode!="plan" || songPaths.size==2))
    val songs=OrtBeatThisBackend(File(args[1]),threads=2).use{backend ->
        songPaths.mapIndexed{index,path ->
            println("Analyzing input ${index+1}")
            val a=LocalSongAnalyzer(backend).analyze(samples(path))
            File(output,"track-$index-canonical.csv").printWriter().use{w ->
                w.println("time,strength");a.pulse.beats.forEach{w.println("${it.seconds},${it.strength}")}
            }
            File(output,"track-$index-bars.csv").printWriter().use{w ->
                w.println("time,beat_index,model_score,posterior");a.barTracking?.boundaries?.forEach{w.println("${it.seconds},${it.beatIndex},${it.modelDownbeatScore},${it.downbeatPosterior}")}
            }
            File(output,"track-$index-analysis.txt").writeText("bpm=${a.audio.bpm}\nduration=${a.audio.durationSeconds}\npulse=${a.pulse.quality}\nbarRegions=${a.barTracking?.regions}\n")
            a
        }
    }
    if(mode=="export") {
        for(i in 0 until songs.size/2){exportCandidates(songs[2*i],songs[2*i+1],File(output,"pair-${i+1}-candidates.csv"));println("Exported candidate set ${i+1}")}
        return
    }
    val rows=File(args[3]).readLines().map{it.split(',').map(String::toDouble).toDoubleArray()}
    require(rows.size==3)
    val model=TrainedCueModel(rows[0],rows[1],rows[2],"beatweave-manual-pilot-v1")
    try {
        val accepted=LocalMixPlanner.bestTransition(songs[0],songs[1],searchOptions=AutoMixSearchOptions(cueModel=model))
        val p=accepted.mixPlan;val selection=accepted.automaticSelection!!
        File(output,"plan.json").writeText("""{"status":"accepted","a":${p.startSeconds},"b":${p.secondSourceTime(p.startSeconds)},"duration":${p.fadeEndSeconds-p.startSeconds},"bars":${selection.barCount},"production_clock_gates":true}"""+"\n")
        File(output,"accepted-map.csv").printWriter().use{w ->
            w.println("output_source_seconds,incoming_source_seconds")
            val from=(p.startSeconds-6).coerceAtLeast(0.0);val until=p.fadeEndSeconds+6
            // Sampling the actual accepted continuous source clock, never a new cue fit.
            var t=from
            while(t<until){w.println("$t,${p.secondSourceTime(t)}");t+=.05}
            w.println("$until,${p.secondSourceTime(until)}")
        }
        File(output,"planning-audit.txt").writeText("${accepted.automaticSelection}\n${accepted.clockFit.report}\n")
        println("ACCEPT ${p.startSeconds} -> ${p.secondSourceTime(p.startSeconds)} / ${selection.barCount} bars")
    } catch(e:AutoMixPlanningException) {
        File(output,"plan.json").writeText("""{"status":"declined","reason":"${e.report.failure}","production_clock_gates":true}"""+"\n")
        File(output,"planning-audit.txt").writeText("${e.report}\n${e.message}\n")
        println("DECLINE ${e.report.failure}")
    }
}
