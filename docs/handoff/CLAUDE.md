# INSTRUCCIONES PARA CLAUDE — TRAZO

Actúa como ingeniero principal Android y responsable de calidad. **Implementa** el proyecto solicitado; no te limites a explicar cómo se haría.

## Lo que NO podés cambiar
- Android **nativo**, con Kotlin y vistas/Compose y procesamiento de S Pen con APIs Android.
- **No** WebView, Capacitor, Cordova, PWA empaquetada, sitio web, React Native ni app Flutter como producto final.
- Cuaderno matemático con **toda la escritura y notación sobre el papel compuesta por trazos manuscritos**.
- **No** MathLive, LaTeX, KaTeX, MathJax, fuentes manuscritas simuladas, fuentes matemáticas, SVG de texto ni imágenes raster como sustituto en la hoja.
- Se puede usar texto convencional en **botones, ajustes y menús**. El veto del usuario es a la escritura/notación matemática sobre la página.
- Patrón de activación y patrón de salida son dos **dibujos editables y distintos** enseñados por el usuario.
- Activación primaria: el lápiz **sigue tocando la pantalla** y queda quieto 1 segundo; cancelación si se levanta antes de tiempo.
- No realizar sustituciones si la confianza es baja; preservar la escritura original.
- Offline-first y documentos editables con persistencia local.

## Metodología exigida
1. Lee primero `00_PEDIDOS_ORIGINALES_LITERAL.md` y utiliza el JSONL si querés conservar literalmente los espacios.
2. Revisa `referencias/trazo_manuscrito_v2.html` por comportamiento, **sin portarlo dentro de WebView**.
3. Diseña la app con núcleo de datos/trazos Kotlin desacoplado de UI y entrada Android.
4. Genera un proyecto Android Studio **completo**, con Gradle wrapper, fuente, pruebas, manifest y recursos.
5. Compila (`assembleDebug`), corrige errores y ejecuta pruebas automáticas; reporta comandos y resultados con evidencia.
6. Implementa test instrumentado/demostración en dispositivo físico con S Pen cuando esté disponible. Si no está disponible, indicá la ausencia claramente y separá resultados de emulador / MotionEvent sintetizado de resultados reales.
7. Entrega APK, repositorio/proyecto fuente, reporte de tests, video donde un lápiz escriba y resuelva un ejercicio completo **en la app Android nativa**. No simules test físico sin aclararlo.
8. No declares “terminado” si falta el núcleo de sustitución de tinta o si se usa texto tipográfico para expresar matemáticas en la hoja.

## No convertir el handoff en otra ronda de planificación
Tomá las decisiones menores de forma razonable. Prioridad de implementación MVP en `06_PLAN_DE_ENTREGA.md`; documentá supuestos en `DECISIONES.md`.

## Definición de terminado
Una persona puede instalar la APK en Android compatible, escribir cualquier trazo con S Pen, enseñar un gesto y su salida manuscrita, activarlo sosteniendo la punta durante 1 s, ver el reemplazo vectorial, modificarlo con borrador/escritura, guardar y reabrir su cuaderno sin perder capas ni trazos. Además existen pruebas y evidencia reproducibles.
