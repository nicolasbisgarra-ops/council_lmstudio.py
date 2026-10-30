# DND-REORDERABLE — componente reutilizable

- Rama: `codex/dnd-reorderable`, creada desde `e07a4f8`.
- SHA de implementación: `7d4f5af98b0752696f72192071388198f31628e3`.
- VM utilizada: solamente `ib4`.
- Este informe se agrega en un commit posterior al código. Sin push ni PR.

## Archivos

- `app/src/main/kotlin/io/trazo/app/ui/Reorderable.kt`: archivo nuevo, con las cuatro APIs del contrato UI y un ejemplo mínimo en KDoc, sin Preview.
- `docs/informes/codex-dnd-reorderable.md`: este informe.

## Implementación

`rememberReorderableState` conserva la key arrastrada y usa el callback actualizado sin reiniciar el gesto cuando cambia la lista. `dragHandle` arranca después de mantener apretado, consume los movimientos y termina o cancela el arrastre al cerrar el gesto.

La fila arrastrada usa `graphicsLayer` para compensar su nuevo offset de layout y el scroll, con `zIndex` alto y elevación de 4dp. Las demás usan `animateItem()`. La fila se fija con `LocalPinnableContainer` durante el arrastre para que LazyColumn no descarte su composición; la fijación se libera al terminar.

Los destinos salen de `layoutInfo.visibleItemsInfo`, usando offsets y tamaños reales. Cada cruce de centro emite un `onMove` entre índices vecinos de LazyColumn, incluso cuando un evento cruza varias filas. Mientras el layout todavía muestra el orden anterior, se proyecta el movimiento pendiente sobre esos mismos offsets para evitar repetir o perder cruces si llega otro evento antes del próximo frame. Soltar no agrega otro `onMove`.

Cada `ReorderableItem` registra su key solamente mientras está compuesto. Una cabecera o cualquier ítem sin ese envoltorio queda fuera de los destinos y separa grupos reordenables. No se presupone que la primera fila movible tenga índice cero. Si un cruce afecta al primer ítem visible, `requestScrollToItem` conserva índice y offset del viewport y evita el salto del anclaje por key.

El auto-scroll corre con el reloj de frames en una coroutine ligada al arrastre. La zona de borde es el menor entre 72dp y el 20% del viewport, y la velocidad aumenta con la cercanía hasta 900dp/s. Se vuelven a evaluar los cruces al hacer scroll; una fila fija vecina frena el avance. Los offsets también contemplan `reverseLayout`.

La búsqueda recorre solo ítems visibles y usa búsquedas binarias por índice para los cruces pendientes. El registro contiene filas compuestas, incluidas las prefetched o fijadas. No se copia ni recorre la lista completa por frame. No se agregaron dependencias ni acceso a red en el código.

La selección, el contenido, el contador y la persistencia siguen a cargo de quien integra: debe usar la misma key estable en LazyColumn, `ReorderableItem` y `dragHandle`, actualizar su lista en cada callback y descontar los ítems previos. No se integró el componente en pantallas ni se modificó core.

## Autorrevisión de APIs

Se revisaron el POM real de BOM `2024.12.01`, el AAR con `javap` y las fuentes de Foundation `1.7.6` en ib4. Se verificaron `LazyItemScope.animateItem`, `LazyListState.requestScrollToItem`, el orden de los índices visibles, los offsets de `reverseLayout` y el tratamiento de cabeceras fijas.

La documentación oficial también confirma que [`animateItem`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyItemScope#animateItem(androidx.compose.ui.Modifier,androidx.compose.animation.core.FiniteAnimationSpec,androidx.compose.animation.core.FiniteAnimationSpec,androidx.compose.animation.core.FiniteAnimationSpec)) existe desde Foundation 1.7.0 y que [`PinnableContainer`](https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/PinnableContainer) permite mantener compuesto un ítem fuera del viewport.

Se leyó el diff completo y pasó `git diff --check`. No hay `break` ni `continue` dentro de lambdas inline. No se tocaron versiones, pantallas ni archivos prohibidos.

## Compilación y pruebas

La sincronización completa se hizo por tar y SSH con las exclusiones indicadas. Las revisiones posteriores copiaron solamente `Reorderable.kt` sobre ese mismo proyecto completo.

| Comando en ib4 | Resultado | Tiempo |
| --- | --- | --- |
| `./gradlew --no-daemon :app:assembleDebug` — primera versión completa | BUILD SUCCESSFUL; 40 tareas, 32 ejecutadas y 8 desde caché | 43 s |
| `./gradlew -q -Ptrazo.coreOnly=true :core:test` | OK; 37 XML, **7.588 pruebas**, **0 fallas**, **0 errores**, **0 omitidas** | 14 s |
| `./gradlew --no-daemon :app:assembleDebug` — código final | BUILD SUCCESSFUL; 40 tareas, 4 ejecutadas, 1 desde caché y 35 actualizadas | 14 s de Gradle / 16 s totales |

Hubo un intento inicial fallido de 7 s: se lanzó antes de terminar la sincronización y todavía faltaba `settings.gradle.kts`, por lo que Gradle no encontraba AGP. Se corrigió esperando la copia completa; no se cambiaron repositorios ni versiones.

La primera compilación completa informó advertencias existentes en `HttpTransport.kt`, `EditorScreen.kt` y el empaquetado de `libandroidx.graphics.path.so`. El archivo nuevo compiló sin advertencias.

APK generada en ib4: `~/trazo-run/app/build/outputs/apk/debug/app-debug.apk`. Logs: `/tmp/trazo-dnd-assemble.log`, `/tmp/trazo-dnd-assemble-final.log` y `/tmp/trazo-dnd-core.log`.

SHA-256 del archivo final, verificado tanto localmente como en ib4: `a14c3e0d713e60e92595843c78a58ec351fd82e946e65da9cfa1e31777339d79`.

## Límites de la validación y prueba al integrar

No se ejecutaron pruebas instrumentadas ni gestos reales: ib4 no tiene emulador ni KVM. Las pruebas JVM corresponden al core existente; no ejercitan este componente Compose. No se midieron FPS con miles de filas.

Al integrar, verificar en un dispositivo: mover 5 a 2 en `1,2,3,4,5,6` debe dar `1,5,2,3,4,6`; repetir con cabecera, alturas distintas, inversión de dirección antes del próximo frame y cancelación. Mantener el dedo quieto en ambos bordes para comprobar auto-scroll, y revisar selección y estado por key con una lista de miles de filas. Cualquier persistencia se verifica en la pantalla que implemente `onMove`.
