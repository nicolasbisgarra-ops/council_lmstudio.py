# Web de revisión: muestras de código y de diseño

Para quien implemente (agente o persona). Son cuatro cambios. Cada uno trae **el contrato** (qué viaja entre el navegador y la tablet), **una muestra de código** con el estilo pedido y **cómo se tiene que ver**.

El diseño completo está en [`muestra-diseno.html`](muestra-diseno.html): abrilo en el navegador. De ahí se copian proporciones, jerarquía, espacios y tokens; no el HTML tal cual.

Archivos:

| Lado | Archivos |
|---|---|
| Navegador | `app/src/main/assets/web/{index.html,app.js,app.css,sw.js,manifest.webmanifest}` |
| Tablet | `app/src/main/kotlin/io/trazo/app/sync/SyncWebServer.kt`, `.../data/AiChatStore.kt`, `.../data/PageExporter.kt`, `.../ui/EditorViewModel.kt` (el `SyncWebHost`) |
| Motor (con pruebas JVM) | `core/src/main/kotlin/io/trazo/core/share/` |

## Estilo de código (vale para los cuatro)

**JavaScript del navegador**

- Sin dependencias, sin build, sin módulos. Un archivo, dentro del IIFE que ya existe.
- ES5 como el resto de `app.js`: `var`, `function`, sin clases, sin `async/await`. Promesas con `.then`.
- El DOM se arma con `document.createElement` y `textContent`. `innerHTML` solo para el HTML de mensajes que ya viene limpio del servidor, y solo a través de `rich()`.
- Nada de `onclick=""` ni `style=""` en el HTML: la política CSP no los deja. Los eventos se enganchan en `listen()`.
- Los pedidos pasan por `request(method, path, body, timeoutMs)`. No se llama a `fetch` suelto, salvo para bajar la imagen.
- Lo que es preferencia de este navegador va a `localStorage` con `load`/`save` (ya atrapan el error). Lo que es dato del estudiante vive en la tablet.
- Funciones cortas, con nombre de verbo. Un comentario arriba solo cuando explica **por qué**.
- Textos de la interfaz en español rioplatense, cortos, sin tecnicismos.

**Kotlin**

- KDoc en español, una o dos líneas, que diga qué devuelve y cuándo devuelve `null`.
- Toda entrada del navegador se valida antes de usarla. Valor desconocido → `400`. Sin hoja → `409` con `fail(NO_SHEET)`.
- Lo que cambia algo va por `POST` dentro de `authorized(req) { … }`.
- Lo que se pueda probar sin Android va en `core/` con su prueba JVM.
- No se agregan dependencias.

**CSS**

- Solo tokens (`var(--…)`); ningún color suelto fuera de los bloques de tema.
- Tres radios (`--r-s`, `--r-m`, `--r-l`) y un paso de espacio (`--gap`).
- Todo botón tiene `:focus-visible` y un área de toque de 40 px o más.

---

## 1. Rediseño

**Qué cambia**

- Barra de una fila: logo, título de la hoja, estado como punto de color + texto corto, botón «Instalar» (cuando corresponde) y menú.
- La hoja tiene su propia tira arriba (color de la hoja, agrandar).
- El chat tiene cabecera propia: nombre de la charla, historial, nueva charla.
- Los mensajes del tutor van sin globo (texto a todo el ancho); los del estudiante, en globo a la derecha.
- El veredicto («Primer error · renglón 5») es un bloque aparte debajo del mensaje.
- La caja de escribir es una sola pieza redondeada que cambia de borde al enfocarla.

**Qué no cambia**

- Los temas que ya existen (Sistema, Claro, Oscuro, Neón, Matrix, OLED, Vintage, Papel viejo, Papel nuevo) y sus tokens.
- Las vistas en PC (chat libre, las dos, hoja libre, solo chat, solo hoja), el arrastre y el cambio de tamaño.
- En teléfono, una columna con las pestañas Chat / Hoja.
- Los `id` que usa `app.js`. Si hay que renombrar uno, se renombra en los dos lados.

**Muestra (CSS)**

```css
/* Estado: un punto que se lee de reojo, y el texto para quien lo necesita. */
.status { display: flex; align-items: center; gap: 6px; font-size: .8em; color: var(--muted); }
.status::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--ok-fg); }
.app[data-online="0"] .status::before { background: var(--bad-fg); }
```

---

## 2. Color de la hoja

La imagen de la hoja la dibuja la tablet. El navegador elige el color y la tablet la dibuja así.

**Contrato**

```
GET /api/page.png?v=<versión>&paper=<id>
    id: claro | crema | gris | oscuro | negro      (sin «paper» = claro, como hoy)
    200 image/png        400 si el id no existe        404 si no hay hoja
```

- En `oscuro` y `negro`, la tinta negra se dibuja clara, igual que en la app con hoja oscura.
- El PNG guardado en el servidor se guarda por `pageKey + paper`, no solo por `pageKey`.
- La elección es de este navegador: `localStorage`, dentro de `trazo_look` como `paper`.

**Muestra (JS)**

```js
var PAPERS = [['claro', 'Clara'], ['crema', 'Crema'], ['gris', 'Gris'], ['oscuro', 'Oscura'], ['negro', 'Negra']];

// La hoja se vuelve a pedir con el color nuevo; la versión no cambia, así que se fuerza la carga.
function setPaper(id) {
  if (!has(PAPERS, id) || id === look.paper) return;
  setLook({ paper: id });
  S.pageV = null;
  if (S.data && S.data.page) loadPage(S.data.page);
}

function pageUrl(v) {
  return '/api/page.png?v=' + encodeURIComponent(v) + '&paper=' + encodeURIComponent(look.paper);
}
```

**Muestra (Kotlin)**

```kotlin
/** Los colores de hoja que puede pedir la web; null si [id] no es uno de ellos. */
fun webPaper(id: String?): PaperColors? = when (id ?: "claro") {
    "claro" -> PaperColors.LIGHT
    "crema" -> PaperColors.LIGHT.copy(sheet = 0xFFF3E9D2.toInt())
    "gris" -> PaperColors.LIGHT.copy(sheet = 0xFFE6EAEE.toInt())
    "oscuro" -> PaperColors.DARK
    "negro" -> PaperColors.BLACK
    else -> null
}
```

**Diseño**

- Cinco círculos de 24 px en la tira de la hoja. El elegido lleva un aro del color de acento.
- Cada círculo tiene `aria-label` con el nombre y `aria-pressed`.
- Mientras llega la imagen nueva se ve la anterior. No se muestra «Cargando».

---

## 3. Historial de charlas de cada hoja

Hoy hay una sola charla por hoja y «Nueva revisión» la borra. Ahora, al empezar una nueva, la anterior **se guarda** y se puede volver a ver, retomar o borrar.

**Dónde se guarda**

- En la tablet, junto al chat actual: `ai_chat/historial/<cuaderno>__<hoja>/<id>.json`.
- `id` = momento del primer mensaje, en milisegundos.
- Hasta 30 charlas por hoja; al pasar, se borra la más vieja.
- Se borran con la hoja. Sus imágenes adjuntas siguen la misma suerte.
- Una charla vacía no se guarda.

**Contrato**

```
GET  /api/history
     200 {"items":[{"id":"1731273240000","at":1731273240000,"count":6,"preview":"Tu idea de descomponer…"}]}
     de la hoja abierta, la más nueva primero. «preview» es texto plano, hasta 120 caracteres.

GET  /api/history/<id>
     200 {"id":"…","at":…,"messages":[ …igual que «messages» de /api/state… ]}
     404 si no existe en esta hoja.

POST /api/history/restore   {"id":"…"}
     Guarda la charla actual en el historial y deja <id> como actual. 200 {"ok":true}

POST /api/history/delete    {"id":"…"}
     200 {"ok":true}
```

- `id` se valida: solo dígitos, hasta 16. Cualquier otra cosa → `400`.
- `POST /api/review {"fresh":true}` ya no borra: guarda la actual y empieza otra.
- En modo examen no hay historial, igual que no hay chat.

**Muestra (Kotlin, en `AiChatStore`)**

```kotlin
/** Una charla guardada de una hoja. [id] es el momento de su primer mensaje. */
data class AiChatArchive(val id: String, val at: Long, val count: Int, val preview: String)

/** Guarda [messages] en el historial de la hoja; no hace nada si está vacía. */
@Synchronized fun archive(notebookId: String, pageId: String, messages: List<AiChatMessage>) {
    if (messages.isEmpty()) return
    val dir = historyDir(notebookId, pageId).apply { mkdirs() }
    writeAtomic(File(dir, "${messages.first().at}.json"), messages)
    dir.listFiles().orEmpty().sortedByDescending { it.name }.drop(MAX_ARCHIVES).forEach { it.delete() }
}
```

**Muestra (JS)**

```js
// Una fila del historial: fecha, cantidad y el comienzo del primer mensaje del tutor.
function historyRow(item, current) {
  var li = document.createElement('li');
  if (current) li.setAttribute('aria-current', 'true');
  var b = document.createElement('button');
  b.type = 'button';
  b.appendChild(span('h-when', when(item.at)));
  b.appendChild(span('h-count', item.count + (item.count === 1 ? ' mensaje' : ' mensajes')));
  b.appendChild(span('h-text', item.preview));
  b.addEventListener('click', function () { openArchive(item.id); });
  li.appendChild(b);
  return li;
}
```

**Diseño**

- Botón de reloj en la cabecera del chat. Abre un cajón que tapa **el chat**, no la hoja.
- Una fila por charla: fecha («Hoy 21:14», «Ayer 18:02», «8 nov 10:40»), cantidad de mensajes y una línea de vista previa.
- Al tocar una fila se ve esa charla en solo lectura, con dos botones abajo: «Retomar esta charla» y «Borrar».
- «Borrar» pide confirmación con el diálogo que ya existe.
- Sin charlas: «Todavía no hay charlas guardadas de esta hoja.»

---

## 4. Instalar como app desde Chrome

**El límite que hay que conocer**

- Chrome solo ofrece su instalación automática en un origen seguro: HTTPS o `localhost`.
- La web sale de la tablet por `http://192.168…`, así que ahí Chrome **no** dispara `beforeinstallprompt`.
- En la computadora igual se puede: menú ⋮ → «Transmitir, guardar y compartir» → «Instalar página como app». Eso funciona con HTTP.
- Por Tailscale con HTTPS, o por `localhost`, sí aparece el aviso automático.

**Qué se hace**

- Botón «Instalar» visible en la barra, no escondido en el menú.
  - Si Chrome dio el aviso (`S.install`), lo abre.
  - Si no, abre los pasos del navegador que se está usando (Chrome de escritorio, Chrome de Android, Safari), no la lista de los tres.
  - Ya instalada (`display-mode: standalone`), el botón no aparece.
  - Se puede cerrar con «Ahora no»; no vuelve a aparecer en ese navegador (queda en el menú).
- `manifest.webmanifest`: agregar `display_override: ["window-controls-overlay", "standalone"]`, `orientation: "any"`, `categories: ["education"]` y un atajo «Nueva revisión».
- `sw.js`: guarda la cáscara (`/`, `app.js`, `app.css`, íconos, KaTeX) para que la app instalada abra aunque la tablet esté apagada y muestre «Abrí Trazo en la tablet», en vez del error de Chrome.
  - `/api/…` nunca se guarda: siempre va a la tablet.
  - Los archivos de la cáscara: primero la red, y si falla, lo guardado.

**Muestra (JS, `sw.js`)**

```js
var SHELL = 'trazo-shell-v1';
var FILES = ['/', '/app.js', '/app.css', '/icon-192.png', '/vendor/katex/katex.min.js', '/vendor/katex/katex.min.css'];

self.addEventListener('fetch', function (e) {
  var url = new URL(e.request.url);
  // El estado, la hoja y el chat nunca salen de lo guardado: siempre de la tablet.
  if (e.request.method !== 'GET' || url.pathname.indexOf('/api/') === 0) return;
  e.respondWith(fetch(e.request).then(function (res) {
    var copy = res.clone();
    caches.open(SHELL).then(function (c) { c.put(e.request, copy); });
    return res;
  }).catch(function () { return caches.match(e.request, { ignoreSearch: true }); }));
});
```

**Diseño**

- Píldora del color de acento en la barra: «⤓ Instalar».
- El diálogo de pasos muestra uno solo, numerado, con el nombre exacto de cada opción del menú entre comillas angulares.

---

## Cómo se da por terminado

- `./gradlew -q -Ptrazo.coreOnly=true :core:test --offline` en verde.
- `node --check app/src/main/assets/web/app.js` y `node --check app/src/main/assets/web/sw.js` sin errores.
- Sin `innerHTML` nuevo, sin `onclick=` ni `style=` en `index.html`.
- Con la tablet apagada, la web muestra «Sin conexión con Trazo» y no se rompe.
- Lista de archivos cambiados al final del informe.
