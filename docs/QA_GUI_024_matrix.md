> Estado de esta matriz: inventario preparado antes de la ejecución final. Los resultados efectivos y límites de la entrega figuran en `validation/BUILD.json`, los XML JUnit y el informe 88 del paquete.

# Auditoría GUI Trazo 0.2.4 — matriz de cobertura

Fecha de revisión: 2026-10-31. Inspección estática del árbol y de las evidencias ya guardadas; esta auditoría no ejecutó pruebas.

## Alcance y procedencia

- Árbol basado en `HEAD 6e37eaa` (`Restore verified complete Trazo 0.2.3 source`). El árbol de trabajo tenía cambios sin confirmar ajenos a esta auditoría en `app/build.gradle.kts`, `EditorScreen.kt`, `PagesPanel.kt`, `Polynomials.kt`, `PolynomialsTest.kt` y dos pruebas nuevas de GUI (`GuiUserFlowsTest.kt`, `InkCanvasMathNativeTest.kt`). No se editaron. Las dos pruebas nuevas se describen como pendientes de ejecución, no como cobertura ya validada.
- La interfaz es Android nativo: Compose para pantallas, barras y diálogos; `InkCanvasView` nativa dentro de `AndroidView` para el lienzo y eventos de lápiz/tacto.
- Evidencia histórica en `output/emulator/interfaz/`: Android 14/API 34, `sdk_gphone64_x86_64`, con lápiz sintetizado. `interfaz.txt` informa un recorrido OK; `formatos.txt` informa OK en teléfono 1080×2400/420 dpi, teléfono horizontal 2400×1080/420 dpi, tableta 1600×2560/320 dpi, tableta horizontal 2560×1600/320 dpi y PC 1920×1080/160 dpi. El script `scripts/ci-emulator.sh` espera 13 capturas principales y 15 capturas de formatos. Esto acredita corridas del emulador anteriores, no un dispositivo físico ni una corrida de esta revisión.
- No hay `/dev/kvm` en este entorno de auditoría; no se intentó instalar ni arrancar un emulador.

## Inventario funcional de GUI

| Área visible | Funciones implementadas que aparecen en la interfaz | Evidencia de código / pruebas existente |
|---|---|---|
| Cuadernos | Crear, abrir, renombrar y borrar; portada; carpetas, filtros, búsqueda, orden reciente/nombre/manual; mover entre carpetas; importación/exportación del cuaderno. | `NotebookListScreen.kt`; `GuiTourTest.tourOfTheInterface` recorre la lista y abre un cuaderno, pero no acciona la mayoría de estas operaciones. |
| Editor y lienzo | Tinta de lápiz/dedo configurable, resaltador, goma parcial o por trazo, lazo, forma paramétrica, mano/pan; zoom y encajar; colores rápidos/personalizados; deshacer/rehacer; copiar/pegar/duplicar; insertar patrones; limpieza y borrado de página; navegación y panel de páginas; ajustes de herramienta y barras acoplables/libres. | `EditorScreen.kt`, `EditorToolbar.kt`, `InkCanvasView.kt`, `PagesPanel.kt`; `EditorInstrumentedTest` comprueba eventos sintetizados y varias operaciones del lienzo. `GuiTourTest` verifica que el editor y paneles pluma/goma estén presentes. |
| Reconocimiento y enseñanza | Conversión por pausa, decisión ante ambigüedad, enseñar gesto no reconocido, reemplazo por patrón y aprendizaje de corrección; activación rápida de detección/autoalineado. | `InkController` y `TeachSheet`; `DemoExerciseTest` cubre enseñanza/reuso en recorrido instrumentado. No hay recorrido GUI por todos los estados de ambigüedad. |
| Cálculo manuscrito | Muestra el resultado manuscrito tras un igual final; selector de color; toque para explicar; calcular selección desde el lazo; confirmar solución imaginaria cuando aplique. | `MathNotes`, `InkController`, `EditorScreen`; cobertura fuerte de cálculo en JVM, pero falta el camino GUI de entrada→recomposición que se detalla abajo. |
| Patrones | Biblioteca, búsqueda y filtros por sección; habilitar/deshabilitar; insertar; crear/editar ejemplos de gesto y salida; probar; eliminar; importar/exportar/restaurar signos. | `PatternScreens.kt`, `PatternPicker.kt`; el tour GUI comprueba biblioteca y apertura de edición, no edita/guarda ni prueba gestos mediante la pantalla. |
| Preferencias | Tema claro/oscuro, acento, papel, herramienta y barra, gestos, autoalineado, tiempo de pausa, respuestas matemáticas y color. | `SettingsScreen.kt`; el tour abre preferencias y cambia al tema oscuro. No hay cobertura de persistencia visual de preferencias por formato. |
| Modo IA / modelos | Configurar perfiles y proveedores, revisar la página, mostrar marcas/respuestas, convertir correcciones en tinta, gestionar modelos locales y exportar con/sin marcas. | `AiSettings.kt`, `AiLayer.kt`, `LocalModelsScreen.kt`, `EditorScreen.kt`; existen pruebas JVM y de integración de datos/IA, pero no una batería GUI de flujos o errores de pantalla. |
| Estudio | Práctica y examen con respuestas, navegación, temporizador, entrega y recuperación de estado dañado; ayudas bloqueadas durante examen. | `PracticeScreen.kt`, `StudySessionScreen.kt`; pruebas instrumentadas de stores y transacciones, sin recorrido GUI de punta a punta. |
| Exportación | PDF/PNG de página y exportación/importación de cuaderno/patrones; selector de documento Android. | `ExportInstrumentedTest` y `ParametricExportInstrumentedTest` validan contenido y render del exportador; falta tocar el selector del sistema desde GUI y verificar URI/archivo resultante. |

## Cobertura automatizada encontrada

| Suite/archivo | Qué verifica | Qué no verifica |
|---|---|---|
| `app/src/androidTest/.../GuiTourTest.kt` | Presencia y navegación básica de lista, editor, paneles de pluma/goma, selector, biblioteca, edición de patrón, preferencias y tema oscuro; captura del selector de color; formato captura lista/editor/pluma. | El propio comentario del test dice que falla por ausencia de controles, no por lógica. El caso `formFactor` verifica texto accesible y capturas, no interacción completa ni ausencia de recortes. |
| `app/src/androidTest/.../EditorInstrumentedTest.kt` | Eventos stylus sintetizados, trazo libre, conversión de integral/raíz/fracción, goma, undo/redo, persistencia al recrear, zoom, doble toque de dos dedos, palma/finger y cancelación. | No es S Pen físico; no acciona la barra Compose para seleccionar esas herramientas. No crea una cuenta con igual ni observa el resultado matemático en la GUI. |
| `app/src/androidTest/.../DemoExerciseTest.kt` | Demo de integral y flujo de enseñar/reutilizar gesto; produce capturas y evidencia instrumentada. | Cobertura de demo delimitada, no navegación general ni matriz de layouts. |
| `app/src/androidTest/.../ReorderableInstrumentedTest.kt` | Arrastrar/reordenar, claves estables, auto-scroll y cancelación en un host de prueba. | No prueba la integración visual en la lista real de cuadernos. |
| `app/src/androidTest/.../PageHistoryInstrumentedTest.kt` | Historial al cambiar entre páginas. | No acciona el panel Compose de páginas. |
| `app/src/androidTest/.../ExportInstrumentedTest.kt`, `ParametricExportInstrumentedTest.kt` | Render PDF/PNG, cuenta de páginas y formas exportadas. | No verifica el selector de archivo/documento ni el flujo de usuario. |
| `app/src/test/.../GuiUserFlowsTest.kt` *(presente sin confirmar en el árbol al auditar)* | Nueva batería Compose/Robolectric: crear/abrir cuaderno, paneles de pluma/goma, vista previa y navegación de páginas, abrir ajustes IA sin ejecutar llamadas remotas, biblioteca, práctica/examen y barra lateral. | No se ha ejecutado en esta auditoría. No dibuja tinta ni prueba teclado/IME, persistencia de pantallas o flujos de IA/examen activos. |
| `app/src/test/.../ink/InkCanvasMathNativeTest.kt` *(presente sin confirmar en el árbol al auditar)* | Nueva prueba Robolectric con `GraphicsMode.NATIVE`: inserta presets `2 + 3 =`, consulta `mathResults`, dibuja `InkCanvasView`, luego convierte un trazo y vuelve a dibujar. Incluye igual aislado. | No se ha ejecutado en esta auditoría. Inserta patrones directamente en `InkController`; no valida reconocimiento desde eventos de mano, la pantalla Compose ni su recomposición. |
| `app/src/test/.../ui/TabletLayoutPolicyTest.kt` | Política de paneles/ancho útil, umbrales 600 dp, fuentes grandes, anchos inválidos, split/orientación como datos de entrada. | No mide una composición Compose real. |
| `app/src/test/.../ui/ToolbarPreferencesTest.kt` | Modos de barra para teléfono/tableta/ancho, breakpoint, tamaño de objetivos y escala de fuente. | No confirma visibilidad, scroll o interacción real de los controles en ventana. |
| `core/src/test/.../MathNotesTest.kt`, `InkControllerTest.kt` y `math/*` | Parsing/layout y salida manuscrita; igual al final; salida bloqueada por tinta posterior; tocar resultado en el controlador; composición matemática y eventos del dominio. | No ejecuta el ciclo Android View → evento → `PageChanged` → recomposición/dibujo. |

## Revisión del posible crash `=` / autocompletar durante eventos y composición

No encontré una ruta de teclado/IME en el lienzo: `InkCanvasView` recibe eventos de puntero; la matemática se deriva de trazos reconocidos. La secuencia observada en código es: `InkController` emite `PageChanged` → `EditorViewModel` incrementa `revision` y `InkCanvasView` invalida el contenido → en `onDraw()` consulta `controller.mathResults` → `MathNotes.results()` calcula la tinta derivada. `InkController.mathResults` mantiene caché por página/versión/lista de patrones y reinvalida por cambio de color o al desactivar cálculo.

La ruta tiene cobertura de dominio para un igual final (produce resultado), un igual con tinta posterior (no tapa la respuesta), y toque en resultado (emite explicación). El layout convierte una `MathException` a línea vacía. Además, el árbol ahora contiene `InkCanvasMathNativeTest`, que inserta presets directamente y dibuja la vista nativa con la salida calculada; está pendiente de ejecución y no cruza la pantalla Compose ni el reconocedor. No hay evidencia en los artefactos consultados de que el crash haya ocurrido. Por tanto se clasifica como **no reproducido; riesgo de integración Compose/evento de escritura aún pendiente**, no como bug confirmado. Conviene capturar logcat/stacktrace si se ha observado externamente.

Prueba GUI nueva recomendada para cerrar el hueco:

1. En una página limpia con patrones matemáticos activados, abrir el editor Compose y confirmar que el lienzo se dibuja.
2. Añadir tinta reconocida equivalente a `2 + 3 =` a través de eventos que terminen en `InkController` (usar el driver/eventos de prueba para los trazos y comprobar que el grupo `Igual` se reconozca). Esperado: un resultado manuscrito `5`, sin excepción en el hilo principal ni fallo de composición.
3. Escribir o insertar tinta después del igual. Esperado: desaparecer el autocompletado del renglón, conservando la tinta nueva.
4. Deshacer y rehacer el último evento. Esperado: el resultado desaparece y vuelve, sin trazo fantasma.
5. Cambiar página, tema/color o recrear la actividad mientras el resultado está visible; volver al editor. Esperado: dibujo estable, resultado correcto y sin duplicación ni crash.
6. Variar a una línea incompleta (`2 + =`), doble igual, ecuación con igual intermedio y patrón igual deshabilitado. Esperado: rechazo/ausencia de resultado controlada; la app sigue operativa.

En el instrumento UI/Robolectric, registrar una excepción de main thread y comprobar semántica/estado observable además de “la actividad sigue viva”. El caso 2 es el crítico porque fuerza el camino evento→mutación→recomposición. Casos 3–6 son regresión de invalidación, caché y frontera entre tinta del usuario y salida calculada.

## Huecos priorizados y escenarios concretos

Prioridad: **P0** evita crash o pérdida de tinta; **P1** valida tareas centrales; **P2** completa variedad y accesibilidad.

| Prioridad / formato | Escenario y resultado esperado | Brecha actual |
|---|---|---|
| P0 · teléfono vertical, 360–599 dp, teclado software cerrado | Secuencia `2 + 3 =`, resultado `5`, añadir tinta tras `=`, undo/redo, pausar/reanudar y volver a abrir; sin crash y sin borrar tinta del usuario. | `InkCanvasMathNativeTest` presente sin confirmar añade inserción directa y dibujo nativo, pero queda pendiente de ejecutar y no cubre escritura reconocida, eventos de stylus ni Compose. |
| P0 · tableta, 840 dp o más, rail de patrones visible | Repetir `=` con el rail abierto/minimizado y panel de páginas abierto; verificar que el resultado no queda fuera del lienzo ni detrás del rail y que el toque explica la cuenta. | Política de ancho tiene tests JVM; no hay verificación del resultado dentro de la composición real. |
| P1 · teléfono vertical | Crear y renombrar cuaderno con teclado abierto; introducir `A = 2` y tildes; confirmar/ cancelar; buscar y limpiar búsqueda; no perder foco, texto ni botones bajo IME. | No existen pruebas de IME para `OutlinedTextField`/diálogos. El lienzo no procesa composición de texto. |
| P1 · teléfono vertical y horizontal | Abrir selector de patrones, seleccionar/insertar, enseñar gesto, guardar salida de patrón; volver atrás; confirmar que sheet/dialog no tapa controles críticos ni deja el estado a medio guardar. | El tour sólo comprueba presencia de la biblioteca y pantalla de edición. |
| P1 · tableta vertical y horizontal | Alternar panel de páginas y rail de patrones; dockear barras a top/bottom/left/right/free; reordenar herramientas; comprobar el lienzo conserva ancho mínimo y todos los targets son accesibles. | Tests de política y barra son matemáticos; capturas actuales sólo cubren orientación fija con barra normal. |
| P1 · teléfono y tableta | Crear página, duplicar, navegar, borrar con confirmación, cancelar y deshacer; rotar/recrear en cada paso; persistencia de trazos e índice. | El historial se prueba a nivel ViewModel/Store, no interacción completa del panel. |
| P1 · teléfono y tableta, lápiz y dedo | Lazo → copiar/pegar/calcular/recolorear/borrar; selección de forma → mover/escalar/girar/duplicar/convertir y undo/redo; usar stylus y dedo según preferencia. | Las pruebas actuales mezclan driver/View con llamadas directas al controlador y no recorren SelectionMenu/ShapeInspector. |
| P1 · teléfono vertical con teclado | Buscar cuaderno/patrón/ajuste; probar acentos, texto largo, borrar, rotación con query y “sin resultados”; cerrar teclado sin salir de pantalla. | No hay test UI para campos de búsqueda y estado guardado durante recreación. |
| P1 · teléfono y tableta, sin red | Abrir práctica/examen con datos semilla; contestar/entregar; navegar atrás y volver; confirmar bloqueo de IA/calculadora y que respuestas sobreviven recreación. | Datos transaccionales están probados; GUI de estados y navegación no. |
| P2 · teléfono y tableta | Elegir y cancelar URI de exportación PDF/PNG y archivo de cuaderno; importar archivo válido/no válido; comprobar avisos y archivo final. | Exportadores y formatos sí están cubiertos, no los selectores Android. |
| P2 · todas las orientaciones/form factors | Ajustar escala de fuente 1.0/1.5/2.0, contraste/tema, navegación por teclado y TalkBack; comprobar descripciones únicas y que no haya controles cortados. | Sin auditoría de accesibilidad ni pruebas visuales automatizadas de bounds; sólo `By.text`/`By.desc` y capturas. |
| P2 · dispositivo físico con S Pen | Presión, botón lateral, borrador trasero, hover, rechazo de palma y pausa mientras llega input real. | Las evidencias guardadas son del emulador con eventos sintetizados; no sustituyen validación física. |

## Conclusión de auditoría

El repo tiene una base útil de tests de dominio y varias pruebas instrumentadas del lienzo, más un tour visual histórico para distintos tamaños. También hay dos fuentes de pruebas GUI nuevas en el árbol de trabajo, aún sin confirmar ni ejecutar en esta auditoría: la batería Compose cubre rutas de usuario y la prueba de canvas cubre presets y dibujo nativo. La brecha restante es probar escritura reconocida con `=` a través del flujo de eventos y validar la recomposición/layout real en teléfono y tableta con paneles activos. No se afirma que ninguna suite se haya ejecutado durante esta auditoría.
