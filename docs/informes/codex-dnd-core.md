# DND-CORE: lógica pura y pruebas JVM

Rama: `codex/dnd-core`. Base: `e07a4f8283b6388501d31e6be512a9a364544ba6`.
SHA del código y las pruebas validados: `f70d4d9621f63e587aa6908b8130f9790d977a10`.
El informe se agrega en un commit posterior de documentación sobre ese SHA.

Archivos de la entrega:

- `core/src/main/kotlin/io/trazo/core/Reorder.kt` (nuevo).
- `core/src/test/kotlin/io/trazo/core/ReorderTest.kt` (nuevo).
- `docs/informes/codex-dnd-core.md` (nuevo).

`moveItem` respeta el contrato: mueve por índice y deja el elemento en el índice
final `to`, acotado a `[0, size - 1]`. Un origen inválido o un destino efectivo
igual al origen conserva la lista. Primero valida el origen, así que una lista
vacía nunca intenta acotar con un intervalo inválido. Para mover, copia el
contenedor y conserva las instancias de los elementos, su contenido, selección
y claves. El costo de un movimiento efectivo es O(n) en tiempo y memoria.

`positionLabel` muestra posiciones desde uno, acota el índice antes de sumarle
uno y devuelve `0 / 0` para total cero o negativo. También cubre extremos de
`Int` sin desbordes.

Se agregaron **29 tests JUnit 4**, siguiendo el framework y estilo de core:
ejemplo del enunciado, ambos sentidos, vecinos, principio/final, lista vacía,
un elemento, índices iguales o inválidos, destinos acotados, duplicados, nulos,
entrada mutable, sublistas y referencias de filas con selección. La propiedad
de permutación y orden relativo recorre **490 combinaciones** sobre listas de
6 a 10 elementos; también se verifica que volver al origen restaure el orden.
La prueba de **100.000 elementos** verifica tamaño y orden sin imponer una cota
de tiempo. Hay cobertura del contador y de persistencia de preferencias.

## Revisión de ToolPreferences

Se leyeron `core/.../settings/ToolPreferences.kt`, sus tests y los consumidores
reales en `app/.../data/ToolPreferences.kt`, `SettingsStore.kt` y
`ui/ToolPreferencesEditor.kt`.

Reordenar una lista válida conserva sus claves únicas y su cantidad. Por eso
la barra conserva `PEN` obligatoria, incluso si cambia su posición, y la ruleta
conserva sus límites de 3 a 10 entradas. La ruleta no exige `PEN`; hoy dispone
de ocho claves conocidas. Los tests recorren los 64 subconjuntos de barra y los
256 de ruleta, normalizan cada entrada y verifican todos los movimientos entre
sus posiciones, incluidos destinos fuera de rango. Comprueban normalización
idempotente y lectura posterior a `serializeToolbar` / `serializeWheel` sin
cambiar el orden ni la visibilidad.

La validez requiere partir de la lista seleccionada válida: `moveItem` conserva
las entradas recibidas y las listas inválidas siguen dependiendo de los
normalizadores existentes. Para integrar las preferencias, se reordena
`toolbarTools` o `wheelItems`; las opciones ocultas no se agregan a esa lista.
No hace falta otra función pura de apoyo ni cambiar los formatos persistidos.

## Validación

Se sincronizó el worktree a `ib3:~/trazo-run` con las exclusiones indicadas y se
ejecutó allí:

```sh
cd ~/trazo-run
chmod +x gradlew
./gradlew -q -Ptrazo.coreOnly=true :core:test
```

El comando terminó con **código 0** y compiló core y sus pruebas con las
versiones existentes del proyecto (Kotlin 2.1.0, JVM 17). El conteo proviene de
los **38 XML** de `core/build/test-results/test/*.xml`:

| Alcance | Tests | Fallas | Errores | Omitidos |
|---|---:|---:|---:|---:|
| Core completo | 7617 | 0 | 0 | 0 |
| ReorderTest, incluido en el total | 29 | 0 | 0 | 0 |

Se autorrevisó el diff contra el contrato y las APIs reales de preferencias.
`git diff --cached --check` pasó. No se agregaron dependencias ni se modificaron
versiones o archivos restringidos. La compilación Android queda fuera de esta
entrega de core, validada únicamente en la VM asignada `ib3`; no se ejecutaron
pruebas instrumentadas. No quedan bloqueos en el área core.

Los cambios quedan commiteados en la rama propia, sin push ni PR.
