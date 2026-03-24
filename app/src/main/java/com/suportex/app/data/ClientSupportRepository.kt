package com.suportex.app.data

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.suportex.app.data.model.ClientFinancialStatus
import com.suportex.app.data.model.ClientHomeSnapshot
import com.suportex.app.data.model.ClientMetaRecord
import com.suportex.app.data.model.ClientRecord
import com.suportex.app.data.model.ClientVerificationRecord
import com.suportex.app.data.model.CreditOrderRecord
import com.suportex.app.data.model.CreditPackageRecord
import com.suportex.app.data.model.SupportAccessDecision
import com.suportex.app.data.model.SupportSessionRecord
import com.suportex.app.data.model.SupportStartContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClientSupportRepository(
    private val db: FirebaseFirestore = FirebaseDataSource.db,
    private val authRepository: AuthRepository = AuthRepository()
) {
    private val clients = db.collection("clients")
    private val clientProfiles = db.collection("client_profiles")
    private val clientAppLinks = db.collection("client_app_links")
    private val clientVerifications = db.collection("client_verifications")
    private val pnvRequests = db.collection("pnv_requests")
    private val supportSessions = db.collection("support_sessions")
    private val supportReports = db.collection("support_reports")
    private val creditPackages = db.collection("credit_packages")
    private val creditOrders = db.collection("credit_orders")

    suspend fun seedDefaultPackagesIfNeeded() {
        authRepository.ensureAnonAuth()
        val defaults = SupportBillingConfig.defaultCreditPackages
        val batch = db.batch()
        var hasPendingWrite = false
        defaults.forEach { pkg ->
            val ref = creditPackages.document(pkg.id)
            val existing = ref.get().await()
            if (!existing.exists()) {
                hasPendingWrite = true
                batch.set(ref, pkg.toMap())
            }
        }
        if (hasPendingWrite) {
            batch.commit().await()
        }
    }

    suspend fun listCreditPackages(): List<CreditPackageRecord> {
        authRepository.ensureAnonAuth()
        val snapshot = creditPackages.get().await()
        val rows = snapshot.documents.mapNotNull { it.toCreditPackageRecord() }
            .filter { it.active }
            .sortedBy { it.displayOrder }
        if (rows.isNotEmpty()) return rows
        return SupportBillingConfig.defaultCreditPackages
    }

    suspend fun loadHomeSnapshot(
        clientUid: String?,
        rawPhone: String?
    ): ClientHomeSnapshot {
        authRepository.ensureAnonAuth()
        val packages = listCreditPackages()
        val normalizedPhone = normalizePhone(rawPhone)
        val resolvedClient = resolveClientForApp(clientUid = clientUid, normalizedPhone = normalizedPhone)
        val client = resolvedClient?.client
        val meta = if (client != null) {
            clientProfiles.document(client.id).get().await().toClientMetaRecord(client.id)
        } else {
            null
        }
        val verification = if (client != null) {
            syncVerificationStatus(
                client = client,
                clientUid = clientUid,
                verifiedPhone = normalizedPhone
            )
        } else {
            null
        }

        return ClientHomeSnapshot(
            clientUid = clientUid,
            phone = normalizedPhone ?: client?.phone,
            client = client,
            clientMeta = meta,
            verification = verification,
            packages = packages
        )
    }

    suspend fun evaluateSupportAccess(
        clientUid: String?,
        fallbackVerifiedPhone: String?
    ): SupportAccessDecision {
        authRepository.ensureAnonAuth()
        val normalizedPhone = normalizePhone(fallbackVerifiedPhone)
        val resolvedClient = resolveClientForApp(clientUid = clientUid, normalizedPhone = normalizedPhone)
        val sanitizedUid = clientUid?.trim()?.takeIf { it.isNotBlank() }
        var client = resolvedClient?.client
        var ensuredAsNewClient = false

        if (client == null) {
            val ensured = when {
                normalizedPhone != null -> ensureClientByPhoneWithUid(
                    normalizedPhone = normalizedPhone,
                    clientUid = sanitizedUid,
                    displayName = null
                )
                sanitizedUid != null -> ensureClientByUid(
                    clientUid = sanitizedUid,
                    displayName = null
                )
                else -> null
            }
            client = ensured?.client
            ensuredAsNewClient = ensured?.isNewClient == true
            if (sanitizedUid != null && client != null) {
                upsertClientAppLink(
                    clientUid = sanitizedUid,
                    clientId = client.id,
                    phone = normalizedPhone ?: client.phone.takeIf { it.isNotBlank() }
                )
            }
        }

        if (client == null) {
            return SupportAccessDecision.Allowed(
                startContext = SupportStartContext(
                    clientId = null,
                    phone = normalizedPhone,
                    isNewClient = true,
                    isFreeFirstSupport = true,
                    creditsToConsume = 0
                ),
                financialStatus = ClientFinancialStatus.UNREGISTERED_NEW_CLIENT,
                client = null
            )
        }
        if (ensuredAsNewClient) {
            return SupportAccessDecision.Allowed(
                startContext = SupportStartContext(
                    clientId = client.id,
                    phone = normalizedPhone ?: client.phone.takeIf { it.isNotBlank() },
                    isNewClient = true,
                    isFreeFirstSupport = true,
                    creditsToConsume = 0
                ),
                financialStatus = ClientFinancialStatus.UNREGISTERED_NEW_CLIENT,
                client = client
            )
        }
        val freePending = !client.freeFirstSupportUsed
        if (freePending) {
            return SupportAccessDecision.Allowed(
                startContext = SupportStartContext(
                    clientId = client.id,
                    phone = client.phone.takeIf { it.isNotBlank() } ?: normalizedPhone,
                    isNewClient = false,
                    isFreeFirstSupport = true,
                    creditsToConsume = 0
                ),
                financialStatus = ClientFinancialStatus.FREE_FIRST_SUPPORT_PENDING,
                client = client
            )
        }

        if (client.credits > 0) {
            return SupportAccessDecision.Allowed(
                startContext = SupportStartContext(
                    clientId = client.id,
                    phone = client.phone.takeIf { it.isNotBlank() } ?: normalizedPhone,
                    isNewClient = false,
                    isFreeFirstSupport = false,
                    creditsToConsume = 1
                ),
                financialStatus = ClientFinancialStatus.WITH_CREDIT,
                client = client
            )
        }

        return SupportAccessDecision.BlockedNeedsCredit(
            client = client,
            packages = listCreditPackages()
        )
    }

    suspend fun registerSupportRequest(
        startContext: SupportStartContext,
        clientName: String?,
        clientUid: String?,
        deviceBrand: String?,
        deviceModel: String?,
        androidVersion: String?
    ): String {
        authRepository.ensureAnonAuth()
        val now = System.currentTimeMillis()
        val doc = supportSessions.document()
        val payload = mapOf(
            "clientId" to startContext.clientId,
            "clientPhone" to startContext.phone,
            "clientName" to clientName,
            "clientUid" to clientUid,
            "techId" to null,
            "techName" to null,
            "startedAt" to now,
            "endedAt" to null,
            "status" to "queued",
            "isFreeFirstSupport" to startContext.isFreeFirstSupport,
            "creditsConsumed" to startContext.creditsToConsume,
            "requiresTechnicianRegistration" to (startContext.isNewClient || startContext.clientId == null),
            "problemSummary" to null,
            "solutionSummary" to null,
            "internalNotes" to null,
            "reportId" to null,
            "source" to "android_app",
            "device" to mapOf(
                "brand" to deviceBrand,
                "model" to deviceModel,
                "androidVersion" to androidVersion
            ).filterValues { it != null },
            "createdAt" to now,
            "updatedAt" to now
        )
        doc.set(payload).await()
        return doc.id
    }

    suspend fun attachRealtimeSession(
        localSupportSessionId: String?,
        realtimeSessionId: String,
        techName: String?
    ): Boolean {
        authRepository.ensureAnonAuth()
        val sessionRef = resolveSupportSessionRef(localSupportSessionId, realtimeSessionId) ?: return false
        sessionRef.set(
            mapOf(
                "sessionId" to realtimeSessionId,
                "status" to "in_progress",
                "techName" to techName,
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()
        return true
    }

    suspend fun cancelSupportRequest(localSupportSessionId: String?) {
        authRepository.ensureAnonAuth()
        val normalized = localSupportSessionId?.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        supportSessions.document(normalized).set(
            mapOf(
                "status" to "cancelled",
                "expiresAt" to ttlTimestampFrom(now, RETENTION_DAYS_SUPPORT_SESSIONS),
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
    }

    suspend fun registerPnvAttempt(
        clientUid: String?,
        clientId: String?,
        fallbackPhone: String?,
        status: String = PNV_REQUEST_STATUS_PENDING,
        manualFallback: Boolean = false,
        reason: String? = null
    ) {
        authRepository.ensureAnonAuth()
        val sanitizedUid = clientUid?.trim()?.takeIf { it.isNotBlank() }
        val sanitizedClientId = clientId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedPhone = normalizePhone(fallbackPhone)
        val now = System.currentTimeMillis()
        val pnvRequestExpiresAt = ttlTimestampFrom(now, RETENTION_DAYS_PNV_REQUESTS)

        val requestPayload = mutableMapOf<String, Any?>(
            "clientUid" to sanitizedUid,
            "clientId" to sanitizedClientId,
            "phone" to normalizedPhone,
            "status" to status,
            "manualFallback" to manualFallback,
            "reason" to reason,
            "createdAt" to now,
            "updatedAt" to now,
            "expiresAt" to pnvRequestExpiresAt,
            "source" to "android_app"
        )
        pnvRequests.document().set(requestPayload.filterValues { it != null }).await()

        if (sanitizedUid != null && sanitizedClientId != null) {
            upsertClientAppLink(
                clientUid = sanitizedUid,
                clientId = sanitizedClientId,
                phone = normalizedPhone
            )
        }

        if (sanitizedClientId != null) {
            val clientSnap = clients.document(sanitizedClientId).get().await()
            val primaryPhone = normalizePhone(clientSnap.getString("phone"))
                ?: normalizedPhone
                ?: clientSnap.getString("phone")
            val verificationStatus = when {
                status == PNV_REQUEST_STATUS_MANUAL_PENDING || manualFallback -> VERIFICATION_STATUS_MANUAL_REQUIRED
                else -> VERIFICATION_STATUS_PENDING
            }
            clientVerifications.document(sanitizedClientId).set(
                mapOf(
                    "clientId" to sanitizedClientId,
                    "primaryPhone" to primaryPhone,
                    "verifiedPhone" to null,
                    "status" to verificationStatus,
                    "mismatchReason" to reason,
                    "lastVerificationAt" to now,
                    "updatedAt" to now
                ).filterValues { it != null },
                SetOptions.merge()
            ).await()
        }
    }

    suspend fun registerPnvSuccess(
        clientUid: String?,
        verifiedPhone: String,
        token: String?,
        localSupportSessionId: String?
    ): ClientRecord? {
        authRepository.ensureAnonAuth()
        val normalizedPhone = normalizePhone(verifiedPhone) ?: return null
        val now = System.currentTimeMillis()
        val pnvRequestExpiresAt = ttlTimestampFrom(now, RETENTION_DAYS_PNV_REQUESTS)
        val sanitizedUid = clientUid?.trim()?.takeIf { it.isNotBlank() }
        val ensured = ensureClientByPhoneWithUid(
            normalizedPhone = normalizedPhone,
            clientUid = sanitizedUid,
            displayName = null
        )

        if (sanitizedUid != null) {
            upsertClientAppLink(
                clientUid = sanitizedUid,
                clientId = ensured.client.id,
                phone = normalizedPhone
            )
        }

        clientVerifications.document(ensured.client.id).set(
            mapOf(
                "clientId" to ensured.client.id,
                "primaryPhone" to normalizedPhone,
                "verifiedPhone" to normalizedPhone,
                "status" to VERIFICATION_STATUS_VERIFIED,
                "mismatchReason" to null,
                "lastVerificationAt" to now,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()

        pnvRequests.document().set(
            mapOf(
                "clientUid" to sanitizedUid,
                "clientId" to ensured.client.id,
                "phone" to normalizedPhone,
                "status" to PNV_REQUEST_STATUS_PROCESSED,
                "manualFallback" to false,
                "tokenPresent" to !token.isNullOrBlank(),
                "processedAt" to now,
                "createdAt" to now,
                "updatedAt" to now,
                "expiresAt" to pnvRequestExpiresAt,
                "source" to "android_pnv_sdk"
            ).filterValues { it != null }
        ).await()

        val sessionRef = resolveSupportSessionRef(
            localSupportSessionId = localSupportSessionId,
            realtimeSessionId = null
        )
        sessionRef?.set(
            mapOf(
                "clientId" to ensured.client.id,
                "clientPhone" to normalizedPhone,
                "requiresTechnicianRegistration" to false,
                "updatedAt" to now
            ),
            SetOptions.merge()
        )?.await()

        return clients.document(ensured.client.id).get().await().toClientRecord()
    }

    suspend fun registerCreditOrderIntent(
        clientId: String,
        packageId: String,
        paymentMethod: String,
        whatsappRequested: Boolean,
        pixPlaceholder: Boolean,
        cardPlaceholder: Boolean
    ): CreditOrderRecord {
        authRepository.ensureAnonAuth()
        val selectedPackage = loadPackageById(packageId)
            ?: SupportBillingConfig.defaultCreditPackages.first()
        val now = System.currentTimeMillis()
        val doc = creditOrders.document()
        val payload = mapOf(
            "clientId" to clientId,
            "packageId" to selectedPackage.id,
            "status" to "pending",
            "paymentMethod" to paymentMethod,
            "amountCents" to selectedPackage.priceCents,
            "createdAt" to now,
            "updatedAt" to now,
            "whatsappRequested" to whatsappRequested,
            "pixPlaceholder" to pixPlaceholder,
            "cardPlaceholder" to cardPlaceholder
        )
        doc.set(payload).await()
        return CreditOrderRecord(
            id = doc.id,
            clientId = clientId,
            packageId = selectedPackage.id,
            status = "pending",
            paymentMethod = paymentMethod,
            amountCents = selectedPackage.priceCents,
            createdAt = now,
            updatedAt = now,
            whatsappRequested = whatsappRequested,
            pixPlaceholder = pixPlaceholder,
            cardPlaceholder = cardPlaceholder
        )
    }

    suspend fun registerTechnicianClient(
        name: String,
        rawPhone: String,
        primaryEmail: String?,
        notes: String?
    ): ClientRecord? {
        authRepository.ensureAnonAuth()
        val normalizedPhone = normalizePhone(rawPhone) ?: return null
        val ensured = ensureClientByPhone(normalizedPhone, name)
        val now = System.currentTimeMillis()
        clients.document(ensured.client.id).set(
            mapOf(
                "phone" to normalizedPhone,
                "name" to name,
                "primaryEmail" to primaryEmail,
                "notes" to notes,
                "profileCompleted" to true,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
        clientVerifications.document(ensured.client.id).set(
            mapOf(
                "clientId" to ensured.client.id,
                "primaryPhone" to normalizedPhone,
                "status" to VERIFICATION_STATUS_PENDING,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
        return clients.document(ensured.client.id).get().await().toClientRecord()
    }

    suspend fun addManualCredit(clientId: String, amount: Int): Boolean =
        applyManualCreditDelta(clientId = clientId, delta = amount)

    suspend fun removeManualCredit(clientId: String, amount: Int): Boolean =
        applyManualCreditDelta(clientId = clientId, delta = -amount)

    suspend fun completeSupportSession(
        localSupportSessionId: String?,
        realtimeSessionId: String?,
        techId: String?,
        techName: String?,
        problemSummary: String?,
        solutionSummary: String?,
        internalNotes: String?,
        reportSummary: String?
    ): Boolean {
        authRepository.ensureAnonAuth()
        val sessionRef = resolveSupportSessionRef(localSupportSessionId, realtimeSessionId) ?: return false
        val now = System.currentTimeMillis()
        val completionResult = db.runTransaction { tx ->
            val sessionSnap = tx.get(sessionRef)
            if (!sessionSnap.exists()) return@runTransaction CompletionTransactionResult(false, null, null)

            val clientId = sessionSnap.getString("clientId") ?: return@runTransaction CompletionTransactionResult(false, null, null)
            val alreadyApplied = sessionSnap.getLong("billingAppliedAt") != null

            val isFreeFirstSupport = sessionSnap.getBoolean("isFreeFirstSupport") ?: false
            val creditsConsumed = (sessionSnap.getLong("creditsConsumed") ?: if (isFreeFirstSupport) 0L else 1L)
                .toInt()
                .coerceAtLeast(0)

            val baseSessionPayload = mutableMapOf<String, Any?>(
                "status" to "completed",
                "endedAt" to now,
                "techId" to techId,
                "techName" to techName,
                "problemSummary" to problemSummary,
                "solutionSummary" to solutionSummary,
                "internalNotes" to internalNotes,
                "updatedAt" to now,
                "expiresAt" to ttlTimestampFrom(now, RETENTION_DAYS_SUPPORT_SESSIONS),
                "billingAppliedAt" to if (alreadyApplied) sessionSnap.getLong("billingAppliedAt") else now
            )

            if (!alreadyApplied) {
                val clientRef = clients.document(clientId)
                val profileRef = clientProfiles.document(clientId)
                val clientSnap = tx.get(clientRef)
                val profileSnap = tx.get(profileRef)

                val oldCredits = (clientSnap.getLong("credits") ?: 0L).toInt().coerceAtLeast(0)
                val oldSupportsUsed = (clientSnap.getLong("supportsUsed") ?: 0L).toInt().coerceAtLeast(0)
                val oldFreeUsed = clientSnap.getBoolean("freeFirstSupportUsed") ?: false

                val newCredits = if (isFreeFirstSupport) {
                    oldCredits
                } else {
                    (oldCredits - creditsConsumed).coerceAtLeast(0)
                }
                val freeUsedAfter = oldFreeUsed || isFreeFirstSupport
                val supportsUsedAfter = oldSupportsUsed + 1

                tx.set(
                    clientRef,
                    mapOf(
                        "credits" to newCredits,
                        "supportsUsed" to supportsUsedAfter,
                        "freeFirstSupportUsed" to freeUsedAfter,
                        "status" to deriveClientStatus(newCredits, freeUsedAfter),
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )

                val totalSessions = (profileSnap.getLong("totalSessions") ?: 0L).toInt()
                val totalPaid = (profileSnap.getLong("totalPaidSessions") ?: 0L).toInt()
                val totalFree = (profileSnap.getLong("totalFreeSessions") ?: 0L).toInt()
                val totalCreditsPurchased = (profileSnap.getLong("totalCreditsPurchased") ?: 0L).toInt()
                val totalCreditsUsed = (profileSnap.getLong("totalCreditsUsed") ?: 0L).toInt()

                tx.set(
                    profileRef,
                    mapOf(
                        "clientId" to clientId,
                        "totalSessions" to totalSessions + 1,
                        "totalPaidSessions" to totalPaid + if (isFreeFirstSupport) 0 else 1,
                        "totalFreeSessions" to totalFree + if (isFreeFirstSupport) 1 else 0,
                        "totalCreditsPurchased" to totalCreditsPurchased,
                        "totalCreditsUsed" to totalCreditsUsed + creditsConsumed,
                        "lastSupportAt" to now,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
            }

            tx.set(sessionRef, baseSessionPayload.filterValues { it != null }, SetOptions.merge())

            CompletionTransactionResult(
                completed = true,
                sessionId = sessionSnap.id,
                clientId = sessionSnap.getString("clientId")
            )
        }.await()

        if (completionResult.completed && !completionResult.clientId.isNullOrBlank()) {
            registerSupportReport(
                sessionId = completionResult.sessionId ?: return true,
                clientId = completionResult.clientId,
                techId = techId,
                summary = reportSummary ?: problemSummary,
                actionsTaken = internalNotes,
                solutionApplied = solutionSummary,
                followUpNeeded = false
            )
        }

        return completionResult.completed
    }

    suspend fun listClientSupportHistory(clientId: String): List<SupportSessionRecord> {
        authRepository.ensureAnonAuth()
        val snapshot = supportSessions
            .whereEqualTo("clientId", clientId)
            .get()
            .await()
        return snapshot.documents.mapNotNull { it.toSupportSessionRecord() }
            .sortedByDescending { it.startedAt }
    }

    suspend fun fetchClientById(clientId: String): ClientRecord? {
        authRepository.ensureAnonAuth()
        return clients.document(clientId).get().await().toClientRecord()
    }

    private suspend fun loadPackageById(packageId: String): CreditPackageRecord? {
        val normalized = packageId.trim()
        if (normalized.isBlank()) return null
        return creditPackages.document(normalized).get().await().toCreditPackageRecord()
    }

    private suspend fun registerSupportReport(
        sessionId: String,
        clientId: String,
        techId: String?,
        summary: String?,
        actionsTaken: String?,
        solutionApplied: String?,
        followUpNeeded: Boolean
    ) {
        val now = System.currentTimeMillis()
        val supportReportExpiresAt = ttlTimestampFrom(now, RETENTION_DAYS_SUPPORT_REPORTS)
        val reportRef = supportReports.document()
        reportRef.set(
            mapOf(
                "sessionId" to sessionId,
                "clientId" to clientId,
                "techId" to techId,
                "createdAt" to now,
                "expiresAt" to supportReportExpiresAt,
                "summary" to summary,
                "actionsTaken" to actionsTaken,
                "solutionApplied" to solutionApplied,
                "followUpNeeded" to followUpNeeded
            ).filterValues { it != null }
        ).await()
        supportSessions.document(sessionId).set(
            mapOf(
                "reportId" to reportRef.id,
                "expiresAt" to ttlTimestampFrom(now, RETENTION_DAYS_SUPPORT_SESSIONS),
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
    }

    private fun ttlTimestampFrom(baseMillis: Long, days: Long): Timestamp {
        val safeDays = days.coerceAtLeast(0L)
        val ttlMillis = baseMillis + (safeDays * MILLIS_PER_DAY)
        return Timestamp(Date(ttlMillis))
    }

    private suspend fun applyManualCreditDelta(clientId: String, delta: Int): Boolean {
        authRepository.ensureAnonAuth()
        if (delta == 0) return true
        val clientRef = clients.document(clientId)
        val now = System.currentTimeMillis()
        return db.runTransaction { tx ->
            val snap = tx.get(clientRef)
            if (!snap.exists()) return@runTransaction false
            val currentCredits = (snap.getLong("credits") ?: 0L).toInt().coerceAtLeast(0)
            val freeUsed = snap.getBoolean("freeFirstSupportUsed") ?: false
            val nextCredits = (currentCredits + delta).coerceAtLeast(0)
            tx.set(
                clientRef,
                mapOf(
                    "credits" to nextCredits,
                    "status" to deriveClientStatus(nextCredits, freeUsed),
                    "updatedAt" to now
                ),
                SetOptions.merge()
            )
            true
        }.await()
    }

    private suspend fun ensureClientByPhone(
        normalizedPhone: String,
        displayName: String?
    ): EnsureClientResult {
        val clientId = clientDocIdFromPhone(normalizedPhone)
        return ensureClientRecord(
            clientId = clientId,
            normalizedPhone = normalizedPhone,
            displayName = displayName
        )
    }

    private suspend fun ensureClientByPhoneWithUid(
        normalizedPhone: String,
        clientUid: String?,
        displayName: String?
    ): EnsureClientResult {
        val sanitizedUid = clientUid?.trim()?.takeIf { it.isNotBlank() }
        if (sanitizedUid == null) {
            return ensureClientByPhone(
                normalizedPhone = normalizedPhone,
                displayName = displayName
            )
        }

        val linkedClientId = runCatching {
            clientAppLinks.document(sanitizedUid)
                .get()
                .await()
                .getString("clientId")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val candidateIds = listOfNotNull(
            linkedClientId,
            clientDocIdFromUid(sanitizedUid),
            clientDocIdFromPhone(normalizedPhone)
        ).distinct()

        var bestClientId: String? = null
        var bestScore = Int.MIN_VALUE
        for (candidateId in candidateIds) {
            val existing = runCatching { clients.document(candidateId).get().await() }.getOrNull()
            if (existing?.exists() == true) {
                val supportsUsed = (existing.getLong("supportsUsed") ?: 0L).toInt().coerceAtLeast(0)
                val freeFirstSupportUsed = existing.getBoolean("freeFirstSupportUsed") ?: false
                val profileCompleted = existing.getBoolean("profileCompleted") ?: false
                val score = (if (freeFirstSupportUsed) 1000 else 0) +
                    (supportsUsed * 100) +
                    (if (profileCompleted) 10 else 0)
                if (score > bestScore) {
                    bestScore = score
                    bestClientId = candidateId
                }
            }
        }
        if (!bestClientId.isNullOrBlank()) {
            return ensureClientRecord(
                clientId = bestClientId,
                normalizedPhone = normalizedPhone,
                displayName = displayName
            )
        }

        return ensureClientRecord(
            clientId = clientDocIdFromPhone(normalizedPhone),
            normalizedPhone = normalizedPhone,
            displayName = displayName
        )
    }

    private suspend fun ensureClientByUid(
        clientUid: String,
        displayName: String?
    ): EnsureClientResult {
        val clientId = clientDocIdFromUid(clientUid)
        return ensureClientRecord(
            clientId = clientId,
            normalizedPhone = null,
            displayName = displayName
        )
    }

    private suspend fun ensureClientRecord(
        clientId: String,
        normalizedPhone: String?,
        displayName: String?
    ): EnsureClientResult {
        val clientRef = clients.document(clientId)
        val profileRef = clientProfiles.document(clientId)
        val now = System.currentTimeMillis()
        return db.runTransaction { tx ->
            val snap = tx.get(clientRef)
            val isNewClient = !snap.exists()
            val credits = (snap.getLong("credits") ?: 0L).toInt().coerceAtLeast(0)
            val supportsUsed = (snap.getLong("supportsUsed") ?: 0L).toInt().coerceAtLeast(0)
            val freeUsed = snap.getBoolean("freeFirstSupportUsed") ?: false
            val profileCompleted = snap.getBoolean("profileCompleted") ?: false
            val createdAt = snap.getLong("createdAt") ?: now
            val existingName = snap.getString("name")
            val existingPhone = snap.getString("phone")
            val phoneToPersist = normalizedPhone ?: existingPhone.orEmpty()
            val name = displayName?.takeIf { it.isNotBlank() } ?: existingName
            val primaryEmail = snap.getString("primaryEmail")
            val notes = snap.getString("notes")
            val status = deriveClientStatus(credits = credits, freeFirstSupportUsed = freeUsed)

            tx.set(
                clientRef,
                mapOf(
                    "phone" to phoneToPersist,
                    "name" to name,
                    "primaryEmail" to primaryEmail,
                    "notes" to notes,
                    "credits" to credits,
                    "supportsUsed" to supportsUsed,
                    "freeFirstSupportUsed" to freeUsed,
                    "profileCompleted" to profileCompleted,
                    "status" to status,
                    "createdAt" to createdAt,
                    "updatedAt" to now
                ).filterValues { it != null },
                SetOptions.merge()
            )

            if (isNewClient) {
                tx.set(
                    profileRef,
                    mapOf(
                        "clientId" to clientId,
                        "totalSessions" to 0,
                        "totalPaidSessions" to 0,
                        "totalFreeSessions" to 0,
                        "totalCreditsPurchased" to 0,
                        "totalCreditsUsed" to 0,
                        "lastSupportAt" to null,
                        "createdAt" to now,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
            }

            EnsureClientResult(
                client = ClientRecord(
                    id = clientId,
                    phone = phoneToPersist,
                    name = name,
                    primaryEmail = primaryEmail,
                    notes = notes,
                    credits = credits,
                    supportsUsed = supportsUsed,
                    freeFirstSupportUsed = freeUsed,
                    createdAt = createdAt,
                    updatedAt = now,
                    status = status
                ),
                isNewClient = isNewClient
            )
        }.await()
    }

    private suspend fun resolveClientForApp(
        clientUid: String?,
        normalizedPhone: String?
    ): ResolvedClientResult? {
        val sanitizedUid = clientUid?.trim()?.takeIf { it.isNotBlank() }

        if (sanitizedUid != null) {
            val linkedClient = resolveLinkedClientByUid(sanitizedUid)
            if (linkedClient != null) return ResolvedClientResult(client = linkedClient)

            val pendingRequest = fetchLatestPendingPnvRequest(
                clientUid = sanitizedUid,
                clientId = null
            )
            val requestedClientId = pendingRequest?.getString("clientId")
            if (!requestedClientId.isNullOrBlank()) {
                val requestedClient = clients.document(requestedClientId).get().await().toClientRecord()
                if (requestedClient != null) {
                    upsertClientAppLink(
                        clientUid = sanitizedUid,
                        clientId = requestedClient.id,
                        phone = requestedClient.phone.takeIf { it.isNotBlank() } ?: normalizedPhone
                    )
                    return ResolvedClientResult(client = requestedClient)
                }
            }

            val provisionalClient = clients.document(clientDocIdFromUid(sanitizedUid))
                .get()
                .await()
                .toClientRecord()
            if (provisionalClient != null) {
                upsertClientAppLink(
                    clientUid = sanitizedUid,
                    clientId = provisionalClient.id,
                    phone = provisionalClient.phone.takeIf { it.isNotBlank() } ?: normalizedPhone
                )
                return ResolvedClientResult(client = provisionalClient)
            }
        }

        if (normalizedPhone != null) {
            val byPhone = clients.document(clientDocIdFromPhone(normalizedPhone)).get().await().toClientRecord()
            if (byPhone != null) {
                if (sanitizedUid != null) {
                    upsertClientAppLink(
                        clientUid = sanitizedUid,
                        clientId = byPhone.id,
                        phone = normalizedPhone
                    )
                }
                return ResolvedClientResult(client = byPhone)
            }
        }

        return null
    }

    private suspend fun resolveLinkedClientByUid(clientUid: String): ClientRecord? {
        val linkSnapshot = clientAppLinks.document(clientUid).get().await()
        val clientId = linkSnapshot.getString("clientId")?.takeIf { it.isNotBlank() } ?: return null
        return clients.document(clientId).get().await().toClientRecord()
    }

    private suspend fun upsertClientAppLink(
        clientUid: String,
        clientId: String,
        phone: String?
    ) {
        val now = System.currentTimeMillis()
        clientAppLinks.document(clientUid).set(
            mapOf(
                "clientUid" to clientUid,
                "clientId" to clientId,
                "phone" to phone,
                "updatedAt" to now,
                "createdAt" to now
            ),
            SetOptions.merge()
        ).await()
    }

    private suspend fun syncVerificationStatus(
        client: ClientRecord,
        clientUid: String?,
        verifiedPhone: String?
    ): ClientVerificationRecord {
        val now = System.currentTimeMillis()
        val primaryPhone = normalizePhone(client.phone) ?: client.phone
        val normalizedVerifiedPhone = normalizePhone(verifiedPhone)
        val verificationRef = clientVerifications.document(client.id)
        val currentSnapshot = verificationRef.get().await()
        val currentStatus = currentSnapshot.getString("status") ?: VERIFICATION_STATUS_PENDING
        var nextStatus = currentStatus
        var mismatchReason = currentSnapshot.getString("mismatchReason")
        var shouldWrite = !currentSnapshot.exists()

        if (normalizedVerifiedPhone != null && primaryPhone.isNotBlank()) {
            if (normalizedVerifiedPhone == primaryPhone) {
                nextStatus = VERIFICATION_STATUS_VERIFIED
                mismatchReason = null
            } else {
                nextStatus = VERIFICATION_STATUS_MISMATCH
                mismatchReason = "phone_divergent"
            }
            shouldWrite = true
        }

        val pendingRequest = fetchLatestPendingPnvRequest(
            clientUid = clientUid,
            clientId = client.id
        )
        val pendingRequestClientId = pendingRequest?.getString("clientId")
        val requestTargetsCurrentClient = pendingRequest != null &&
            (pendingRequestClientId.isNullOrBlank() || pendingRequestClientId == client.id)
        if (requestTargetsCurrentClient &&
            normalizedVerifiedPhone == null &&
            nextStatus != VERIFICATION_STATUS_VERIFIED
        ) {
            nextStatus = if (pendingRequest?.getBoolean("manualFallback") == true) {
                VERIFICATION_STATUS_MANUAL_REQUIRED
            } else {
                VERIFICATION_STATUS_PENDING
            }
            shouldWrite = true
        }

        if (shouldWrite || requestTargetsCurrentClient) {
            val payload = mutableMapOf<String, Any?>(
                "clientId" to client.id,
                "primaryPhone" to primaryPhone,
                "verifiedPhone" to normalizedVerifiedPhone,
                "status" to nextStatus,
                "lastVerificationAt" to now,
                "updatedAt" to now
            )
            payload["mismatchReason"] = mismatchReason
            verificationRef.set(
                payload.filterValues { it != null },
                SetOptions.merge()
            ).await()
        }

        if (requestTargetsCurrentClient) {
            pendingRequest?.reference?.set(
                mapOf(
                    "status" to PNV_REQUEST_STATUS_PROCESSED,
                    "processedAt" to now,
                    "updatedAt" to now
                ),
                SetOptions.merge()
            )?.await()
        }

        return verificationRef.get().await()
            .toClientVerificationRecord(client.id, primaryPhone)
            ?: ClientVerificationRecord(
                clientId = client.id,
                primaryPhone = primaryPhone,
                verifiedPhone = normalizedVerifiedPhone,
                status = nextStatus,
                lastVerificationAt = now,
                updatedAt = now
            )
    }

    private suspend fun fetchLatestPendingPnvRequest(
        clientUid: String?,
        clientId: String?
    ): DocumentSnapshot? {
        val docs = mutableListOf<DocumentSnapshot>()
        clientUid?.trim()?.takeIf { it.isNotBlank() }?.let { uid ->
            val byUid = pnvRequests
                .whereEqualTo("clientUid", uid)
                .get()
                .await()
            docs.addAll(byUid.documents)
        }
        clientId?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
            val byClient = pnvRequests
                .whereEqualTo("clientId", id)
                .get()
                .await()
            docs.addAll(byClient.documents)
        }
        return docs
            .distinctBy { it.id }
            .filter { doc ->
                val status = doc.getString("status") ?: PNV_REQUEST_STATUS_PENDING
                status == PNV_REQUEST_STATUS_PENDING || status == PNV_REQUEST_STATUS_MANUAL_PENDING
            }
            .maxByOrNull { it.getLong("createdAt") ?: 0L }
    }

    private suspend fun resolveSupportSessionRef(
        localSupportSessionId: String?,
        realtimeSessionId: String?
    ): DocumentReference? {
        localSupportSessionId?.takeIf { it.isNotBlank() }?.let { localId ->
            val doc = supportSessions.document(localId).get().await()
            if (doc.exists()) return supportSessions.document(localId)
        }
        realtimeSessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
            val snapshot = supportSessions
                .whereEqualTo("sessionId", sessionId)
                .get()
                .await()
            if (!snapshot.isEmpty) {
                return snapshot.documents.first().reference
            }
        }
        return null
    }

    private fun clientDocIdFromPhone(phone: String): String =
        "phone_${phone.filter(Char::isDigit)}"

    private fun clientDocIdFromUid(clientUid: String): String =
        "uid_${clientUid.filter { it.isLetterOrDigit() }}"

    private fun deriveClientStatus(credits: Int, freeFirstSupportUsed: Boolean): String {
        return when {
            !freeFirstSupportUsed -> "first_support_pending"
            credits > 0 -> "with_credit"
            else -> "without_credit"
        }
    }

    private fun normalizePhone(raw: String?): String? {
        val cleaned = raw
            ?.trim()
            ?.filter { it.isDigit() || it == '+' }
            ?.replace(" ", "")
            .orEmpty()
        if (cleaned.isBlank()) return null
        val digits = cleaned.filter(Char::isDigit)
        if (digits.length < 10) return null
        if (cleaned.startsWith("+")) return "+$digits"
        if (cleaned.startsWith("00") && digits.length > 2) return "+${digits.drop(2)}"
        if ((digits.length == 10 || digits.length == 11) && !digits.startsWith(DEFAULT_PHONE_COUNTRY_CODE)) {
            return "+$DEFAULT_PHONE_COUNTRY_CODE$digits"
        }
        if ((digits.length == 12 || digits.length == 13) && digits.startsWith(DEFAULT_PHONE_COUNTRY_CODE)) {
            return "+$digits"
        }
        return "+$digits"
    }

    private fun CreditPackageRecord.toMap(): Map<String, Any> = mapOf(
        "name" to name,
        "supportCount" to supportCount,
        "priceCents" to priceCents,
        "active" to active,
        "displayOrder" to displayOrder,
        "updatedAt" to System.currentTimeMillis()
    )

    private fun DocumentSnapshot.toClientRecord(): ClientRecord? {
        if (!exists()) return null
        return ClientRecord(
            id = id,
            phone = getString("phone").orEmpty(),
            name = getString("name"),
            primaryEmail = getString("primaryEmail"),
            notes = getString("notes"),
            credits = (getLong("credits") ?: 0L).toInt().coerceAtLeast(0),
            supportsUsed = (getLong("supportsUsed") ?: 0L).toInt().coerceAtLeast(0),
            freeFirstSupportUsed = getBoolean("freeFirstSupportUsed") ?: false,
            createdAt = getLong("createdAt") ?: 0L,
            updatedAt = getLong("updatedAt") ?: 0L,
            status = getString("status") ?: "without_credit"
        )
    }

    private fun DocumentSnapshot.toClientMetaRecord(defaultClientId: String): ClientMetaRecord? {
        if (!exists()) return null
        return ClientMetaRecord(
            clientId = getString("clientId") ?: defaultClientId,
            totalSessions = (getLong("totalSessions") ?: 0L).toInt(),
            totalPaidSessions = (getLong("totalPaidSessions") ?: 0L).toInt(),
            totalFreeSessions = (getLong("totalFreeSessions") ?: 0L).toInt(),
            totalCreditsPurchased = (getLong("totalCreditsPurchased") ?: 0L).toInt(),
            totalCreditsUsed = (getLong("totalCreditsUsed") ?: 0L).toInt(),
            lastSupportAt = getLong("lastSupportAt")
        )
    }

    private fun DocumentSnapshot.toClientVerificationRecord(
        defaultClientId: String,
        defaultPrimaryPhone: String?
    ): ClientVerificationRecord? {
        if (!exists()) return null
        return ClientVerificationRecord(
            clientId = getString("clientId") ?: defaultClientId,
            primaryPhone = getString("primaryPhone") ?: defaultPrimaryPhone,
            verifiedPhone = getString("verifiedPhone"),
            status = getString("status") ?: VERIFICATION_STATUS_PENDING,
            lastVerificationAt = getLong("lastVerificationAt"),
            updatedAt = getLong("updatedAt")
        )
    }

    private fun DocumentSnapshot.toCreditPackageRecord(): CreditPackageRecord? {
        if (!exists()) return null
        return CreditPackageRecord(
            id = id,
            name = getString("name") ?: return null,
            supportCount = (getLong("supportCount") ?: return null).toInt(),
            priceCents = (getLong("priceCents") ?: return null).toInt(),
            active = getBoolean("active") ?: true,
            displayOrder = (getLong("displayOrder") ?: 0L).toInt()
        )
    }

    private fun DocumentSnapshot.toSupportSessionRecord(): SupportSessionRecord? {
        if (!exists()) return null
        val clientId = getString("clientId") ?: return null
        return SupportSessionRecord(
            id = id,
            clientId = clientId,
            techId = getString("techId"),
            startedAt = getLong("startedAt") ?: 0L,
            endedAt = getLong("endedAt"),
            status = getString("status") ?: "queued",
            isFreeFirstSupport = getBoolean("isFreeFirstSupport") ?: false,
            creditsConsumed = (getLong("creditsConsumed") ?: 0L).toInt(),
            problemSummary = getString("problemSummary"),
            solutionSummary = getString("solutionSummary"),
            internalNotes = getString("internalNotes")
        )
    }

    private data class EnsureClientResult(
        val client: ClientRecord,
        val isNewClient: Boolean
    )

    private data class ResolvedClientResult(
        val client: ClientRecord
    )

    private data class CompletionTransactionResult(
        val completed: Boolean,
        val sessionId: String?,
        val clientId: String?
    )

    private companion object {
        const val RETENTION_DAYS_PNV_REQUESTS = 15L
        const val RETENTION_DAYS_SUPPORT_SESSIONS = 30L
        const val RETENTION_DAYS_SUPPORT_REPORTS = 30L
        const val MILLIS_PER_DAY = 86_400_000L

        const val VERIFICATION_STATUS_PENDING = "pending"
        const val VERIFICATION_STATUS_VERIFIED = "verified"
        const val VERIFICATION_STATUS_MISMATCH = "mismatch"
        const val VERIFICATION_STATUS_MANUAL_REQUIRED = "manual_required"

        const val PNV_REQUEST_STATUS_PENDING = "pending"
        const val PNV_REQUEST_STATUS_MANUAL_PENDING = "manual_pending"
        const val PNV_REQUEST_STATUS_PROCESSED = "processed"
        private const val DEFAULT_PHONE_COUNTRY_CODE = "55"
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
            }
        }
    }
