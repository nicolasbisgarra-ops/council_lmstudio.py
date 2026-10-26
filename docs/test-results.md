# Resultados de pruebas

Los resultados están separados por **nivel de evidencia**, como pide `05_PRUEBAS_ACEPTACION.md`. Una prueba solo figura como "pasó" si se ejecutó.

## Resumen

| Nivel | Estado | Detalle |
|---|---|---|
| 1. JVM: motor `:core` | ✅ **67/67 pasaron** (ejecutadas) | 26/10/2026, OpenJDK 21, Gradle 8.11.1, Kotlin 2.1.0 |
| 2. Compilación de la APK (`:app:assembleDebug`) | ⏳ **No ejecutada** | sin Android SDK en el entorno; preparada en CI |
| 3. Pruebas JVM de la app (`SourcePolicyTest`) | ⏳ **No ejecutadas** con Gradle | reglas verificadas con un script equivalente: 0 violaciones |
| 4. Instrumentadas en emulador + demo grabada | ⏳ **No ejecutadas** | `EditorInstrumentedTest`, `DemoExerciseTest` y `scripts/ci-emulator.sh` listos |
| 5. Físicas con S Pen (S24 Ultra / Tab S11) | ❌ **No realizadas** | no hubo hardware disponible |

### Por qué los niveles 2–4 no se ejecutaron

El entorno de desarrollo (contenedor en la nube) **no tenía Android SDK** y su política de red bloquea `dl.google.com`, host del SDK y de Google Maven (`maven.google.com` redirige ahí). Sin eso no se puede descargar el Android Gradle Plugin, AndroidX ni Compose. El motor `:core` no depende de Android y sí se compiló y probó.

El flujo de GitHub Actions (`.github/workflows/android.yml`) compila la APK, corre las pruebas JVM y las instrumentadas en un emulador API 34 (Pixel 6) y graba la demo con `screenrecord`. **Todavía no corrió**: el envío (push) al repositorio fue rechazado (403, la GitHub App de Claude no tiene acceso al repositorio). Cuando se habilite el acceso, cada push lo ejecuta; con `[publicar]` en el mensaje del commit, deja `output/Trazo-debug.apk` y `output/demo-real-android.mp4` en la rama.

**Riesgo pendiente:** el código Android (`app/`) se escribió y se revisó a mano, pero nunca pasó por el compilador. Puede necesitar ajustes menores en la primera compilación.

## 1. JVM — motor `:core` (ejecutado)

Comando:

```bash
./gradlew -Ptrazo.coreOnly=true :core:test -Ptrazo.frames=true --rerun-tasks
```

Resultado: `BUILD SUCCESSFUL`, **67 pruebas, 0 fallas, 0 omitidas**. Reportes JUnit en `core/build/test-results/test/`.

Cobertura de la matriz de aceptación **a nivel motor** (lápiz sintético, sin Android):

| ID | Prueba JVM | Resultado |
|---|---|---|
| T01 | `T01 free drawing is stored with points, pressure and time` | ✅ |
| T02 | `T02 integral held 1000 ms while touching converts to output ink only`, `conversion happens at 1000 ms of stillness, not before` | ✅ |
| T03 | `T03 lifting at 900 ms never converts and ink is identical` (+ versión del detector) | ✅ |
| T04 | `T04 touching without drawing changes nothing` | ✅ |
| T05 | `T05 tremor below radius does not reset the hold` | ✅ |
| T06 | `T06 continuous movement never converts` | ✅ |
| T07 | `T07 unknown gesture held keeps the original ink` | ✅ |
| T08 | `T08 two similar patterns are not converted automatically and user can choose` | ✅ |
| T09 | `T09 custom pattern inserts the user's own drawing` | ✅ |
| T10 | `T10 two-stroke root…`, `T10b one-stroke root works with short and long bars` | ✅ |
| T11 | `T11 fraction line converts to handwritten bar` | ✅ |
| T12 | `T12 output can be written over and partially erased` | ✅ |
| T13 | `T13 undo restores the complete original gesture in one action` | ✅ |
| T14 | `T14 redo restores exactly the output strokes` | ✅ |
| T15 | `T15 editing a pattern never alters notes already written` | ✅ |
| T16 | `T16 notebook json round trip is lossless`, `T16 editable zip export and import…` | ✅ |
| T22 (motor) | `T22 engine solves the full integral exercise by hand with gesture conversions` | ✅ |

Además: los 16 casos del core de referencia del handoff (portados), sin trazo fantasma tras convertir, `CANCEL` durante la pausa, escritura cercana no absorbida, pausa propia por patrón, goma del S Pen, goma de trazo, lazo mover/escalar con deshacer, inserción desde la biblioteca, esquemas futuros rechazados.

### Evidencia T22 (motor, simulación rotulada)

- `output/jvm/exercise-final.png`: render del modelo tras resolver ∫₀² (3x² + 2x) dx = [x³ + x²]₀² = (8 + 4) − 0 = 12, √16 = 4 y 6⁄3 = 2. La integral, la raíz y la barra de fracción se insertaron por gesto + pausa de 1 s; todo lo demás son trazos del lápiz sintético.
- `output/jvm/exercise-log.txt`: conversiones con puntaje (integral 0,96 · raíz 0,97 · fracción 0,98).
- `output/jvm/simulacion-motor-jvm.mp4`: fotogramas del motor mientras escribe (se ve el anillo de pausa y el reemplazo).

> **Esto NO es la app Android ni un S Pen.** Cada imagen lleva la cabecera "SIMULACIÓN JVM DEL MOTOR". El video funcional exigido (T22 en Android) sale de `DemoExerciseTest` en CI o en un dispositivo.

## 2–4. Android (preparado, no ejecutado)

| ID | Prueba instrumentada (`app/src/androidTest`) |
|---|---|
| T01 | `t01_freeDrawingCreatesAStrokeWithSamples` |
| T02 | `t02_integralHeldOneSecondBecomesHandwrittenOutput` |
| T03 | `t03_liftingAt900msNeverConverts` |
| T10 / T11 | `t10_twoStrokeRootIsReplacedAsVectors`, `t11_fractionLineBecomesHandwrittenBar` |
| T12 | `t12_outputCanBeWrittenOverAndPartiallyErased` |
| T13 / T14 | `t13_t14_undoAndRedoConversionAsSingleActions` |
| T17 | `t17_notesAndPatternsPersistAcrossRecreateAndBackground` |
| T18 | `t18_zoomKeepsDocumentGeometry` |
| T19 (simulada) | `t19_fingerTouchWhileStylusIsDownDoesNotDraw`, `t19b_actionCancelDiscardsTheStroke` |
| T20 | `t20_recreatingDuringAGestureCancelsSafely` |
| T21 | `scripts/ci-emulator.sh` apaga wifi y datos antes de las pruebas |
| T22 | `DemoExerciseTest.solveFullIntegralExerciseByHand` + `screenrecord` |
| T23 | `t23_noWebViewInTheHierarchy` + `SourcePolicyTest` |
| T24 | `SourcePolicyTest` (sin LaTeX/KaTeX/MathJax/MathLive; la vista de tinta no usa `drawText`, `Typeface`, `TextView` ni bitmaps decodificados) |

Los eventos de estas pruebas son `MotionEvent` **sintetizados** (`TOOL_TYPE_STYLUS`, `SOURCE_STYLUS`) inyectados con `UiAutomation`; no prueban latencia real ni rechazo de palma físico.

### Ejecutarlas en una máquina con Android SDK

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
# con emulador o dispositivo conectado:
mkdir -p output && cp app/build/outputs/apk/debug/app-debug.apk output/Trazo-debug.apk \
  && cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk output/Trazo-debug-androidTest.apk
bash scripts/ci-emulator.sh output output/emulator   # pruebas + demo grabada
```

## 5. Plan de validación física con S Pen (pendiente)

En Galaxy S24 Ultra y Galaxy Tab S11, con la APK instalada, grabando pantalla y mano:

1. T01/T18: escribir una página completa; zoom 1×–4× y desplazamiento: sin cortes ni pixelado.
2. T02/T05: integral + mantener apoyado 1 s con la mano natural (registrar tasa de éxito en 20 intentos y temblor; ajustar el radio si hace falta).
3. T03/T06: levantar a ~0,9 s y escribir lento: 0 conversiones involuntarias en 20 intentos.
4. T19: escribir con la palma apoyada; palma antes del lápiz; botón lateral como goma.
5. T09/T10/T11: enseñar un patrón propio con el editor y usarlo; raíz y fracción.
6. T12–T14: escribir sobre la salida, borrar parcialmente, deshacer/rehacer.
7. T17/T20/T21: cerrar y reabrir, rotar durante un gesto, modo avión.
8. T22: grabar la resolución completa de ∫₀² (3x² + 2x) dx = 12 a mano.
