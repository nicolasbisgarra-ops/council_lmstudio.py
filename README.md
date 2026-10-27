# Trazo — cuaderno matemático manuscrito (Android nativo)

App **nativa de Android** (Kotlin, Jetpack Compose + una `View` de tinta propia que procesa `MotionEvent` del S Pen). Es un cuaderno de notas libre, al estilo Samsung Notes, con **patrones enseñables**: dibujás un gesto (integral, raíz, fracción…), **mantenés la punta apoyada y quieta ~0,5 s** y el gesto se reemplaza por **la tinta manuscrita que vos definiste**. Todo lo que queda en la hoja son trazos vectoriales editables: sin LaTeX, sin fuentes matemáticas, sin imágenes, sin WebView.

> Especificación de origen: [`docs/handoff/`](docs/handoff/README_PRIMERO.md). Los pedidos literales están en [`docs/handoff/00_PEDIDOS_ORIGINALES_LITERAL.md`](docs/handoff/00_PEDIDOS_ORIGINALES_LITERAL.md).

## Qué hace

- **Cuadernos** con páginas (rayado, cuadriculado, punteado, liso). La lista muestra una miniatura de la primera página de cada cuaderno, se puede buscar y ordenar (recientes o nombre).
- **Barra de herramientas de íconos**: volver, páginas (anterior, siguiente, nueva), deshacer y rehacer, pluma, resaltador, goma, lazo, formas, mover la hoja, colores, gestos sí/no, insertar patrón, zoom (−, %, +; tocar el % ajusta a la página) y papel. Cada herramienta abre su panel (color, grosor, estilo…) al tocarla de nuevo o con una pulsación larga.
- **Pluma** con presión y muestras históricas, en cuatro estilos (pluma, bolígrafo, caligrafía, lápiz); **resaltador** translúcido que queda debajo de la tinta; **goma** de área (recorta tramos) o de trazo; **lazo** para mover, escalar, girar, duplicar, cambiar el color o borrar; **formas** (línea, flecha, rectángulo, elipse, triángulo, ejes) que se dibujan arrastrando: la línea se alinea cada 45° y el rectángulo o la elipse se vuelven cuadrado o círculo si casi lo son.
- **Colores**: cinco colores rápidos en la barra (se cambian con una pulsación larga o desde Preferencias) y un selector con cuadro de saturación y brillo, barra de tono y código hexadecimal.
- **S Pen**: mientras se mantiene apretado el **botón lateral**, el lápiz borra (o selecciona con lazo, según Preferencias); el extremo goma también borra. Al soltar el botón se vuelve a la herramienta anterior.
- **Gesto + pausa de 0,5 s (ajustable de 0,1 a 1 s) con contacto**: reconoce el gesto (uno o varios trazos recientes y cercanos) y lo reemplaza en **un solo paso deshacible** por la tinta de salida. Si se levanta antes, no convierte. Si no hay coincidencia clara, conserva la escritura. Si dos patrones se parecen, no convierte y ofrece elegir (con vistas previas en tinta).
- **Biblioteca de partida con 104 patrones**: integral, raíz y fracción; tus 11 patrones (a, b, x, y, 2, 3, Más, Igual, f( ), f(x), g( ) con sus ejemplos; y **90 signos dibujados como tinta vectorial**: números del 0 al 10, operadores, paréntesis, cálculo, letras griegas, conjuntos y lógica, flechas y geometría. Se ven por secciones, se buscan sin importar tildes y se activan o desactivan de a uno o por sección.
- **Editor de patrones**: a la izquierda el *gesto a reconocer* (uno o más ejemplos), a la derecha *cómo quiero que quede*; sección, tamaño de salida, escala, confianza mínima, pausa propia, barra elástica y una zona de **prueba**.
- **Aprende de tus correcciones**: si una pausa no reconoce el gesto, aparece "Enseñar"; elegís qué trazos eran el gesto y qué patrón querías, se reemplaza y el gesto queda guardado como ejemplo, así la próxima vez se reconoce solo. También se puede crear un patrón nuevo a partir de ese gesto.
- **Páginas**: agregar, duplicar, mover, borrar el contenido (deshacible) y eliminar. "Seleccionar todo" y "Ajustar a la página".
- **Apariencia**: tema del sistema, claro, oscuro u OLED; ocho acentos de color o uno propio; hoja clara, oscura o según el tema (la tinta negra se aclara sobre la hoja oscura sin cambiar el archivo).
- **Persistencia local offline** (JSON versionado, escritura atómica), autoguardado, **exportar/importar** `.trazo` (ZIP editable con cuaderno + patrones usados) y la **biblioteca de patrones** (JSON). Sin permisos de red.
- **Compartir** el cuaderno como **PDF vectorial** o una página como **PNG** (solo exportación; el formato fuente sigue siendo de trazos).
- En **tablet** los patrones quedan en un panel lateral; en teléfono, en una hoja inferior. Vibración breve al convertir, rechazo de palma también por tamaño de contacto, **toque con dos dedos = deshacer**.

## Estructura

```
core/   Motor en Kotlin puro (sin Android): modelo de tinta, pausa, reconocimiento,
        historial, goma, lazo, persistencia. Probado en JVM.
app/    App Android: InkCanvasView (MotionEvent/S Pen), Compose UI, almacenamiento.
        src/test       → pruebas JVM de política de fuentes (sin WebView/LaTeX/texto en la hoja)
        src/androidTest→ pruebas instrumentadas + demo automatizada del ejercicio completo
docs/   ARCHITECTURE.md · DECISIONES.md · test-results.md · handoff/ (especificación original)
scripts/ci-emulator.sh  pruebas y grabación en emulador (CI)
scripts/ci-publish.sh   publica la APK y las evidencias en la rama (CI)
.github/workflows/android.yml  compila APK, corre pruebas y graba la demo
```

## Instalar la APK de prueba

[`output/Trazo-debug.apk`](output/Trazo-debug.apk) (Android 8.0 o superior; suma SHA-256 en `output/SHA256SUMS-apk.txt`). Copiala al teléfono o tablet, permití "instalar apps desconocidas" a la app con que la abrís y, si Play Protect avisa, elegí "Instalar de todas formas". Está firmada con una clave debug fija del repositorio: cada versión nueva se instala encima de la anterior sin desinstalar ni perder cuadernos.

## Compilar e instalar

Requisitos: JDK 17, Android SDK (API 35). Desde Android Studio o línea de comandos:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pruebas instrumentadas (emulador o dispositivo): `./gradlew :app:connectedDebugAndroidTest`.

Solo el motor, sin Android SDK: `./gradlew -Ptrazo.coreOnly=true :core:test`.

La APK también la genera GitHub Actions (artefacto `trazo-build`); el job `emulator` corre las pruebas instrumentadas y graba la demo (artefacto `trazo-emulator`). Si el mensaje del commit lleva `[publicar]`, CI sube la APK a la rama apenas compila y, si el emulador pasa, también la demo, las capturas y los resultados (`scripts/ci-publish.sh`).

## Uso rápido

1. **Nuevo cuaderno** → elegí el papel y escribí con el S Pen. El dedo desplaza / hace zoom (se puede habilitar escribir con el dedo en Preferencias). Con el botón lateral apretado, el lápiz borra.
2. Dibujá una **integral** sin levantar el lápiz y quedate quieto: aparece un anillo; al completarse (0,5 s por defecto) el gesto se reemplaza por la integral manuscrita. *Deshacer* lo revierte de una vez.
3. Escribí límites e integrando alrededor: es tinta normal, se borra y edita.
4. **Insertar patrón** (ícono de patrones en la barra) agrega cualquier signo de la biblioteca al centro de la vista, sin dibujar el gesto.
5. **Biblioteca de patrones → Enseñar patrón**: dibujá el gesto y, aparte, la salida. Probalo abajo y guardá.

## Estado y evidencias

Ver [`docs/test-results.md`](docs/test-results.md): separa pruebas JVM, instrumentadas en emulador y la validación física con S Pen (pendiente: no hubo hardware disponible).
