#!/usr/bin/env bash
# Corre en el emulador de CI: instala las APK, ejecuta las pruebas instrumentadas y graba la
# demo automatizada con screenrecord. Uso: ci-emulator.sh <dir-con-apks> <dir-salida>
set -uo pipefail
APKS="${1:-output}"
OUT="${2:-output/emulator}"
mkdir -p "$OUT"
RUNNER="io.trazo.test/androidx.test.runner.AndroidJUnitRunner"

adb wait-for-device
{
  echo "Fecha: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Modelo: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "Android: $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "Pantalla: $(adb shell wm size | tr -d '\r') / $(adb shell wm density | tr -d '\r')"
  echo "Tipo: EMULADOR (eventos de lápiz sintetizados; no es S Pen físico)"
} | tee "$OUT/dispositivo.txt"

adb install -r -t "$APKS/Trazo-debug.apk" || exit 1
adb install -r -t "$APKS/Trazo-debug-androidTest.apk" || exit 1

# Sin red: las funciones centrales deben operar offline (T21).
adb shell svc wifi disable || true
adb shell svc data disable || true

echo "== Pruebas instrumentadas =="
adb shell am instrument -w -e class io.trazo.app.EditorInstrumentedTest,io.trazo.app.ExportInstrumentedTest "$RUNNER" | tee "$OUT/pruebas-instrumentadas.txt"

echo "== Demo grabada =="
adb shell rm -f /sdcard/trazo-demo.mp4
adb shell screenrecord --bit-rate 6000000 --time-limit 175 /sdcard/trazo-demo.mp4 &
REC=$!
sleep 3
adb shell am instrument -w -e class io.trazo.app.DemoExerciseTest "$RUNNER" | tee "$OUT/demo.txt"
sleep 2
adb shell pkill -INT screenrecord || true
wait $REC || true
sleep 4
adb pull /sdcard/trazo-demo.mp4 "$OUT/demo-real-android-emulador.mp4" || true
for f in trazo-demo-1-integral trazo-demo-2-integral-resuelta trazo-demo-3-final trazo-demo-4-ensenar trazo-demo-5-aprendido; do
  adb pull "/data/local/tmp/$f.png" "$OUT/$f.png" || true
done
adb logcat -d -t 2000 > "$OUT/logcat.txt" || true

status=0
for f in pruebas-instrumentadas.txt demo.txt; do
  if grep -q "FAILURES!!!" "$OUT/$f" || ! grep -q "^OK (" "$OUT/$f"; then
    echo "FALLÓ: $f"; status=1
  fi
done
exit $status
