# Arquitectura

## Módulos

| Módulo | Contenido | Depende de Android |
|---|---|---|
| `:core` | `InkStroke`/`InkPoint` (coordenadas de documento, presión, tiempo), `DwellDetector`, `GestureMatcher` + `GestureRecognizer`, `InkPattern` + `InkReplacement`, `PageState` + `History` (comandos atómicos), `Eraser`, `Lasso`, `InkController` (máquina de estados de la sesión), `TrazoFormat` (JSON/ZIP versionado), `DefaultPatterns` | No |
| `:app` | `InkCanvasView` (entrada `MotionEvent`, render), `TrazoRepository` (AtomicFile), `SettingsStore`, pantallas Compose, ViewModels | Sí |

La lógica completa del flujo "dibujar → pausa → reemplazo → deshacer" vive en `InkController`, así se prueba en JVM con un lápiz sintético (`core/src/testFixtures`). La capa Android solo traduce eventos y dibuja.

## Flujo de entrada

```
MotionEvent (InkCanvasView)
  ├─ STYLUS / ERASER / botón lateral ──► InkController.penDown/penMove(históricos)/penUp
  ├─ FINGER ──► pan / zoom (ScaleGestureDetector) · ignorado si el lápiz está apoyado o cerca (palma)
  ├─ ACTION_CANCEL o FLAG_CANCELED ──► InkController.cancel()  (nunca convierte)
  └─ postOnAnimation(ticker) mientras hay contacto ──► InkController.tick(uptime)
```

`InkController` (modo lápiz):

```
DOWN → DRAWING ── quieto ≥ pausa (radio de temblor) y tinta ≥ mínimo ──► reconocer (1 vez por quietud)
         │                                   ├─ Convert   → espera la pausa del patrón → StrokeEdit(CONVERT) → CONSUMED
         │                                   ├─ Ambiguous → evento a la UI, se conserva la tinta
         │                                   └─ None      → se conserva la tinta
         ├─ moverse fuera del radio → nueva quietud (puede volver a evaluar)
         └─ UP → StrokeEdit(DRAW)  ·  CONSUMED + UP → nada (sin trazo fantasma)
```

- La cola de temblor de la pausa se reemplaza por un punto de reposo antes de reconocer.
- `GestureRecognizer` arma grupos con el trazo actual y hasta 3 trazos anteriores manuscritos, consecutivos, cercanos en tiempo (≤1,5 s) y espacio; prueba del grupo más grande al más chico. Nunca incluye tinta que vino de un patrón.
- `GestureMatcher`: normaliza el conjunto (escala uniforme), remuestrea a 32 puntos, mezcla distancia de posición y de dirección, admite trazos invertidos y otro orden (≤4 trazos), umbral por patrón y margen de ambigüedad.
- `InkReplacement.place` copia en profundidad la tinta de salida (puntos, presión, tiempos), la escala según el modo (alto/ancho del gesto, dentro del gesto o fijo), la centra en el gesto y le asigna un `groupId`.

## Documento e historial

`PageState` es la lista ordenada de trazos; `StrokeEdit(removed, added)` es el único tipo de cambio. Un mismo id en ambas listas se reemplaza en su lugar (mover/escalar). `revert` restaura el orden exacto (z) con las posiciones que devuelve `apply`. Gesto→salida, una pasada de goma o un arrastre del lazo son **un** comando cada uno.

## Render

`InkCanvasView` dibuja la tinta seca en un bitmap caché del tamaño de la vista, regenerado al cambiar la página o la transformación (zoom/pan redibuja vectores: no hay pixelado). La tinta húmeda, el lazo, la selección y el anillo de pausa se dibujan encima en cada frame. El ancho depende de la presión (tramos agrupados por ancho cuantizado, uniones redondas).

## Persistencia

- `filesDir/notebooks/<id>.trazo.json`: `{format, schemaVersion, notebook}` escrito con `AtomicFile`.
- `filesDir/patterns.json`: biblioteca de patrones.
- Autoguardado 0,7 s después de cada cambio y guardado inmediato en `ON_STOP`.
- Exportación `.trazo` = ZIP con `manifest.json`, `notebook.json`, `patterns.json` (los patrones usados).
- `TrazoFormat.migrate` es el punto único de migraciones; rechaza esquemas más nuevos e ignora campos desconocidos.
