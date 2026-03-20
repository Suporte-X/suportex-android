package com.suportex.app.data

import com.google.firebase.firestore.FirebaseFirestore

object FirebaseDataSource {

    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
}
