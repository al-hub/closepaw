plugins { id("com.android.application") }
android {
    namespace = "com.termux"
    compileSdk = 36
    defaultConfig { applicationId = "com.termux"; minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "fixture" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
