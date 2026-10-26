# TRAZO — Handoff para Claude / implementación nativa Android

**Estado:** especificación y referencias; **NO contiene una APK, NO es una app Android final**.
**Prioridad absoluta:** una app nativa para Android con escritura matemática 100 % manuscrita.
**Fecha del traspaso:** 26 de octubre de 2026.

## Ruta rápida para Claude
1. Lee `CLAUDE.md` y `00_PEDIDOS_ORIGINALES_LITERAL.md` sin reinterpretar ni descartar las correcciones del usuario.
2. Lee `01_CONTRATO_PRODUCTO.md` y `02_EXPERIENCIA_Y_GESTOS.md`.
3. Sigue la propuesta de arquitectura de `03_ARQUITECTURA_ANDROID.md` y los criterios de `05_PRUEBAS_ACEPTACION.md`.
4. Implementa la **app Android nativa** en un proyecto Gradle real y compilable, crea una APK instalable y adjunta código, pruebas, evidencias y un video de la app real.
5. `native-kotlin-core/` es código Kotlin **de referencia comprobado en JVM**, no reemplaza la integración Android ni el reconocimiento de producción.

## Lo que se pide (resumen, no sustituye los textos literales)
- Experiencia de cuaderno de notas libre similar a Samsung Notes, con S Pen.
- El usuario enseña gestos/patrones (ej.: integral, raíz, barra de fracción).
- Al terminar el dibujo **sin levantar la punta**, mantener quieto el lápiz ~1 segundo dispara reconocimiento.
- Reemplazar el gesto por **tinta vectorial manuscrita que el usuario mismo haya dibujado o pueda personalizar**.
- La tinta resultante se edita, borra, escala y combina con nueva escritura. No se inserta LaTeX, tipografía matemática, texto ni imagen estática sobre la hoja.
- La app final debe ser **nativa Android**: Kotlin, Jetpack Compose / APIs Android, sin WebView ni PWA empaquetada.

## Orden de archivos
- `00_PEDIDOS_ORIGINALES_LITERAL.md` + `.jsonl`: palabras del usuario, transcripción literal.
- `01_CONTRATO_PRODUCTO.md`: producto, restricciones, prioridades y fuera de alcance.
- `02_EXPERIENCIA_Y_GESTOS.md`: flujo UX preciso y casos límite.
- `03_ARQUITECTURA_ANDROID.md`: módulos, Android Ink, persistencia, S Pen.
- `04_MODELO_Y_RECONOCIMIENTO.md`: modelo de datos y pseudocódigo del motor.
- `05_PRUEBAS_ACEPTACION.md`: pruebas automatizadas + dispositivo real.
- `06_PLAN_DE_ENTREGA.md`: hitos y entregables exigidos a Claude.
- `07_PROTOTIPOS_EVIDENCIAS.md`: qué respaldan los HTML y videos previos.
- `08_FUENTES_TECNICAS.md`: referencias oficiales verificadas.
- `native-kotlin-core/`: algoritmos Kotlin iniciales independientes de Android + pruebas.
- `referencias/`: HTML históricos, videos, capturas y reporte antiguo.

## Jerarquía para resolver conflictos
1. Correcciones más recientes del usuario > mensajes antiguos.
2. Texto **literal del usuario** > interpretación asistente > referencias HTML/video.
3. La demanda **Android nativo** descarta los HTML como implementación final.
4. La demanda **todo manuscrito** descarta LaTeX/MathLive/tipografía matemática del prototipo v1.

## Dispositivos previstos para validación física
Priorizar Samsung Galaxy S24 Ultra y Galaxy Tab S11 con S Pen si están disponibles. No declarar compatibilidad probada sin medición en los dispositivos.
