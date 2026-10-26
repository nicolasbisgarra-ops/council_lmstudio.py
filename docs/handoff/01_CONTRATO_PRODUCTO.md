# Contrato de producto — especificación interpretada

> Este documento es interpretación técnica, NO una transcripción. Consultar los textos literales para todo conflicto.

## Visión
**Trazo**: aplicación de cuadernos manuscritos Android similar en fluidez a Samsung Notes, especializada en patrones matemáticos configurables. La persona escribe con un lápiz sin depender del teclado ni de símbolos tipográficos. Un gesto largo/toca-y-sostiene convierte su propia marca en la **salida manuscrita** que definió previamente.

## Requisitos MUST del MVP
| ID | Exigencia | Fuente literal |
|----|-----------|---------------|
| R01 | Aplicación Android nativa, instalable (.apk), Kotlin | P05 |
| R02 | Lienzo de tinta libre, S Pen, notas manuscritas | P01, P03 |
| R03 | Enseñar patrones de activación dibujándolos | P01 |
| R04 | Dibujar un patrón de salida diferente, definido por el usuario | P01, P03 |
| R05 | Al mantener la punta quieta un segundo sin levantarla, reconocer y convertir | P01 |
| R06 | El reemplazo es **tinta manuscrita editable**, nunca LaTeX ni texto de computadora en la hoja | P03 |
| R07 | Integral, raíz y fracción como casos de ejemplo; patrones ilimitados según almacenamiento | P01 |
| R08 | Mantener los trazos no reconocidos; jamás perder una operación por un falso positivo | P01, P03 (implícito UX) |
| R09 | Pruebas documentadas y video escribiendo + ejercicio completo usando la herramienta | P02 |
| R10 | ZIP de traspaso contiene pedidos literales separados de interpretación | P04 |

## Criterios sugeridos por ingeniería (no son frases literales)
- Opciones de nombre de cuaderno, páginas, deshacer/rehacer, borrador parcial, zoom, pan, grosor y color.
- Patrón activador y patrón de salida como grupos de trazos vectoriales con puntos, presión, tiempo y orden; permitir editar ambos.
- Pausa predeterminada 1000 ms; configuración global y opcionalmente por patrón, p. ej. 500–2000 ms.
- Retroalimentación visual discreta mientras la punta está quieta; coincidencias ambiguas no se sustituyen automáticamente.
- Funcionamiento sin nube ni suscripción. Sin solicitudes de permisos de red salvo necesidad explicada.
- Soporte teléfono y tablet, retrato/paisaje, lápiz con la palma apoyada.

## Restricciones negativas (anti-requisitos)
- PROHIBIDO: poner un `TextView`, `EditText`, MathView, WebView o fórmula TeX en la página para representar una expresión matemática.
- PROHIBIDO: reemplazar símbolos con fuente “handwriting” o con una imagen PNG; deben ser trazos manipulables, con grosor y puntos.
- PROHIBIDO: convertir cuando la punta ya se levantó si no completó la pausa con contacto.
- PROHIBIDO: forzar IA o conexión a Internet para operar las funciones centrales.
- PROHIBIDO: reutilizar el prototipo web como producto final, aunque sea visualmente idéntico.
- PROHIBIDO: afirmar que tests de Chromium/HTML validan la APK o el S Pen.

## Ejemplo de ejercicio para video de aceptación
Resolver manuscritamente en la hoja, sin teclados ni fórmulas generadas por fuente:
```
 ∫₀² (3x² + 2x) dx
 = [x³ + x²]₀²
 = (8 + 4) - 0
 = 12
```
Los caracteres de arriba **solo documentan qué se escribe**. En el producto TODO se traza con lápiz y las estructuras predefinidas deben insertarse como tinta vectorial personalizada.

## Aclaración importante: qué significa "reemplaza por la función"
Interpretación conservadora: sustituir un dibujo gestual por una estructura visual matemática ya dibujada por el usuario (por ejemplo, el símbolo de integral con zona libre para límites e integrando). **No** interpretar automáticamente como 'resolver la integral', 'calcular' ni 'escribir LaTeX'. Resolver ejercicios completos puede hacerse manualmente como en un cuaderno, usando las plantillas para facilitar notación.
