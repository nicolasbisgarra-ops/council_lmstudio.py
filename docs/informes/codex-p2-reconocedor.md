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

## Seguimiento de la revisión final de Sol · niveles de escritura

Base: `90862dd85a024ca8678362ea53cda83ede4e2575`, misma rama `codex/p2-reconocedor`. Se leyó `revision-p2-sol.md` y se recuperó por lectura el archivo `/tmp/trazo-p2-final-extra.tar` de ib4. Todas las compilaciones y pruebas de esta ronda corrieron exclusivamente en **ib3**.

### Commits y archivos

- **`86a720e0406dabd17a575a2dc20151819fc23407`**: agrega únicamente `core/src/test/kotlin/io/trazo/core/math/MathLayoutLevelsTest.kt`, antes de cambiar el conversor. Incorpora los **70 escenarios completos** de la revisión, con sus identificadores, geometrías, AST esperados y posiciones de error. Conserva las cinco escalas (0.01, 0.5, 1, 3, 100) y los tres órdenes (original, inverso y permutación con semilla 923): **1050 pruebas parametrizadas**. Una aserción verifica que sean 70 escenarios distintos. Se quitaron solamente el nombre temporal y la impresión diagnóstica de la fuente recibida.
- Esa batería se commiteó en rojo, indicándolo en el mensaje: la suite en ib3 dio **3339 pruebas, 465 fallas**, todas en la batería nueva. Las 2289 pruebas previas pasaron. Reprodujo exactamente las discrepancias informadas por la revisión.
- **`422a8768b5879525ac7f188013fe3adc4e0a337b`**: cambia `core/src/main/kotlin/io/trazo/core/math/layout/MathLayoutConverter.kt` y agrega `core/src/test/kotlin/io/trazo/core/math/MathLayoutLexicalBoundaryTest.kt`.
- `docs/informes/codex-p2-reconocedor.md`: esta sección se entrega en el commit documental siguiente al de implementación.

### Corrección estructural

1. **Bandas y cifras (A01–A08, F03):** las bandas se forman usando el pie y el centro de los símbolos, con referencias estables a los glifos de mayor altura. La unión numérica compara vecinos de la misma banda; los símbolos de otros renglones, numeradores o denominadores ya no quedan intercalados en esa búsqueda. Se conserva el pie al construir el número, incluso con alturas individuales diferentes.
2. **Decimales (B05, B06, F04–F06, F08):** el punto o la coma se vincula al nivel de los números que lo rodean mediante cercanía horizontal y pertenencia a su región vertical. El número decimal se agrupa antes de resolver potencias y fracciones. Esto cubre puntos bajos y pequeños, además de las cajas convencionales.
3. **Operandos con potencias (C01, C02, G03):** para cada barra se resuelven primero las potencias dentro de su alcance horizontal. La selección de numerador y denominador usa la base y su exponente como un solo operando; el símbolo elevado más cercano a la barra no puede dejar afuera su propia base. Al cerrar la fracción se consumen sus símbolos originales mediante el árbol, sin duplicarlos ni perderlos.
4. **Exponentes completos (B01–B04, B16, G02, G07):** una semilla cercana vincula el nivel elevado con su base y la asociación continúa entre vecinos de ese nivel. El límite de distancia desde la base se aplica a la semilla; la longitud de la expresión elevada depende de la continuidad entre sus símbolos. Un término basal o una potencia con otra base conserva su asociación propia.
5. **Alturas, paréntesis y tolerancias (B13, D04, G01):** la elevación se distingue del mismo nivel por su posición y su pie, permitiendo exponentes de altura cercana a la base. El avance horizontal de un grupo deriva de su contenido, en lugar del ancho del trazo de apertura. Los límites de asociación incorporan tolerancia en coordenadas normalizadas, evitando cambios por redondeo de Float al escalar.
6. **Límites léxicos (B07–B10, G04–G06):** la emisión contempla el inicio y el final de identificadores de cada nodo e inserta multiplicación cuando el lexer uniría dos átomos en otro nombre. Así se conserva `x^y*z`, también con griegas y bases estructuradas. La prueba adicional verifica los límites entre variables basales y después de potencias, en cinco escalas y dos órdenes.

No se modificaron `MathLayoutConverterTest.kt`, `MathLayoutBatteryTest.kt`, `MathLayoutHierarchyTest.kt` ni las restantes pruebas existentes. La nueva batería permanece igual a su commit inicial. No se quitaron, debilitaron ni omitieron aserciones.

### Verificación

El árbol se sincronizó en ib3 mediante un tar comprimido a archivo y SSH con stdin redirigido; las iteraciones actualizaron los archivos cambiados del mismo modo, siempre con rutas absolutas. Los SHA-256 del conversor y de ambas clases nuevas coincidieron entre el worktree y `/data/home/trazo-run`. JDK 21 compiló para JVM 17 sin cambiar versiones.

```sh
ssh -F /data/data/com.termux/files/home/.ssh/instabox.sshconfig ib3 'cd /data/home/trazo-run && /data/home/trazo-run/gradlew -q -Ptrazo.coreOnly=true :core:test'
```

Resultado final: **3340 pruebas, 0 fallas, 0 errores, 0 omitidas** = 2289 previas + 1050 de la revisión final + 1 prueba de límites léxicos. Log: `/data/home/trazo-p2-niveles-final.log`; XML: `/data/home/trazo-run/core/build/test-results/test/`.

### Límites conocidos y entrega

No quedan discrepancias en los 70 escenarios nuevos ni en los 172 anteriores, incluidas sus variantes. Se mantiene el límite ya documentado de barras idénticas y equidistantes: las cajas admiten dos jerarquías y se desempata cerrando primero la barra superior. Su prueba específica sigue activa. `f( )` conserva la interpretación del parser existente como multiplicación; no se cambió la gramática.

Sin dependencias nuevas, red en el código, cambios de SDK/versiones, cambios de `CONFLICT_SCORE` ni ediciones de los archivos prohibidos. Sin amend, rebase, push, PR ni publicación. Después del commit documental no se modifican archivos; la siguiente revisión independiente queda a cargo del orquestador.

## Seguimiento 2 · última ronda de geometría local

Base: `3fb77d07eb56089f9c0a91a0085d5be3348b7792`, rama `codex/p2-reconocedor`. Se leyó `revision-p2-sol2.md` y se recuperó por lectura `/tmp/trazo-p2-review2-extra.tar` de ib4. También se leyeron sus XML y los de la comparación contra `90862dd` para identificar las seis regresiones. No se compiló ni se ejecutaron pruebas en ib4; toda la validación de esta entrega se hizo en **ib3**.

### Batería y commits

- **`b311ee46f715f48e1465748e0f695e1fc9ca8deb`** agrega únicamente `core/src/test/kotlin/io/trazo/core/math/MathLayoutLocalGeometryTest.kt`, antes de cambiar el conversor. Conserva los **119 escenarios**, sus geometrías con alturas individuales, AST de sentencias esperados y posiciones de error. Comprueba que existan exactamente 119 identificadores distintos.
- Conserva las siete transformaciones originales: escalas uniformes `1e-6`, `0.003`, `1`, `23` y `1e6`, más estiramientos `(sx=0.01, sy=100)` y `(sx=100, sy=0.01)`. Cada escenario corre en cinco órdenes: original, inverso y tres permutaciones con semillas 73, 74 y 75. Total: **4165 pruebas parametrizadas**.
- Primera ejecución en ib3: **7505 pruebas, 1050 fallas**, correspondientes a los 30 escenarios del informe. Las 3340 pruebas anteriores pasaron. El commit de la batería indica explícitamente su estado en rojo.
- Las seis regresiones respecto de `90862dd` quedaron identificadas y cubiertas: **V01, V03, V04, V05, V06 y V07**. Cada una corre en las 35 combinaciones de transformación y orden.
- **`54a657a541b4101060dff73a03976b306950e3f4`** cambia `core/src/main/kotlin/io/trazo/core/math/layout/MathLayoutConverter.kt` y agrega `core/src/test/kotlin/io/trazo/core/math/MathLayoutScopeInvariantTest.kt`.
- `docs/informes/codex-p2-reconocedor.md`: esta sección se entrega en el commit documental posterior al de implementación.

### Correcciones

1. **Los operadores cortan los números.** La unión de cifras verifica también los operadores que atraviesan el hueco horizontal y el cuerpo vertical de sus vecinos, aunque por su caja fina hayan quedado en otra banda. La compatibilidad de nivel de un operador fino usa el cuerpo de los glifos vecinos. Esto preserva restas y divisiones basales, dentro de raíces y fracciones y dentro de exponentes; incluye las seis regresiones.
2. **Los paréntesis delimitan una región vertical real.** Los miembros iniciales se toman dentro de la caja vertical de los delimitadores. La región sólo se amplía siguiendo estructuras que le pertenecen: operandos de una barra interior, contenido de una raíz interior o exponentes de una base interior. Se conserva así una fracción completa dentro de paréntesis bajos sin admitir un numerador externo ni otro renglón. Esto cubre T01–T04, los denominadores entre paréntesis y las mezclas M01–M03.
3. **Las raíces conservan el comienzo sin importar el largo del techo.** El margen izquierdo usa el tamaño de los glifos vecinos; si el primer hijo es una estructura, busca el cuerpo de escritura contenido en ella. No usa el ancho total de la raíz ni el tamaño total de ese hijo. Los errores del primer término siguen el cursor real de emisión. Pasan T05–T11 y una regresión adicional con una raíz de 500×500 y un primer hijo raíz de 400×400.
4. **Los exponentes se asocian por tramos completos.** La separación vertical se mide entre las cajas vecinas usando su tamaño de escritura local. Los tramos continuos se vinculan en ambos sentidos para conservar signos iniciales finos. Su comienzo debe estar después de la base: no se puede robar únicamente un sufijo del numerador para convertirlo en exponente del denominador. Esto cubre L02, Z05, U22–U30, T15 y conserva los exponentes negativos previos.
5. **Las cajas de altura cero conservan su pie.** El tamaño nominal se estima con glifos medidos cercanos y el centro se deriva del pie observado, en lugar de inventar una caja alrededor de un centro sin altura. Así Z03 conserva `12`. Cuando falta todo tamaño medido en un eje, la normalización usa su extensión geométrica; no introduce una distancia fija que cambie al escalar.

`MathLayoutScopeInvariantTest.kt` agrega tres pruebas activas: operadores finos en diez posiciones verticales dentro del cuerpo de sus vecinos, raíz con primer hijo grande y el límite sin ninguna altura de referencia descrito abajo. Corren en cinco transformaciones, incluidas escalas extremas y estiramientos por eje, y dos órdenes. Los dos primeros controles encontraron bordes adicionales de las mismas reglas durante el desarrollo y permitieron corregirlos antes del commit.

No se modificó ninguna prueba anterior, incluidas `MathLayoutConverterTest`, `MathLayoutBatteryTest`, `MathLayoutHierarchyTest`, `MathLayoutLevelsTest` y `MathLayoutLexicalBoundaryTest`. La matriz nueva permanece igual a su commit inicial. No hay pruebas desactivadas ni aserciones debilitadas.

### Verificación final

Sincronización mediante tar comprimido a archivo y SSH con stdin redirigido, usando rutas absolutas; actualizaciones posteriores de los archivos cambiados mediante el mismo mecanismo. Los SHA-256 del conversor y de las dos clases de pruebas nuevas coincidieron entre el worktree y `/data/home/trazo-run`. JDK 21 compiló para JVM 17 sin modificar versiones. No se ejecutó Gradle en el teléfono.

```sh
ssh -F /data/data/com.termux/files/home/.ssh/instabox.sshconfig ib3 'cd /data/home/trazo-run && /data/home/trazo-run/gradlew -q -Ptrazo.coreOnly=true :core:test'
```

**Resultado: 7508 pruebas, 0 fallas, 0 errores, 0 omitidas** = 3340 previas + 4165 de la revisión + 3 pruebas adicionales. Pasan los 119 escenarios y sus transformaciones completas, las seis regresiones contra `90862dd` y todas las baterías anteriores. Evidencia: XML en `/data/home/trazo-run/core/build/test-results/test/`; salida de Gradle en `/data/home/trazo-p2-local-final.log`.

### Límites conocidos finales

- **Barras idénticas y equidistantes:** se mantiene el límite anterior. Las mismas cajas admiten `(1/2)/3` o `1/(2/3)`; se desempata cerrando primero la barra superior. La prueba específica sigue activa.
- **Ninguna altura de escritura medida:** si todas las cajas de los glifos tienen altura cero, no hay referencia para distinguir niveles basales de exponentes. Ejemplo `(etiqueta,x,y,ancho,alto)`: `("1",0,0,2,0)` y `("2",6,-10,2,0)` pueden representar dos renglones o una potencia. Se conservan como `2\n1`, sin inventar una potencia. Esta convención y su invariancia a escala están en la prueba activa `limiteConocidoSinNingunaAlturaDeReferencia`. Si existe un glifo cercano con altura medida, se usa esa referencia, como en Z03 y los controles previos de cajas degeneradas.

No quedan discrepancias en los escenarios solicitados. `f( )` mantiene la interpretación existente del parser como multiplicación; la gramática no cambió. Sin dependencias nuevas, red en el código, cambios de SDK/versiones, cambios de `CONFLICT_SCORE` ni ediciones de los archivos prohibidos. Sin amend, rebase, push, PR ni publicación. Se cierra esta ronda con el informe y no se modifican archivos después del último commit.
