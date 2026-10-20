// AGP 9+: Kotlin ist eingebaut, kein org.jetbrains.kotlin.android Plugin nötig.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.reygnn.osview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.reygnn.osview"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
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

    // Java 21 end to end.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources.
            isIncludeAndroidResources = true
            // Plain JVM tests fail loudly on any android.* call instead of getting silent
            // 0/null/false from the stub jar — SysParse.kt must stay free of Android types.
            // (Robolectric tests are unaffected: they run against a real android-all jar.)
            isReturnDefaultValues = false
            all {
                // Robolectric self-attaches a ByteBuddy agent; JDK 21 wants that opted into.
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
                // Conscrypt breaks on a Turkish-locale host; osview uses no TLS.
                it.systemProperty("robolectric.conscryptMode", "OFF")
            }
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
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // Keine Null-Check-Intrinsics generieren
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

// No PRODUCTION dependencies: kotlin-stdlib comes automatically, R8 keeps only what's used.
// Every entry below is testImplementation, so none of it enters the shipped APK.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
