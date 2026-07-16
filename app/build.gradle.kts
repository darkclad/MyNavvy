plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.dvladi.mynavvy"
    compileSdk = 36

    // -PsimEnabled=true|false overrides the per-build-type default for the debug simulator.
    // The publish/deliver script passes -PsimEnabled=false so shipped APKs never include it.
    val simEnabledOverride: Boolean? = when ((project.findProperty("simEnabled") as String?)?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    defaultConfig {
        applicationId = "com.dvladi.mynavvy"
        // minSdk 21 so a ~2018 tablet (Android 5.0+) can run it.
        minSdk = 21
        targetSdk = 34
        versionCode = 51
        versionName = "0.51"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GlitchTip DSN for crash/log reporting. Not a secret (it's a client
        // ingest key, safe to embed in the APK). Override per-machine with a
        // `SENTRY_DSN=...` line in gradle.properties or `-PSENTRY_DSN=...`.
        val sentryDsn = (project.findProperty("SENTRY_DSN") as String?)
            ?: "https://8b0fc1fcea9b4356b8ff7ccfec60e169@glitchtip.darkclad.org/1"
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // Fallback for any build type that doesn't override it below.
        buildConfigField("boolean", "SIM_ENABLED", (simEnabledOverride ?: false).toString())
    }

    buildTypes {
        debug {
            // Emulator/dev builds default the simulator ON. Flip with -PsimEnabled=false to
            // produce a debug APK that behaves like the field build (real GPS, no SIM_FIX).
            buildConfigField("boolean", "SIM_ENABLED", (simEnabledOverride ?: true).toString())
        }
        release {
            // Field / delivered APK: the simulator is ALWAYS compiled out — no SIM_FIX
            // receiver, and the app always uses the real GPS.
            buildConfigField("boolean", "SIM_ENABLED", "false")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.maplibre)
    implementation(libs.nanohttpd)
    implementation(libs.sentry.android)
    implementation(libs.androidx.splashscreen)

    // Jetpack Compose (BOM-managed versions). UI chrome is built in Compose; MapLibre's MapView is
    // hosted via AndroidView interop; instrument gauges draw into Compose Canvas via nativeCanvas.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
