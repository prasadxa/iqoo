pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Aasra Voice Companion"

// Track A owns: settings wiring + app module.
// core-audio, core-pipeline, engine-sherpa, engine-llama,
// cloud-callmissed, tools, models, data are owned by other
// tracks; they are included here so the final graph resolves.
include(
    ":app",
    ":core-audio",
    ":core-pipeline",
    ":engine-sherpa",
    ":engine-llama",
    ":cloud-callmissed",
    ":tools",
    ":models",
    ":data"
)
