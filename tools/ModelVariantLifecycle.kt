import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.sin
import org.metrolist.beatweave.learned.BeatThisLogits
import org.metrolist.beatweave.learned.BeatThisModelVariant
import org.metrolist.beatweave.learned.OrtBeatThisBackend

private const val lifecycleMebibyte = 1024L * 1024L
private const val lifecycleCycles = 3
private const val lifecycleFrames = 151

/**
 * Bounded Linux/JVM session ownership regression for both pinned local models. Run via the offline
 * verification launcher with: java -XX:-UsePerfData -Xmx256m -XX:+UseSerialGC ...
 * ModelVariantLifecycleKt output-directory small0.onnx final0.onnx The parent process owns
 * execution scheduling. This program never downloads a model, launches an emulator, or interprets
 * logits as musical ground truth. Growth gates concern retained resources after
 * largest-FINAL0-shape warmup; they are not a maximum total memory budget or a phone-performance
 * claim.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "ModelVariantLifecycleKt output-directory small0.onnx final0.onnx" }
    val output = File(args[0]).apply { mkdirs() }
    require(output.isDirectory) { "Cannot create result directory" }
    val resultFile = File(output, "model-variant-lifecycle.json")
    require(!resultFile.exists()) {
        "Use a fresh result directory; a stale pass must not survive a failed run"
    }
    check(System.getProperty("os.name") == "Linux") {
        "Linux /proc resource accounting is required"
    }
    val modelFiles =
        linkedMapOf(
            BeatThisModelVariant.SMALL0 to File(args[1]).canonicalFile,
            BeatThisModelVariant.FINAL0 to File(args[2]).canonicalFile,
        )
    require(modelFiles.values.distinct().size == 2) { "Supply both distinct model files" }
    val snapshots = mutableListOf<VariantLifecycleSnapshot>()
    val sessions = mutableListOf<VariantLifecycleSession>()
    var assertions = 0
    fun verify(condition: Boolean, message: String) {
        check(condition) { message }
        assertions++
    }
    val digests =
        modelFiles.mapValues { (_, file) ->
            require(file.isFile) { "Model file is missing: $file" }
            lifecycleFileSha256(file)
        }
    modelFiles.keys.forEach { variant ->
        verify(
            digests.getValue(variant) == variant.sha256,
            "${variant.name} file hash does not match the pinned model",
        )
    }
    verify(
        BeatThisModelVariant.FINAL0.sha256 ==
            "c8293b7b787e73b40ad5e899624dbe08763268031543ef7ebcab53f8e78da66f",
        "This lifecycle regression targets the pinned final0 export",
    )
    verify(
        BeatThisModelVariant.SMALL0.modelId != BeatThisModelVariant.FINAL0.modelId,
        "Different model variants share a cache identity",
    )

    fun openModelDescriptors(): Int =
        File("/proc/self/fd").listFiles().orEmpty().count { descriptor ->
            try {
                val target =
                    Files.readSymbolicLink(descriptor.toPath())
                        .toString()
                        .removeSuffix(" (deleted)")
                modelFiles.values.any { it.path == target }
            } catch (_: java.io.IOException) {
                false
            }
        }
    fun observe(label: String): VariantLifecycleSnapshot {
        // Post-GC heap is only one oracle. Model descriptors, total descriptors,
        // close rejection and reuse after another session closes are checked too.
        repeat(3) {
            System.gc()
            Thread.sleep(25)
        }
        val runtime = Runtime.getRuntime()
        val status = File("/proc/self/status").readLines()
        fun statusBytes(name: String): Long =
            status
                .firstOrNull { it.startsWith("$name:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024) ?: error("Missing Linux $name resource accounting")
        val descriptorCount =
            (ManagementFactory.getOperatingSystemMXBean()
                    as? com.sun.management.UnixOperatingSystemMXBean)
                ?.openFileDescriptorCount ?: error("Unix file-descriptor accounting is required")
        return VariantLifecycleSnapshot(
                label,
                runtime.totalMemory() - runtime.freeMemory(),
                statusBytes("VmRSS"),
                statusBytes("VmHWM"),
                descriptorCount,
                openModelDescriptors(),
            )
            .also(snapshots::add)
    }
    val maxInput =
        FloatArray(1500 * 128) { index ->
            // Deterministic, finite synthetic log-mel input. No copyrighted recording,
            // rhythm annotation, or inferred beat correctness is part of this test.
            val frame = index / 128
            val band = index % 128
            (0.6 +
                    0.24 * sin(frame * 0.071 + band * 0.043) +
                    0.11 * sin(frame * 0.017 - band * 0.091))
                .toFloat()
        }
    val shortInput = maxInput.copyOf(lifecycleFrames * 128)
    val references = mutableMapOf<BeatThisModelVariant, BeatThisLogits>()
    fun inferChecked(backend: OrtBeatThisBackend, input: FloatArray, frames: Int): BeatThisLogits {
        val logits = backend.infer(input, frames)
        verify(
            logits.beat.size == frames && logits.downbeat.size == frames,
            "${backend.modelVariant.name} returned an incorrect output shape",
        )
        verify(
            logits.beat.all { it.isFinite() } && logits.downbeat.all { it.isFinite() },
            "${backend.modelVariant.name} returned non-finite logits",
        )
        return logits
    }
    fun oneSession(
        variant: BeatThisModelVariant,
        label: String,
        maxShapeWarmup: Boolean,
    ): BeatThisLogits {
        val openStarted = System.nanoTime()
        val backend = OrtBeatThisBackend(modelFiles.getValue(variant), threads = 2)
        val openNanos = System.nanoTime() - openStarted
        var inferenceNanos = 0L
        var closeNanos = 0L
        var shortOutput: BeatThisLogits? = null
        try {
            verify(backend.modelVariant == variant, "Model file was assigned to the wrong variant")
            verify(
                backend.modelId == variant.modelId,
                "Model identity omitted or changed its pinned graph/checkpoint",
            )
            if (maxShapeWarmup) {
                val start = System.nanoTime()
                inferChecked(backend, maxInput, 1500)
                inferenceNanos += System.nanoTime() - start
                observe("${label}_after_1500_frames_open")
            }
            val start = System.nanoTime()
            val inferred = inferChecked(backend, shortInput, lifecycleFrames)
            shortOutput = inferred
            inferenceNanos += System.nanoTime() - start
            references[variant]?.let { reference ->
                verify(
                    inferred.beat.contentEquals(reference.beat) &&
                        inferred.downbeat.contentEquals(reference.downbeat),
                    "${variant.name} changed identical-input logits after session reopen",
                )
            }
        } finally {
            val closeStarted = System.nanoTime()
            backend.close()
            closeNanos = System.nanoTime() - closeStarted
        }
        val closeStarted = System.nanoTime()
        backend.close() // Closing an already closed session must be harmless.
        val repeatCloseNanos = System.nanoTime() - closeStarted
        var rejected = false
        try {
            backend.infer(shortInput, lifecycleFrames)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        verify(rejected, "${variant.name} accepted inference after close")
        verify(openModelDescriptors() == 0, "A closed backend retained a model-file descriptor")
        sessions +=
            VariantLifecycleSession(
                label,
                variant.name,
                if (maxShapeWarmup) listOf(1500, lifecycleFrames) else listOf(lifecycleFrames),
                openNanos,
                inferenceNanos,
                closeNanos,
                repeatCloseNanos,
            )
        return checkNotNull(shortOutput)
    }

    references[BeatThisModelVariant.SMALL0] =
        oneSession(BeatThisModelVariant.SMALL0, "small0_warmup", false)
    references[BeatThisModelVariant.FINAL0] =
        oneSession(BeatThisModelVariant.FINAL0, "final0_warmup", true)
    val baseline = observe("both_warmup_sessions_closed")
    verify(baseline.modelDescriptors == 0, "Warmup left a model descriptor open")
    fun bounded(snapshot: VariantLifecycleSnapshot) {
        verify(snapshot.modelDescriptors == 0, "${snapshot.label}: model descriptor leaked")
        verify(
            snapshot.descriptors <= baseline.descriptors + 2,
            "${snapshot.label}: total open descriptors grew by more than two",
        )
        verify(
            snapshot.heap <= baseline.heap + 16 * lifecycleMebibyte,
            "${snapshot.label}: retained heap grew by more than 16 MiB after max-shape warmup",
        )
        verify(
            snapshot.rss <= baseline.rss + 128 * lifecycleMebibyte,
            "${snapshot.label}: RSS grew by more than 128 MiB after max-shape warmup",
        )
    }
    repeat(lifecycleCycles) { cycle ->
        modelFiles.keys.forEach { variant ->
            val label = "${variant.name.lowercase()}_reopen_${cycle + 1}"
            oneSession(variant, label, false)
            val snapshot = observe("${label}_closed")
            bounded(snapshot)
            println(
                "Variant lifecycle: $label; heap=${snapshot.heap}; rss=${snapshot.rss}; fds=${snapshot.descriptors}"
            )
        }
    }
    val finalSnapshot = observe("all_sessions_closed")
    bounded(finalSnapshot)
    // Also prove the supplied artifacts were not modified during native inference.
    modelFiles.forEach { (variant, file) ->
        verify(
            lifecycleFileSha256(file) == digests.getValue(variant),
            "${variant.name} artifact changed during the test",
        )
    }
    val modelJson =
        modelFiles.keys.joinToString(",") { variant ->
            """{"variant":${lifecycleJson(variant.name)},"asset_name":${lifecycleJson(variant.assetFileName)},"bytes":${modelFiles.getValue(variant).length()},"sha256":${lifecycleJson(digests.getValue(variant))},"checkpoint_sha256":${lifecycleJson(variant.checkpointSha256)},"model_id":${lifecycleJson(variant.modelId)},"reference_151_logits_sha256":${lifecycleJson(lifecycleLogitsSha256(references.getValue(variant)))}}"""
        }
    resultFile.writeText(
        """{
      "status":"passed","scope":"Linux JVM model session lifecycle; deterministic synthetic log-mel input",
      "host":{"java_version":${lifecycleJson(System.getProperty("java.version"))},"os_name":${lifecycleJson(System.getProperty("os.name"))},"os_arch":${lifecycleJson(System.getProperty("os.arch"))},"available_processors":${Runtime.getRuntime().availableProcessors()},"max_heap_bytes":${Runtime.getRuntime().maxMemory()}},
      "models":[$modelJson],"reopen_cycles_per_variant":$lifecycleCycles,"sessions_opened_and_closed":${sessions.size},
      "final0_1500_frame_warmup_calls":1,"post_warmup_inference_frames":$lifecycleFrames,
      "model_descriptors_after_close":0,"same_input_reopened_logits_bit_exact":true,"close_idempotent":true,"inference_after_close_rejected":true,
      "jvm_wide_ort_environment_reused":true,"process_peak_rss_bytes":${snapshots.maxOf { maxOf(it.rss, it.rssHighWater) }},
      "bounds":{"fd_growth":2,"retained_heap_growth_bytes":${16 * lifecycleMebibyte},"rss_growth_bytes":${128 * lifecycleMebibyte}},
      "bounds_scope":"Post-close retained-resource growth after largest FINAL0-shape warmup; not a total memory ceiling or phone-performance claim",
      "timings_are_pass_fail_criteria":false,"musical_accuracy_tested":false,"physical_android_tested":false,"emulator_used":false,
      "assertions":$assertions,"sessions":[${sessions.joinToString(",") { it.json() }}],
      "snapshots":[${snapshots.joinToString(",") { it.json() }}]
    }"""
            .trimIndent() + "\n"
    )
    println(
        "Model variant lifecycle passed: $assertions assertions; ${sessions.size} sessions; three reopens per variant"
    )
}

private data class VariantLifecycleSnapshot(
    val label: String,
    val heap: Long,
    val rss: Long,
    val rssHighWater: Long,
    val descriptors: Long,
    val modelDescriptors: Int,
) {
    fun json() =
        """{"label":${lifecycleJson(label)},"retained_heap_bytes":$heap,"rss_bytes":$rss,"rss_high_water_bytes":$rssHighWater,"open_file_descriptors":$descriptors,"model_file_descriptors":$modelDescriptors}"""
}

private data class VariantLifecycleSession(
    val label: String,
    val variant: String,
    val frames: List<Int>,
    val openNanos: Long,
    val inferenceNanos: Long,
    val closeNanos: Long,
    val repeatCloseNanos: Long,
) {
    fun json() =
        """{"label":${lifecycleJson(label)},"variant":${lifecycleJson(variant)},"inference_frames":[${frames.joinToString(",")}],"open_seconds":${openNanos / 1e9},"inference_seconds":${inferenceNanos / 1e9},"close_seconds":${closeNanos / 1e9},"repeat_close_seconds":${repeatCloseNanos / 1e9}}"""
}

private fun lifecycleFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun lifecycleLogitsSha256(logits: BeatThisLogits): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (values in listOf(logits.beat, logits.downbeat)) for (value in values) {
        val bits = value.toRawBits()
        repeat(4) { digest.update((bits ushr (8 * it)).toByte()) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun lifecycleJson(value: String) =
    "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") +
        "\""
