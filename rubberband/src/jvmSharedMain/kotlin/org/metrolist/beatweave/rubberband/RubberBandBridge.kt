/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.metrolist.beatweave.rubberband

import java.nio.file.Files

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
        val file = Files.createTempFile("beatweave-rubberband-", ".so")
        try {
            resource.use { input ->
                Files.newOutputStream(file).use { input.copyTo(it) }
            }
            System.load(file.toAbsolutePath().toString())
        } finally {
            // Linux keeps the loaded inode alive after unlinking it. Remove the extracted copy
            // immediately so an abnormal JVM exit cannot leave one native binary per process.
            // Retain the old exit-time cleanup as a fallback for an unusual filesystem failure.
            val extracted = file.toFile()
            if (!extracted.delete() && extracted.exists()) extracted.deleteOnExit()
        }
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
