import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Modo IA: perfiles de modelos, clientes HTTP de cada proveedor y lectura de las correcciones.
// Kotlin puro (sin Android) para probarlo en JVM; el motor (:core) sigue sin red.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
    }
}
