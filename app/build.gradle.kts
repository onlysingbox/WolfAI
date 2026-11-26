plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val wolfStoreFile = System.getenv("WOLF_KEYSTORE_FILE")
val wolfStorePassword = System.getenv("WOLF_KEYSTORE_PASSWORD")
val wolfKeyAlias = System.getenv("WOLF_KEY_ALIAS") ?: "wolfai"
val wolfKeyPassword = System.getenv("WOLF_KEY_PASSWORD") ?: wolfStorePassword
val hasReleaseSigning = !wolfStoreFile.isNullOrBlank() &&
    !wolfStorePassword.isNullOrBlank() &&
    !wolfKeyAlias.isNullOrBlank() &&
    !wolfKeyPassword.isNullOrBlank()

android {
    namespace = "com.onlysingbox.wolfai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onlysingbox.wolfai"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("wolfRelease") {
                storeFile = file(wolfStoreFile!!)
                storePassword = wolfStorePassword
                keyAlias = wolfKeyAlias
                keyPassword = wolfKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("wolfRelease")
            }
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
