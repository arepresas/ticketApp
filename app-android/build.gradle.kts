// Root build script. Plugins are declared here (apply false) and applied
// in `:app`, so the version catalog is the single place where the
// toolchain versions live.
//
// Note: AGP 9 ships built-in Kotlin support, so `kotlin.android` is
// NOT applied — applying it is a hard error since 9.0. The Compose
// compiler and the serialization compiler still ship as separate Kotlin
// plugins because they transform code rather than provide a toolchain.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}