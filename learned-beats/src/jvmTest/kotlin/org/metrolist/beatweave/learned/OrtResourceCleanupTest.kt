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
    fun failedSessionCloseKeepsDependentOptionsOpenUntilRetry() {
        val transient = IllegalStateException("session close failed")
        var sessionCloses = 0
        var optionsCloses = 0
        val closer =
            RetryableResourceCloser(
                {
                    sessionCloses++
                    if (sessionCloses == 1) throw transient
                },
                { optionsCloses++ },
            )

        assertSame(transient, closer.close())
        assertEquals(1, sessionCloses)
        assertEquals(0, optionsCloses, "options must outlive a session that failed to close")

        assertNull(closer.close())
        assertEquals(2, sessionCloses, "the failed session close must remain retryable")
        assertEquals(1, optionsCloses)
        assertNull(closer.close())
        assertEquals(2, sessionCloses)
        assertEquals(1, optionsCloses)
    }

    @Test
    fun failedOptionsCloseRetriesWithoutClosingSessionTwice() {
        val transient = IllegalStateException("options close failed")
        var sessionCloses = 0
        var optionsCloses = 0
        val closer =
            RetryableResourceCloser(
                { sessionCloses++ },
                {
                    optionsCloses++
                    if (optionsCloses == 1) throw transient
                },
            )

        assertSame(transient, closer.close())
        assertEquals(1, sessionCloses)
        assertEquals(1, optionsCloses)

        assertNull(closer.close())
        assertEquals(1, sessionCloses, "successful resources must not be closed twice")
        assertEquals(2, optionsCloses, "the failed close must remain retryable")
        assertNull(closer.close())
        assertEquals(2, optionsCloses)
    }
}
