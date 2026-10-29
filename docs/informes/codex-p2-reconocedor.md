# Math Notes P2 · Reconocedor de disposición

- Rama: `codex/p2-reconocedor`
- Commit de código: `91c3584a829e588376ae2ec6299da7c3128cba7d`
- CI: sin run; no se hizo push, según las reglas de la tarea.

## Cambios

- `core/src/main/kotlin/io/trazo/core/math/layout/MathLayoutConverter.kt`: agrega `LayoutSymbol` y `MathLayoutConverter.convert`. Ordena símbolos por caja, agrupa cifras cercanas, exponentes, fracciones y raíces; conserva los renglones separados con salto de línea y traduce etiquetas de operadores, paréntesis y letras griegas. Las etiquetas desconocidas y las estructuras incompletas producen `MathException` con posición.
- `core/src/test/kotlin/io/trazo/core/math/MathLayoutConverterTest.kt`: cubre orden y números de varias cifras, exponentes, fracciones, raíz compuesta, paréntesis, líneas múltiples, etiqueta desconocida y el flujo símbolos → texto → `MathParser` → `Evaluator`.

## Verificación

- VM: `ib3`.
- Comando: `./gradlew -q -Ptrazo.coreOnly=true :core:test`.
- Resultado: 196 pruebas, 0 fallas.

## Cómo probarlo

Desde la raíz del proyecto, ejecutar el comando de core indicado arriba. La interfaz pública toma una lista de `LayoutSymbol` y devuelve la expresión como texto; las líneas del cuaderno salen separadas por `\n`.
# Revisión de hallazgos de MathLayoutConverter

Rama: `codex/p2-reconocedor`. VM de verificación: `ib3`.

## Correcciones

### Ronda 1

El commit `90612c02db8c1e33f088d421bf4ccb37330c5792` fue la primera corrección. La re-revisión posterior mostró que no cerraba el hallazgo 6b y que introducía regresiones en raíces laterales, fracciones de varias líneas, operadores entre fracciones y posiciones de error. Esos resultados no se consideran resueltos por la ronda 1.

### Ronda 2

El commit de código `87718c0` reemplaza la reconstrucción especial por un recorrido recursivo uniforme:

1. Primero separa las cajas en renglones; barras y raíces relacionan las bandas verticales de sus operandos antes de emitir los saltos de línea.
2. Dentro de cada línea resuelve primero las estructuras de mayor extensión espacial. Numeradores, denominadores, radicandos y exponentes vuelven a pasar por el mismo conversor, que conserva operadores y símbolos externos en su orden horizontal.
3. Las raíces y fracciones pueden aparecer como laterales o dentro de otras estructuras; se evita convertir operandos estructurados con `atom`.
4. La asociación de exponentes limita distancia horizontal y vertical con umbrales relativos a la altura de la escritura. Un exponente puede ser una estructura, y los operadores del grupo se conservan entre paréntesis.
5. Las pruebas de regresión cubren raíces con elementos laterales, raíces dentro de fracciones, fracciones por renglón, tres fracciones con operadores, fracciones recursivas, exponentes estructurados, separación de renglones y posiciones de error dentro de texto generado.

Los índices de sintaxis se expresan desde cero en el texto emitido de la línea, contando prefijos generados; el `detail` de `MathError.Syntax` se propaga sin el mensaje ya formateado con una posición local.

`f( )` se conserva como lo interpreta hoy `MathParser`: emite `f(` y el parser no ofrece llamadas a función. No se cambió la gramática.

## Verificación de ronda 2

Se sincronizó el worktree en `ib3` y se ejecutó únicamente `./gradlew -q -Ptrazo.coreOnly=true :core:test`. Resultado final: **216 pruebas, 0 fallas**. Las ejecuciones intermedias que fallaron guiaron ajustes antes del commit; la corrida final pasó.

## Commits

- Código y regresiones, ronda 1: `90612c02db8c1e33f088d421bf4ccb37330c5792`.
- Código y regresiones, ronda 2: `87718c0`.
- Informe de ronda 2: se completa en el commit de documentación que contiene este archivo.

### Ronda 3

El commit de código `0f945214f3a538de478c2020346ddb5035dfc691` cierra los casos priorizados de exponentes y evita las regresiones observadas al limitarse a la altura típica de la escritura el alcance vertical de las barras.

- Cada símbolo elevado se asigna a la base válida más cercana a la izquierda; los descendientes forman el subárbol recursivo, para casos como `x+y^2`, `x^2+y^3` y `x^(2^3)`.
- Los grupos entre paréntesis completos se convierten en una base; las cifras contiguas de un exponente se agrupan, y se asocian tokens estructurados, incluyendo fracción elevada a raíz.
- Las barras de igual ancho se desempatan por posición vertical para conservar la fracción exterior y la interior. El alcance de numerador y denominador depende de la altura típica, no del ancho de la barra.
- Los errores dentro de raíces con fracciones suman los prefijos ya emitidos, incluso al convertir renglones. La multiplicación implícita ante números se aplica a átomos variables sin lista de letras predefinida.
- Se agregaron cinco pruebas agrupadas con entradas mínimas y resultados esperados para esas asociaciones, grupos, jerarquía de barras, renglones alejados, variables y posición de error.

**Límites conocidos:** no se quitaron pruebas. No quedó un límite conocido entre los casos priorizados que se agregaron en esta ronda. La matriz temporal completa del revisor no está disponible en el checkout, así que la corrida cubre los casos mínimos agregados y las pruebas previas, no cada variante de escala de esa matriz.

## Verificación de ronda 3

- VM: `ib3`.
- Comando: `./gradlew -q -Ptrazo.coreOnly=true :core:test`.
- Resultado: **221 pruebas, 0 fallas** (216 previas y 5 pruebas nuevas).

## Commits de ronda 3

- Código y pruebas: `0f945214f3a538de478c2020346ddb5035dfc691`.
- Informe: se completa en el commit documental siguiente.

## Desarrollo completo por Sol · 29/10/2026

Rama `codex/p2-reconocedor`, partiendo de `cd2011b`. Se conservó la historia de las rondas anteriores: sin restauración, amend ni rebase.

### Batería primero

El commit **`fee10ac4f31c1defb732e3b417f603147b92c2fd`** agrega únicamente `MathLayoutBatteryTest.kt`, antes de modificar el conversor.

- Incorpora los **132 casos** de `revision3-tests.kt.txt`, correspondientes a los tres primeros informes, y los **40 nuevos** de `Revision4NuevosTest`, en `revision4-tests.kt.txt`. Conserva los identificadores, las cajas originales y las salidas o posiciones esperadas. Comprueba que sean exactamente 172 casos distintos.
- Cada caso corre en escalas **0.5, 1, 2 y 3**, con alturas de referencia **10, 20 y 40**: **2064 variantes parametrizadas**. Para cambiar la altura sin cambiar el significado se transforman tanto las cajas como las distancias verticales; se preserva la diferencia original entre glifos de altura 10 y 20, incluidos O02 y N19. Las variantes originales de escala 0.01–100 y las traslaciones también se conservan.
- Cada variante comprueba el orden original, el inverso y una permutación con semilla fija: **6192 conversiones**. Compara el AST por renglón, la evaluación con variables de valor 2 y las posiciones de error desde cero, sin mensajes con posiciones locales contradictorias. Los casos donde la precedencia cambiaba el resultado incluyen valores explícitos de referencia, como 16, 25/12, 1/8388608, 1/32 y 8388608.
- Primera ejecución completa en ib3: **2285 pruebas, 480 fallas**, todas en la batería nueva. Las **221 pruebas previas pasaron**. El mensaje del commit deja explícito que esta entrega de pruebas está en rojo, según la excepción solicitada.

### Implementación

El commit **`481c005165065bc6c2b9dd5867e0a73f3f317c9c`** cambia el conversor y agrega las regresiones de jerarquía.

- `core/src/main/kotlin/io/trazo/core/math/layout/MathLayoutConverter.kt`: reemplaza las sucesivas mezclas de símbolos y texto por un árbol espacial con nodos para átomos, números, secuencias, fracciones, raíces, paréntesis y potencias. Cada nodo conserva su caja completa y, por separado, el nivel y tamaño de su escritura.
- Normaliza ambos ejes con medidas de los glifos y opera internamente con `Double`. Las comparaciones geométricas y sus desempates usan coordenadas normalizadas; no hay límites de distancia en píxeles.
- Los paréntesis y las raíces delimitan regiones recursivas. Las barras interiores más angostas se cierran antes que las exteriores; a igual ancho se usa la cercanía de los operandos y después la posición vertical. Cada lado toma la banda de escritura más cercana y sus exponentes, sin absorber el renglón siguiente. El alcance considera la caja completa de un operando ya estructurado.
- Agrupa cifras del mismo nivel antes de asociar exponentes. Cada nodo elevado tiene una sola base válida, la más cercana a su izquierda, tanto para glifos como para estructuras. Los exponentes compuestos y negativos se conservan como subárboles.
- Emite el texto una sola vez, con los paréntesis y multiplicaciones necesarios para el parser. Las posiciones de error salen del cursor real de emisión, incluyendo prefijos y separadores; el cursor se reinicia en cada renglón.
- `core/src/test/kotlin/io/trazo/core/math/MathLayoutHierarchyTest.kt`: agrega cuatro pruebas permanentes para anidamiento de tres niveles en numerador y denominador, denominador anidado de dos niveles y el desempate ambiguo descrito abajo. Corren en cinco escalas (0.01–100) y dos órdenes de entrada. La prueba de tres niveles detectó y permitió corregir una inversión de jerarquía que no cubrían los 172 casos.
- `core/src/test/kotlin/io/trazo/core/math/MathLayoutBatteryTest.kt`: permanece igual al commit de la batería. `MathLayoutConverterTest.kt` y todas las pruebas anteriores permanecen sin modificaciones.
- `docs/informes/codex-p2-reconocedor.md`: esta sección registra la entrega; va en el commit documental posterior al de implementación.

### Verificación final

Se usó exclusivamente **ib3**, con JDK 21. La compilación para JVM 17 funcionó sin cambiar versiones. No se ejecutó Gradle en el teléfono.

Se sincronizó primero el árbol completo mediante un tar a archivo y SSH con stdin redirigido, usando rutas absolutas; las iteraciones posteriores actualizaron los archivos cambiados de la misma manera. Una ejecución iniciada antes de terminar la primera copia falló al resolver el plugin de Android y se descartó: la ejecución sobre el árbol completo funcionó sin modificar la configuración. Los SHA-256 del conversor, la batería, las pruebas de jerarquía y `MathLayoutConverterTest.kt` coincidieron entre el worktree y la VM.

Comando de la suite completa:

```sh
ssh -F /data/data/com.termux/files/home/.ssh/instabox.sshconfig ib3 'cd /data/home/trazo-run && /data/home/trazo-run/gradlew -q -Ptrazo.coreOnly=true :core:test'
```

**Resultado: 2289 pruebas, 0 fallas, 0 errores, 0 omitidas** (221 previas + 2064 de batería + 4 de jerarquía). Los 172 casos solicitados pasan en todas sus variantes; no se quitó, desactivó ni debilitó ninguna prueba. El detalle de esta ejecución quedó en ib3 en `/data/home/trazo-p2-final.log` y los XML de `/data/home/trazo-run/core/build/test-results/test/`.

### Límites conocidos

**Barras idénticas y equidistantes:** tres operandos alineados verticalmente, separados por dos barras del mismo ancho y con iguales distancias, admiten tanto `(1/2)/3` como `1/(2/3)`. La etiqueta y la caja no contienen información que permita distinguir esas intenciones: los valores serían 1/6 y 3/2. El conversor desempata cerrando primero la barra superior. Queda documentado y probado por separado en `limiteConocidoBarrasIdenticasYEquidistantes`, como prueba activa, sin omitir ninguno de los casos solicitados. Si hay anchos o distancias que diferencien la jerarquía, se usan esas señales antes del desempate.

La etiqueta `f( )` conserva el comportamiento del parser existente: `f(x)` se interpreta como multiplicación, según C07. No se cambió la gramática.

Sin dependencias nuevas, red en el código, cambios de SDK/versiones ni cambios de `CONFLICT_SCORE`. No se tocaron los archivos prohibidos. No se hizo push, PR ni publicación. La revisión final en un hilo nuevo queda a cargo del orquestador; después del commit documental no se modifican archivos.
