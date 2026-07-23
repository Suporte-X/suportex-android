package com.suportex.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientNotificationVisibilityTest {

    @Test
    fun `registro de dispositivo usa cliente provisório próprio quando vínculo ainda não existe`() {
        assertEquals(
            "uid_firebase-user-1",
            resolveClientDeviceOwnerId(
                authUid = "firebase-user-1",
                requestedClientId = null
            )
        )
    }

    @Test
    fun `registro de dispositivo preserva cliente verificado e rejeita uid vazio`() {
        assertEquals(
            "phone_5565999999999",
            resolveClientDeviceOwnerId(
                authUid = "firebase-user-1",
                requestedClientId = " phone_5565999999999 "
            )
        )
        assertNull(resolveClientDeviceOwnerId(authUid = " ", requestedClientId = "client"))
    }

    @Test
    fun `notificacao somente push nao aparece na central interna`() {
        val notification = notificationRecord(deliveryInApp = false)

        assertFalse(shouldShowInNotificationCenter(notification, now = 1_000L))
    }

    @Test
    fun `notificacao interna ativa aparece na central`() {
        val notification = notificationRecord(deliveryInApp = true)

        assertTrue(shouldShowInNotificationCenter(notification, now = 1_000L))
    }

    @Test
    fun `notificacao dispensada ou expirada nao aparece na central`() {
        assertFalse(
            shouldShowInNotificationCenter(
                notificationRecord(deliveryInApp = true, dismissed = true),
                now = 1_000L
            )
        )
        assertFalse(
            shouldShowInNotificationCenter(
                notificationRecord(deliveryInApp = true, expiresAt = 999L),
                now = 1_000L
            )
        )
    }

    private fun notificationRecord(
        deliveryInApp: Boolean,
        dismissed: Boolean = false,
        expiresAt: Long? = null
    ): ClientNotificationRecord {
        return ClientNotificationRecord(
            id = "notification-id",
            title = "Aviso",
            body = "Mensagem",
            type = "MANUAL_NOTICE",
            iconType = "info",
            priority = "normal",
            status = if (dismissed) "dismissed" else "unread",
            read = false,
            dismissed = dismissed,
            actionLabel = null,
            actionType = "NONE",
            deliveryInApp = deliveryInApp,
            createdAt = 500L,
            expiresAt = expiresAt
        )
    }
}
