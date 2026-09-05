plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aasra.tools"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

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
    // Public signatures (ContactTools, ReminderTools, SosTools) expose Room
    // DAOs/entities, so :data is an `api` dependency, not `implementation`.
    api(project(":data"))
    implementation("androidx.core:core-ktx:1.13.1")
    // Room supertypes leak into public signatures via :data entities/DAOs.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
}
