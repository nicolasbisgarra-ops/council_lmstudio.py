# Plan de trabajo para Claude (prioridad por valor)

## Fase 1 — Esqueleto Android nativo utilizable
- Proyecto Kotlin/Android Gradle compilable, Compose UI y superficie nativa de tinta.
- Crear/abrir nota y escribir en tiempo real con `MotionEvent` de stylus; guardar trazos.
- Funciones lápiz, borrador, undo/redo, persistencia de notas.
- APK debug funcional y primera captura de prueba.

## Fase 2 — Gesto de 1 s y sustitución 100 % manuscrita
- Detector de contacto/quietud robusto; rechazo de falsos positivos.
- Biblioteca de 3 patrones de muestra construidos **con vectores de trazos**, no tipografías.
- Motor de reconocimiento y salida como trazos; undo atómico; edición después del reemplazo.
- Pruebas automáticas de dwell y matcher.

## Fase 3 — Enseñar patrones propios y edición
- Editor izquierda/derecha: gesto versus salida; borrar/cambiar/capturar múltiples strokes.
- Ajustar tamaños/tiempo/confianza; guardar en base local.
- Insertar vectorialmente, seleccionar, mover, escalar y borrar parcialmente.
- Pruebas de persistencia y patrones de usuario.

## Fase 4 — Calidad y evidencias
- Optimizar rendering S Pen y palma; revisar accesibilidad y tablet/teléfono.
- `assembleDebug`, unit tests, instrumentadas; prueba física S Pen cuando posible.
- Resolver en pantalla una integral manuscrita completa grabada en Android.
- Entregar código completo, `app-debug.apk`, guías de instalación, logs, test report y video.

## Paquete final esperado de Claude
```
Trazo-Android/
  settings.gradle.kts
  gradlew / gradlew.bat / gradle/wrapper/*
  app/src/main/...          # app Kotlin nativa, no WebView
  app/src/test/...          # tests JVM
  app/src/androidTest/...   # test instrumentados
  README.md
  docs/ARCHITECTURE.md
  docs/DECISIONES.md
  docs/test-results.md
  output/Trazo-debug.apk
  output/demo-real-android.mp4
```
No exigir que Claude tenga un S Pen físico si no existe; exigir transparencia y pruebas automatizadas alternativas + plan exacto de validación física pendiente.
