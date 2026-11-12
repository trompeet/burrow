plugins {
    alias(libs.plugins.android.application) apply false
    // Pins the Kotlin Gradle plugin version used by AGP's built-in Kotlin support.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
