plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.cleanmytv.transport"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    implementation(project(":pairing"))
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
