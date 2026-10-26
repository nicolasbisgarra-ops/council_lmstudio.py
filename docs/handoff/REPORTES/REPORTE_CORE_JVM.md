# Evidencia de pruebas del núcleo Kotlin incluido

**Fecha:** 2026-10-26
**Entorno:** Kotlin/JVM ejecutado localmente durante la preparación del ZIP (no dispositivo Android)
**Compilador:** info: kotlinc-jvm 1.9.0 (JRE 21.0.12.1+1-1-deb13u1-Debian)
**Comandos reales:**

```bash
cd native-kotlin-core
kotlinc InkCore.kt CoreSelfTest.kt -include-runtime -d /tmp/trazo-ink-core.jar
java -jar /tmp/trazo-ink-core.jar
```

**Resultado:** 16/16 comprobaciones ejecutadas y superadas. Los casos cubren matching geométrico básico, patrones ambiguos, pausas del lápiz mientras está apoyado, cancelación antes de 1 s y reproducción de salida como puntos vectoriales.

**NO se verificó:** compilación Android, APK, integración Android Ink, latencia real, compatibilidad con S Pen, rechazo físico de palma ni ejecución de interfaz nativa. Todos esos puntos quedan para Claude en el proyecto final.
