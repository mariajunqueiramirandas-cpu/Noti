plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin): no separate Kotlin plugin.
    id("com.android.application") version "9.5.0" apply false
    // Must match the Kotlin Gradle plugin that AGP's built-in Kotlin uses (2.2.10 for AGP 9.5).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
