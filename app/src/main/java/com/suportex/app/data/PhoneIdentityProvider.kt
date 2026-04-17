package com.suportex.app.data

import android.app.Activity
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.pnv.FirebasePhoneNumberVerification
import kotlinx.coroutines.tasks.await

data class PhonePnvSupportInfo(
    val hasSupportedSim: Boolean,
    val rawResultCount: Int,
    val failureReason: String? = null
)

sealed class PhonePnvVerificationResult {
    data class Success(
        val phoneNumber: String,
        val token: String
    ) : PhonePnvVerificationResult()

    data class Failure(
        val reason: String,
        val userCancelled: Boolean
    ) : PhonePnvVerificationResult()
}

interface PhoneIdentityProvider {
    suspend fun getVerifiedPhoneNumber(): String?
    suspend fun saveVerifiedPhoneNumber(phoneNumber: String?)
    suspend fun checkPnvSupport(): PhonePnvSupportInfo
    suspend fun verifyWithPnv(activity: Activity): PhonePnvVerificationResult
}

class FirebasePhoneIdentityProvider(
    context: Context,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) : PhoneIdentityProvider {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyPrefsIfNeeded()
    }

    override suspend fun getVerifiedPhoneNumber(): String? {
        val cached = prefs.getString(KEY_VERIFIED_PHONE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (!cached.isNullOrBlank()) return cached
        return firebaseAuth.currentUser?.phoneNumber
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun saveVerifiedPhoneNumber(phoneNumber: String?) {
        prefs.edit()
            .putString(KEY_VERIFIED_PHONE, phoneNumber?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    override suspend fun checkPnvSupport(): PhonePnvSupportInfo {
        return try {
            val results = FirebasePhoneNumberVerification.getInstance()
                .getVerificationSupportInfo()
                .await()
            PhonePnvSupportInfo(
                hasSupportedSim = results.any { it.isSupported() },
                rawResultCount = results.size
            )
        } catch (error: Exception) {
            PhonePnvSupportInfo(
                hasSupportedSim = false,
                rawResultCount = 0,
                failureReason = error.message?.take(220) ?: error.javaClass.simpleName
            )
        }
    }

    override suspend fun verifyWithPnv(activity: Activity): PhonePnvVerificationResult {
        return try {
            val result = FirebasePhoneNumberVerification.getInstance(activity)
                .getVerifiedPhoneNumber()
                .await()
            val phoneNumber = result.getPhoneNumber().trim()
            val token = result.getToken().toString().trim()
            if (phoneNumber.isBlank() || token.isBlank()) {
                return PhonePnvVerificationResult.Failure(
                    reason = "pnv_empty_response",
                    userCancelled = false
                )
            }
            saveVerifiedPhoneNumber(phoneNumber)
            PhonePnvVerificationResult.Success(
                phoneNumber = phoneNumber,
                token = token
            )
        } catch (error: Exception) {
            PhonePnvVerificationResult.Failure(
                reason = error.message?.take(220) ?: error.javaClass.simpleName,
                userCancelled = isUserCancellation(error)
            )
        }
    }

    private fun isUserCancellation(error: Throwable): Boolean {
        val msg = error.message?.lowercase().orEmpty()
        return msg.contains("cancel") ||
            msg.contains("declin") ||
            msg.contains("dismiss")
    }

    private fun migrateLegacyPrefsIfNeeded() {
        val hasCurrentValue = !prefs.getString(KEY_VERIFIED_PHONE, null).isNullOrBlank()
        if (hasCurrentValue) return

        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyValue = legacyPrefs.getString(KEY_VERIFIED_PHONE, null)?.trim()?.takeIf { it.isNotBlank() }
        if (legacyValue.isNullOrBlank()) return

        prefs.edit()
            .putString(KEY_VERIFIED_PHONE, legacyValue)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "suporte_x_pnv_identity"
        const val LEGACY_PREFS_NAME = "supp" + "ortx_pnv_identity"
        const val KEY_VERIFIED_PHONE = "verified_phone"
    }
}
