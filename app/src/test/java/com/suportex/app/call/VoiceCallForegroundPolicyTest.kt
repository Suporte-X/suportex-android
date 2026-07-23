package com.suportex.app.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallForegroundPolicyTest {

    @Test
    fun startRequiresExplicitUserActionAndMicrophonePermission() {
        assertTrue(
            VoiceCallForegroundPolicy.canStart(
                VoiceCallForegroundPolicy.ACTION_START_FROM_USER,
                recordAudioGranted = true
            )
        )
        assertFalse(
            VoiceCallForegroundPolicy.canStart(
                VoiceCallForegroundPolicy.ACTION_START_FROM_USER,
                recordAudioGranted = false
            )
        )
        assertFalse(
            VoiceCallForegroundPolicy.canStart(
                action = null,
                recordAudioGranted = true
            )
        )
        assertFalse(
            VoiceCallForegroundPolicy.canStart(
                action = "com.suportex.app.action.UNRELATED",
                recordAudioGranted = true
            )
        )
    }

    @Test
    fun captureRequiresLocalAuthorizationAndCurrentPermission() {
        assertTrue(
            VoiceCallForegroundPolicy.canCapture(
                locallyAuthorizedForCall = true,
                recordAudioGranted = true
            )
        )
        assertFalse(
            VoiceCallForegroundPolicy.canCapture(
                locallyAuthorizedForCall = false,
                recordAudioGranted = true
            )
        )
        assertFalse(
            VoiceCallForegroundPolicy.canCapture(
                locallyAuthorizedForCall = true,
                recordAudioGranted = false
            )
        )
    }

    @Test
    fun onlyTerminalOrIdleStatesStopTheService() {
        val runningStates = listOf(
            CallState.OUTGOING_RINGING,
            CallState.INCOMING_RINGING,
            CallState.CONNECTING,
            CallState.IN_CALL
        )
        val stoppedStates = listOf(
            CallState.IDLE,
            CallState.DECLINED,
            CallState.TIMEOUT,
            CallState.FAILED,
            CallState.ENDED
        )

        runningStates.forEach { state ->
            assertFalse(VoiceCallForegroundPolicy.shouldStopFor(state))
        }
        stoppedStates.forEach { state ->
            assertTrue(VoiceCallForegroundPolicy.shouldStopFor(state))
        }
    }
}
