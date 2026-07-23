package com.suportex.app.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOperationTrackerTest {

    @Test
    fun finishedOperationCannotBecomeActiveAgain() {
        val tracker = CallOperationTracker()
        val token = tracker.begin(
            sessionId = "session-1",
            callId = "call-1",
            direction = CallDirection.CLIENT_TO_TECH
        )

        assertTrue(tracker.isActive(token))
        assertTrue(tracker.finish(token))
        assertFalse(tracker.isActive(token))
        assertNull(tracker.current())
        assertTrue(tracker.wasTerminated("session-1", "call-1"))
    }

    @Test
    fun delayedCompletionCannotFinishReplacementCall() {
        val tracker = CallOperationTracker()
        val oldToken = tracker.begin(
            sessionId = "session-1",
            callId = "call-old",
            direction = CallDirection.CLIENT_TO_TECH
        )
        assertTrue(tracker.finish(oldToken))

        val replacement = tracker.begin(
            sessionId = "session-1",
            callId = "call-new",
            direction = CallDirection.TECH_TO_CLIENT
        )

        assertFalse(tracker.finish(oldToken))
        assertTrue(tracker.isActive(replacement))
        assertNotEquals(oldToken.generation, replacement.generation)
    }

    @Test
    fun resetInvalidatesActiveTokenAndClearsPreviousSessionHistory() {
        val tracker = CallOperationTracker()
        val token = tracker.begin(
            sessionId = "session-1",
            callId = "call-1",
            direction = CallDirection.CLIENT_TO_TECH
        )
        assertTrue(tracker.finish(token))
        assertTrue(tracker.wasTerminated("session-1", "call-1"))

        tracker.reset(clearTerminatedHistory = true)

        assertNull(tracker.current())
        assertFalse(tracker.wasTerminated("session-1", "call-1"))
    }

    @Test
    fun sdpIsAcceptedOnlyWhenTaggedWithActiveCallId() {
        assertEquals(
            "valid-sdp",
            verifiedCallSdp(
                sdp = "valid-sdp",
                sdpCallId = "call-current",
                activeCallId = "call-current"
            )
        )
        assertNull(
            verifiedCallSdp(
                sdp = "stale-sdp",
                sdpCallId = "call-old",
                activeCallId = "call-current"
            )
        )
        assertNull(
            verifiedCallSdp(
                sdp = "legacy-without-id",
                sdpCallId = null,
                activeCallId = "call-current"
            )
        )
    }

    @Test
    fun compatibleUntaggedSdpMustBeNewForTheActiveCall() {
        val token = CallOperationToken(
            generation = 1,
            sessionId = "session-1",
            callId = "call-current",
            direction = CallDirection.TECH_TO_CLIENT
        )
        val guard = CallSdpCompatibilityGuard()
        guard.begin(
            token = token,
            initialOfferSdp = "stale-offer",
            initialAnswerSdp = null
        )

        assertNull(
            guard.verifyOffer(
                token = token,
                sdp = "stale-offer",
                sdpCallId = null
            )
        )
        assertEquals(
            "fresh-offer",
            guard.verifyOffer(
                token = token,
                sdp = "fresh-offer",
                sdpCallId = null
            )
        )
        assertEquals(
            "fresh-offer",
            guard.verifyOffer(
                token = token,
                sdp = "fresh-offer",
                sdpCallId = "call-current"
            )
        )
        assertNull(
            guard.verifyOffer(
                token = token,
                sdp = "fresh-offer",
                sdpCallId = "call-old"
            )
        )
    }

    @Test
    fun compatibleUntaggedAnswerIsAcceptedAfterOutgoingCallClearedLegacySdp() {
        val token = CallOperationToken(
            generation = 2,
            sessionId = "session-1",
            callId = "call-current",
            direction = CallDirection.CLIENT_TO_TECH
        )
        val guard = CallSdpCompatibilityGuard()
        guard.begin(
            token = token,
            initialOfferSdp = null,
            initialAnswerSdp = null
        )

        assertEquals(
            "fresh-answer",
            guard.verifyAnswer(
                token = token,
                sdp = "fresh-answer",
                sdpCallId = null
            )
        )
    }

    @Test
    fun compatibilityGuardRejectsAnotherCallToken() {
        val active = CallOperationToken(
            generation = 3,
            sessionId = "session-1",
            callId = "call-current",
            direction = CallDirection.CLIENT_TO_TECH
        )
        val other = active.copy(generation = 4, callId = "call-other")
        val guard = CallSdpCompatibilityGuard()
        guard.begin(active, initialOfferSdp = null, initialAnswerSdp = null)

        assertNull(
            guard.verifyAnswer(
                token = other,
                sdp = "other-answer",
                sdpCallId = null
            )
        )
    }
}
