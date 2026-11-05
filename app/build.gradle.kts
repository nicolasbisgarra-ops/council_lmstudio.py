import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// App Android nativa: Kotlin + Jetpack Compose para menús y una View nativa de baja
// latencia para la tinta (MotionEvent de S Pen). Sin WebView.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.trazo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.trazo"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Optional verified checkout for reproducible/offline builds; normal CI uses the pinned fetch.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                providers.gradleProperty("trazo.llamaSource").orNull?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=$it"
                }
            }
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Clave debug fija (no secreta): las APK de prueba de cada compilación de CI se actualizan
    // unas sobre otras sin desinstalar (con claves distintas Android exige borrar la app y sus datos).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // APK de prueba instalable: firmada con la clave debug. Cambiar antes de publicar.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        animationsDisabled = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ai"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // Solo el chat de revisión: LaTeX nativo (Canvas), sin WebView ni red.
    implementation("ru.noties:jlatexmath-android:0.2.0")
    // QR para vincular dispositivos (escanear con la cámara y dibujar el código). Todo local, sin servicios.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// Robolectric resolves its Android runtime in the test JVM, outside Gradle's resolver.
tasks.withType<Test>().configureEach {
    // Compose/Espresso idlers and Robolectric native graphics retain process-wide state.
    // Run each test class in a fresh JVM so GUI checks cannot contaminate one another.
    forkEvery = 1

    System.getProperty("trazo.userPatterns")?.let { systemProperty("trazo.userPatterns", it) }
    System.getProperty("trazo.userNotes")?.let { systemProperty("trazo.userNotes", it) }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}
