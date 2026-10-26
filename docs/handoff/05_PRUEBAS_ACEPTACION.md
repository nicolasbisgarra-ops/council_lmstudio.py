# Matriz QA obligatoria

**Separar resultados por nivel de evidencia:** JVM unit tests, instrumentadas Android/emulador, dispositivo físico con S Pen. Los tests web previos NO certifican una app nativa.

| ID | Caso | Criterio de aceptación | Automatización |
|----|------|------------------------|----------------|
| T01 | Dibujar libremente | Muestras persistidas y renderizadas con fluidez | Instrumentada + física |
| T02 | Integral + mantener punta 1000 ms | Convierte a patrón de salida 100 % trazos | Instrumentada + física |
| T03 | Levantar en 900 ms | No convierte, tinta idéntica | Unit + instrumentada |
| T04 | No dibujar; solo apoyar 1500 ms | Ningún cambio a la página | Unit |
| T05 | Temblor menor al radio | Sin falsos resets | Unit |
| T06 | Mover punta de forma continua | No hay reemplazo involuntario | Unit + física |
| T07 | Gesto desconocido | Se conservan trazos | Unit + física |
| T08 | Dos gestos de parecido cercano | Sin sustitución si hay ambigüedad | Unit |
| T09 | Patrón integral personalizado | La salida es el dibujo del usuario, no fuente | Unit + física |
| T10 | Patrón raíz con varios trazos | Reconoce, reemplaza como vectores | Unit + instrumentada |
| T11 | Patrón de fracción (línea) | Reemplazo manuscrito, sin texto | Unit + instrumentada |
| T12 | Salida + seguir escribiendo encima | Se combinan trazos y se puede editar/borrar parcialmente | Instrumentada + física |
| T13 | Deshacer conversión | Restaura gesto original completo de una sola acción | Unit + instrumentada |
| T14 | Rehacer conversión | Recupera exactamente los trazos de salida | Unit + instrumentada |
| T15 | Editar patrón existente | No altera histórico de notas previas | Unit |
| T16 | Exportar/importar cuaderno editable | Equivalencia de puntos/colores/grupos | Unit + instrumentada |
| T17 | Cerrar/abrir app | Persisten notas y biblioteca de patrones | Instrumentada |
| T18 | Zoom / pan / rotación | Trazos sin degradación y ubicaciones correctas | Instrumentada + física |
| T19 | Palma sobre pantalla con S Pen | No aparecen trazos falsos; CANCEL manejado | Física |
| T20 | Rotar pantalla en pausa y durante gesto | No crash, datos estables | Instrumentada |
| T21 | Android sin conexión | Escritura/conversión sin internet | Física/emulador |
| T22 | Video funcional completo | Se ve mano/lápiz escribiendo notación **toda manuscrita** y resolución | Física preferible |
| T23 | App nativa real | APK Android; sin WebView/HTML como motor | Revisión fuente + apk |
| T24 | Sin LaTeX/Texto en papel | Ninguna representación tipográfica de las expresiones en lienzo | Revisión fuente + UI |

## Ejercicio funcional que debe resolverse en video
Usar un gesto de integral y mostrar que sustituye por salida manuscrita personalizada; completar límites e integrando a mano, luego resolver **una** integral completa de ejemplo:
```
∫₀² (3x² + 2x) dx
= [x³ + x²]₀²
= (8 + 4) − 0
= 12
```
La cadena de arriba describe la solución matemática; no autoriza fuentes digitales sobre el papel. Mostrar al menos un segundo ejemplo de raíz o fracción.

## Reporte obligatorio para Claude
- `test-results.md` con comando, fecha, dispositivo/API/versión, resultado real, evidencias (log/captura/video) y pendientes.
- No afirmar que pasó una prueba que no se ejecutó.
- Verificar archivo APK existe, se instala y abre (idealmente física + instrumentada).
- Grabación con S Pen **real** cuando esté disponible. Si es automatizada, señalarlo en video y reporte, y no fingir contacto humano.

## Tests históricos incluidos en este ZIP
`referencias/trazo_tests_v1.json` contiene 12 checks del HTML v1, declarados previamente. No validar en base a ellos el motor nativo. Para v2 hay HTML y capturas/video, pero no se encontró en los adjuntos disponibles un reporte automatizado de 14 casos; ese número no se reutiliza como verificación nueva.
