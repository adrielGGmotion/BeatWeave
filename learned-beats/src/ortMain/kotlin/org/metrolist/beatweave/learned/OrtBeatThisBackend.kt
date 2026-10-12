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
    private val closeSequence: RetryableCloseSequence
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
            collectResourceCleanupFailure(failure, sessionOptions::close)
            throw failure
        }
        try {
            require(session.inputNames == setOf("spectrogram")) { "Unexpected model input names" }
            require(session.outputNames == setOf("beat_logits", "downbeat_logits")) {
                "Unexpected model output names"
            }
        } catch (failure: Throwable) {
            collectResourceCleanupFailure(failure, session::close, sessionOptions::close)
            throw failure
        }
        closeSequence = RetryableCloseSequence(session::close, sessionOptions::close)
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
        val completed = AtomicBoolean(false)
        val cancellation = AtomicReference<Throwable?>(null)
        val runOptions = OrtSession.RunOptions()
        var watcher: Thread? = null
        var watcherStarted = false
        var result: BeatThisLogits? = null
        var inferenceFailure: Throwable? = null
        var completionFailure: Throwable? = null
        try {
            val activeWatcher = Thread {
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
            watcher = activeWatcher
            activeWatcher.name = "BeatWeave-ORT-cancellation"
            activeWatcher.isDaemon = true
            activeWatcher.start()
            watcherStarted = true
            result = runInference(logMel, frames, runOptions)
        } catch (failure: Throwable) {
            inferenceFailure = failure
        } finally {
            completed.set(true)
            completionFailure =
                collectResourceCleanupFailure(
                    null,
                    {
                        if (watcherStarted) {
                            val activeWatcher = checkNotNull(watcher)
                            LockSupport.unpark(activeWatcher)
                            joinUninterruptibly(activeWatcher)
                        }
                    },
                    runOptions::close,
                )
        }
        val primaryFailure = cancellation.get() ?: inferenceFailure
        if (primaryFailure != null) {
            completionFailure?.let {
                if (it !== primaryFailure) primaryFailure.addSuppressed(it)
            }
            throw primaryFailure
        }
        completionFailure?.let { throw it }
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
        // Reject inference as soon as shutdown starts, but keep retrying any resource whose close
        // failed. SessionOptions must remain alive until the session has closed successfully.
        closed = true
        closeSequence.close()?.let { throw it }
        // OrtEnvironment is a JVM-wide singleton owned by ORT. Do not close it here.
    }
}

/** Close dependent resources in order, resuming at the first failed action on the next call. */
internal class RetryableCloseSequence(vararg actions: () -> Unit) {
    private val actions = actions.copyOf()
    private var next = 0

    fun close(): Throwable? {
        while (next < actions.size) {
            try {
                actions[next]()
                next++
            } catch (failure: Throwable) {
                return failure
            }
        }
        return null
    }
}

/** Run every owned-resource cleanup while retaining an existing primary failure, if any. */
internal fun collectResourceCleanupFailure(
    primaryFailure: Throwable?,
    vararg cleanup: () -> Unit,
): Throwable? {
    var failure = primaryFailure
    for (action in cleanup) {
        try {
            action()
        } catch (cleanupFailure: Throwable) {
            val previous = failure
            if (previous == null) failure = cleanupFailure
            else if (cleanupFailure !== previous) previous.addSuppressed(cleanupFailure)
        }
    }
    return failure
}

private const val CANCELLATION_POLL_NANOS = 25_000_000L
