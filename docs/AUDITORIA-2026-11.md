# Auditoría de Trazo (noviembre 2026)

Hecha con **Gemini 4 Argon (high)** en tres pasadas, en modo solo lectura:

1. `core/`: 63 archivos.
2. `app/ui`, `app/ink` y `MainActivity`: 47 archivos.
3. `ai`, `app/ai`, `app/data`, `app/sync` y la web: 46 archivos.

No se gastó la cuota, así que no hizo falta pasar a Gemini 3.8.

Después revisé a mano en el código los hallazgos más graves. Estado de cada uno:

- **✅ Verificado:** lo confirmé leyendo el código.
- **🟡 Plausible:** el razonamiento cierra, pero falta probarlo en la tableta o con un test.
- **⬇️ Rebajado:** el auditor exageró la gravedad, o el problema está mitigado.

No se arregló nada todavía.

---

## 1. Críticos (cuelgue o pérdida de datos probable)

| # | Dónde | Problema | Estado |
|---|---|---|---|
| K1 | `app/sync/SyncWebServer.kt:191` | `input.readNBytes(int)` existe recién en Android 13 (API 33). Pero `minSdk = 26`, así que en Android 8–12 cualquier POST a la web de revisión tira `NoSuchMethodError` y la conexión se corta. El desugaring no cubre `InputStream`. | ✅ |
| K2 | `ui/LocalModelsScreen.kt:186` dentro de `SettingsScreen.kt:131` (vía `AiSettings.kt:86`) | «Modelos en la tableta» se dibuja dentro del `Column` con `verticalScroll` de Ajustes. Adentro hay un `LazyColumn` con `weight(1f)`, que recibe una altura infinita: puede colgarse o quedar con altura 0 cuando hay modelos. Tampoco tiene `BackHandler`, así que «atrás» sale de Ajustes entero. | 🟡 (anidado verificado; falta probarlo en la tableta) |
| K3 | `ai/AiMarkPainter.kt:30-59` | Es un `object` con `Paint`, `Path` y `RectF` compartidos. Lo usan a la vez el lienzo (hilo principal, `InkCanvasView:915`) y la exportación a PDF/PNG (`PageExporter:291`, en segundo plano). Si exportás mientras se dibuja, las marcas pueden salir corruptas. | ✅ (estado compartido); 🟡 (choque real) |
| K4 | `ui/EditorViewModel.kt:~1796, 1275, 1282` | Al empezar un examen no se cierra ni se cancela el chat de IA abierto. Además `writeExplanation` y `writeTutorAnswer` no chequean `study.isAllowed`. Una respuesta que llega después de arrancar el examen se escribe igual en la hoja. | ✅ (faltan los chequeos en 1275/1282) |

## 2. Altos

### Núcleo (`core`)

| # | Dónde | Problema | Estado |
|---|---|---|---|
| A1 | `SyncModel.kt:101-107`, `SyncEngine.serve` | `SyncKey.parse("nb:…")` no valida el id. El auditor lo marcó como path traversal, pero `TrazoRepository.safe()` (línea 613) reemplaza todo lo que no sea `[A-Za-z0-9_-]`. El riesgo real es otro: dos ids distintos, como `a/b` y `a_b`, terminan en el mismo archivo y uno pisa al otro. | ⬇️ (era crítico; queda medio) |
| A2 | `Calculus.kt:22-35`, `Evaluator.kt:37-45` | ∫₋₁¹ 1/x² dx da un número finito. Tendría que decir que diverge, porque hay una singularidad dentro del intervalo. | 🟡 |
| A3 | `MathNotes.solveLinear` (383-405) | Decide si la ecuación es lineal probando solo x = 0, 1 y 2. Con `x³-3x²+3x=5` responde x = 5, que está mal. | 🟡 (el contraejemplo cierra: es (x-1)³ = 4) |
| A4 | `InkController.cancel()` (563-566) | Cancelar mientras movés o escalás una selección no devuelve las imágenes arrastradas a su lugar (`dragTo` sí lo hace, 1239-1242). | 🟡 |
| A5 | `Editing.kt:28-35` (Eraser), `MathNotes.refresh` (900-923) | Al cortar un trazo con la goma, los pedazos conservan `calc`, `patternId` y `groupId`, así que el glifo cuenta doble. Además `refresh` no detecta que se recortó una entrada. | 🟡 |
| A6 | `SyncEngine.runClient` (154-167) | Si el cambio local sale bien pero falla el envío remoto, `SyncBase` no se actualiza y en la próxima sincronización se duplican las copias de conflicto. | 🟡 |

### App: interfaz y tinta

| # | Dónde | Problema | Estado |
|---|---|---|---|
| A7 | `EditorViewModel` `onCleared` (~1765), `saveNow` (1740-1747) | Si hay un trazo en curso, `onCleared` no guarda. `saveNow` corre en el hilo principal sin `runCatching`. Y el `ON_STOP` se pierde después de `nav.push`. Puede perderse lo último que escribiste. | 🟡 |
| A8 | `InkCanvasView` `drawInkOverlay` (1095-1120) | En API 29+, `drawDryLayer` sale antes en la línea 1054 y nunca asigna `currentMatrixValues` ni `cacheMath`. El resaltador se dibuja con la matriz vieja y queda corrido al hacer zoom o desplazar. | 🟡 |
| A9 | `InkCanvasView:722` | Con «Escribir con el dedo» en modo cuaderno, el segundo dedo no pasa a navegar, porque la condición pide el modo PAD. | 🟡 |
| A10 | `webHost.send` (1448-1467), `chatImageFile` (1659) | La imagen se guarda antes de chequear `webAllowed` y `chatBusy`. `chatImageFile` no valida el nombre con `isValidName`. `MainActivity.onStop` no pausa la web. | 🟡 |
| A11 | `ErrorSheetScreen:37`, `StudyDestination:20`, `PracticeScreen:38` | `loadNotebook` se llama sin `runCatching`, así que un cuaderno dañado cuelga la app. Además Práctica reusa ejercicios de otro cuaderno. | 🟡 |
| A12 | `ImagePainter`, `StrokePainter`, `PaperPainter` | Comparten `Paint` y `Path` entre hilos, igual que K3. `ImagePainter.bitmap` decodifica la imagen dentro de `onDraw`, lo que traba el dibujo. | 🟡 |

### App: IA, datos y sincronización

| # | Dónde | Problema | Estado |
|---|---|---|---|
| A13 | `LocalCompletion` (54-63) | Los motores se guardan por `model.id`. Los modelos quedan en memoria y sus reservas impiden borrarlos o cambiar el proyector. | 🟡 |
| A14 | `HuggingFace.fileUrl`, `OnDevice.kt:91-121`, `LocalModelStore` 113/139 | La URL usa `/resolve/main/`, pero el validador exige un hash de commit. `.litertlm` no está soportado y el `.gguf` del proyector se rechaza. | 🟡 |
| A15 | `TrazoRepository.listNotebooks` (171-175) | `it.page(0)` está fuera del `runCatching`. Un cuaderno con la primera página dañada cuelga la portada. | ✅ |
| A16 | `TrazoRepository` `upsertPattern`/`deletePattern` (106-119) | No están dentro de `synchronized(lock)`. Si dos escrituras se cruzan, se puede perder un patrón. | 🟡 |
| A17 | `AiOAuth` `LoopbackReceiver.await` (214-230) | No tiene try/catch por conexión y no comprueba el `state` de OAuth. Una conexión rara corta el inicio de sesión, y falta esa protección contra CSRF. | 🟡 |
| A18 | `MediaPipeLocalInference` (60-80, 209-219) | Hay una carrera entre `close` y la generación: puede filtrar la sesión o cerrarla dos veces. | 🟡 |
| A19 | `LlamaCppInference` (51-85, 115-119) | `nativeResetCancel` después de cargar el modelo borra un «cancelar» que se pidió durante la carga. | 🟡 |
| A20 | `SyncService` (535-544, 697-702, 742) | `stopDiscovery` no limpia `nearby`, `unpaired` ni `serviceIds`. Si una resolución falla, `resolveNext` queda trabado y no aparecen más dispositivos. | 🟡 |

## 3. Medios

| # | Dónde | Problema |
|---|---|---|
| M1 | `SyncMerge.folders` (126-127) | Puede crear ciclos de carpetas o pasar `MAX_DEPTH`. |
| M2 | `MathLayoutConverter` 398-406, `Parser.kt:183` | La fila de matriz `[1 -2]` se lee como una resta, y `[a]` se colapsa a un escalar. |
| M3 | `FunctionPlot.kt:448` | `sin^-1 x` falla, porque el exponente se lee con `primary()`. |
| M4 | `WebText.closing` (404-427) | Empieza a buscar en `from+1` y se saltea escapes y comillas invertidas al principio de un énfasis. |
| M5 | `PatternEditorViewModel:88`, `:155`, `PatternScreens` 111-120 | Error de índice si no hay `gestureExamples`. UNKNOWN con la letra vacía vuelve a AUTO. Importar y exportar corre en el hilo principal. |
| M6 | `ReviewCancelHandle` (~1066), `deletePage` (694-715) | Cancelar vuelve antes de `discardPartialNotes`. Borrar una página deja revisiones durables y entradas del diario huérfanas. |
| M7 | `AiChat.kt:203`, `:393` | `canAsk` exige un mensaje que no sea de error. `markdown()` des-escapa demasiado temprano. |
| M8 | `AiSettings` 339/344/477 | Iniciar o cerrar sesión en el editor de perfil se guarda antes de tocar «Guardar». |
| M9 | `GraphMode` 92-101, 155, 307 | Recalcula los puntos notables en cada frame. Las claves de `pointerInput` están mal. `coerceIn` puede tirar excepción si min > max. |
| M10 | `InkCanvasView` 719-760, 841 | La palma entra en el promedio al navegar con dos dedos. |
| M11 | `web/app.js` 58-83, 433-456 | Un 401 de un tick en vuelo deshace un emparejamiento recién hecho. |
| M12 | `web/app.js` 292-327 | Carrera en `renderPage`: al deshacer puede quedar la imagen vieja. |
| M13 | `AiClient` 250-251, 510-511 | Las partes de «pensamiento» de Gemini se concatenan a la respuesta. `plusWhenBothReported` pierde `imageInputTokens`. |
| M14 | `AiReview` 126-189 | `parseFreeText` puede desbordar en `toInt`. `parseJson` toma desde la primera `{` hasta la última `}`. |
| M15 | `AiTutor` 83-102 | La regex de CORRECT es demasiado laxa y da por buenas respuestas que no lo son. |
| M16 | `DurableReviewQueue.acknowledge` (186-195) | Si falla la escritura, la reserva queda trabada. |
| M17 | `AiStore` 89-95, 107-119 | Carreras en `credential` y `secretKey`. |
| M18 | `StudyStore` 139-140, 237-241 | `startPractice` y `clearSession` no llaman a `refreshExam`. |

## 4. Bajos

- `SecureChannel.writeName` puede partir un par sustituto UTF-16. `sessionKeys` separa con un 0 sin prohibir el 0 en los ids.
- `SyncWebAccess` guarda un puerto efímero (95-108).
- `web/app.js`: el ancho al redimensionar no está acotado (869-875).
- `NotebookListScreen:483` cambia el filtro desde el hilo de IO.
- `EditorScreen:141`: `includeAi` junto con `exportClean`.
- `DockableBars`: el `DragHandle` no se libera (falta `dispose`).
- `MathBubble` y `AmbiguityBubble`: no acotan la posición vertical.
- `SettingsScreen:220`: usa `accent2` donde no corresponde.

---

## Orden sugerido para arreglar

1. **K1** (`readNBytes`): una línea, y rompe la web en Android 8–12.
2. **K4 + A10**: bloqueo de la IA durante el examen.
3. **A15 + A11 + A7**: cuelgues con cuadernos dañados y pérdida de lo último escrito.
4. **K3 + A12**: pinceles compartidos entre hilos.
5. **K2**: pantalla de modelos locales.
6. Matemática (**A2**, **A3**, **M2**, **M3**), con un test por caso.
7. Sincronización (**A1**, **A6**, **M1**, **A20**).
8. El resto de los medios y bajos.

Las salidas completas del auditor están en el scratchpad de la sesión (`audit/out1.txt`, `out2.txt`, `out3.txt`).
