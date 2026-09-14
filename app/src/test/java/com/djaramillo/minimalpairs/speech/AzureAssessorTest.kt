package com.djaramillo.minimalpairs.speech

import com.djaramillo.minimalpairs.domain.SayItScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each way Azure can refuse means to the learner, and which of them is
 * worth asking twice. Pure table, no socket: **no test here calls Azure.**
 */
class AzureAssessorTest {

    @Test
    fun aRefusedKeyIsNamedAsSuchAndNeverAskedTwice() {
        // A key rotated or deleted in the portal answers the same way for ever.
        // Calling it "could not score this just now" would let him drill for
        // weeks with scoring quietly dead.
        for (code in listOf(401, 403, 404)) {
            assertEquals(SayItScoring.Unscored.KEY_REJECTED, AzureAssessor.reasonFor(code))
            assertFalse(AzureAssessor.retryable(AzureAssessor.reasonFor(code)))
        }
    }

    @Test
    fun aThrottledFreeResourceIsNotHitAgainOneSecondLater() {
        assertEquals(SayItScoring.Unscored.THROTTLED, AzureAssessor.reasonFor(429))
        assertFalse(AzureAssessor.retryable(AzureAssessor.reasonFor(429)))
    }

    @Test
    fun aServerFaultIsTheOneThingWorthAskingTwice() {
        for (code in listOf(500, 502, 503)) {
            assertEquals(SayItScoring.Unscored.AZURE_ERROR, AzureAssessor.reasonFor(code))
            assertTrue(AzureAssessor.retryable(AzureAssessor.reasonFor(code)))
        }
        assertEquals(2, AzureAssessor.ATTEMPTS)
    }

    @Test
    fun aServerFaultThatAsksForALongWaitIsNotRetriedAtAll() {
        val wait = AzureAssessor.retryAfterMs("120")
        assertNull(wait)
        assertEquals(SayItScoring.Unscored.THROTTLED, AzureAssessor.reasonFor(503, wait))
        assertFalse(AzureAssessor.retryable(AzureAssessor.reasonFor(503, wait)))
    }

    @Test
    fun retryAfterIsHonouredWhenItIsShortEnoughToWaitFor() {
        assertEquals(3_000L, AzureAssessor.retryAfterMs("3"))
        // Absent, unreadable or nonsensical: the default beat.
        assertEquals(AzureAssessor.RETRY_DELAY_MS, AzureAssessor.retryAfterMs(null))
        assertEquals(AzureAssessor.RETRY_DELAY_MS, AzureAssessor.retryAfterMs("soon"))
        assertEquals(AzureAssessor.RETRY_DELAY_MS, AzureAssessor.retryAfterMs("-5"))
        // Never shorter than the default, never longer than the learner will stand.
        assertEquals(AzureAssessor.RETRY_DELAY_MS, AzureAssessor.retryAfterMs("1"))
        assertNull(AzureAssessor.retryAfterMs("6"))
    }

    @Test
    fun a400IsNotScoredAndNotBlamedOnTheKey() {
        // Bad audio or a missing language: a bug on our side, not his key.
        assertEquals(SayItScoring.Unscored.AZURE_ERROR, AzureAssessor.reasonFor(400))
    }

    @Test
    fun theContentTypeMatchesTheBytesTheAppSends() {
        assertEquals("audio/wav; codecs=audio/pcm; samplerate=16000", AzureAssessor.CONTENT_TYPE)
        assertEquals(16_000, com.djaramillo.minimalpairs.audio.Wav.SAMPLE_RATE)
    }

    @Test
    fun everyRefusalIsAReasonAndNeverAScore() {
        // The whole point: no status code anywhere produces a number.
        for (code in listOf(400, 401, 403, 404, 408, 429, 500, 502, 503, 599)) {
            val reason = AzureAssessor.reasonFor(code)
            assertTrue(reason in SayItScoring.Unscored.values())
        }
    }
}
