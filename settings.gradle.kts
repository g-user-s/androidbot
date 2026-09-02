pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "alf"

include(":domain:assistant")
include(":data:nlu")
include(":data:dsp")

// The Android modules need an SDK installed; the modules above do not. Including them only when
// one is present keeps the core buildable and testable anywhere — a container without the SDK,
// a reviewer's laptop — instead of making an Android toolchain a prerequisite for running unit
// tests. CI's android job sets ANDROID_HOME and asks for the Android tasks by name, so a missing
// SDK there fails the job rather than quietly skipping the compile.
val androidSdkPresent = sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
    .any { !System.getenv(it).isNullOrBlank() } || file("local.properties").exists()

if (androidSdkPresent) {
    include(":data:audio")
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle("No Android SDK found — configuring the JVM core only.")
    }
}
