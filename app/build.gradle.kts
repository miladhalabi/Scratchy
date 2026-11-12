plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scratchy.app"
    compileSdk = 35

    defaultConfig {
        // -PappId=com.scratchy.app.demo builds a separate copy that seeds made-up notes (for screenshots).
        applicationId = (project.findProperty("appId") as String?) ?: "com.scratchy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.0"
    }

    // Release signing. Published builds use your own private key, given outside the repository: in
    // ~/.gradle/gradle.properties (scratchy.keystore, scratchy.storePassword, scratchy.keyAlias, scratchy.keyPassword)
    // or, for CI, as SCRATCHY_KEYSTORE, SCRATCHY_STORE_PASSWORD, SCRATCHY_KEY_ALIAS, SCRATCHY_KEY_PASSWORD.
    // Without them it is a personal build, signed with the debug key so it installs over the debug app.
    fun secret(name: String, env: String) = (project.findProperty("scratchy.$name") as String?) ?: System.getenv(env)
    val keystore = secret("keystore", "SCRATCHY_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = secret("storePassword", "SCRATCHY_STORE_PASSWORD")
                keyAlias = secret("keyAlias", "SCRATCHY_KEY_ALIAS")
                keyPassword = secret("keyPassword", "SCRATCHY_KEY_PASSWORD")
            } else {
                storeFile = file(System.getProperty("user.home") + "/.config/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.9.1")
    // Installs the startup profile below, so Android compiles the card and bar code ahead of time.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    testImplementation("junit:junit:4.13.2")
}
