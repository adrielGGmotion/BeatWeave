package org.metrolist.beatweave.learned

import java.io.PrintWriter
import java.io.File
import org.metrolist.beatweave.BarGrid
import org.metrolist.beatweave.BeatGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Build-time observation only; never changes candidates or acceptance gates. */
internal object CandidateRecorder {
    var writer: PrintWriter? = null
    fun cached(backend: BeatThisBackend): BeatThisBackend {
        val directory=System.getProperty("beatweave.logitCache")?.let{File(it).also{d->d.mkdirs()}}
            ?: return backend
        return object:BeatThisBackend {
            override val modelId=backend.modelId
            override fun infer(logMel:FloatArray,frames:Int):BeatThisLogits {
                val input=ByteBuffer.allocate(4+logMel.size*4).order(ByteOrder.LITTLE_ENDIAN)
                input.putInt(frames);logMel.forEach{input.putFloat(it)}
                val digest=MessageDigest.getInstance("SHA-256")
                digest.update(modelId.toByteArray());digest.update(input.array())
                val key=digest.digest().joinToString(""){"%02x".format(it)}
                val file=File(directory,"$key.bin")
                if(file.exists()) {
                    val bytes=file.readBytes();require(bytes.size==frames*8)
                    val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    return BeatThisLogits(FloatArray(frames){b.float},FloatArray(frames){b.float})
                }
                val value=backend.infer(logMel,frames)
                val b=ByteBuffer.allocate(frames*8).order(ByteOrder.LITTLE_ENDIAN)
                value.beat.forEach{b.putFloat(it)};value.downbeat.forEach{b.putFloat(it)}
                file.writeBytes(b.array());return value
            }
        }
    }
    fun audit(song:LocalSongAnalysis,directory:File,index:Int) {
        val tracking=song.barTracking?:return
        File(directory,"track-$index-bar-support.csv").printWriter().use{w ->
            w.println("bar,start,end,centered_usable,issues")
            for(bar in 0 until tracking.barCount)w.println("$bar,${tracking.boundaries[bar].seconds},${tracking.boundaries[bar+1].seconds},${tracking.isBarUsable(bar)},${tracking.issuesForBar(bar).joinToString("|")}")
        }
        File(directory,"track-$index-pulse-selection.txt").writeText("${song.pulseSelection}\n")
        song.acousticPulse?.let{e ->
            File(directory,"track-$index-acoustic.csv").printWriter().use{w ->
                w.println("window_start,window_end,active_fraction,p_value,supported")
                e.windows.forEach{w.println("${it.startBeat},${it.endBeatExclusive},${it.activeIntervalFraction},${it.pValue},${it.supported}")}
            }
        }
    }
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
