# Prototipos y material histórico — qué hacer con ellos

## Prototipo web v1 (DESCARTADO COMO MODELO DE MATH RENDERING)
- `referencias/math_notes_prototype_v1.html`: demo inicial; utiliza expresiones tipográficas/LaTeX en la interfaz y por tanto NO cumple la corrección P03.
- `referencias/Trazo_demo_integral_y_pruebas_v1.mp4`: demo histórica HTML con lápiz simulado, no evidencia de Android nativo.
- `referencias/trazo_tests_v1.json`: 12 checks web anteriores; su estado proviene de la prueba histórica, no de nueva validación Android.

## Prototipo web v2 (REFERENCIA DE INTERACCIÓN Y TINTA)
- `referencias/trazo_manuscrito_v2.html`: gesto y reemplazo son 2 conjuntos de trazos vectoriales dentro de canvas; sirve como referencia conceptual para editor y hold.
- `referencias/Trazo_v2_todo_manuscrito.mp4`: demo HTML con un puntero simulado, no Android ni S Pen real.
- `referencias/trazo_manuscrito_v2_prueba.png` y `referencias/trazo_manuscrito_v2_video_final.png`: capturas.

## Principio para migración
1. Estudiar el flujo y el modelo de datos del HTML v2.
2. REIMPLEMENTAR nativamente en Kotlin/Android, no envolver HTML en una app.
3. Adaptar el procesamiento a herramientas reales de Android, presión, palm rejection, y precisión de Samsung S Pen.
4. Mejorar robustez: no copiar necesariamente el matcher v2 ni sus posibles limitaciones de selección/agrupación.
5. Documentar qué funcionalidad está portada y qué faltó.

## Advertencia sobre confianza de resultados
Los videos son demostraciones de una aplicación web mediante automatización de navegador. Las capturas muestran apariencias, no sustituyen tests automatizados de una APK nativa. No adjuntar test pass inventado para la versión Android.
