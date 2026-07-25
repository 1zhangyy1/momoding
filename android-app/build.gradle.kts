plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.serialization) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
