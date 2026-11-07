// The original Home 1.7.1 source is compiled from the pinned upstream-home-1.7.1/ snapshot; the desktop home/ is unchanged.
// Signing is injected using the upstream key, exactly as scripts/build-home.ts does.
plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "io.github.wingedpanther.cleanmytv.home"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.wingedpanther.cleanmytv.home"
        minSdk = 28; targetSdk = 35; versionCode = 19; versionName = "1.7.1"
    }
    sourceSets["main"].apply {
        manifest.srcFile("../upstream-home-1.7.1/app/src/main/AndroidManifest.xml")
        java.srcDirs("../upstream-home-1.7.1/app/src/main/java")
        res.srcDirs("../upstream-home-1.7.1/app/src/main/res")
        assets.srcDirs("../upstream-home-1.7.1/app/src/main/assets")
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material:material-icons-extended")
}
