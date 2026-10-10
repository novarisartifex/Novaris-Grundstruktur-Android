plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace = "artifex.novaris.grundstruktur"; compileSdk = 35
 defaultConfig { applicationId = "artifex.novaris.grundstruktur"; minSdk = 26; targetSdk = 35; versionCode = 201; versionName = "0.2.1" }
 signingConfigs {
  create("novarisRelease") {
   val keystore = System.getenv("NOVARIS_SIGNING_STORE_FILE")
   if (!keystore.isNullOrBlank()) storeFile = file(keystore)
   storePassword = System.getenv("NOVARIS_SIGNING_STORE_PASSWORD")
   keyAlias = System.getenv("NOVARIS_SIGNING_KEY_ALIAS")
   keyPassword = System.getenv("NOVARIS_SIGNING_KEY_PASSWORD")
  }
 }
 buildTypes {
  getByName("release") {
   signingConfig = signingConfigs.getByName("novarisRelease")
   isMinifyEnabled = false
  }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
