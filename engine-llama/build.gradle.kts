plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aasra.llama"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        // llama.cpp ships its own native lib via the Llamatik AAR / JNI binding.
        // Do NOT lower minSdk: Vulkan + 16 KB page-size assumptions need API 29+.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ── JNI / CMake note for the integrator ──────────────────────────────────
    // Option A (preferred): consume llama.cpp through the Llamatik Android
    // binding AAR that the speech-to-speech-mobile fork already pulls in
    // (see PLAN 4.1). In that case NO externalNativeBuild block is needed here;
    // LlamaEngine talks to `com.llamatik.*` classes only, and this module stays
    // pure Kotlin (it compiles today with zero .so files present).
    //
    // Option B (vendored llama.cpp): drop the llama.cpp tree under
    //   engine-llama/src/main/cpp/llama.cpp
    // add a CMakeLists.txt building a `llama_jni` shared lib, then uncomment:
    //   externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    // and set `LlamaEngine.backend = JniLlamaBackend()` once the symbols exist.
    // Vulkan offload lives behind LlamaLoadParams.preferVulkan so a failed GPU
    // init always falls back to CPU instead of crashing load.
    // ─────────────────────────────────────────────────────────────────────────

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.llamatik:library:1.7.0")
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.18.0")
    // Tool-call JSON is parsed with android.util/org.json (in-framework) so this
    // module needs no serialization plugin to compile. If @Serializable models
    // are introduced later, add `org.jetbrains.kotlin.plugin.serialization` above.
}
