package com.suportex.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportRequestCorrelationTest {

    @Test
    fun legacyEventConfirmsPendingRequestWithoutLocalId() {
        val correlation = SupportRequestCorrelation()
        val token = correlation.begin("local-1")

        val result = correlation.confirmFromLegacyEvent(
            eventLocalSupportSessionId = null,
            requestId = "request-1"
        )

        assertEquals(SupportCorrelationStatus.CONFIRMED, result.status)
        assertEquals(token, result.confirmation?.token)
        assertEquals(SupportQueueConfirmationSource.LEGACY_EVENT, result.confirmation?.source)
    }

    @Test
    fun delayedAckFromPreviousGenerationIsIgnored() {
        val correlation = SupportRequestCorrelation()
        val oldToken = correlation.begin("local-old")
        correlation.invalidate()
        val currentToken = correlation.begin("local-current")

        val delayed = correlation.confirmFromAck(
            token = oldToken,
            ackLocalSupportSessionId = "local-old",
            requestId = "request-old",
            reused = false
        )

        assertEquals(SupportCorrelationStatus.IGNORED, delayed.status)
        assertTrue(correlation.isActive(currentToken))
        assertNotEquals(oldToken.generation, currentToken.generation)
    }

    @Test
    fun untaggedLegacyEventCannotConfirmRequestAfterUnconfirmedOneWasSuperseded() {
        val correlation = SupportRequestCorrelation()
        correlation.begin("local-old")
        correlation.invalidate()
        correlation.begin("local-current")

        val delayedLegacy = correlation.confirmFromLegacyEvent(
            eventLocalSupportSessionId = null,
            requestId = "request-old"
        )
        val correlatedCurrent = correlation.confirmFromLegacyEvent(
            eventLocalSupportSessionId = "local-current",
            requestId = "request-current"
        )

        assertEquals(SupportCorrelationStatus.IGNORED, delayedLegacy.status)
        assertEquals(SupportCorrelationStatus.CONFIRMED, correlatedCurrent.status)
    }

    @Test
    fun confirmationCannotBeOverwrittenByDifferentRequestId() {
        val correlation = SupportRequestCorrelation()
        correlation.begin("local-1")
        val first = correlation.confirmFromLegacyEvent(
            eventLocalSupportSessionId = "local-1",
            requestId = "request-current"
        )
        val delayed = correlation.confirmFromLegacyEvent(
            eventLocalSupportSessionId = null,
            requestId = "request-old"
        )

        assertEquals(SupportCorrelationStatus.CONFIRMED, first.status)
        assertEquals(SupportCorrelationStatus.IGNORED, delayed.status)
        assertEquals(
            "request-current",
            correlation.confirmFromLegacyEvent(
                eventLocalSupportSessionId = null,
                requestId = "request-current"
            ).confirmation?.requestId
        )
    }

    @Test
    fun ackWithMismatchedLocalIdIsIgnored() {
        val correlation = SupportRequestCorrelation()
        val token = correlation.begin("local-current")

        val result = correlation.confirmFromAck(
            token = token,
            ackLocalSupportSessionId = "local-other",
            requestId = "request-1",
            reused = false
        )

        assertEquals(SupportCorrelationStatus.IGNORED, result.status)
    }

    @Test
    fun uncorrelatedLegacyErrorCannotClearCurrentRequest() {
        val correlation = SupportRequestCorrelation()
        correlation.begin("local-current")

        assertFalse(
            correlation.canApplyServerError(
                eventLocalSupportSessionId = null,
                eventRequestId = null
            )
        )
        assertFalse(
            correlation.canApplyServerError(
                eventLocalSupportSessionId = "local-old",
                eventRequestId = null
            )
        )
        assertTrue(
            correlation.canApplyServerError(
                eventLocalSupportSessionId = "local-current",
                eventRequestId = null
            )
        )

        correlation.confirmFromLegacyEvent(
            eventLocalSupportSessionId = "local-current",
            requestId = "request-current"
        )
        assertFalse(
            correlation.canApplyServerError(
                eventLocalSupportSessionId = "local-current",
                eventRequestId = "request-current"
            )
        )
    }
}
