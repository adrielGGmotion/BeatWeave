import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import org.metrolist.beatweave.*
import org.metrolist.beatweave.learned.BeatThisFrontend
import org.metrolist.beatweave.learned.OrtBeatThisBackend
import org.metrolist.beatweave.rubberband.RubberBandEngine

private const val stressRate = 44100
private const val stressSeconds = 6.25
private const val mebibyte = 1024L * 1024L

/**
 * Linux/JVM resource regression, independent of a UI or emulator. Each playlist item owns its
 * prepared stem, while one process-wide model backend is reused. Run with -Xmx256m -XX:+UseSerialGC
 * for a reproducible, intentionally constrained heap. Timings are measurements; no CPU-throughput
 * target determines pass/fail.
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "ResourceStressKt output-directory model.onnx [cycles=48]" }
    val output = File(args[0]).apply { mkdirs() }
    val cycles = args.getOrNull(2)?.toInt() ?: 48
    require(cycles in 24..512) { "At least 24 cycles are required for the resource regression" }
    val cache =
        File(System.getProperty("beatweave.stress.cache") ?: File(output, "owned-cache").path)
            .apply { mkdirs() }
    require(cache.listFiles().orEmpty().isEmpty()) { "Use an empty stress output cache" }
    val cacheFreeBefore = cache.usableSpace
    val sentinel = File(cache, "caller-owned.txt").apply { writeText("caller-owned sentinel\n") }
    val sentinelContent = sentinel.readText()
    val snapshots = mutableListOf<ResourceSnapshot>()
    var assertions = 0
    var nativePreparations = 0
    var nativePrepareNanos = 0L
    var inferenceCalls = 0
    var inferenceNanos = 0L
    var cancelledStudy = 0
    var cancelledNativeRender = 0
    var cancelledPlayback = 0
    var maxReadFrames = 0
    var peakOwnedCacheBytes = 0L
    fun verify(condition: Boolean, message: String) {
        check(condition) { message }
        assertions++
    }
    fun ownedFiles() = cache.listFiles().orEmpty().filter { it != sentinel }.sortedBy { it.name }
    fun measureCache() {
        peakOwnedCacheBytes = maxOf(peakOwnedCacheBytes, ownedFiles().sumOf { it.length() })
    }
    fun cacheDescriptors(): Int =
        File("/proc/self/fd").listFiles().orEmpty().count {
            try {
                Files.readSymbolicLink(it.toPath()).toString().startsWith(cache.canonicalPath + "/")
            } catch (_: java.io.IOException) {
                false
            }
        }
    fun clean() {
        verify(ownedFiles().isEmpty(), "An owned preparation file leaked: ${ownedFiles()}")
        verify(
            sentinel.isFile && sentinel.readText() == sentinelContent,
            "Caller-owned cache content was changed",
        )
        verify(cacheDescriptors() == 0, "A closed preparation still owns an open cache descriptor")
    }
    fun observe(label: String): ResourceSnapshot {
        // GC is diagnostic, never the only leak oracle: owned files, open descriptors,
        // closed-state behavior and per-iteration cleanup are independently asserted.
        repeat(3) {
            System.gc()
            Thread.sleep(25)
        }
        val runtime = Runtime.getRuntime()
        val status = File("/proc/self/status").takeIf { it.isFile }?.readLines().orEmpty()
        fun kilobytes(name: String) =
            status
                .firstOrNull { it.startsWith("$name:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.get(1)
                ?.toLong()
                ?.times(1024)
        val rss = kilobytes("VmRSS")
        val rssHighWater = kilobytes("VmHWM")
        val fd =
            (ManagementFactory.getOperatingSystemMXBean()
                    as? com.sun.management.UnixOperatingSystemMXBean)
                ?.openFileDescriptorCount
        return ResourceSnapshot(
                label,
                runtime.totalMemory() - runtime.freeMemory(),
                rss,
                rssHighWater,
                fd,
            )
            .also(snapshots::add)
    }
    val outgoing = StressPcm(220.0)
    val incoming = StressPcm(440.0)
    val alternate = StressPcm(659.2551138)
    val first = BeatGrid(DoubleArray(16) { .2 + it * 60.0 / 156.0 })
    val second = BeatGrid(DoubleArray(16) { .31 + it * 60.0 / 155.0 })
    val plan = MixPlan(first, second, 3, 2, 8, stressRate)
    val mixer = BeatMixer(RubberBandEngine(cache))
    val executor = Executors.newFixedThreadPool(2)
    fun prepare(source: StressPcm = incoming): PreparedMix {
        val start = System.nanoTime()
        var sampledStage = -1
        val result =
            mixer.prepare(
                outgoing,
                source,
                plan,
                progress = { progress ->
                    val stage = (progress * 8).toInt()
                    if (stage != sampledStage) {
                        measureCache()
                        sampledStage = stage
                    }
                },
            )
        nativePrepareNanos += System.nanoTime() - start
        nativePreparations++
        maxReadFrames = maxOf(maxReadFrames, source.largestRead)
        verify(
            !result.schedule.isTranslationOnly,
            "Resource test accidentally bypassed the native stretcher",
        )
        return result
    }
    fun closeAndCheck(session: PreparedMix) {
        session.close()
        session.close()
        var rejected = false
        try {
            session.readIncoming(0, 1)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        verify(rejected, "A closed native session remained readable")
    }
    fun seekAndRender(session: PreparedMix, seed: Int): String {
        val random = Random(seed)
        val start = session.schedule.outputOriginFrame
        val end = start + session.schedule.outputFrames
        val digest = MessageDigest.getInstance("SHA-256")
        val offsets = LongArray(12) { start + random.nextLong(session.schedule.outputFrames) }
        val reference =
            offsets.map { offset -> session.readIncoming(offset, min(1024L, end - offset).toInt()) }
        for (index in offsets.indices.reversed()) {
            val actual = session.readIncoming(offsets[index], reference[index].size / 2)
            verify(reference[index].contentEquals(actual), "Random seeks changed prepared samples")
            verify(actual.all { it.isFinite() }, "A random seek returned non-finite samples")
            actual.forEach { value ->
                val bits = value.toRawBits()
                repeat(4) { digest.update((bits ushr (it * 8)).toByte()) }
            }
        }
        verify(
            session.readIncoming(start - 16, 16).all { it == 0f },
            "Pre-source reads are not zero padded",
        )
        verify(
            session.readIncoming(end, 16).all { it == 0f },
            "Post-source reads are not zero padded",
        )
        var written = 0L
        session.renderComplete(
            object : PcmSink {
                override fun write(interleavedStereo: FloatArray, frames: Int) {
                    verify(
                        frames in 1..1024 && interleavedStereo.size == frames * 2,
                        "Invalid render block",
                    )
                    verify(interleavedStereo.all { it.isFinite() }, "Non-finite mixed output")
                    written += frames
                }
            },
            MixMode.OVERLAP,
        )
        verify(
            written == session.frameCount(MixMode.OVERLAP),
            "Complete rendering lost or added frames",
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun cancelPreparation(threshold: Double) {
        var cancelled = false
        var requested = false
        var lastProgress = 0.0
        try {
            val unexpected =
                mixer.prepare(outgoing, incoming, plan, { requested }) { progress ->
                    lastProgress = progress
                    if (progress >= threshold) {
                        measureCache()
                        requested = true
                    }
                }
            unexpected.close()
        } catch (_: MixCancelledException) {
            cancelled = true
        }
        verify(requested && cancelled, "Preparation did not honor cancellation at $threshold")
        verify(
            lastProgress >= threshold && lastProgress < if (threshold < .45) .45 else 1.0,
            "Cancellation missed its intended native pass",
        )
        if (threshold < .45) cancelledStudy++ else cancelledNativeRender++
        clean()
    }
    fun playbackCancellation() {
        var requested = false
        val session = mixer.prepare(outgoing, incoming, plan, { requested })
        var writes = 0
        var cancelled = false
        try {
            session.renderComplete(
                object : PcmSink {
                    override fun write(interleavedStereo: FloatArray, frames: Int) {
                        writes++
                        requested = true
                    }
                },
                MixMode.OVERLAP,
            )
        } catch (_: MixCancelledException) {
            cancelled = true
        } finally {
            closeAndCheck(session)
        }
        verify(cancelled && writes == 1, "Playback did not cancel before writing the next block")
        cancelledPlayback++
        clean()
    }
    fun concurrentSessions() {
        // Preparation counters and assertion counts are only changed on this thread.
        // Separate sessions may run in parallel; sharing one PreparedMix is not supported.
        val jobs =
            listOf(incoming, alternate).map { source ->
                Callable { mixer.prepare(outgoing, source, plan) }
            }
        val prepared = mutableListOf<PreparedMix>()
        try {
            var preparationFailure: Throwable? = null
            executor.invokeAll(jobs).forEach { future ->
                try {
                    prepared += future.get()
                } catch (failure: Throwable) {
                    if (preparationFailure == null) preparationFailure = failure
                }
            }
            preparationFailure?.let { throw it }
            nativePreparations += prepared.size
            measureCache()
            verify(
                ownedFiles().size == 2,
                "Independent preparations did not retain distinct owned stems",
            )
            verify(
                cacheDescriptors() == 2,
                "Each prepared stem must retain exactly one file descriptor",
            )
            val reads =
                executor
                    .invokeAll(
                        prepared.map { session ->
                            Callable {
                                val offset = session.schedule.outputOriginFrame + stressRate
                                val expected = session.readIncoming(offset, 4096)
                                repeat(16) {
                                    check(
                                        expected.contentEquals(session.readIncoming(offset, 4096))
                                    )
                                }
                                expected
                            }
                        }
                    )
                    .map { it.get() }
            verify(
                !reads[0].contentEquals(reads[1]),
                "Concurrent sessions returned another session's audio",
            )
            closeAndCheck(prepared[0])
            verify(
                ownedFiles().size == 1 && cacheDescriptors() == 1,
                "Closing one session damaged the other session's resources",
            )
            verify(
                reads[1].contentEquals(
                    prepared[1].readIncoming(
                        prepared[1].schedule.outputOriginFrame + stressRate,
                        4096,
                    )
                ),
                "Closing one session changed another's PCM",
            )
        } finally {
            prepared.forEach { it.close() }
        }
        clean()
    }
    var modelClosed = false
    val backend = OrtBeatThisBackend(File(args[1]), threads = 2)
    try {
        val mono =
            FloatArray(22050 * 6) { frame ->
                val t = frame / 22050.0
                val attack = ((frame % 8520).toDouble() / 90).let { kotlin.math.exp(-it) }
                (.12 * sin(2 * PI * 220 * t) + .35 * attack * sin(2 * PI * 1300 * t)).toFloat()
            }
        val mel = BeatThisFrontend().transform(mono)
        val inferenceFrames = intArrayOf(64, 151, mel.frames, 1500)
        // copyOf zero-pads the synthetic spectrogram to the production 1500-frame
        // chunk shape; small shapes use real frontend output from the same PCM.
        val inferenceInputs = inferenceFrames.map { mel.values.copyOf(it * 128) }
        val references =
            inferenceFrames.indices.map { backend.infer(inferenceInputs[it], inferenceFrames[it]) }
        fun inference(shape: Int) {
            val start = System.nanoTime()
            val actual = backend.infer(inferenceInputs[shape], inferenceFrames[shape])
            inferenceNanos += System.nanoTime() - start
            inferenceCalls++
            verify(
                actual.beat.contentEquals(references[shape].beat) &&
                    actual.downbeat.contentEquals(references[shape].downbeat),
                "Reusing one ONNX backend changed identical-input inference",
            )
            verify(
                actual.beat.all { it.isFinite() } && actual.downbeat.all { it.isFinite() },
                "Non-finite ONNX output",
            )
        }
        repeat(inferenceFrames.size * 2) { warmup ->
            val session = prepare()
            try {
                seekAndRender(session, warmup)
            } finally {
                closeAndCheck(session)
            }
            inference(warmup % inferenceFrames.size)
            clean()
        }
        concurrentSessions()
        cancelPreparation(.10)
        cancelPreparation(.60)
        playbackCancellation()
        val baseline = observe("after_warmup")
        verify(
            baseline.rss != null && baseline.descriptors != null,
            "Linux RSS and file-descriptor accounting are required",
        )
        var stableDigest: String? = null
        repeat(cycles) { index ->
            val session = prepare()
            try {
                verify(
                    ownedFiles().size == 1 && cacheDescriptors() == 1,
                    "Unexpected live preparation ownership",
                )
                val digest = seekAndRender(session, 1234)
                if (stableDigest == null) stableDigest = digest
                else
                    verify(
                        digest == stableDigest,
                        "Repeated independent preparations changed identical-input PCM",
                    )
            } finally {
                closeAndCheck(session)
            }
            inference(index % inferenceFrames.size)
            clean()
            if ((index + 1) % 8 == 0) {
                cancelPreparation(.10)
                cancelPreparation(.60)
                playbackCancellation()
                val current = observe("after_${index + 1}_items")
                verify(
                    current.descriptors!! <= baseline.descriptors!! + 2,
                    "Open descriptor count grew after closing playlist items: ${current.descriptors} vs ${baseline.descriptors}",
                )
                verify(
                    current.heap <= baseline.heap + 16 * mebibyte,
                    "Retained JVM heap grew by more than 16 MiB after warmup",
                )
                verify(
                    current.rss!! <= baseline.rss!! + 128 * mebibyte,
                    "Process RSS grew by more than 128 MiB after warmup",
                )
                println(
                    "Resource stress: ${index + 1}/$cycles items; heap=${current.heap}; rss=${current.rss}; fds=${current.descriptors}"
                )
            }
        }
        concurrentSessions()
        // A failing sink must release its owned native preparation automatically.
        class SinkFailure : RuntimeException()
        var sinkFailed = false
        try {
            mixer.render(
                outgoing,
                incoming,
                plan,
                object : PcmSink {
                    override fun write(interleavedStereo: FloatArray, frames: Int) {
                        throw SinkFailure()
                    }
                },
                MixMode.OVERLAP,
            )
        } catch (_: SinkFailure) {
            sinkFailed = true
        }
        verify(sinkFailed, "Sink failure did not propagate")
        clean()
        backend.close()
        backend.close()
        modelClosed = true
        var rejected = false
        try {
            backend.infer(mel.values, mel.frames)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        verify(rejected, "A closed ONNX backend remained usable")
        executor.shutdown()
        verify(
            executor.awaitTermination(30, TimeUnit.SECONDS),
            "Worker threads did not terminate after shutdown",
        )
        val final = observe("all_owned_resources_closed")
        verify(
            final.descriptors!! <= baseline.descriptors!! + 2,
            "Descriptors leaked after model and session closure",
        )
        verify(
            final.heap <= baseline.heap + 16 * mebibyte,
            "Retained heap exceeded its post-close bound",
        )
        verify(final.rss!! <= baseline.rss!! + 128 * mebibyte, "RSS exceeded its post-close bound")
        verify(maxReadFrames <= 4096, "Native preparation materialized an oversized source block")
        clean()
        File(output, "resource-stress.json")
            .writeText(
                """{
          "status":"passed","fixture":"deterministic synthetic PCM","cycles":$cycles,
          "host":{"java_version":${stressJsonString(System.getProperty("java.version"))},"os_name":${stressJsonString(System.getProperty("os.name"))},"os_arch":${stressJsonString(System.getProperty("os.arch"))},"available_processors":${Runtime.getRuntime().availableProcessors()},"max_heap_bytes":${Runtime.getRuntime().maxMemory()}},
          "source_seconds_per_item":$stressSeconds,"sample_rate":$stressRate,
          "native_preparations_completed_at_least":$nativePreparations,"measured_native_prepare_seconds":${nativePrepareNanos / 1e9},
          "one_reused_onnx_backend":true,"measured_inference_calls":$inferenceCalls,"measured_inference_seconds":${inferenceNanos / 1e9},
          "inference_frame_shapes":[${inferenceFrames.joinToString(",")}],"each_shape_warmed_before_baseline":true,
          "study_cancellations":$cancelledStudy,"native_render_cancellations":$cancelledNativeRender,"playback_cancellations":$cancelledPlayback,
          "concurrent_session_pairs":2,"same_input_pcm_bit_exact":true,"same_input_inference_bit_exact":true,
          "owned_cache_files_after_close":0,"owned_cache_descriptors_after_close":0,"caller_sentinel_preserved":true,
          "sampled_peak_owned_cache_bytes":$peakOwnedCacheBytes,
          "cache_storage":{"total_bytes":${cache.totalSpace},"usable_bytes_before_run":$cacheFreeBefore,"usable_bytes_after_owned_close":${cache.usableSpace}},
          "process_peak_rss_bytes":${snapshots.mapNotNull { it.rssHighWater }.maxOrNull()},
          "maximum_source_read_frames":$maxReadFrames,"assertions":$assertions,
          "bounds":{"fd_growth":2,"retained_heap_growth_bytes":${16 * mebibyte},"rss_growth_bytes":${128 * mebibyte}},
          "timings_are_pass_fail_criteria":false,"physical_android_tested":false,"emulator_used":false,
          "snapshots":[${snapshots.joinToString(",") { it.json() }}]
        }"""
                    .trimIndent() + "\n"
            )
        println(
            "Resource stress passed: $assertions assertions, $cycles playlist items, one reused ONNX backend"
        )
    } finally {
        if (!modelClosed) backend.close()
        executor.shutdownNow()
    }
}

private data class ResourceSnapshot(
    val label: String,
    val heap: Long,
    val rss: Long?,
    val rssHighWater: Long?,
    val descriptors: Long?,
) {
    fun json() =
        """{"label":"$label","retained_heap_bytes":$heap,"rss_bytes":$rss,"rss_high_water_bytes":$rssHighWater,"open_file_descriptors":$descriptors}"""
}

private fun stressJsonString(value: String) =
    "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") +
        "\""

private class StressPcm(private val frequency: Double) : StereoPcm {
    override val durationSeconds = stressSeconds
    var largestRead = 0
        private set

    override fun read(startSeconds: Double, frames: Int, outputSampleRate: Int) =
        readFrames((startSeconds * outputSampleRate).roundToLong(), frames, outputSampleRate)

    override fun readFrames(startFrame: Long, frames: Int, outputSampleRate: Int): FloatArray {
        require(outputSampleRate == stressRate)
        largestRead = maxOf(largestRead, frames)
        return FloatArray(frames * 2) { index ->
            val t = (startFrame + index / 2).toDouble() / outputSampleRate
            if (t < 0 || t >= durationSeconds) 0f
            else {
                val signal =
                    .25 * sin(2 * PI * frequency * t) + .08 * sin(2 * PI * frequency * 2 * t)
                (if (index % 2 == 0) signal else -.6 * signal).toFloat()
            }
        }
    }
}
