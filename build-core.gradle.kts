// Build raíz usado solo con -Ptrazo.coreOnly=true (sin Android Gradle Plugin).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
