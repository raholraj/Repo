plugins {
    id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.gms.google-services")
}
android {
    namespace = "com.remotehotspot.user"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.remotehotspot.user"
        minSdk = 26; targetSdk = 34
        versionCode = 2; versionName = "2.0.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.google.firebase:firebase-messaging-ktx")
}
