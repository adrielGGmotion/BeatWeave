package org.metrolist.beatweave.learned

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Fully local CPU inference. Owns its session; use .use { ... } or close(). Inference and close are
 * serialized to prevent use-after-close. Model bytes are checked against a bundled, reproducibly
 * exported MIT model before opening a session. Python is not needed by consumers. The application
 * supplies local model bytes.
 */
class OrtBeatThisBackend(modelBytes: ByteArray, threads: Int = 2) :
    CancellableBeatThisBackend, Closeable {
    constructor(modelFile: java.io.File, threads: Int = 2) : this(modelFile.readBytes(), threads)

    companion object {
        const val MODEL_SHA256 = "b6d54bca156b039593b6d9d48fd3ab3e5d09be06bbbe2706f0376c9f103d5191"
        const val CHECKPOINT_SHA256 =
            "6074be2c4d490c5f6101fcc374a1ec72ae93456e23bb6019783b849f5dc7d47b"
    }

    val modelVariant: BeatThisModelVariant
    override val modelId: String
        get() = modelVariant.modelId

    private val environment: OrtEnvironment
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession
    private var closed = false

    init {
        require(threads in 1..8) { "threads must be in 1..8" }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(modelBytes).joinToString("") {
                "%02x".format(it)
            }
        modelVariant =
            BeatThisModelVariant.entries.firstOrNull { it.sha256 == digest }
                ?: throw IllegalArgumentException("Beat This model checksum mismatch: $digest")
        environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        // ORT requires SessionOptions to outlive every session created from them.
        sessionOptions = OrtSession.SessionOptions()
        try {
            sessionOptions.setIntraOpNumThreads(threads)
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Dynamic attention buffers otherwise stay in the CPU arena, and
            // cached shape patterns can allocate a second large working set.
            // These allocation policies preserve the full 1500-frame context.
            sessionOptions.setCPUArenaAllocator(false)
            sessionOptions.setMemoryPatternOptimization(false)
            session = environment.createSession(modelBytes, sessionOptions)
        } catch (failure: Throwable) {
            sessionOptions.close()
            throw failure
        }
        try {
            require(session.inputNames == setOf("spectrogram")) { "Unexpected model input names" }
            require(session.outputNames == setOf("beat_logits", "downbeat_logits")) {
                "Unexpected model output names"
            }
        } catch (failure: Throwable) {
            try {
                session.close()
            } finally {
                sessionOptions.close()
            }
            throw failure
        }
    }

    @Synchronized
    override fun infer(logMel: FloatArray, frames: Int): BeatThisLogits {
        check(!closed) { "BeatThisBackend is closed" }
        validateInput(logMel, frames)
        return runInference(logMel, frames, null)
    }

    @Synchronized
    override fun infer(
        logMel: FloatArray,
        frames: Int,
        cancellationCheck: () -> Unit,
    ): BeatThisLogits {
        check(!closed) { "BeatThisBackend is closed" }
        validateInput(logMel, frames)
        cancellationCheck()
        val runOptions = OrtSession.RunOptions()
        val completed = AtomicBoolean(false)
        val cancellation = AtomicReference<Throwable?>(null)
        val watcher =
            Thread {
                while (!completed.get()) {
                    LockSupport.parkNanos(CANCELLATION_POLL_NANOS)
                    if (completed.get()) break
                    try {
                        cancellationCheck()
                    } catch (failure: Throwable) {
                        if (cancellation.compareAndSet(null, failure)) {
                            try {
                                runOptions.setTerminate(true)
                            } catch (terminationFailure: Throwable) {
                                failure.addSuppressed(terminationFailure)
                            }
                        }
                        break
                    }
                }
            }
        watcher.name = "BeatWeave-ORT-cancellation"
        watcher.isDaemon = true
        var watcherStarted = false
        var result: BeatThisLogits? = null
        var inferenceFailure: Throwable? = null
        try {
            watcher.start()
            watcherStarted = true
            result = runInference(logMel, frames, runOptions)
        } catch (failure: Throwable) {
            inferenceFailure = failure
        } finally {
            completed.set(true)
            if (watcherStarted) {
                LockSupport.unpark(watcher)
                joinUninterruptibly(watcher)
            }
            runOptions.close()
        }
        cancellation.get()?.let { throw it }
        inferenceFailure?.let { throw it }
        cancellationCheck()
        return checkNotNull(result)
    }

    private fun validateInput(logMel: FloatArray, frames: Int) {
        require(frames in 1..1500 && logMel.size == frames * 128)
        require(logMel.all { it.isFinite() })
    }

    private fun runInference(
        logMel: FloatArray,
        frames: Int,
        runOptions: OrtSession.RunOptions?,
    ): BeatThisLogits {
        OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(logMel),
                longArrayOf(1, frames.toLong(), 128),
            )
            .use { tensor ->
                val output =
                    if (runOptions == null) session.run(mapOf("spectrogram" to tensor))
                    else session.run(mapOf("spectrogram" to tensor), runOptions)
                output.use {
                    fun read(name: String): FloatArray {
                        val value =
                            it.get(name).orElseThrow {
                                IllegalStateException("Missing model output $name")
                            } as OnnxTensor
                        val buffer = value.floatBuffer
                        require(buffer.remaining() == frames) { "Unexpected model output shape" }
                        return FloatArray(frames).also { buffer.get(it) }
                    }
                    return BeatThisLogits(read("beat_logits"), read("downbeat_logits"))
                }
            }
    }

    private fun joinUninterruptibly(thread: Thread) {
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try {
                session.close()
            } finally {
                sessionOptions.close()
            }
        }
        // OrtEnvironment is a JVM-wide singleton owned by ORT. Do not close it here.
    }
}

private const val CANCELLATION_POLL_NANOS = 25_000_000L
