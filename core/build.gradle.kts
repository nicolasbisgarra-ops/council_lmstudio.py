import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Motor de Trazo: modelo de tinta, detección de pausa, reconocimiento, historial,
// borrador, lazo y persistencia. Kotlin puro: no depende de Android y se prueba en JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
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
    testFixturesApi(libs.junit)
}

tasks.test {
    // Las pruebas de evidencia escriben renders PNG del motor en build/reports/trazo-evidence.
    // Es salida de la tarea: si la caché de Gradle evita correr las pruebas, igual restaura la evidencia.
    val evidence = layout.buildDirectory.dir("reports/trazo-evidence")
    outputs.dir(evidence)
    systemProperty("trazo.evidenceDir", evidence.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    systemProperty("trazo.frames", providers.gradleProperty("trazo.frames").orNull ?: "false")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
