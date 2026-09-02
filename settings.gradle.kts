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

// The Android modules need an SDK installed; the modules above do not. Including them only on
// request keeps the core buildable and testable anywhere — a container without the SDK, a
// reviewer's laptop — instead of making an Android toolchain a prerequisite for running unit
// tests. CI's android job passes -Palf.android=true and asks for the Android tasks by name, so a
// broken Android build fails that job rather than being quietly skipped.
//
// An environment variable would be the obvious switch and is the wrong one: GitHub's runner
// images ship an Android SDK, so ANDROID_HOME is set even on the job that is meant to prove the
// core builds without one.
val androidRequested = startParameter.projectProperties["alf.android"]?.toBoolean() == true
val localSdkConfigured = file("local.properties").exists()

if (androidRequested || localSdkConfigured) {
    include(":data:audio")
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle("Android modules skipped — pass -Palf.android=true to include them.")
    }
}
