/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.metrolist.beatweave.rubberband

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RubberBandBridgeTest {
    @Test
    fun tempFileCreationFailureClosesBundledLibraryResource() {
        var closed = false
        val resource =
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        val expected = IOException("temporary directory is not writable")

        val failure =
            assertFailsWith<IOException> {
                loadBundledLinuxX86_64Library(resource, createTempFile = { throw expected })
            }

        assertSame(expected, failure)
        assertTrue(closed)
    }
}
