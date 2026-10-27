# Arquitectura

## Módulos

| Módulo | Contenido | Depende de Android |
|---|---|---|
| `:core` | `InkStroke`/`InkPoint` (coordenadas de documento, presión, tiempo), `DwellDetector`, `GestureMatcher` + `GestureRecognizer`, `InkPattern` + `InkReplacement`, `PageState` + `History` (comandos atómicos), `Eraser`, `Lasso`, `InkController` (máquina de estados de la sesión), `TrazoFormat` (JSON/ZIP versionado), `DefaultPatterns`, `PresetPatterns` (biblioteca de partida: signos escritos en un mini lenguaje de trazos + tus patrones como recurso), `PatternUpgrades`, `Shapes` | No |
| `:app` | `InkCanvasView` (entrada `MotionEvent`, render), `TrazoRepository` (AtomicFile), `SettingsStore`, pantallas Compose (barra de íconos, paneles por herramienta, selector de patrones, tema), ViewModels | Sí |

La lógica completa del flujo "dibujar → pausa → reemplazo → deshacer" vive en `InkController`, así se prueba en JVM con un lápiz sintético (`core/src/testFixtures`). La capa Android solo traduce eventos y dibuja.

## Flujo de entrada

```
MotionEvent (InkCanvasView)
  ├─ STYLUS / ERASER / botón lateral ──► InkController.penDown/penMove(históricos)/penUp
  │     (apretar el botón a mitad de trazo ──► changeKind: termina el trazo y sigue con la herramienta del botón)
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
- `GestureMatcher`: normaliza el conjunto (escala uniforme), remuestrea a 32 puntos, mezcla distancia de posición y de dirección con alineación elástica (desfase de hasta 3 muestras), admite trazos invertidos y otro orden (≤4 trazos), umbral por patrón y margen de ambigüedad.
- `InkReplacement.place` copia en profundidad la tinta de salida (puntos, presión, tiempos), la escala según el modo (alto/ancho del gesto, dentro del gesto o fijo), la centra en el gesto y le asigna un `groupId`.

## Aprender de las correcciones

```
pausa sin coincidencia ─► InkEvent.NotRecognized(candidates = grupos de ids, del trazo actual a los vecinos)
   └─ aviso "Enseñar" ─► hoja "¿Qué patrón era?" (vistas previas en tinta: grupo de trazos + patrón)
         ├─ elegir patrón ─► InkController.convertWith (un paso deshacible)
         │                   └─ si "aprender" está activo: InkPattern.withGestureExample → biblioteca
         └─ "Crear un patrón nuevo con este gesto" ─► editor de patrones con el gesto como ejemplo 1
ambigüedad ─► el usuario elige ─► mismo camino (convierte y aprende)
```

`GestureExamples.normalize` guarda cada ejemplo trasladado (y achicado si hace falta) al lienzo de 400 unidades del editor; un patrón conserva hasta 8 ejemplos (siempre el primero y los más recientes). La próxima vez que se dibuje un gesto parecido se reconoce solo.

## Interrupciones

Una pausa de la actividad, la pérdida de foco de la ventana, ocultarla, `ACTION_CANCEL` o `FLAG_CANCELED` cancelan el contacto en curso: el trazo pendiente se descarta y la pausa deja de contar, así un gesto interrumpido nunca se convierte (T20). Un `ACTION_DOWN` nuevo descarta cualquier contacto colgado. Los diálogos (ambigüedad, enseñar) esperan a que el lápiz se levante.

## Documento e historial

`PageState` es la lista ordenada de trazos; `StrokeEdit(removed, added)` es el único tipo de cambio. Un mismo id en ambas listas se reemplaza en su lugar (mover/escalar). `revert` restaura el orden exacto (z) con las posiciones que devuelve `apply`. Gesto→salida, una pasada de goma o un arrastre del lazo son **un** comando cada uno.

## Render

`InkCanvasView` dibuja la tinta seca en un bitmap caché del tamaño de la vista, regenerado al cambiar la página o la transformación (zoom/pan redibuja vectores: no hay pixelado). La tinta húmeda, el lazo, la selección y el anillo de pausa se dibujan encima en cada frame. El ancho depende de la presión (tramos agrupados por ancho cuantizado, uniones redondas).

## Navegación

Pila de pantallas propia (`NavViewModel`); cada entrada es un `ViewModelStoreOwner` cuyo almacén se limpia al volver atrás, así cada pantalla tiene su estado y no arrastra el de una visita anterior. Los ViewModels sobreviven a rotaciones. La vista de tinta solo escucha al controlador mientras está en pantalla (sin fugas de actividades).

## Persistencia

- `filesDir/notebooks/<id>.trazo.json`: `{format, schemaVersion, notebook}` escrito con `AtomicFile`.
- `filesDir/patterns.json`: biblioteca de patrones; `filesDir/presets-version`: versión de la biblioteca de partida ya instalada (al actualizar la app solo se agregan los patrones nuevos, ver `PresetPatterns.install`).
- Autoguardado 0,7 s después de cada cambio y guardado inmediato en `ON_STOP`.
- Exportación `.trazo` = ZIP con `manifest.json`, `notebook.json`, `patterns.json` (los patrones usados).
- `TrazoFormat.migrate` es el punto único de migraciones; rechaza esquemas más nuevos e ignora campos desconocidos.
- Biblioteca de patrones exportable/importable como JSON; `PatternLibrary.merge` omite idénticos y renombra conflictos.
- Para compartir (nunca como fuente): PDF vectorial del cuaderno (`PdfDocument`) y PNG de una página.
