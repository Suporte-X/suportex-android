import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // NOVO:
    id("com.google.gms.google-services")
}

android {
    namespace = "com.suportex.app"
    // use um SDK estável que você tenha instalado:
    compileSdk = 36

    defaultConfig {
        applicationId = "com.suportex.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Compose (mantém as suas)
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    // Ícones para os botões (microfone, anexo, telefone, etc.)
    implementation("androidx.compose.material:material-icons-extended:1.7.2")

    // OkHttp / WebRTC / Socket.IO (seus)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:137.7151.04")
    implementation("io.socket:socket.io-client:2.1.0") { exclude(group = "org.json", module = "json") }
    implementation("com.google.android.material:material:1.12.0")

    // --- NOVO: Firebase (use BOM para alinhar versões) ---
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    // (opcional) se quiser auth anônima para separar usuários
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-pnv:16.0.0")

    implementation("com.google.firebase:firebase-analytics")

    // Coroutines helpers para Tasks.await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Testes…
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.compose.animation:animation")

    // Áudio no chat
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
