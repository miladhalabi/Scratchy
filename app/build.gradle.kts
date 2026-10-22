plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scratchy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scratchy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Springs: interruptible, velocity-aware motion (damping ratio + stiffness).
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
