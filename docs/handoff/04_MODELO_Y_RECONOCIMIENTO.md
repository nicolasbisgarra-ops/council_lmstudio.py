# Modelo de datos y motor de reconocimiento

## Estructuras lógicas propuestas
```kotlin
data class InkSample(val x: Float, val y: Float, val pressure: Float, val timeMs: Long)
data class InkStroke(val id: String, val points: List<InkSample>, val brush: BrushRef)
data class Pattern(
    val id: String,
    val name: String,
    val gestureExamples: List<List<InkStroke>>,
    val outputInk: List<InkStroke>,
    val outputWidth: Float,
    val outputHeight: Float,
    val minConfidence: Float,
    val holdMillis: Long
)
```
Estas clases son **orientativas**; el core incluido usa tipos mínimos similares.

## Por qué distinguir gesto y salida
Una integral trazada de forma abreviada puede invocar una integral manuscrita más precisa, completa y de tamaño fijo. Se debe almacenar: A) dibujo que se reconoce, B) dibujo que se inserta, independientemente. La salida no es fórmula tipográfica ni imagen rasterizada.

## Reconocimiento de referencia
1. Preprocesar: resamplear puntos (ej.: 32 por trazo), normalizar traslación y escala conservando aspecto, filtrar ruido.
2. Para cada patrón compatible por cantidad de trazos (o mediante agrupación), comparar secuencias 2D con orientación bidireccional opcional.
3. Calcular puntaje y margen respecto al segundo candidato; devolver `no match` ante dudas.
4. Tomar solo trazos de un candidato espacial y temporalmente conectado a la punta de activación.
5. Al convertir, remover el gesto; insertar una **copia profunda transformada** de los puntos de `outputInk` anclada en ubicación del gesto.
6. Guardar una única transacción reversible; mantener intactos otros trazos y presión.

### Máquina de estados sugerida
```
IDLE --stylus DOWN--> DRAWING
DRAWING --motion--> DRAWING + reset idle timer
DRAWING --stable + pathLength>mínimo--> HOLDING
HOLDING --motion outside radius--> DRAWING (reset)
HOLDING --1000ms still DOWN--> RECOGNIZE
RECOGNIZE --good match--> REPLACE_INK + consume current gesture
RECOGNIZE --no/ambiguous match--> COMMIT_ORIGINAL_INK
DRAWING/HOLDING --UP before threshold--> COMMIT_ORIGINAL_INK
ANY --CANCEL--> CANCEL_PENDING safely
```
El `HOLDING` puede implementarse dentro de DRAWING con un timer monotónico y comprobación de estado. No usar `sleep` bloqueante del hilo UI.

## Motor Kotlin suministrado
`native-kotlin-core/InkCore.kt` contiene un matcher simple, detector de quietud, y una transformación de ink salida. `CoreSelfTest.kt` aporta pruebas reproducibles JVM. **No es reconocimiento de producción, no conoce `MotionEvent`, no incluye UI, storage Android ni APK**; Claude debe adaptarlo/mejorarlo e integrarlo.

## Errores a evitar
- No enganchar el matcher a movimientos de dedo/palma accidentalmente.
- No confundir `ACTION_HOVER_MOVE` con punta tocando; la activación requiere contacto real.
- No dibujar output en canvas como bitmap estático: conservar su lista de muestras.
- No insertar fracciones como cadenas de texto o SVG de texto.
- No agrupar indiscriminadamente tres trazos anteriores (pueden ser de otra operación): exigir proximidad, tiempo y score.
- No comenzar un segundo trazo fantasma después de haber convertido mientras el lápiz sigue abajo.
