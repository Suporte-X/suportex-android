package com.suportex.app.data.model

data class ClientRecord(
    val id: String,
    val phone: String,
    val name: String?,
    val primaryEmail: String?,
    val notes: String?,
    val credits: Int,
    val supportsUsed: Int,
    val freeFirstSupportUsed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String
)

data class ClientVerificationRecord(
    val clientId: String,
    val primaryPhone: String?,
    val verifiedPhone: String?,
    val status: String,
    val lastVerificationAt: Long?,
    val updatedAt: Long?
)

data class ClientMetaRecord(
    val clientId: String,
    val totalSessions: Int,
    val totalPaidSessions: Int,
    val totalFreeSessions: Int,
    val totalCreditsPurchased: Int,
    val totalCreditsUsed: Int,
    val lastSupportAt: Long?
)

data class CreditPackageRecord(
    val id: String,
    val name: String,
    val supportCount: Int,
    val priceCents: Int,
    val active: Boolean,
    val displayOrder: Int
)

data class CreditOrderRecord(
    val id: String,
    val clientId: String,
    val packageId: String,
    val status: String,
    val paymentMethod: String,
    val amountCents: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val whatsappRequested: Boolean,
    val pixPlaceholder: Boolean,
    val cardPlaceholder: Boolean
)

data class SupportSessionRecord(
    val id: String,
    val clientId: String,
    val techId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val isFreeFirstSupport: Boolean,
    val creditsConsumed: Int,
    val problemSummary: String?,
    val solutionSummary: String?,
    val internalNotes: String?
)

data class SupportReportRecord(
    val id: String,
    val sessionId: String,
    val clientId: String,
    val techId: String?,
    val createdAt: Long,
    val summary: String?,
    val actionsTaken: String?,
    val solutionApplied: String?,
    val followUpNeeded: Boolean
)

data class ClientHomeSnapshot(
    val clientUid: String?,
    val phone: String?,
    val client: ClientRecord?,
    val clientMeta: ClientMetaRecord?,
    val verification: ClientVerificationRecord?,
    val packages: List<CreditPackageRecord>
) {
    private val hasRecordedSupport: Boolean
        get() = (client?.supportsUsed ?: 0) > 0 || (clientMeta?.totalSessions ?: 0) > 0
    private val firstSupportWindowReached: Boolean
        get() {
            val marker = clientMeta?.lastSupportAt ?: 0L
            if (marker <= 0L) return hasRecordedSupport
            return System.currentTimeMillis() - marker >= FIRST_SUPPORT_PURCHASE_DELAY_MS
        }

    val isRegisteredClient: Boolean get() = client != null
    val creditsAvailable: Int get() = client?.credits ?: 0
    val freeFirstSupportPending: Boolean get() = client?.freeFirstSupportUsed == false || client == null
    val shouldShowPurchaseEntry: Boolean get() = isRegisteredClient && hasRecordedSupport && firstSupportWindowReached
    val shouldAutoOpenPurchase: Boolean get() = shouldShowPurchaseEntry && isRegisteredWithoutCredit
    val lifecycleState: ClientLifecycleState
        get() = when {
            client == null -> ClientLifecycleState.UNREGISTERED
            !client.freeFirstSupportUsed -> ClientLifecycleState.REGISTERED_FIRST_SUPPORT_PENDING
            client.credits > 0 -> ClientLifecycleState.REGISTERED_WITH_CREDIT
            else -> ClientLifecycleState.REGISTERED_WITHOUT_CREDIT
        }
    val isRegisteredWithoutCredit: Boolean
        get() = lifecycleState == ClientLifecycleState.REGISTERED_WITHOUT_CREDIT
    val canRequestSupport: Boolean get() = !isRegisteredWithoutCredit
    val verificationState: ClientVerificationState
        get() = if (verification?.status == "verified") {
            ClientVerificationState.VERIFIED
        } else {
            ClientVerificationState.NOT_VERIFIED
        }
}

private const val FIRST_SUPPORT_PURCHASE_DELAY_MS = 5 * 60 * 1000L

enum class ClientLifecycleState {
    UNREGISTERED,
    REGISTERED_FIRST_SUPPORT_PENDING,
    REGISTERED_WITH_CREDIT,
    REGISTERED_WITHOUT_CREDIT
}

enum class ClientVerificationState {
    VERIFIED,
    NOT_VERIFIED
}

enum class ClientFinancialStatus {
    UNREGISTERED_NEW_CLIENT,
    WITH_CREDIT,
    WITHOUT_CREDIT,
    FREE_FIRST_SUPPORT_PENDING
}

data class SupportStartContext(
    val clientId: String?,
    val phone: String?,
    val isNewClient: Boolean,
    val isFreeFirstSupport: Boolean,
    val creditsToConsume: Int
)

sealed class SupportAccessDecision {
    data class Allowed(
        val startContext: SupportStartContext,
        val financialStatus: ClientFinancialStatus,
        val client: ClientRecord?
    ) : SupportAccessDecision()

    data class BlockedNeedsCredit(
        val client: ClientRecord,
        val packages: List<CreditPackageRecord>
    ) : SupportAccessDecision()

    data class BlockedUnavailable(
        val message: String
    ) : SupportAccessDecision()
}
