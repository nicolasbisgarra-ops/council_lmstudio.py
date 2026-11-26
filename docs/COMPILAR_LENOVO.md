# Compilar Trazo en la Lenovo (NicoLenovo)

- Repo: `D:\Trazo` (clon HTTPS de este repositorio).
- JDK 17: `D:\dev\jdk17`. Android SDK: `D:\dev\android-sdk`. Caché de Gradle: `D:\dev\gradle-home`.
- Ciclo: se sube a GitHub desde la nube, la Lenovo hace `git pull` y corre
  `.\gradlew.bat assembleDebug --daemon`.
- APK: `D:\Trazo\app\build\outputs\apk\debug\app-debug.apk`.
- Primer build en frío: ~15 min; sin cambios: ~2 s.
