# DND-INTEG — integración y persistencia

Rama: `codex/dnd-integ`. Base efectiva de OLA 2: `915555f` (incluye core y UI).
SHA de implementación validada: `f3a4618d55413deba4b7f48085cb3284d3d7d9cf`.
Este informe se confirma en un commit posterior de documentación.
Sin push, PR ni cambios en otras ramas o worktrees.

## Lectura previa y persistencia por lista

Se leyeron ambos informes de OLA 1 y las implementaciones reales de
`Reorder.kt` y `Reorderable.kt`, además de los modelos, serializadores y
consumidores siguientes, antes de cambiar cada lista.

### 1. Perfiles del Modo IA

| Aspecto | Hallazgo y resultado |
| --- | --- |
| Archivos | `ui/AiSettings.kt`, `data/AiStore.kt`; leído también `ai/AiProfile.kt` |
| Antes | `AiSettings.profiles` es una lista. `AiProfile.encodeList/decodeList` guarda/lee un array JSON ordenado en `trazo_ai/profiles`. `defaultId` selecciona por id; las claves y sesiones cifradas viven aparte. |
| Persistencia | Orden local de ids durante el gesto; `AiStore.reorderProfiles` aplica esos ids a los perfiles vigentes y escribe una vez al terminar. Conserva el modelo en uso incluso si datos antiguos no tienen `defaultId`. |
| Migración | No hace falta un campo nuevo: el array ya representa el orden explícitamente. Los datos viejos conservan su secuencia exacta. No se modifica el formato, las credenciales, OAuth ni el editor de inicio de sesión. |

La aplicación del orden por ids conserva contenido actualizado, incorporaciones
y bajas que pudieran ocurrir mientras se arrastra. No vuelve a guardar copias
viejas de los perfiles ni toca sus claves. La lista usa ids como keys y muestra
`positionLabel`.

### 2. Herramientas de barra y rueda

| Lista / archivos | Modelo previo | Persistencia y migración |
| --- | --- | --- |
| Barra: `ui/ToolPreferencesEditor.kt`, `ui/SettingsSections.kt`; leídos `data/ToolPreferences.kt`, `data/SettingsStore.kt`, `core/settings/ToolPreferences.kt` y `ui/SettingsScreen.kt` | `toolbarTools` contiene únicamente las herramientas visibles, en orden. El editor agregaba las ocultas al final y ofrecía flechas. `PEN` es obligatoria en la barra. | Se mantiene `SettingsStore.update` → `ToolPreferences.serializeToolbar`, sin cambio de formato ni migración. Actualización local inmediata y guardado barato por cruce: son seis herramientas conocidas. |
| Rueda: mismos archivos | `wheelItems` contiene las entradas elegidas en orden. Su normalizador exige entre 3 y 10; hay ocho claves conocidas. Según la API real de core, la obligatoriedad de PEN corresponde a la barra. | Se mantiene `SettingsStore.update` → `ToolPreferences.serializeWheel`, sin migración. Actualización inmediata y guardado barato por cruce, con las validaciones existentes al activar/desactivar. |

Solo las entradas seleccionadas/visibles están envueltas en `ReorderableItem`.
Las ocultas quedan después de una fila fija y no son destinos ni tienen handle;
activarlas las agrega al final. Mover no cambia pertenencia ni cantidad. Las
flechas Subir/Bajar conservan sus descripciones accesibles y usan `moveItem`.
Durante el gesto se desactivan cambios de visibilidad y flechas. Se usan las
claves persistidas de enum (`name`), no índices, y contadores de posición. El
buscador de preferencias incorpora el orden por arrastre.

### 3. Panel de páginas

| Aspecto | Hallazgo y resultado |
| --- | --- |
| Archivos | `ui/PagesPanel.kt`, `ui/EditorViewModel.kt`; leídos `data/TrazoRepository.kt`, `core/Storage.kt` y `core/LazyNotebook.kt` |
| Antes | El orden es la secuencia de `LazyNotebook.slots`, escrita por `TrazoFormat.encodeLazy` en `notebook.pages`. Cada `PageSlot` tiene id y puede conservar su JSON sin decodificar. `movePage(from,to)` ya ajustaba `pageIndex/lastPage` para conservar la misma hoja abierta. El panel calculaba el destino con una altura estimada y recién movía al soltar. |
| Persistencia | Cada cruce llama `movePage(..., deferSave = true)` y aplica `moveItem` a los slots. Ajusta inmediatamente el índice abierto sin cambiar controlador, selección, viewport ni historial. Suspende el guardado pendiente durante los cruces y al terminar lo programa con el mecanismo habitual de 700 ms. `saveNow` sigue cubriendo salida/segundo plano. |
| Migración | Ninguna: se conserva el formato versionado y el orden de `pages`. No se toca el mapa `sessions` ni `InkCanvasView.onSizeChanged`. |

El gesto se limita a un handle visible; desaparece la estimación manual de
altura. El auto-scroll y la animación salen del componente integrado. El
scroll hacia la hoja seleccionada se suspende durante el arrastre. El contador
superior y el de cada hoja usan `positionLabel`. La carga de miniaturas se
cancela/reinicia también si cambia el índice para no cargar otra hoja después
de una espera. Durante cada cruce no se copian los trazos vivos: se vuelcan una
vez mediante el guardado existente, usando el nuevo índice de la misma hoja.

### 4. Lista de cuadernos

| Aspecto | Hallazgo y resultado |
| --- | --- |
| Archivos | `ui/NotebookListScreen.kt`, `data/TrazoRepository.kt` |
| Antes | Un archivo atómico `<id>.trazo.json` por cuaderno; no había orden manual. `listNotebooks` ordenaba por `updatedAt` descendente y la UI ofrecía grillas Recientes/Nombre. |
| Persistencia | Nuevo `filesDir/notebook-order.json`, array de ids con `AtomicFile` y el lock existente del repositorio. Orden local inmediato y una escritura al soltar/cancelar o cerrar la pantalla. No relee las tapas para guardar los ids. Los errores de guardado se informan con Toast. |
| Migración | La primera lectura sin archivo de orden inicializa exactamente la secuencia por fecha que se mostraba antes. Lecturas posteriores conservan los ids conocidos, agregan nuevos al final y depuran borrados/repetidos. Si el archivo falta o está dañado, se recupera el orden previo por fecha sin tocar cuadernos. Una falla de escritura de la migración no impide listar. |

Se usa un archivo aparte porque el orden pertenece a la biblioteca completa,
no a un cuaderno individual: evita reescribir todos los documentos o cambiar
su formato/exportación. Renombrar o editar ya no altera el orden manual.
La vista Manual abre por defecto como LazyColumn con tapas compactas, handles
y contadores. Recientes/Nombre siguen disponibles como grillas. Con una
búsqueda activa se ocultan los handles y se rechazan movimientos para evitar
reordenar solo una parte filtrada. Cada menú y fila conserva su key por id.

**Al integrar con carpetas y portadas:** la lista usa un solo mecanismo de orden,
`NotebookOrder` (core, mismo `notebook-order.json`). Los cuadernos que no figuran
(nuevos o importados) van primero, del más reciente al más viejo, y `listNotebooks`
ya no reescribe el archivo. Dentro de una carpeta, arrastrar reacomoda solo los
cuadernos visibles y deja los demás en sus lugares (`NotebookOrder.withSubset`).
Subir/Bajar sigue en el menú de cada cuaderno como alternativa sin gesto.

## Integración común y rendimiento

Se respetan las firmas reales: `onMove` ocurre en cada cruce y no al soltar.
Todas estas LazyColumn comienzan directamente con filas reordenables: offset
cero; en herramientas la separación fija va después. Cada callback trabaja
sobre el estado mutable actualizado, incluso si hay varios cruces en un frame.
Las keys de LazyColumn, ReorderableItem y dragHandle coinciden. Las filas tienen
fondo opaco del mismo color de su contenedor para conservar la legibilidad
al pasar por encima de otras filas durante el gesto.

`ui/ReorderPersistence.kt` observa `draggingKey` y confirma cambios pendientes
al terminar/cancelar, con un fallback de `DisposableEffect` al salir. En
preferencias se usan listas virtualizadas con altura máxima explícita (400 dp
para perfiles y 320 dp para herramientas), porque la página existente tiene
scroll vertical y no puede medir una LazyColumn con altura infinita.

No se modifica `Reorderable.kt`: se reutilizan su long-press, traslación,
animateItem y auto-scroll. Los movimientos copian el contenedor en O(n), sin
recrear modelos ni decodificar tinta por cruce. La aplicación de ids guardados
es O(n + m), con un mapa enlazado. No se mide rendimiento de gestos/FPS en VM.
La lectura inicial de tapas de cuadernos sigue usando el mecanismo existente.

## Lógica pura y archivos nuevos

- `core/.../SavedOrder.kt`: `applySavedOrder`, para ids únicos; ignora ids
  inexistentes/repetidos y agrega nuevos en el orden de la fuente actual.
- `core/.../SavedOrderTest.kt`: 9 pruebas JVM de migración sin orden previo,
  orden completo, bajas/duplicados, altas, contenido actualizado durante el
  gesto, reapertura tras cambios de fechas, vacío, 100.000 filas y persistencia
  de slots congelados con la misma hoja abierta (ejemplo 5 → 2).
- `app/.../ui/ReorderPersistence.kt`: confirmación compartida al terminar.

No se agregan dependencias, cambios de versiones ni red al código. No se
modifican los archivos prohibidos. La entrega incluye este informe y los ocho
archivos de app enumerados en las tablas, más el helper de persistencia y los
dos archivos de core.

## Validación y autorrevisión

La sincronización completa se hizo por tar con las exclusiones pedidas. Las
correcciones posteriores de app se copiaron sobre ese proyecto, esperando la
copia antes de compilar. No se ejecutó Gradle en el teléfono.

| VM / directorio | Comando | Resultado |
| --- | --- | --- |
| ib3, `~/trazo-run` | `./gradlew -q -Ptrazo.coreOnly=true :core:test` | Código 0; **7.626 pruebas, 0 fallas, 0 errores, 0 omitidas**, contadas en 39 XML. Incluye las 9 nuevas de `SavedOrderTest`. |
| ib4, `~/trazo-run-i` | `./gradlew --no-daemon :app:assembleDebug` | **BUILD SUCCESSFUL en 29 s**; 40 tareas: 25 ejecutadas y 15 de caché. |
| ib4, mismo directorio, ajuste final de fondos | Mismo comando | **BUILD SUCCESSFUL en 10 s**; 40 tareas: 4 ejecutadas y 36 actualizadas. |

Logs de las VMs: `/tmp/trazo-dnd-integ-core.log`,
`/tmp/trazo-dnd-integ-assemble.log` y
`/tmp/trazo-dnd-integ-assemble-final.log`. APK en
`ib4:~/trazo-run-i/app/build/outputs/apk/debug/app-debug.apk`.
No se usa `~/trazo-run-t` ni se toca el directorio de pruebas del otro agente.

Solo se observaron advertencias existentes: constructor URL en
`HttpTransport.kt`, `LocalLifecycleOwner` en `EditorScreen.kt` y símbolos de
`libandroidx.graphics.path.so`. No hubo cambios de versiones ni dependencias.

Se autorrevisó el diff completo contra las APIs reales, los tres puntos de uso
del helper de confirmación, los índices sin cabeceras, las keys estables,
los límites de barra/rueda y la preservación de la hoja abierta. Se verificó
que los consumidores previos de `movePage` conservan su guardado habitual por
el parámetro opcional; `sessions` y los archivos prohibidos no cambian.
`git diff --check` pasó. Los SHA-256 de los 11 archivos del commit de
implementación coinciden con ib4; los dos archivos nuevos de core también
coinciden con ib3, donde se ejecutaron sus pruebas.

No hay emulador ni KVM: no se ejecutaron pruebas instrumentadas ni gestos
reales, ni se midieron FPS con miles de filas. Las pruebas JVM verifican la
lógica de orden y el formato de páginas; no ejecutan SharedPreferences,
AtomicFile de Android ni Compose. La compilación verifica la integración de
sus APIs; queda para dispositivo la validación visual del long-press,
auto-scroll, cancelación y persistencia de punta a punta al reabrir pantallas.
