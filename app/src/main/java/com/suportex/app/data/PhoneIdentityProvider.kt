package com.suportex.app.data

import com.google.firebase.auth.FirebaseAuth

interface PhoneIdentityProvider {
    suspend fun getVerifiedPhoneNumber(): String?
}

class FirebasePhoneIdentityProvider(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) : PhoneIdentityProvider {
    override suspend fun getVerifiedPhoneNumber(): String? {
        return firebaseAuth.currentUser?.phoneNumber
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
