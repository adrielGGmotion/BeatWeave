package org.metrolist.beatweave.learned

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun pcm(file:File):FloatArray {
    val bytes=file.readBytes();require(bytes.size%4==0)
    val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size/4){b.float}
}

/** Two audio inputs and frozen models only. References are optional collection labels, never cues. */
fun main(args:Array<String>) {
    require(args.size==7)
    val output=File(args[0]).also{it.mkdirs()};val small=File(args[1]);val full=File(args[2])
    val rows=File(args[3]).readLines().map{it.split(',').map(String::toDouble).toDoubleArray()}
    val model=TrainedCueModel(rows[0],rows[1],rows[2],args[4])
    val inputs=listOf(pcm(File(args[5])),pcm(File(args[6])))
    val songs=List(2){ArrayList<LocalSongAnalysis>()}
    for ((m,file) in listOf(small,full).withIndex()) {
        OrtBeatThisBackend(file,threads=2).use{backend ->
            for(i in 0..1) {
                println("Analyze detector $m / source $i")
                val a=LocalSongAnalyzer(CandidateRecorder.cached(backend)).analyze(inputs[i])
                songs[i].add(a)
                val dir=File(output,"detector-$m").also{it.mkdirs()}
                CandidateRecorder.audit(a,dir,i)
                File(dir,"track-$i-canonical.csv").writeText("time,strength\n"+a.pulse.beats.joinToString("\n"){"${it.seconds},${it.strength}"}+"\n")
            }
        }
    }
    val collect=System.getProperty("beatweave.collect")=="true"
    try {
        val selected=AutoMixAnalysisEnsemble.bestTransition(songs[0],songs[1],
            searchOptions=AutoMixSearchOptions(cueModel=model),onComparison={i,j ->
                CandidateRecorder.writer?.close();CandidateRecorder.writer=null
                if(collect) {
                    val folder=File(output,"pool-$i-$j").also{it.mkdirs()}
                    CandidateRecorder.writer=File(folder,"candidates.csv").printWriter().also{
                        it.println("a,b,duration,speed,bars,baseline,old_score,"+(0..12).joinToString(","){n->"x$n"})
                    }
                    System.getProperty("beatweave.referenceFile")?.let {
                        CandidateRecorder.reference(songs[0][i],songs[1][j],File(it),File(folder,"approved-features.csv"))
                    }
                }
            })
        val accepted=selected.plan;val p=accepted.mixPlan;val choice=accepted.automaticSelection!!
        File(output,"plan.json").writeText("""{"status":"accepted","a":${p.startSeconds},"b":${p.secondSourceTime(p.startSeconds)},"duration":${p.fadeEndSeconds-p.startSeconds},"bars":${choice.barCount},"outgoing_analysis":${selected.outgoingAnalysis},"incoming_analysis":${selected.incomingAnalysis},"incoming_start_limit_seconds":${choice.search.incomingStartLimitSeconds},"incoming_duration_seconds":${songs[1][selected.incomingAnalysis].audio.durationSeconds},"production_clock_gates":true}"""+"\n")
        File(output,"accepted-map.csv").printWriter().use{w ->
            w.println("output_source_seconds,incoming_source_seconds")
            var t=(p.startSeconds-6).coerceAtLeast(0.0);val end=p.fadeEndSeconds+6
            while(t<end){w.println("$t,${p.secondSourceTime(t)}");t+=.05}
            w.println("$end,${p.secondSourceTime(end)}")
        }
        File(output,"planning-audit.txt").writeText("${selected.attempts}\n$choice\n${accepted.clockFit.report}\n")
        println("ACCEPT ${p.startSeconds} -> ${p.secondSourceTime(p.startSeconds)} / ${choice.barCount} bars / detectors ${selected.outgoingAnalysis},${selected.incomingAnalysis}")
    } catch(e:AutoMixAnalysisException) {
        File(output,"plan.json").writeText("""{"status":"declined","reason":"ALL_ANALYSIS_POOLS_DECLINED","production_clock_gates":true}"""+"\n")
        File(output,"planning-audit.txt").writeText(e.attempts.joinToString("\n"))
        println("DECLINE all pools")
    } finally {CandidateRecorder.writer?.close();CandidateRecorder.writer=null}
}
