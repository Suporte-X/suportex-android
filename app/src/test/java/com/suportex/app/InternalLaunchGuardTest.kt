package com.suportex.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalLaunchGuardTest {
    @Test
    fun `aceita somente nonce interno exato`() {
        val nonce = "nonce-interno-123"

        assertTrue(InternalLaunchGuard.noncesMatch(nonce, nonce))
        assertFalse(InternalLaunchGuard.noncesMatch(nonce, "outro"))
        assertFalse(InternalLaunchGuard.noncesMatch(nonce, ""))
        assertFalse(InternalLaunchGuard.noncesMatch("", nonce))
    }
}
