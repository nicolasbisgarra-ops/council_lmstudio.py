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
