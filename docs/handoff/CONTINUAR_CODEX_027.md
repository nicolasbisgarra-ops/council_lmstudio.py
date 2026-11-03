# Continuar Trazo desde la entrega 0.2.7

Este commit de integración recupera la entrega de Codex que estaba en Drive y todavía no había llegado a GitHub. Parte de `b3365c2eed4528ff8a4bed60099f1e3e27e15163`, último commit de Claude al preparar esta integración.

`ai/src/main/kotlin/io/trazo/ai/OnDevice.kt` conserva exactamente el contenido de ese commit. Los cambios de Codex se agregan encima, sin reescribir la rama de Claude. GitHub rechazó la escritura desde la conexión de ChatGPT con HTTP 403; este paquete permite importar el commit local con Git.

## Qué se recuperó

- Trazo 0.2.7, versionCode 10: 81 archivos nuevos y 35 modificados respecto de la base, además de esta documentación.
- Revisiones IA manuales. Abrir la app, recuperar conexión, navegar y terminar otra revisión no envían la cola automáticamente.
- Imagen manuscrita y etiquetas originales compactas para el modelo. No se envían la interpretación ni los resultados del motor de resolución.
- Eliminación de UUID y listas internas de trazos del pedido. En TP2, página 2, el texto pasó de 29.842 a 3.228 tokens locales de referencia, excluyendo la imagen.
- Registro de uso informado por los proveedores, sin sustituir datos ausentes por estimaciones.
- Reajuste de la transformación al cambiar de página, antes del lápiz, pellizco o dibujo. Invalidación de la caché al cambiar entre bloc y cuaderno.
- Espacio de escritura 3×3, selector de páginas inicialmente cerrado, deduplicación de patrones al importar y esfuerzos por modelo.
- Implementación local, almacén y descargas de modelos, control de sesiones, recuperación del progreso, correcciones de exportación y controles de evidencias en CI.

## Etapa 3: revisar la implementación existente

La entrega ya contiene código para inferencia local. `OnDevice.kt` no es el único punto de partida:

| Función | Archivo |
| --- | --- |
| Contrato de inferencia usado por `AiClient` | `ai/.../OnDeviceCompletion.kt` |
| Adaptación de la revisión local | `ai/.../OnDeviceReviewAdapter.kt` |
| Conexión de la app con el contrato | `app/.../ai/LocalCompletion.kt` |
| Interfaz de motores | `app/.../ai/LocalInference.kt` |
| Implementación llama.cpp | `app/.../ai/LlamaCppInference.kt` y `app/src/main/cpp/llama_jni.cpp` |
| Implementación MediaPipe | `app/.../ai/MediaPipeLocalInference.kt` |
| Persistencia, descargas y uso de archivos | `app/.../data/LocalModelStore.kt`, `LocalModelDownloads.kt` y `LocalModelLeases.kt` |
| Pantalla de modelos locales | `app/.../ui/LocalModelsScreen.kt` |
| Registro en la app | `app/.../TrazoApp.kt` |

El nuevo catálogo de Claude permanece disponible. Su contrato `OnDevice.engine` y el contrato `OnDeviceCompletion` son distintos: no conectar ambos sin revisar el flujo existente. La generación con modelos reales en S24 Ultra o Tab S11 todavía requiere validación en hardware.

## Entrega comprobada en Drive

Los tres archivos siguientes estaban publicados desde el 31 de octubre de 2026. La ausencia de commits remotos no significaba ausencia de esos archivos.

- [Fuente completa, pruebas y registros](https://drive.google.com/file/d/16kH-a3yLiHoYYxP0gOXtO-2A_-7DZa55/view?usp=drivesdk).
- [APK 0.2.7](https://drive.google.com/file/d/1u9BRYH046NR2PCl-NvpTNNQ23uqemdve/view?usp=drivesdk).
- [Informe 91: cambios, mediciones y límites](https://drive.google.com/file/d/1v2It_471mPKs-KLk895BUJN-1v3YByQD/view?usp=drivesdk).

SHA256 del ZIP de fuentes: `f36c3d68102f128d8111a2003a9be1c4804e4ddca2d295e01e83f6906fd3f41b`.

SHA256 del APK: `f9938771ae61d9e8e733451ab918518e3067f6484d2c911fa8b5781f014119e9`.

El commit local de aquella compilación fue `625ed66d81cc559a84b28d88d9cf50d0594f5608`. Ese identificador no era un commit publicado en GitHub. La integración actual tiene su propia historia a partir de `b3365c2`.

## Pruebas y límites

La entrega 0.2.7 incluye evidencia de 7.985 pruebas aprobadas: core 7.728, AI 144 y app 113. Los XML JUnit y el log están dentro del ZIP, en `validation/`.

Esos resultados corresponden a la compilación anterior, no a una ejecución nueva de CI sobre esta rama. Las tres notas privadas usadas para recorrer 14 páginas están en el ZIP del Drive; no se incorporan al repositorio público. Las pruebas opcionales requieren `-Dtrazo.userNotes` y `-Dtrazo.userPatterns`; sin esos archivos pueden omitirse.

No se reprodujo el cierre fatal original del Samsung. Las regresiones de navegación, igual y autocompletado pasaron en Robolectric. Hace falta probar el comportamiento en el dispositivo y recuperar su informe de cierre si vuelve a ocurrir.

Al preparar esta integración se volvieron a ejecutar core y AI: pasaron 7.872 pruebas (7.728 + 144), sin fallos, errores ni omisiones. Esta ejecución incluye el archivo de catálogo de Claude intacto.

Para repetir las pruebas sin Android SDK:

```bash
./gradlew -Ptrazo.coreOnly=true :core:test :ai:test
```

Con Android SDK/NDK disponibles:

```bash
./gradlew :core:test :ai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

La compilación histórica del APK reutilizó las 16 bibliotecas nativas verificadas de 0.2.6. La configuración CMake de este fuente compila llama.cpp desde el commit fijado. No confundir la evidencia del staging con una compilación nativa nueva de esta rama.

## Cómo retomar sin perder trabajo

1. Importar el commit del bundle o del parche adjunto antes de seguir implementando sobre la base anterior.
2. Conservar los cambios locales propios y comparar con `b3365c2`.
3. Confirmar que `OnDevice.kt` no presenta diferencias respecto de ese commit.
4. Revisar CI y probar el APK en el dispositivo antes de declarar corregidos los cierres.
5. Integrar después de revisar los cambios. Esta entrega no modifica por la fuerza la rama de Claude ni publica una nueva APK mediante CI.

Existe además un paquete separado de mejoras de GUI del 1 de noviembre, basado en código que declara versión 0.1.0. No se aplicó sobre 0.2.7 porque reemplazar sus pantallas directamente podría quitar correcciones posteriores. Debe portarse como un cambio separado.
