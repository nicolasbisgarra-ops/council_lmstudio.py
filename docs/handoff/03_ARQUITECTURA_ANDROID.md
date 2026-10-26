# Arquitectura Android nativa (sugerencia implementable)

## Requisito obligatorio
Entregar un proyecto Android Studio 100 % **nativo Android** en **Kotlin**. Jetpack Compose está recomendado para menús/listas/diálogos; la superficie de dibujo puede usar **Android Ink API** o una vista Android de baja latencia que capture `MotionEvent`. **No** WebView, PWA ni puente JS como implementación.

## Pila recomendada
- Kotlin + Gradle Kotlin DSL; compatibilidad con Android 8.0+ (`minSdk 26` si se usa AndroidX Ink v1).
- `androidx.ink:ink-*` versión estable igualada entre módulos (1.0.0 disponible en octubre de 2026). Inspeccionar APIs reales antes de escribir imports.
- Jetpack Compose para UI y ViewModel para estado; `InProgressStrokes` para wet ink y `CanvasStrokeRenderer` para dry ink cuando corresponda.
- Input con `MotionEvent` nativo: `TOOL_TYPE_STYLUS`, `TOOL_TYPE_ERASER`, `getPressure`, `AXIS_TILT`, `AXIS_ORIENTATION`, timestamps y eventos históricos. Administrar IDs de puntero.
- Persistence Room o archivos transaccionales versionados, más base de patrones. Guardar stroke data original; no guardar el documento únicamente como imagen o PDF.
- Para exportar: formato editable propio (p. ej. ZIP con JSON de metadatos + muestras/ink strokes), opcional PDF/PNG para compartir (solo exportación, nunca formato fuente).

## Capas
```
app/
  ui/            Compose home, notebooks, toolbar, pattern editor, settings
  ink/           input adapter, stroke renderer, hit testing, eraser, lasso
  gesture/       dwell state machine, grouping, normalization, matching
  template/      authored gesture/output pairs, editable previews, transforms
  document/      pages, immutable commands, undo/redo, coordinate system
  storage/       local persistence, migration, autosave, import/export
  test/          JVM + Android instrumented + S Pen physical QA
```

## Canal de entrada recomendado
1. Capturar DOWN/MOVE/UP/CANCEL y samples históricos para stylus real.
2. Mostrar wet ink inmediatamente; no bloquear dibujado esperando reconocer.
3. Mantener grupo de trazos recientes cerca de la punta (ventana temporal/espacial), excluyendo salidas ya convertidas.
4. En `MOVE`, actualizar detector de estabilidad. El temporizador dura **1 s de quietud mientras el lápiz está abajo**, no 1 s desde levantarlo.
5. Al vencer, se evalúa candidato con puntaje mínimo y margen de ambigüedad.
6. Ejecutar una transacción `ReplaceGestureWithInk` sobre modelo de página, renderizar y guardar; evitar duplicación del evento `UP` posterior.
7. Cancelar y revertir estado pendiente en `ACTION_CANCEL`, cambio de herramienta, interrupción, pérdida de foco.

## Ink representation
- Trazo contiene: id, lista ordenada de puntos `(x, y, pressure, elapsedTime, tilt?)`, pincel/color/ancho y propiedades.
- Grupo lógico opcional (después de reemplazo) para mover el símbolo de forma conjunta; aun así es un conjunto de trazos editable punto a punto.
- Documentos en **coordenadas lógicas** independientes de píxeles, densidad y zoom; usar transformación inversa durante entrada.
- Distinción entre `gestureTrace` y `outputInkTrace` en biblioteca; ambos pueden ser dibujados por el usuario.
- Ningún componente de UI inserta texto/LaTeX en el lienzo.

## Offline, confiabilidad y privacidad
- El reconocimiento base funciona totalmente offline.
- Autosave atómico a intervalos y cierre de nota, migrations de esquema, pruebas de recuperación ante crash.
- Pedir solo permisos necesarios; red no requerida para MVP.

## Compatibilidad / hardware
Probar en S24 Ultra y Tab S11 con S Pen cuando haya hardware. Distinguir claramente tests de mouse/emulador de pruebas físicas. Rechazo de palma con señales `ACTION_CANCEL` y `FLAG_CANCELED`; no afirmar que una biblioteca de tinta implementa por sí sola todos los requisitos.
