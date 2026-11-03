# Trazo 0.2.0-preview — APK de prueba

Fecha: 30/10/2026. Base: `6009d09c7e42f7b816914ca98392ef459c681268`.

Esta APK contiene la reconstrucción inicial de IA local (llama.cpp y MediaPipe), importación de modelos, práctica y examen básicos, barra adaptable y arreglos de importación y orden. Los pesos de modelos se importan por separado. MediaPipe revisa solo texto reconocido con consentimiento explícito; los GGUF con visión requieren un proyector compatible.

Las entregas avanzadas de los agentes web siguen pendientes de revisión e integración; esta compilación no las presenta como incorporadas. En particular, el descargador, la nueva cola durable y el examen robusto de esas entregas no sustituyen todavía sus componentes iniciales.

Compilación real: Gradle 8.11.1, Kotlin 2.1.0, serialization 1.7.3, JDK 17, SDK 35, NDK 27.2.12479018 y CMake 3.22.1. Resultado: `BUILD SUCCESSFUL`; 7668 tests core, 62 IA y 17 app, sin fallos, errores ni omitidos.

Se verificaron la firma APK v2 y el certificado coincidente con la APK anterior; versionCode 2 permite actualizarla. Las 16 bibliotecas nativas empaquetadas tienen segmentos LOAD de 16 KB; zipalign también pasó. Incluye arm64-v8a y x86_64; minSdk 26.

APK SHA-256: `c646cd54a6ca7f25caae6557df5fa47d11dc9ad6e7580da15e5d38b91ecd1bee`.

No se ejecutaron esta APK ni modelos en Android, emulador, S24 Ultra o Tab S11. La compilación y las pruebas JVM no certifican interacción física, S Pen, inferencia, latencia o comportamiento del dispositivo.
