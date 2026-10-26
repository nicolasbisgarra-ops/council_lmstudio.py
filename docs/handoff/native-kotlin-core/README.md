# Motor de referencia en Kotlin (JVM, independiente Android)

Esta carpeta **no es una APK ni una aplicación Android terminada**. Contiene solo lógica portable para que Claude use como punto de partida o descarte si desarrolla algo mejor.

## Ejecutar pruebas
Con Kotlin compiler / JDK instalados:

```bash
kotlinc InkCore.kt CoreSelfTest.kt -include-runtime -d /tmp/trazo-core-test.jar
java -jar /tmp/trazo-core-test.jar
```

## Limitaciones conocidas
- Matcher geométrico simple, no probó handwriting real ni variaciones de muchos usuarios.
- No maneja recolocación multitrace avanzada, cambios de pincel en Android, persistencia, ni selección/edición.
- `DwellDetector` no programa ticks; el adaptador Android debe planificar comprobación monotónica.
- `InkReplacement.stamp` escala la salida guardada; a Claude le corresponde añadir grupos, gestión undo atómica, presión física y geometría adaptativa.
