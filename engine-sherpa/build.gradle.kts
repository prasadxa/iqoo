plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aasra.sherpa"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        // sherpa-onnx ships its own native libs inside the JitPack AAR.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ── sherpa-onnx / JitPack note for the integrator (Track A owns root files) ─
    // This module needs ONE root-owned change to resolve the dependency below:
    // add `maven { url = uri("https://jitpack.io") }` to the `repositories` block
    // in settings.gradle.kts (dependencyResolutionManagement). Track B does not
    // touch root gradle files, so until that lands this dependency stays
    // unresolved — every wrapper in this module degrades gracefully (ready=false)
    // and the module still compiles because sherpa is only touched via reflection.
    // Pinned to the v1.12.x line per PLAN 4.1 (Llamatik-era fork baseline).
    // ────────────────────────────────────────────────────────────────────────────

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":core-pipeline"))
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
