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
    val phone: String?,
    val client: ClientRecord?,
    val clientMeta: ClientMetaRecord?,
    val packages: List<CreditPackageRecord>
) {
    val creditsAvailable: Int get() = client?.credits ?: 0
    val freeFirstSupportPending: Boolean get() = client?.freeFirstSupportUsed == false
}

enum class ClientFinancialStatus {
    WITH_CREDIT,
    WITHOUT_CREDIT,
    FREE_FIRST_SUPPORT_PENDING
}

data class SupportStartContext(
    val clientId: String,
    val phone: String,
    val isNewClient: Boolean,
    val isFreeFirstSupport: Boolean,
    val creditsToConsume: Int
)

sealed class SupportAccessDecision {
    data class Allowed(
        val startContext: SupportStartContext,
        val financialStatus: ClientFinancialStatus,
        val client: ClientRecord
    ) : SupportAccessDecision()

    data class BlockedNeedsCredit(
        val client: ClientRecord,
        val packages: List<CreditPackageRecord>
    ) : SupportAccessDecision()

    data object NeedsPhone : SupportAccessDecision()
}
