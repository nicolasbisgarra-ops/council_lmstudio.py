# Resultados de pruebas

Separados por **nivel de evidencia**, como pide `05_PRUEBAS_ACEPTACION.md`. Una prueba figura como "pasó" solo si se ejecutó; cada resultado indica dónde y cuándo.

## Resumen

| Nivel | Estado | Dónde |
|---|---|---|
| 1. JVM — motor `:core` | ✅ **81/81** | local (OpenJDK 21) y GitHub Actions (JDK 17) |
| 2. JVM — app (`SourcePolicyTest`: sin WebView/LaTeX/texto en la hoja) | ✅ 5/5 | GitHub Actions |
| 3. Compilación `assembleDebug` + `assembleDebugAndroidTest` | ✅ | GitHub Actions, AGP 8.7.3, compileSdk 35 |
| 4. Instrumentadas en emulador (MotionEvent de lápiz sintetizado) | ⏳ en curso (última corrida completa: 11/13; las 2 fallas ya corregidas) | GitHub Actions, emulador API 34 Pixel 6, sin red |
| 5. Demo grabada en la app nativa (emulador, automatizada) | ✅ ejercicio grabado (corrida #2); la versión con "enseñar" está en curso | `output/demo-real-android.mp4` |
| 6. Físicas con S Pen (S24 Ultra / Tab S11) | ❌ **no realizadas** | no hubo hardware disponible |

Los niveles 4 y 5 usan eventos `TOOL_TYPE_STYLUS` / `SOURCE_STYLUS` **sintetizados** e inyectados con `UiAutomation` (o despachados a la vista). Prueban la app real y el flujo de entrada de Android, pero **no** la latencia, la presión física, el rechazo de palma real ni la ergonomía del S Pen.

## 1. JVM — motor `:core`

```bash
./gradlew -Ptrazo.coreOnly=true :core:test          # sin Android SDK
./gradlew :core:test :app:testDebugUnitTest          # con SDK (CI)
```

81 pruebas, 0 fallas. Cobertura de la matriz **a nivel motor** (lápiz sintético):

| ID | Prueba | Resultado |
|---|---|---|
| T01 | `T01 free drawing is stored with points, pressure and time` | ✅ |
| T02 | `T02 integral held 1000 ms while touching converts…`, `conversion happens at 1000 ms of stillness, not before` | ✅ |
| T03 | `T03 lifting at 900 ms never converts and ink is identical` | ✅ |
| T04 | `T04 touching without drawing changes nothing` | ✅ |
| T05 | `T05 tremor below radius does not reset the hold` | ✅ |
| T06 | `T06 continuous movement never converts` | ✅ |
| T07 | `T07 unknown gesture held keeps the original ink` | ✅ |
| T08 | `T08 two similar patterns are not converted automatically and user can choose` | ✅ |
| T09 | `T09 custom pattern inserts the user's own drawing` | ✅ |
| T10 | `T10 two-stroke root…`, `T10b one-stroke root works with short and long bars` | ✅ |
| T11 | `T11 fraction line converts to handwritten bar` | ✅ |
| T12 | `T12 output can be written over and partially erased` | ✅ |
| T13 / T14 | `T13 undo restores…`, `T14 redo restores…` | ✅ |
| T15 | `T15 editing a pattern never alters notes already written` | ✅ |
| T16 | `T16 notebook json round trip…`, `T16 editable zip export and import…` | ✅ |
| T22 (motor) | `T22 engine solves the full integral exercise…` | ✅ |
| Aprender | `unrecognized gesture can be converted, then taught and recognized automatically` y otras 13 de `CapabilitiesTest` | ✅ |

Además: casos del core de referencia del handoff, sin trazo fantasma, `CANCEL` en la pausa, escritura cercana no absorbida, pausa propia por patrón, gomas, lazo, páginas (eliminar/duplicar/mover), duplicar y recolorear selección, fusión de bibliotecas de patrones, esquemas futuros rechazados.

Evidencia del motor (rotulada "SIMULACIÓN JVM"): `output/jvm/exercise-final.png`, `output/jvm/simulacion-motor-jvm.mp4`.

## 4. Instrumentadas en emulador

`scripts/ci-emulator.sh` instala las APK, **apaga wifi y datos** (T21) y corre:

| ID | Prueba | Resultado |
|---|---|---|
| T01 | `t01_freeDrawingCreatesAStrokeWithSamples` | ✅ corrida #2 |
| T02 | `t02_integralHeldOneSecondBecomesHandwrittenOutput` | ✅ corrida #2 |
| T03 | `t03_liftingAt900msNeverConverts` | ✅ corrida #2 |
| T10 | `t10_twoStrokeRootIsReplacedAsVectors` | ✅ corrida #2 |
| T11 | `t11_fractionLineBecomesHandwrittenBar` | ✅ corrida #2 |
| T12 | `t12_outputCanBeWrittenOverAndPartiallyErased` | ✅ corrida #2 |
| T13 / T14 | `t13_t14_undoAndRedoConversionAsSingleActions` | ✅ corrida #2 |
| T17 | `t17_notesAndPatternsPersistAcrossRecreateAndBackground` | ✅ corrida #2 |
| T18 | `t18_zoomKeepsDocumentGeometry` | ❌ corrida #2 (el pellizco de prueba era menor al mínimo de Android); prueba corregida, ⏳ |
| T19 (simulada) | `t19_fingerTouchWhileStylusIsDownDoesNotDraw`, `t19b_actionCancelDiscardsTheStroke` | ✅ corrida #2 |
| T20 | `t20_recreatingDuringAGestureCancelsSafely` | ❌ corrida #2: **error real de la app** (la pausa seguía contando durante la recreación y convertía); corregido (pausa/foco/ventana oculta cancelan), ⏳ |
| T23 | `t23_noWebViewInTheHierarchy` | ✅ corrida #2 |
| T16 | `ExportInstrumentedTest` (PDF con una página por hoja, PNG válido) | ⏳ nueva |
| — | `t25_twoFingerTapUndoesTheLastChange` | ⏳ nueva |

### Historia de ejecuciones en CI

- Corrida #1: compilación y pruebas JVM ✅ al primer intento; falló solo el paso que arma el video del motor (faltaba `ffmpeg` en el runner) → corregido.
- Corrida #2: APK ✅; emulador: 11/13 instrumentadas ✅, demo del ejercicio ✅ (video de 7,5 MB y capturas); fallaron T18 (prueba) y T20 (error real) → corregidos.
- Corrida #3: compilación de las nuevas capacidades ✅ (aprender de correcciones, páginas, selección, PDF/PNG, patrones, tablet).

## 5. Demo grabada (T22 en la app nativa)

`DemoExerciseTest` (eventos sintetizados, cartel "DEMO AUTOMATIZADA" en la interfaz, fuera de la hoja):

1. `a_solveFullIntegralExerciseByHand`: gesto de integral + pausa de 1 s → tinta del patrón; se escriben límites, integrando y la resolución ∫₀² (3x² + 2x) dx = [x³ + x²]₀² = (8 + 4) − 0 = 12; luego √16 = 4 (raíz por gesto) y 6⁄3 = 2 (barra de fracción por gesto).
2. `b_teachAnUnknownGestureFromThePageAndReuseIt`: un gesto propio no se reconoce, se toca "Enseñar" → "Integral" (UiAutomator), se reemplaza y queda como ejemplo; el mismo gesto dibujado en otro lugar se convierte solo.

Archivos: `output/demo-real-android.mp4` y capturas `output/emulator/trazo-demo-*.png`.

## 6. Plan de validación física con S Pen (pendiente)

En Galaxy S24 Ultra y Galaxy Tab S11, con la APK instalada, grabando pantalla y mano:

1. T01/T18: escribir una página completa; zoom 1×–4× y desplazamiento: sin cortes ni pixelado.
2. T02/T05: integral + mantener apoyado 1 s con la mano natural (tasa de éxito en 20 intentos; ajustar el temblor tolerado si hace falta).
3. T03/T06: levantar a ~0,9 s y escribir lento: 0 conversiones involuntarias en 20 intentos.
4. T19: escribir con la palma apoyada; palma antes del lápiz; botón lateral como goma.
5. T09–T11 y aprendizaje: enseñar un patrón propio desde el editor y desde la hoja ("Enseñar") y reutilizarlo.
6. T12–T14: escribir sobre la salida, borrar parcialmente, deshacer/rehacer.
7. T17/T20/T21: cerrar y reabrir, rotar durante un gesto, modo avión.
8. T22: grabar la resolución completa de ∫₀² (3x² + 2x) dx = 12 a mano.
