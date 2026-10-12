/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.metrolist.beatweave.rubberband

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal object RubberBandBridge {
    private const val NATIVE_LIBRARY = "beatweave_rubberband"
    private const val LINUX_X86_64_LIBRARY =
        "/META-INF/native/linux-x86_64/libbeatweave_rubberband.so"

    init {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            System.loadLibrary("beatweave_rubberband")
        } else {
            loadJvmNative()
        }
    }

    private fun loadJvmNative() {
        try {
            System.loadLibrary(NATIVE_LIBRARY)
        } catch (failure: UnsatisfiedLinkError) {
            if (System.getProperty("os.name") != "Linux" ||
                System.getProperty("os.arch").orEmpty() !in setOf("amd64", "x86_64")) {
                throw failure
            }
            loadBundledLinuxX86_64Library(failure)
        }
    }

    private fun loadBundledLinuxX86_64Library(failure: UnsatisfiedLinkError) {
        val resource = RubberBandBridge::class.java.getResourceAsStream(LINUX_X86_64_LIBRARY)
            ?: throw failure
        loadBundledLinuxX86_64Library(resource)
    }

    external fun create(sampleRate: Int, sourceFrames: Long, outputFrames: Long): Long

    external fun createWithPitch(
        sampleRate: Int,
        sourceFrames: Long,
        outputFrames: Long,
        pitchRatio: Double,
        preserveFormants: Boolean,
    ): Long

    external fun destroy(handle: Long)

    external fun engineVersion(handle: Long): Int

    external fun setKeyFrames(handle: Long, source: LongArray, output: LongArray)

    external fun study(handle: Long, interleaved: FloatArray, frames: Int, finalBlock: Boolean)

    external fun process(handle: Long, interleaved: FloatArray, frames: Int, finalBlock: Boolean)

    external fun available(handle: Long): Int

    external fun retrieve(handle: Long, interleaved: FloatArray, maxFrames: Int): Int
}

internal fun loadBundledLinuxX86_64Library(
    resource: InputStream,
    createTempFile: () -> Path = { Files.createTempFile("beatweave-rubberband-", ".so") },
    loadLibrary: (Path) -> Unit = { System.load(it.toAbsolutePath().toString()) },
) {
    resource.use { input ->
        // Keep temp-file creation inside the resource's ownership scope. A missing or unwritable
        // java.io.tmpdir must not leak the JAR entry stream before a file exists to clean up.
        val file = createTempFile()
        try {
            Files.newOutputStream(file).use { input.copyTo(it) }
            loadLibrary(file)
        } finally {
            // Linux keeps the loaded inode alive after unlinking it. Remove the extracted copy
            // immediately so an abnormal JVM exit cannot leave one native binary per process.
            // Retain the old exit-time cleanup as a fallback for an unusual filesystem failure.
            val extracted = file.toFile()
            if (!extracted.delete() && extracted.exists()) extracted.deleteOnExit()
        }
    }
}
