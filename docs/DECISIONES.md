# Decisiones y supuestos

1. **Vista de tinta propia en lugar de AndroidX Ink.** El handoff permite "Android Ink API o una vista Android de baja latencia que capture MotionEvent". Se eligió una `View` propia porque el modelo de trazos (presión, tiempos, grupos, edición punto a punto, goma parcial) ya está en `:core` y así no dependemos de APIs que no pudimos verificar en este entorno. Migrar el render húmedo a `androidx.ink` / front-buffer es una mejora de latencia posible sin tocar el modelo.
2. **Núcleo en Kotlin puro (`:core`).** Basado en el core de referencia del handoff (sus 16 casos se portaron a JUnit), ampliado: varios ejemplos por patrón, multi-trazo con orden libre, término de dirección (evita que una línea recta pase por integral), agrupación por tiempo/espacio, recorte del temblor de la pausa, comando atómico de reemplazo.
3. **Pausa medida con contacto.** Se usa el reloj de los eventos (`eventTime`) y `uptimeMillis` en ticks por frame (`postOnAnimation`). Valores iniciales: 1000 ms, temblor 6 dp, tinta mínima 12 dp. Son valores de diseño, **no medidos con S Pen real**.
4. **Apoyar sin dibujar ≥ pausa no deja tinta** (T04). Un toque corto sí deja un punto (puntos de la "i", decimales).
5. **Ambigüedad:** nunca convierte; la UI ofrece elegir entre los dos patrones (vistas previas dibujadas con tinta) o conservar la escritura.
6. **Grupo más grande primero.** Si los últimos trazos juntos coinciden con un patrón de varios trazos, gana sobre una coincidencia de un solo trazo (p. ej. la barra de una raíz no se toma como fracción).
7. **Patrones de ejemplo** generados con curvas (Catmull-Rom) y presión variable: son trazos, no fuentes. La raíz trae 3 ejemplos de gesto (barra corta, barra larga y en dos trazos) porque la longitud de la barra cambia mucho la forma normalizada.
8. **Editar un patrón no altera notas existentes**: la salida se inserta como copia profunda.
9. **El dedo navega por defecto**; escribir con el dedo es opcional (Ajustes). La palma se ignora mientras el lápiz está apoyado y 500 ms después de usarlo o de detectarlo en hover; `ACTION_CANCEL` y `FLAG_CANCELED` (API 33+) descartan el trazo.
10. **Página de tamaño lógico fijo** 1000 × 1414 unidades (proporción A4); varias páginas por cuaderno. Las coordenadas no dependen de densidad ni zoom.
11. **Texto en la interfaz** (botones, nombres de patrones, menús) es texto convencional, permitido por el handoff. En la hoja no hay texto: `SourcePolicyTest` lo verifica sobre el código fuente.
12. **Inclinación (tilt) no se guarda** en esta versión; el modelo admite agregarla sin romper archivos (`ignoreUnknownKeys`).
13. **Entorno de desarrollo sin Android SDK.** El contenedor donde se escribió el código no podía descargar el SDK ni Google Maven (host bloqueado por la política de red). El motor se compiló y probó localmente; la app se compila, prueba y graba en GitHub Actions (`.github/workflows/android.yml`). Ver `test-results.md`.
14. **APK de release** firmada con la clave debug para facilitar pruebas; cambiar la firma antes de publicar.
15. **Demo automatizada:** `HandScript` (en `testFixtures`, nunca en la APK) genera los recorridos del lápiz sintético para escribir el ejercicio; la app los recibe como eventos de lápiz y los guarda como tinta normal. La demo muestra un cartel "DEMO AUTOMATIZADA" en la interfaz (fuera de la hoja).
