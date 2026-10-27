# Resultados de pruebas

Separados por **nivel de evidencia**, como pide `05_PRUEBAS_ACEPTACION.md`. Una prueba figura como "pasó" solo si se ejecutó; cada resultado indica dónde y cuándo.

## Resumen

| Nivel | Estado | Dónde |
|---|---|---|
| 1. JVM — motor `:core` | ✅ **133/133** | local (OpenJDK 21) y GitHub Actions (JDK 17) |
| 2. JVM — app (`SourcePolicyTest`: sin WebView/LaTeX/texto en la hoja) | ✅ 5/5 | GitHub Actions |
| 3. Compilación `assembleDebug` + `assembleDebugAndroidTest` | ✅ | GitHub Actions, AGP 8.7.3, compileSdk 35 |
| 4. Instrumentadas en emulador (MotionEvent de lápiz sintetizado) | ✅ **16/16** (corrida #10, commit `2c5f848`) | GitHub Actions, emulador API 34 Pixel 6, sin red |
| 5. Demo grabada en la app nativa (emulador, automatizada) | ✅ **2/2** (ejercicio completo + enseñar un gesto), video de 10,8 MB | `output/demo-real-android.mp4` |
| 6. Físicas con S Pen (S24 Ultra / Tab S11) | ❌ **no realizadas** | no hubo hardware disponible |

Los niveles 4 y 5 usan eventos `TOOL_TYPE_STYLUS` / `SOURCE_STYLUS` **sintetizados** e inyectados con `UiAutomation` (o despachados a la vista). Prueban la app real y el flujo de entrada de Android, pero **no** la latencia, la presión física, el rechazo de palma real ni la ergonomía del S Pen.

## 1. JVM — motor `:core`

```bash
./gradlew -Ptrazo.coreOnly=true :core:test          # sin Android SDK
./gradlew :core:test :app:testDebugUnitTest          # con SDK (CI)
```

133 pruebas en 14 clases, 0 fallas. Cobertura de la matriz **a nivel motor** (lápiz sintético):

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
| Signos de partida | `PresetPatternsTest` (13): números 0–10 y secciones, tus patrones con nombre, instalar sin pisar nada (ni lo borrado), signos que ceden ante tus patrones; `PresetAnalysisTest` (6): ningún par activo confundible, tus ejemplos y gestos deformados nunca se convierten en otro patrón, un número escrito antes de un gesto no se absorbe | ✅ |
| Raíz elástica | `StretchTest` (10): la barra llega hasta donde terminó el gesto, largo mínimo con gestos chicos, raíces viejas mejoradas sin tocar los ejemplos | ✅ |
| Herramientas y S Pen | `ToolsTest` (21): formas, mano, resaltador, lazo, color y punta tomados al apoyar; botón lateral (goma, lazo o nada; apretarlo o soltarlo a mitad de un trazo) y extremo goma | ✅ |
| Comparador elástico | `a part drawn longer than in the example still matches` y las otras 13 de `GestureMatcherTest` | ✅ |

Además: casos del core de referencia del handoff, sin trazo fantasma, `CANCEL` en la pausa, escritura cercana no absorbida, pausa propia por patrón, gomas, lazo, páginas (eliminar/duplicar/mover), duplicar y recolorear selección, fusión de bibliotecas de patrones, esquemas futuros rechazados.

Evidencia del motor (rotulada "SIMULACIÓN JVM"): `output/jvm/exercise-final.png`, `output/jvm/simulacion-motor-jvm.mp4`.

Calibración con los patrones del usuario (`PresetAnalysisTest`; reportes en `output/jvm/presets-*.txt`):

- Biblioteca de partida: 104 patrones (3 del núcleo, 11 del usuario, 90 signos), 89 activos. El par activo más parecido puntúa 0,81, por debajo del umbral de confusión (0,84).
- Cada uno de los 59 ejemplos del usuario, sacado de su patrón y usado como gesto nuevo: 51 bien, 8 sin convertir y **0 convertidos en otro patrón**. Antes del comparador elástico: 12 bien.
- 1240 gestos deformados como los haría una mano (8 por ejemplo): 1239 bien, 1 ambiguo (7 frente a 1: se ofrece elegir), 0 mal.

## 4. Instrumentadas en emulador

`scripts/ci-emulator.sh` instala las APK, **apaga wifi y datos** (T21) y corre:

| ID | Prueba | Resultado |
|---|---|---|
| T01 | `t01_freeDrawingCreatesAStrokeWithSamples` | ✅ |
| T02 | `t02_integralHeldOneSecondBecomesHandwrittenOutput` | ✅ |
| T03 | `t03_liftingBeforeThePauseEndsNeverConverts` (levanta tras 1 s quieto con una pausa de 4 s; el borde de 900 ms con pausa de 1 s lo prueba T03 en JVM, con reloj sintético) | ✅ |
| T10 | `t10_twoStrokeRootIsReplacedAsVectors` | ✅ |
| T11 | `t11_fractionLineBecomesHandwrittenBar` | ✅ |
| T12 | `t12_outputCanBeWrittenOverAndPartiallyErased` | ✅ |
| T13 / T14 | `t13_t14_undoAndRedoConversionAsSingleActions` | ✅ |
| T17 | `t17_notesAndPatternsPersistAcrossRecreateAndBackground` | ✅ |
| T18 | `t18_zoomKeepsDocumentGeometry` | ✅ (en la corrida #2 falló la prueba: el pellizco era menor al mínimo de Android; corregida) |
| T19 (simulada) | `t19_fingerTouchWhileStylusIsDownDoesNotDraw`, `t19b_actionCancelDiscardsTheStroke` | ✅ |
| T20 | `t20_recreatingDuringAGestureCancelsSafely` (pausa de 10 s: verifica que el gesto siga en curso antes de recrear y que recrear cancele el contacto) | ✅ (ver historia: se corrigió la app —interrupciones cancelan— y la prueba) |
| T23 | `t23_noWebViewInTheHierarchy` | ✅ |
| T16 | `ExportInstrumentedTest` (PDF con una página por hoja, PNG válido) | ✅ |
| — | `t25_twoFingerTapUndoesTheLastChange` | ✅ |

### Historia de ejecuciones en CI

- Corrida #1: compilación y pruebas JVM ✅ al primer intento; falló solo el paso que arma el video del motor (faltaba `ffmpeg` en el runner) → corregido.
- Corrida #2: APK ✅; emulador: 11/13 instrumentadas ✅, demo del ejercicio ✅ (video de 7,5 MB y capturas); fallaron T18 (prueba) y T20 (error real) → corregidos.
- Corrida #3: compilación de las nuevas capacidades ✅ (aprender de correcciones, páginas, selección, PDF/PNG, patrones, tablet); el emulador se canceló al llegar la corrida #4.
- Corrida #4: 15/16 instrumentadas ✅ (T18 ya pasa; exportación y deshacer con dos dedos nuevos ✅) y **demo 2/2 ✅**; T20 seguía fallando porque la prueba esperaba inactividad con el lápiz apoyado (ese tiempo quieto contaba como pausa) → prueba corregida.
- Corrida #6 (`c4491c7`): **todo verde** — compilación, JVM, 16/16 instrumentadas y demo 2/2 (video de 10 MB y 5 capturas).
- Corrida #7 (`381d5a2`, mismo código de la app): compilación ✅; emulador ❌ por el entorno: una ventana del sistema quedó encima de la app y se tragó los toques inyectados, aunque la inyección "funcionaba" (10 de 16 instrumentadas fallaron, todas las que dibujan con el lápiz por el sistema; la demo, 0/2). Corregido: `ci-emulator.sh` oculta los diálogos de error del sistema, desactiva el bloqueo, deja la pantalla encendida y espera un foco estable; `StylusDriver` espera a que el lienzo tenga el foco antes de apoyar el lápiz; T17 ahora verifica que haya trazos antes de comparar (si no, pasaba vacía).
- Corrida #8 (`8e2afd0`): compilación ✅ y **APK publicada en la rama** al terminar de compilar (clave debug fija); emulador: 15/16 ✅ y demo 2/2 ✅ (video de 9,7 MB y 5 capturas). Falló T03: con pausa de 1 s y 800 ms quieto, el emulador muy cargado (carga 21) entregó tarde el UP y la app vio, con razón, una pausa completa → la prueba ahora levanta a mitad de una pausa de 2 s.
- Corrida #9 (`17d0c83`): compilación ✅ (APK publicada); T03 ✅ y demo 2/2 ✅ (video de 9,1 MB). Falló T20: la hoja ya tenía trazos al levantar el lápiz tras recrear. La app cancela al recrear (al pausar y al soltar la vista), así que el trazo se cerró antes: lo más probable es que el emulador cargado frenara la inyección o la recreación más que la pausa de 2 s, o que esa pausa aún no hubiera llegado al controlador (la prueba no la esperaba). El mensaje no decía qué trazos había. → T20 fija una pausa de 10 s, espera a que llegue al controlador y verifica que el gesto siga en curso antes de recrear y que recrear cancele el contacto; T03 usa una pausa de 4 s. Los mensajes de falla ahora muestran los trazos y el mayor hueco entre eventos inyectados. Además, `settle` perdía ~3,5 min: leía el foco guardado en la sección «LAST ANR» de `dumpsys window` (null tras un ANR del arranque) → se ignora esa sección.
- Corrida #10 (`2c5f848`): **todo verde** — compilación, JVM, 16/16 instrumentadas y demo 2/2 (video de 10,8 MB y 5 capturas, publicados en la rama).

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
