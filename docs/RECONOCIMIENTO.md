# Reconocimiento · benchmark determinista (Agente 3)

Fecha: 2026-10-28. Referencia inicial: `claude/imolementa-esto-ag7u18`, matcher `GestureMatcher.kt` SHA de blob `95483c0c80bd06d58a537d00788ac24d4b3a79ca`.

## Estado de ejecución y alcance

**Estado: validado en Gradle.** Integrado por Claude en `claude/imolementa-esto-ag7u18` (el agente no tenía permiso de push). `:core:test` corrió el benchmark en el proyecto real y la salida `RECOGNITION_BENCH` coincide exactamente con las cifras de abajo, que pasan a ser oficiales.

## Reproducción oficial

```bash
./gradlew -Ptrazo.coreOnly=true :core:test --rerun-tasks
# O solo el benchmark:
./gradlew -Ptrazo.coreOnly=true :core:test --tests '*GestureRobustnessBenchmarkTest' --rerun-tasks --info
```

El test imprime un bloque `RECOGNITION_BENCH` en stdout. Si Gradle lo oculta por `showStandardStreams=false`, consultar `core/build/test-results/test/` o activar temporalmente `--info`/`showStandardStreams` **sin editar archivos fuera del carril**. El test realiza dos corridas idénticas, comprueba su igualdad y exige un tiempo conjunto inferior a 60 segundos. La prueba usa semilla fija `975183910` (`0x3A202026`) e identifica los patrones por ID, ordenados alfabéticamente para evitar cambios por orden de lista.

## Conjunto sintético

- `DefaultPatterns.all()` en la referencia: 3 patrones (`fraccion`, `integral`, `raiz`), con **5 ejemplos de gesto**: 1, 1 y 3 respectivamente.
- Cada ejemplo × 7 escenarios × 5 intentos = **175 positivos**.
- Jitter: entre 0,6 % y 1,8 % del lado mayor del gesto, coordenadas perturbadas individualmente.
- Rotación: **±15°** alrededor del centro del gesto.
- Escala: **0,5×, 0,875×, 1,25×, 1,625× y 2×**; transformaciones conservan proporciones.
- Velocidad/muestreo: arco remuestreado de forma no uniforme (`u^p`, `p` entre 0,5 y 2,2), de 8 a 86 puntos por trazo, distintos tiempos relativos.
- Trazos partidos: divide el trazo más largo en dos fragmentos que comparten el punto de corte.
- Orden inverso: invierte el orden de los trazos para gestos multitrazo; si hay uno solo, invierte su recorrido.
- Combinado: jitter + rotación + escala + velocidad/muestreo.
- Negativos: **128 garabatos aleatorios** con 1–3 trazos y caminatas angulares, semilla fija. No representan letras ni dibujos reales de usuarios.

**Definiciones:** top-1/top-3 = posición del patrón verdadero ordenando `GestureMatcher.bestScore` entre patrones con ejemplos de **la misma cantidad de trazos**, independientemente de su umbral. Un patrón sin puntuación elegible cuenta como fallo. Conversión correcta = `recognize` devuelve `Found` con el patrón verdadero. Falso positivo = un garabato devuelve `Found` (una ambigüedad no convierte y se informa aparte). La matriz muestra el resultado operativo de `recognize`: IDs, `AMBIGUOUS` o `NONE`.

## Resultados — salida oficial de Gradle

| Métrica | Antes (matcher original) | Después (sin modificación) |
|---|---:|---:|
| Top-1 | **159/175 · 90,86 %** | 159/175 · 90,86 % |
| Top-3 | **160/175 · 91,43 %** | 160/175 · 91,43 % |
| Conversiones automáticas correctas | **148/175 · 84,57 %** | 148/175 · 84,57 % |
| Falsos positivos en garabatos | **0/128 · 0 %** | 0/128 · 0 % |
| Garabatos ambiguos | 0/128 | 0/128 |

No se modificó `GestureMatcher.kt`: **no existe una mejora «después» validada**. Un 0/128 no prueba que los falsos positivos sean imposibles, y un top-3 alto entre solo 3 patrones no equivale a rendimiento en la biblioteca ampliada. Repetir con `PresetPatterns` por separado cuando sea parte del alcance.

### Desglose de escenarios

| Escenario | Casos | Top-1 | Conversiones correctas |
|---|---:|---:|---:|
| jitter | 25 | 25 | 25 |
| rotation | 25 | 25 | 25 |
| scale | 25 | 25 | 25 |
| speed-sampling | 25 | 25 | 25 |
| split | 25 | 10 | **0** |
| reverse-order | 25 | 25 | 25 |
| combined | 25 | 24 | 23 |

### Matriz de confusión — conversión operativa

| Real / Predicho | fraccion | integral | raiz | AMBIGUOUS | NONE | Total |
|---|---:|---:|---:|---:|---:|---:|
| fraccion | 30 | 0 | 0 | 0 | 5 | 35 |
| integral | 0 | 30 | 0 | 0 | 5 | 35 |
| raiz | 1 | 0 | 88 | 0 | 16 | 105 |
| **Total** | 31 | 30 | 88 | 0 | 26 | 175 |

### Diez pares de mayor confusión / no reconocimiento

Para los 3 patrones base solo aparecen **4 pares no diagonales con recuento distinto de cero**. Se muestran los otros seis con 0 para completar los 10 solicitados sin inventar errores. `NONE` indica rechazo, no confusión con otro símbolo.

| # | Real → Resultado | Casos |
|---:|---|---:|
| 1 | raiz → NONE | 16 |
| 2 | fraccion → NONE | 5 |
| 3 | integral → NONE | 5 |
| 4 | raiz → fraccion | 1 |
| 5 | fraccion → AMBIGUOUS | 0 |
| 6 | fraccion → integral | 0 |
| 7 | fraccion → raiz | 0 |
| 8 | integral → AMBIGUOUS | 0 |
| 9 | integral → fraccion | 0 |
| 10 | integral → raiz | 0 |

## Análisis y propuesta de mejora

**Cuello de botella principal: trazos partidos**. El comparador exige `gesture.size == example.size`; todos los 25 escenarios de partición dejan de convertir correctamente. Algunos siguen rankeando por similitud accidental contra el ejemplo raíz de dos trazos, pero la puntuación queda por debajo del umbral o corresponde a otra clase.

**Propuesta 1 (prioritaria, sin implementar todavía):** tolerancia opcional a una interrupción muy corta del S Pen que fragmenta un recorrido. Reconstruir segmentos solo cuando el final y el comienzo estén espacialmente próximos y separados temporalmente por un margen breve; evaluar el gesto unido como candidato adicional, sin quitar la ruta exacta. **Requiere validar** falsos positivos, ambigüedades, prioridad de ejemplos multitrazo e integración con `GestureRecognizer`. La prueba existente `stroke count respected` exige `NoMatch` ante dos líneas independientes, así que una unión indiscriminada **rompería una regresión**. Una solución sin contexto temporal en `GestureMatcher` es insegura; se propone coordinar el cambio con Claude antes de editar otro carril.

**Propuesta 2:** aplicar una leve suavización sólo para estimar tangentes tras remuestrear cuando haya jitter fuerte; mantener la posición original y comparar el tradeoff frente a fracciones e integrales. No modificar los pesos globales (`POSITION_SCALE`, `DIRECTION_WEIGHT`, `DIRECTION_SCALE`) sin prueba A/B y pruebas de letras manuscritas.

**Propuesta 3:** agregar negativos reales: letras/cifras (`0`, `2`, `3`, `5`, `6`, `8`, `9`, paréntesis), líneas sueltas, correcciones de usuario y trazos con S Pen. Los garabatos aleatorios no son un detector suficiente de falsos positivos. Para aceptar un ajuste exigir: cero regresiones JVM, mejoras de conversión correcta, falsos positivos no superiores al basal en muestras fijas y <=60 s.

## Trazos reales desde Drive

El documento **«ChatGPT → Agente 3 · 01 · trazos reales» todavía no aparece** en la carpeta compartida al momento de la búsqueda. No se fabricaron muestras reales ni métricas de usuarios. La API del banco `RecognitionBenchmark.run(realCases = ...)` admite casos inmutables etiquetados como `Case(truth, scenario, strokes)`; cuando se publique el documento, deben transcribirse en fixtures fuente fijos, con ID de caso, clase y puntos `(x, y, pressure, timeMs)` y reportarse por separado de los 175 sintéticos.

## Integración pendiente para Claude

1. Reautorizar escritura de GitHub o crear la rama `agente-3/reconocimiento` desde `claude/imolementa-esto-ag7u18`; no tocar la rama base directamente.
2. Anotar el carril en `docs/COORDINACION.md`, commit `[skip ci]` y push a rama propia.
3. Agregar el archivo de benchmark y esta documentación mediante commits chicos; antes de **cada** push, correr `./gradlew -Ptrazo.coreOnly=true :core:test`.
4. Confirmar métricas oficiales, duración, SHA, tests preexistentes y run de CI verde. No generar PR, no `[publicar]`, no `force-push`.
5. Cuando lleguen trazos reales, incorporarlos a fixtures y volver a medir antes de cualquier cambio a `GestureMatcher.kt`.
