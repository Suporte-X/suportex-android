package com.suportex.app.data

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.suportex.app.data.model.ClientFinancialStatus
import com.suportex.app.data.model.ClientHomeSnapshot
import com.suportex.app.data.model.ClientMetaRecord
import com.suportex.app.data.model.ClientRecord
import com.suportex.app.data.model.CreditOrderRecord
import com.suportex.app.data.model.CreditPackageRecord
import com.suportex.app.data.model.SupportAccessDecision
import com.suportex.app.data.model.SupportSessionRecord
import com.suportex.app.data.model.SupportStartContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClientSupportRepository(
    private val db: FirebaseFirestore = FirebaseDataSource.db,
    private val authRepository: AuthRepository = AuthRepository()
) {
    private val clients = db.collection("clients")
    private val clientProfiles = db.collection("client_profiles")
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

    suspend fun loadHomeSnapshot(rawPhone: String?): ClientHomeSnapshot {
        authRepository.ensureAnonAuth()
        val packages = listCreditPackages()
        val normalizedPhone = normalizePhone(rawPhone)
        if (normalizedPhone == null) {
            return ClientHomeSnapshot(
                phone = null,
                client = null,
                clientMeta = null,
                packages = packages
            )
        }

        val clientDocId = clientDocIdFromPhone(normalizedPhone)
        val client = clients.document(clientDocId).get().await().toClientRecord()
        val meta = if (client != null) {
            clientProfiles.document(client.id).get().await().toClientMetaRecord(client.id)
        } else {
            null
        }

        return ClientHomeSnapshot(
            phone = normalizedPhone,
            client = client,
            clientMeta = meta,
            packages = packages
        )
    }

    suspend fun evaluateSupportAccess(rawPhone: String, displayName: String?): SupportAccessDecision {
        authRepository.ensureAnonAuth()
        val normalizedPhone = normalizePhone(rawPhone) ?: return SupportAccessDecision.NeedsPhone
        val clientResult = ensureClientByPhone(
            normalizedPhone = normalizedPhone,
            displayName = displayName
        )

        val client = clientResult.client
        val freePending = !client.freeFirstSupportUsed
        if (freePending) {
            return SupportAccessDecision.Allowed(
                startContext = SupportStartContext(
                    clientId = client.id,
                    phone = normalizedPhone,
                    isNewClient = clientResult.isNewClient,
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
                    phone = normalizedPhone,
                    isNewClient = clientResult.isNewClient,
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
        supportSessions.document(normalized).set(
            mapOf(
                "status" to "cancelled",
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()
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
        val reportRef = supportReports.document()
        reportRef.set(
            mapOf(
                "sessionId" to sessionId,
                "clientId" to clientId,
                "techId" to techId,
                "createdAt" to now,
                "summary" to summary,
                "actionsTaken" to actionsTaken,
                "solutionApplied" to solutionApplied,
                "followUpNeeded" to followUpNeeded
            ).filterValues { it != null }
        ).await()
        supportSessions.document(sessionId).set(
            mapOf(
                "reportId" to reportRef.id,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
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
        val clientRef = clients.document(clientId)
        val profileRef = clientProfiles.document(clientId)
        val now = System.currentTimeMillis()
        return db.runTransaction { tx ->
            val snap = tx.get(clientRef)
            val isNewClient = !snap.exists()
            val credits = (snap.getLong("credits") ?: 0L).toInt().coerceAtLeast(0)
            val supportsUsed = (snap.getLong("supportsUsed") ?: 0L).toInt().coerceAtLeast(0)
            val freeUsed = snap.getBoolean("freeFirstSupportUsed") ?: false
            val createdAt = snap.getLong("createdAt") ?: now
            val existingName = snap.getString("name")
            val name = displayName?.takeIf { it.isNotBlank() } ?: existingName
            val primaryEmail = snap.getString("primaryEmail")
            val notes = snap.getString("notes")
            val status = deriveClientStatus(credits = credits, freeFirstSupportUsed = freeUsed)

            tx.set(
                clientRef,
                mapOf(
                    "phone" to normalizedPhone,
                    "name" to name,
                    "primaryEmail" to primaryEmail,
                    "notes" to notes,
                    "credits" to credits,
                    "supportsUsed" to supportsUsed,
                    "freeFirstSupportUsed" to freeUsed,
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
                    phone = normalizedPhone,
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
        return if (cleaned.startsWith("+")) cleaned else "+$digits"
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

    private data class CompletionTransactionResult(
        val completed: Boolean,
        val sessionId: String?,
        val clientId: String?
    )
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
