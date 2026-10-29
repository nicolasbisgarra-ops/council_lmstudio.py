# Pruebas adicionales de Math Notes P1

- Rama: `codex/pruebas-math`
- Commit de código: `cbb421471201849dd4d5998531cc5437adfae29f`
- Archivos cambiados: `core/src/test/kotlin/io/trazo/core/math/MathEdgeCasesTest.kt`
- Qué se hizo: se agregaron casos JVM para división por cero, fracciones grandes, exponentes negativos y racionales, potencias de π, posiciones de errores de sintaxis, ciclos independientes y variables sin definir.
- Pruebas: `./gradlew -q -Ptrazo.coreOnly=true :core:test` en `ib4`; resultado: **194 tests completados, todos verdes** luego de documentar los dos casos con comportamiento pendiente.
- Casos mínimos que revelaron limitaciones, anotados junto a las pruebas y omitidos para no dejar pruebas rotas:
  - `16^(1/2)=` devuelve `≈ 4` en vez de conservar `4` exacto.
  - `(π²)^(1/2)=` devuelve `≈ 3.141592654` en vez de conservar `π` exacto.
- Cómo probar: sincronizar el worktree a `ib4` y correr `cd ~/trazo-run && ./gradlew -q -Ptrazo.coreOnly=true :core:test`.
- Riesgos o pedidos: ninguno; los dos comportamientos anotados quedan para una tarea del motor.
