package com.suportex.app.engagement

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.suportex.app.BuildConfig

class GooglePlayReviewPrompt(
    context: Context
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onInternalRatingSubmitted(activity: Activity) {
        incrementCompletedSupportCount()
        requestReview(activity)
    }

    fun requestReview(activity: Activity, onFinished: (Boolean) -> Unit = {}) {
        val eligibility = evaluateEligibility(activity)
        if (!eligibility.eligible) {
            logEvent("play_review_not_eligible_reason", eligibility.reason)
            onFinished(false)
            return
        }

        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_REQUEST_AT, now)
            .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
            .putInt(KEY_REQUEST_COUNT, prefs.getInt(KEY_REQUEST_COUNT, 0) + 1)
            .apply()

        logEvent("play_review_eligible")
        logEvent("play_review_request_started")

        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow()
            .addOnCompleteListener { requestTask ->
                if (!requestTask.isSuccessful) {
                    logEvent("play_review_flow_failed", requestTask.exception?.javaClass?.simpleName)
                    onFinished(false)
                    return@addOnCompleteListener
                }

                logEvent("play_review_info_received")
                if (activity.isFinishing || activity.isDestroyed) {
                    logEvent("play_review_flow_failed", "activity_invalid_before_launch")
                    onFinished(false)
                    return@addOnCompleteListener
                }

                manager.launchReviewFlow(activity, requestTask.result)
                    .addOnCompleteListener { launchTask ->
                        logEvent("play_review_flow_launched")
                        if (launchTask.isSuccessful) {
                            logEvent("play_review_flow_completed")
                            onFinished(true)
                        } else {
                            logEvent("play_review_flow_failed", launchTask.exception?.javaClass?.simpleName)
                            onFinished(false)
                        }
                    }
            }
    }

    private fun incrementCompletedSupportCount() {
        prefs.edit()
            .putInt(KEY_COMPLETED_SUPPORT_COUNT, prefs.getInt(KEY_COMPLETED_SUPPORT_COUNT, 0) + 1)
            .apply()
        logEvent("internal_rating_submitted")
    }

    private fun evaluateEligibility(activity: Activity): Eligibility {
        if (activity.isFinishing || activity.isDestroyed) {
            return Eligibility(false, "activity_invalid")
        }
        if (!isInstalledFromPlayStore()) {
            return Eligibility(false, "not_installed_from_play_store")
        }
        if (prefs.getInt(KEY_LAST_VERSION_CODE, -1) == BuildConfig.VERSION_CODE) {
            return Eligibility(false, "already_requested_this_version")
        }
        val lastRequestAt = prefs.getLong(KEY_LAST_REQUEST_AT, 0L)
        if (lastRequestAt > 0L && System.currentTimeMillis() - lastRequestAt < REVIEW_COOLDOWN_MS) {
            return Eligibility(false, "cooldown_active")
        }
        return Eligibility(true, "eligible")
    }

    @Suppress("DEPRECATION")
    private fun isInstalledFromPlayStore(): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appContext.packageManager.getInstallSourceInfo(appContext.packageName).installingPackageName
            } else {
                appContext.packageManager.getInstallerPackageName(appContext.packageName)
            }
        }.getOrNull()
        return installer == PLAY_STORE_PACKAGE
    }

    private fun logEvent(name: String, detail: String? = null) {
        if (detail.isNullOrBlank()) {
            Log.i(TAG, name)
        } else {
            Log.i(TAG, "$name: $detail")
        }
    }

    private data class Eligibility(
        val eligible: Boolean,
        val reason: String
    )

    private companion object {
        const val TAG = "SXS/PlayReview"
        const val PREFS_NAME = "play_review_prompt"
        const val KEY_LAST_REQUEST_AT = "lastPlayReviewRequestAt"
        const val KEY_LAST_VERSION_CODE = "lastPlayReviewVersionCode"
        const val KEY_COMPLETED_SUPPORT_COUNT = "completedSupportCount"
        const val KEY_REQUEST_COUNT = "playReviewRequestCount"
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val REVIEW_COOLDOWN_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
