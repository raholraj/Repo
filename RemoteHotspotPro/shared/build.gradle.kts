plugins {
    id("com.android.library"); id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.remotehotspot.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
}
dependencies {
    api(platform("com.google.firebase:firebase-bom:33.1.2"))
    api("com.google.firebase:firebase-database-ktx")
    api("com.google.firebase:firebase-auth-ktx")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("org.json:json:20240303")
}
