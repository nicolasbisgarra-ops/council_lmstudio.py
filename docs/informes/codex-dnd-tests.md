# DND-TESTS: prueba instrumentada de reordenamiento

Rama: `codex/dnd-tests`. Base real de OLA 2: `915555f`.
VM: solamente `ib4`, directorio exclusivo `~/trazo-run-t`.
SHA del código compilado: `14c17ac2da34644453c4107ce07712e01ea21e25`.
Este informe se agrega en un commit posterior de documentación.

## Archivos y alcance

- `app/src/androidTest/kotlin/io/trazo/app/ReorderableInstrumentedTest.kt`: nuevo host Compose y seis pruebas JUnit 4.
- `docs/informes/codex-dnd-tests.md`: este informe.

Se leyeron los informes de core y UI, las implementaciones reales de
`Reorder.kt` y `Reorderable.kt`, `ReorderTest.kt`, `app/build.gradle.kts`, el
catálogo de versiones y los tests instrumentados existentes. No se cambiaron
dependencias, versiones, código de producción ni archivos restringidos.

## Avisos para el orquestador

**No está declarada `compose-ui-test-junit4` para androidTest.** Según la
restricción de la tarea, no se agregó. Por eso no se usan `createComposeRule`,
`performTouchInput` ni el reloj virtual de Compose. El reemplazo usa el runner
existente `AndroidJUnit4`, `ActivityScenario<MainActivity>` y eventos
`MotionEvent` de dedo despachados a la ventana en el hilo principal, siguiendo
el estilo de los tests existentes. `advanceEventTime` espera tiempo real con
`SystemClock.sleep`, sin bloquear el hilo principal. El long-press usa
`LocalViewConfiguration.current.longPressTimeoutMillis + 100 ms`.

**Cancelar después de cruzar una fila no restaura el orden original en la API
real.** `onMove` modifica la lista en cada cruce; `onDragCancel` llama a `stop`,
que libera el arrastre sin deshacer callbacks. El contrato entregado también
exige esos movimientos inmediatos. Por eso se verifica que cancelar antes de
cruzar no reordene, y una prueba adicional deja explícito que cancelar después
de cruzar conserva los movimientos ya aplicados. Si se pretende rollback de
todo el gesto, hay que acordar y ampliar el contrato; no se modificó el
componente para imponer esa semántica.

## Host y cobertura

El host reemplaza el contenido de `MainActivity` durante cada prueba y lo cierra
al terminar, sin guardar preferencias ni modificar cuadernos. Usa una
`LazyColumn` de 20 filas (2.000 para auto-scroll), lista en `remember`, callback
con `moveItem`, y la misma key entera en `items`, `ReorderableItem` y
`dragHandle`. Cada fila tiene un checkbox con `remember` propio, separado del
modelo de datos, para detectar pérdidas de estado de composición.

La lista mide 384dp de alto y las filas/handles 48dp; se respetan los insets del
sistema. Se leen coordenadas reales en la ventana y se convierten al origen del
decor al despachar eventos. Los tags se agregan solo en el host. Las aserciones
leen estado y layout en el hilo principal; las esperas son acotadas y permiten
que corran los frames reales, sin esperar inactividad global durante el loop
de arrastre. Se consultan `LayoutCoordinates` al leer para incluir la traslación
actual de `graphicsLayer`.

Las seis pruebas cubren:

1. Long-press de la fila 5, 24 movimientos hasta la posición 2 y UP: orden
   completo `1,5,2,3,4,6…20`, tres callbacks de cruce y ninguna llamada extra al
   soltar. Verifica keys visibles y posiciones tras terminar la animación.
2. Marcar el checkbox de la fila 5 con DOWN/UP, moverla y verificar que sigue
   marcada, las vecinas siguen desmarcadas y las otras 19 filas mantienen su
   orden relativo.
3. Con 2.000 filas, llevar el dedo al borde inferior y dejarlo quieto, sin más
   MOVE. El primer índice visible debe aumentar respecto del registrado al
   llegar al borde, mientras la key arrastrada sigue siendo 5. Al soltar se
   verifica que no se pierdan/dupliquen filas ni cambie el orden relativo del
   resto. No es una medición de FPS.
4. CANCEL antes de completar el long-press: sin arrastre pendiente ni callbacks,
   incluso después de esperar el timeout.
5. CANCEL durante un arrastre activo que se movió menos de una fila: libera la
   key y conserva el orden completo sin callbacks.
6. CANCEL después de los tres cruces: termina el arrastre, conserva el orden ya
   aplicado y no emite otro callback.

No se agregaron pruebas JVM: los 29 casos existentes de `ReorderTest.kt` ya
cubren el ejemplo, ambos sentidos, acotación, índices inválidos, inmutabilidad,
keys/selección, orden relativo, listas grandes y etiquetas. Esta área usó ib4
solo para compilar; no volvió a ejecutar core.

## Validación

Se sincronizó el worktree completo con tar/SSH y las exclusiones indicadas a
`ib4:~/trazo-run-t`. Se esperó a que terminara la transferencia antes de lanzar
Gradle. Después de la autorrevisión se actualizó solamente el archivo nuevo
por tar/SSH sobre ese mismo directorio.

Comando de compilación, ejecutado dos veces en ib4:

```sh
cd ~/trazo-run-t
echo sdk.dir=/data/home/android-sdk > local.properties
chmod +x gradlew
./gradlew --no-daemon :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

| Compilación | Resultado | Tiempo Gradle | Tareas |
|---|---|---:|---|
| Primera versión completa | BUILD SUCCESSFUL, código 0 | 27 s | 51: 34 ejecutadas, 17 desde caché |
| Código final, tras ajustar el despacho directo en el hilo principal | BUILD SUCCESSFUL, código 0 | 7 s | 51: 1 ejecutada, 50 actualizadas |

Se compilaron la app y todo el source set `debugAndroidTest`, incluidas las
**6 pruebas nuevas**. Se verificó con `javap` que la clase generada contiene
los seis métodos de prueba. **Pruebas instrumentadas ejecutadas: 0**; no hay
resultados de aprobación/falla de ejecución. **Pruebas JVM ejecutadas en esta
entrega: 0**, según la asignación de ib4 solo para compilar.

La primera compilación informó las advertencias preexistentes de
`LocalLifecycleOwner` en `EditorScreen.kt` y de símbolos nativos sin quitar en
`libandroidx.graphics.path.so`. El archivo nuevo compiló sin advertencias; la
compilación final no informó advertencias nuevas. No hubo fallas de compilación.

Artefactos en ib4:

- App: `~/trazo-run-t/app/build/outputs/apk/debug/app-debug.apk`.
- Clase: `~/trazo-run-t/app/build/tmp/kotlin-classes/debugAndroidTest/io/trazo/app/ReorderableInstrumentedTest.class`.
- Logs: `/tmp/trazo-dnd-tests-build.log` y `/tmp/trazo-dnd-tests-build-final.log`.

El SHA-256 del archivo Kotlin final coincide localmente y en ib4:
`e7811b0de35078328db0f6ce4126c5870c52ff02e907cb658e2fd321193295d7`.

Se autorrevisó el diff completo contra las firmas reales y el comportamiento de
`start`, `drag`, `stop`, `dragLoop` y `dragHandle`. Se revisaron las coordenadas,
los cruces estrictos de centros, la conservación de keys y el cierre del gesto
incluso si falla una aserción. `git diff --cached --check` pasó. No hay
`break`/`continue` en lambdas ni cambios de producción.

## Ejecución pendiente en dispositivo o emulador

No se ejecutó ninguna de las seis pruebas instrumentadas: ib4 no tiene
emulador ni KVM. Compilar no valida gestos, animaciones, selección ni
auto-scroll en ejecución. Usar un dispositivo o emulador en orientación
vertical, con espacio para al menos seis filas de 48dp dentro del área segura.

Con un dispositivo conectado, ejecutar solamente esta clase con el filtro del
runner AndroidJUnitRunner (equivalente al filtro `--tests` del enunciado):

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.trazo.app.ReorderableInstrumentedTest
```

No se hizo push, PR ni cambios en otras ramas.
