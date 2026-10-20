// AGP 9+: Kotlin ist eingebaut, kein org.jetbrains.kotlin.android Plugin nötig.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.reygnn.osview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.reygnn.osview"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    // Keine Abhängigkeits-Metadaten im Signing-Block
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Kotlin-Metadaten aus der APK werfen
    packaging {
        resources {
            excludes += setOf("META-INF/**", "kotlin/**", "**.kotlin_builtins", "DebugProbesKt.bin")
        }
    }
}

kotlin {
    compilerOptions {
        // Keine Null-Check-Intrinsics generieren
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

// Keine dependencies: kotlin-stdlib kommt automatisch, R8 lässt nur Benutztes übrig.
