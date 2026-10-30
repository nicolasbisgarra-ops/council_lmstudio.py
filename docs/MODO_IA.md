# Modo IA

El Modo IA revisa una resolución escrita a mano. Trazo envía a un modelo de lenguaje con visión dos cosas: una captura de la hoja y el texto que reconoció su motor, con los renglones numerados. El modelo **corrige y comprueba**; no resuelve el ejercicio. Su respuesta vuelve como marcas sobre la hoja: un círculo, una flecha con una nota al margen y una burbuja que se puede tocar, en el renglón donde está cada error.

Las 50 decisiones salen del cuestionario respondido por el usuario. El número entre paréntesis es la pregunta.

## Principios

- **Internet solo en este modo** (2). El motor (`:core`) sigue sin red. Todo lo que habla con la red vive en el módulo `:ai` y en la pantalla del Modo IA. `SourcePolicyTest` lo comprueba.
- **Claves cifradas** (3). Las ingresa el usuario y se cifran con una clave AES/GCM del Android Keystore. Su archivo (`trazo_ai_keys.xml`) queda fuera de las copias de seguridad, porque en otro equipo no se podría descifrar.
- **Solo corregir** (9). El modelo no agrega contenido. El paso corregido se muestra solo si se pide con «Mostrar corrección» (27).
- **El motor de Trazo verifica dos veces** (15). Si una marca contradice la cuenta que hace el motor, la marca queda como «dudosa».
- **Contexto: solo la hoja actual** (48). No se envía el resto del cuaderno.

## Proveedores y modelos (1, 4, 5, 6, 40)

| Proveedor | API | Clave |
|---|---|---|
| Claude (Anthropic) | `POST /v1/messages`, imagen en base64 | `x-api-key` |
| OpenAI | `POST /chat/completions`, imagen como `data:` URI, `max_completion_tokens` | Bearer |
| Compatible con OpenAI (LM Studio, Ollama, OpenRouter) | Igual que OpenAI, con `max_tokens` | Opcional |
| Gemini | `POST /models/{m}:generateContent`, `inline_data` | `x-goog-api-key` |
| En la tableta | llama.cpp (GGUF con visión), MediaPipe LLM, Gemini Nano | — (etapa 3) |

- **«Obtener modelos»** pide la lista al servidor (`/v1/models`, `/models`). En Gemini quedan solo los modelos que generan contenido.
- **«Probar conexión»** envía una imagen chica con «1+2» escrito y mide la latencia. Si el modelo contesta 3, lee imágenes.
- **Perfiles:** cada perfil guarda proveedor, URL, modelo y clave. Uno queda «En uso», y el botón de estrellas permite cambiarlo rápido (6).
- **Opciones avanzadas** (24): temperatura (por omisión no se envía), tokens máximos, tiempo de espera e instrucciones editables por completo (47).
- **Servidor de la red local:** a una URL sin ruta se le agrega `/v1`. El manifiesto permite `http` para LM Studio y Ollama.

## Qué se envía (7, 8, 17, 33, 38, 39)

- **La captura:** fondo blanco, tinta negra y sin cuadrícula (39). El tamaño es configurable: baja 1024 px, media 1568 px y alta 2048 px (38).
- **El texto reconocido:** los renglones van numerados desde 1, y el modelo contesta por número de renglón (17). Lo que el motor no reconoció va como «?», y el modelo lo lee en la imagen (33).
- **El alcance:** la página entera, o solo lo elegido con el lazo, desde «Revisar con IA» del menú de selección (8, 13).
- **Qué lee:** también gráficos de funciones, figuras y tablas (45). Si el enunciado está en la hoja, se verifica que la resolución responda a lo pedido (28).
- **Temas:** álgebra y polinomios, álgebra lineal y cálculo (16).

## Respuesta del modelo (41)

El modelo debe responder con este JSON:

```json
{"correcto": false, "puntaje": 7,
 "marcas": [{"renglon": 3, "tipo": "cuenta", "breve": "…", "detalle": "…", "correccion": "…"}]}
```

`AiReviewParser` lo lee en tres intentos:

1. Busca el JSON: quita los bloques de código y toma el `{…}` más externo. Acepta claves en español o en inglés.
2. Si el JSON no es válido, le pide **una vez** al modelo que lo arregle (`AiReviewPrompt.repairText`).
3. Si tampoco sirve, rescata las marcas del texto libre con regex, con frases como «Renglón 4 (signo): …» o «Línea 6 – …».

## Marcas en la hoja (10, 11, 12, 18, 19, 22, 26, 29, 30, 31)

**Tipos y colores (29, 30).** Cada tipo tiene su color, y hay una leyenda:

| Tipo | Color |
|---|---|
| Cuenta o signo | rojo |
| Concepto o regla | naranja |
| Paso faltante | azul |
| Notación | gris; solo con rigor «estricto» (14) |

**Cómo se ven.**
- Van en una capa aparte que se oculta con el botón del ojo (11, 31).
- La letra simula escritura a mano, en el color propio de cada tipo (12).
- En la hoja se ve una explicación breve. «Ver más» abre la burbuja con el detalle (26).
- Las marcas aparecen a medida que llegan, con una barra de progreso (18).

**Si no hay errores** aparecen un tilde verde y el puntaje (19).

**Cómo se quitan y se discuten.**
- La goma borra una marca sola. No hay «borrar todas» (31).
- Una marca también se puede convertir en tinta (11).
- Si cambian los trazos de un renglón marcado, su marca se borra sola (22).
- Desde la burbuja se puede discutir una marca, y el modelo la vuelve a revisar (20).

## Historial, privacidad y fallos (21, 32, 35, 36, 37)

- **Historial:** cada página guarda sus revisiones en el archivo `.trazo` (21).
- **Exportar:** el PDF o PNG ofrece la opción «Incluir correcciones de la IA» (32).
- **Registro de envíos:** guarda qué se envió, cuándo y a qué modelo (36).
- **Aprendizaje (35):**
  - Trazo ofrece guardar como patrones los símbolos que leyó la IA.
  - Lleva un registro de los errores frecuentes.
- **Sin conexión (37):**
  - Si hay un modelo en la tableta, se usa ese.
  - Si no, el pedido queda en cola hasta que vuelva la conexión.
- **Costos:** no hay control de costos (23).
- **Símbolos dudosos:** se pide confirmar la lectura solo cuando un símbolo es ambiguo (34).

## Interfaz (13, 44, 50)

- **Botón «Modo IA».** Está en la barra, junto a deshacer y rehacer. Su ícono son estrellas azules con acentos celestes.
  - Muestra el modelo en uso y permite cambiarlo.
  - Tiene el botón «Revisar página» y un acceso a «Configurar».
- **Configuración.** Está en Preferencias → Modo IA, con los perfiles de modelos y la revisión (rigor y calidad de la captura).
- **Tono.** Las instrucciones por omisión piden un español formal y neutro (25).

## Etapas

| Etapa | Contenido | Estado |
|---|---|---|
| 1 | Módulo `:ai` (proveedores, «Obtener modelos», «Probar conexión», lector de respuestas con arreglo y regex), perfiles con clave cifrada, Preferencias → Modo IA, botón de estrellas en la barra, permiso de internet | Hecha |
| 2 | Captura de la hoja y numeración de renglones; revisión con marcas en streaming; capa de marcas, burbujas y leyenda; «Revisar con IA» en la selección; doble verificación con el motor; historial en `.trazo`; registro de envíos; cola sin conexión; exportar con correcciones | Pendiente |
| 3 | Modelos en la tableta: llama.cpp (GGUF con visión) y MediaPipe LLM / Gemini Nano. Se descargan desde una lista recomendada o desde Hugging Face, o se importa un archivo | Pendiente |
| Después | Ejercicios de práctica a partir de los errores frecuentes; modo examen que bloquea la IA y la calculadora (46) | Pendiente |

**Pruebas (42).** La parte de reconocimiento se prueba con agent manager, usando un agente «luna 6 med» con razonamiento medio.

**Configuración real.** Queda pendiente la captura y la explicación que mandará el usuario de cómo lo configura en su tableta (44).

## Código

- **`ai/` (`io.trazo.ai`).** Kotlin puro y probado en JVM (`./gradlew -Ptrazo.coreOnly=true :ai:test`).
  - `AiProfile` define el perfil, el proveedor, el rigor, la calidad de la captura y las instrucciones por omisión.
  - `AiClient` habla con cada proveedor.
  - `HttpTransport` y `UrlConnectionTransport` hacen la red sin dependencias nuevas.
  - `AiReview` define las marcas, el lector de respuestas y el armado del pedido.
- **`app/.../data/AiStore.kt`.** Guarda los perfiles y las claves cifradas con el Keystore.
- **`app/.../ui/AiSettings.kt`.** Contiene la página de Preferencias y el menú del botón de estrellas.
