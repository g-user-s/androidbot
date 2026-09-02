// Deliberately declares no plugins.
//
// A plugin declared here — even with `apply false` — lands on a classloader that is the parent of
// every subproject's. The Kotlin Android plugin needs to see the Android Gradle Plugin, and it
// cannot see one that a subproject added to a child classloader: the failure is an obscure
// "could not generate a decorated class ... com/android/build/gradle/api/BaseVariant".
//
// Letting each module declare its own plugins puts AGP and Kotlin in the same classloader, and
// keeps the Android toolchain out of the build entirely when the Android modules are excluded.
