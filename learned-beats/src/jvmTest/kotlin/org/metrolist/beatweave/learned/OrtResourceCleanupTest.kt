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

    @Test
    fun retryClosesOnlyResourcesThatPreviouslyFailed() {
        val transient = IllegalStateException("options close failed")
        var sessionCloses = 0
        var optionsCloses = 0
        var otherCloses = 0
        val closer =
            RetryableResourceCloser(
                { sessionCloses++ },
                {
                    optionsCloses++
                    if (optionsCloses == 1) throw transient
                },
                { otherCloses++ },
            )

        assertSame(transient, closer.close())
        assertEquals(1, sessionCloses)
        assertEquals(1, optionsCloses)
        assertEquals(1, otherCloses)

        assertNull(closer.close())
        assertEquals(1, sessionCloses, "successful resources must not be closed twice")
        assertEquals(2, optionsCloses, "the failed close must remain retryable")
        assertEquals(1, otherCloses, "later successful resources must not be closed twice")
        assertNull(closer.close())
        assertEquals(2, optionsCloses)
    }
}
