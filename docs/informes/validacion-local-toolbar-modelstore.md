# Validación manual de barra adaptable y helpers de modelos locales

Fecha: 2026-10-30
Checkout inspeccionado: `6009d09c7e42f7b816914ca98392ef459c681268` (`claude/imolementa-esto-ag7u18`).
Validación solicitada para el snapshot de la rama compartida; no se editaron features durante esta revisión.

## Pruebas JVM ejecutadas

El wrapper del proyecto no pudo iniciar Gradle porque intentó descargar `gradle-8.11.1` y no había red. Se usó el compilador Kotlin embebido `2.0.20` y JUnit `4.13.2` disponibles en el entorno para compilar y ejecutar los tests con sus fuentes reales.

- `ToolbarPreferences.kt` + `ToolbarPreferencesTest.kt`: **5 tests, 0 fallos**.
- `LocalModelStoreTest.kt`: **4 tests, 0 fallos**. Para aislar la lógica JVM pura del archivo Android `LocalModelStore.kt`, se compiló el segmento original desde `localModelExtension` hasta el final, que contiene también `hasValidModelHeader` y `safeModelStem`; no se sustituyeron las funciones probadas.

Salida JUnit:

```text
JUnit version 4.13.2
.....
Time: 0.004
OK (5 tests)

JUnit version 4.13.2
....
Time: 0.035
OK (4 tests)
```

Gradle full task intentado: `/workspace/scratch/1a5d65bbe0af/tools/gradle-8.11.1/bin/gradle :app:testDebugUnitTest --offline`. Falló durante configuración porque el plugin `com.android.application:8.7.3` no está en caché local; no se llegó a compilar la app.

`git diff --check`: sin errores de whitespace al momento de validar.

## Revisión estática de integración

- `AiSettingsPage` abre `LocalModelsScreen`, reutiliza o crea perfiles `AiProvider.ON_DEVICE` y selecciona el modelo importado. Las clases/enum llamados están presentes en el árbol inspeccionado.
- `MainActivity` incluye `Screen.Study`; `StudyDestination` lee `app.study`, transforma páginas con `PracticePlanner.notesFromPages` y enruta práctica/examen. Las propiedades y funciones consultadas existen.
- El banner de examen quedó dentro de `Scaffold.topBar`, no como sibling fuera del layout del editor.
- `EditorScreen` pasa `canClose = !fixedPages` a `PagesPanel`; la firma ya declara `canClose: Boolean = true` y solo dibuja el botón cerrar cuando es true. Esto resuelve el mismatch observado durante la edición concurrente.

## Limitación

La ejecución manual valida preferencias adaptables y helpers puros de archivos. No reemplaza `:app:testDebugUnitTest`, compilación Android ni pruebas de UI/SAF; esas tareas requieren resolver los plugins Android y dependencias del proyecto.
