package org.metrolist.beatweave.learned

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OrtResourceCleanupTest {
    @Test
    fun primaryFailureSurvivesEveryCleanupFailure() {
        val primary = IllegalArgumentException("invalid model outputs")
        val sessionFailure = IllegalStateException("session close failed")
        val optionsFailure = IllegalStateException("options close failed")
        var cleanups = 0

        val result =
            collectResourceCleanupFailure(
                primary,
                {
                    cleanups++
                    throw sessionFailure
                },
                {
                    cleanups++
                    throw optionsFailure
                },
            )

        assertSame(primary, result)
        assertEquals(listOf(sessionFailure, optionsFailure), primary.suppressedExceptions.toList())
        assertEquals(2, cleanups)
    }

    @Test
    fun firstCleanupFailureSurvivesAndLaterResourcesStillClose() {
        val first = IllegalStateException("session close failed")
        val second = IllegalStateException("options close failed")
        var cleanups = 0

        val result =
            collectResourceCleanupFailure(
                null,
                {
                    cleanups++
                    throw first
                },
                {
                    cleanups++
                    throw second
                },
            )

        assertSame(first, result)
        assertEquals(listOf(second), first.suppressedExceptions.toList())
        assertEquals(2, cleanups)
        assertNull(collectResourceCleanupFailure(null, {}, {}))
    }
}
