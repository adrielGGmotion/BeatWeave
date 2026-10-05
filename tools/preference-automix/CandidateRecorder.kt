package org.metrolist.beatweave.learned

import java.io.PrintWriter
import java.io.File
import org.metrolist.beatweave.BarGrid
import org.metrolist.beatweave.BeatGrid

/** Build-time observation only; never changes candidates or acceptance gates. */
internal object CandidateRecorder {
    var writer: PrintWriter? = null
    fun reference(first: LocalSongAnalysis, second: LocalSongAnalysis, input: File, output: File) {
        val v=input.readText().trim().split(',').map(String::toDouble)
        val a=v[0];val b=v[1];val duration=v[2];val speed=v[3];val bars=v[4].toInt()
        fun window(start:Double, length:Double)=BarGrid(
            BeatGrid(DoubleArray(bars*4+1){start+length*it/(bars*4)}),IntArray(bars+1){it*4})
        // This grid is ONLY a container for exact labeled window endpoints.
        // It is never passed to the planner or treated as detected beat truth.
        val ranking=MusicalCueRanking(first.audio,second.audio)
        val exit=ranking.outgoing(window(a,duration),0,bars)
        val entry=ranking.incoming(window(b,duration*speed),0,bars)
        val evidence=ranking.evidence(exit,entry,bars)
        output.writeText(TrainedCueModel.features(exit,entry,evidence,bars,
            first.audio.durationSeconds,second.audio.durationSeconds).joinToString(",")+"\n")
    }
    fun record(out: MusicalCueRanking.Part, into: MusicalCueRanking.Part,
               evidence: MusicalCueEvidence, bars: Int, firstDuration: Double,
               secondDuration: Double, score: Double) {
        writer?.println(listOf(out.from, into.from, out.to-out.from,
            (into.to-into.from)/(out.to-out.from), bars.toDouble(), evidence.score, score)
            .plus(TrainedCueModel.features(out, into, evidence, bars, firstDuration, secondDuration).toList())
            .joinToString(","))
    }
}
