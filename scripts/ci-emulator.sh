#!/usr/bin/env bash
# Corre en el emulador de CI: instala las APK, ejecuta las pruebas instrumentadas y graba la
# demo automatizada con screenrecord. Uso: ci-emulator.sh <dir-con-apks> <dir-salida> [fases]
# fases: «pruebas», «interfaz» y/o «demo» separadas por comas (por defecto, todas).
set -uo pipefail
APKS="${1:-output}"
OUT="${2:-output/emulator}"
FASES="${3:-pruebas,interfaz,demo}"
has() { [[ ",$FASES," == *",$1,"* ]]; }
# Los dos trabajos del emulador publican en la misma rama: lo común lleva la fase en el nombre.
SUF="$(echo "$FASES" | tr , -)"
mkdir -p "$OUT"
RUNNER="io.trazo.test/androidx.test.runner.AndroidJUnitRunner"

adb wait-for-device

# Un emulador recién arrancado en un host cargado puede dejar encima de todo un diálogo del
# sistema («System UI no responde»), el panel de notificaciones o el bloqueo: se tragan los
# toques inyectados aunque la inyección «funcione». Sin diálogos de error (un ANR posterior
# cierra el proceso), sin bloqueo y con la pantalla siempre encendida.
adb shell settings put global hide_error_dialogs 1 || true
adb shell locksettings set-disabled true || true
adb shell svc power stayon true || true

# Sin la sección «LAST ANR» de dumpsys: guarda el foco del momento del último ANR (null tras uno
# en el arranque) y `settle` esperaba en vano.
focus() { timeout 15 adb shell dumpsys window 2>/dev/null | tr -d '\r' | awk '/^WINDOW MANAGER /{skip = /LAST ANR/} !skip && /mCurrentFocus|mFocusedApp/'; }
anrs() { timeout 15 adb shell dumpsys dropbox 2>/dev/null | grep -E '_anr|_crash' | tail -n 20; }
diag() {
  echo "-- $1: carga $(timeout 15 adb shell cat /proc/loadavg | tr -d '\r'); $(timeout 15 adb shell dumpsys power 2>/dev/null | grep -oE 'mWakefulness=[A-Za-z]+' | head -n 1)"
  focus
}
# Toca el botón con ese resource-id en la ventana activa (diálogos de error del sistema).
# Solo sin instrumentación en curso: `uiautomator dump` choca con UiAutomation.
tap_res() {
  local b
  b="$(timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "resource-id=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -n 1 | grep -oE '[0-9]+')" || return 1
  set -- $b
  [ $# -eq 4 ] && timeout 10 adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# Espera a que el foco sea de una app y no de un diálogo del sistema, el panel o el bloqueo, y
# cierra lo que se interponga. $1 = mínimo de vueltas antes de darlo por estable.
settle() {
  local min="${1:-1}" good=0 bad=0 i f
  for i in $(seq 1 45); do
    timeout 10 adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    timeout 10 adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    f="$(focus | grep mCurrentFocus)"
    if [ -z "$f" ] || echo "$f" | grep -qE 'Not Responding|Application Error|NotificationShade|Keyguard|=null'; then
      echo "Esperando sistema estable ($i): ${f:-sin foco}"
      if echo "$f" | grep -qE 'Not Responding|Application Error'; then
        bad=$((bad + 1))
        [ "$bad" -ge 2 ] && { tap_res "android:id/aerr_wait" || tap_res "android:id/aerr_close" || true; }
      fi
      # El broadcast es ordenado y espera a SystemUI: con SystemUI colgado puede bloquear.
      [ $((i % 3)) -eq 1 ] && { timeout 8 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true; }
      timeout 10 adb shell cmd statusbar collapse >/dev/null 2>&1 || true
      good=0
    else
      good=$((good + 1))
      if [ "$good" -ge 3 ] && [ "$i" -ge "$min" ]; then echo "Sistema estable: $f"; return 0; fi
    fi
    sleep 2
  done
  echo "AVISO: el sistema no se estabilizó; se sigue igual"
}
failure_diag() {
  echo "== Diagnóstico =="; diag "al fallar"
  anrs
  timeout 30 adb logcat -d -b events 2>/dev/null | grep -E 'input_focus|input_interaction|am_anr|am_crash' | tail -n 60
  timeout 30 adb logcat -d 2>/dev/null | grep -iE 'ANR in|not responding|FATAL EXCEPTION|untrusted touch|trazo' | tail -n 60
  timeout 30 adb exec-out screencap -p > "$OUT/fallo-pantalla-$SUF.png" 2>/dev/null || true
  timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml | head -c 4000; echo
}

{
  echo "Fecha: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Modelo: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "Android: $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "Pantalla: $(adb shell wm size | tr -d '\r') / $(adb shell wm density | tr -d '\r')"
  echo "Tipo: EMULADOR (eventos de lápiz sintetizados; no es S Pen físico)"
} | tee "$OUT/dispositivo-$SUF.txt"

echo "== Estado del sistema =="
anrs
settle 10
diag "antes de instalar"

adb install -r -t "$APKS/Trazo-debug.apk" || exit 1
adb install -r -t "$APKS/Trazo-debug-androidTest.apk" || exit 1

# Sin red: las funciones centrales deben operar offline (T21).
adb shell svc wifi disable || true
adb shell svc data disable || true

if has pruebas; then
echo "== Pruebas instrumentadas =="
diag "antes de pruebas"
adb shell am instrument -w -e class io.trazo.app.EditorInstrumentedTest,io.trazo.app.ExportInstrumentedTest "$RUNNER" | tee "$OUT/pruebas-instrumentadas.txt"
diag "tras pruebas"
fi

# Capturas de la interfaz (lista, editor, paneles, biblioteca, preferencias, tema oscuro). Bloquea:
# si un control no aparece, la interfaz cambió o se rompió y la APK no se da por validada. Va antes
# de la demo grabada, con el emulador fresco: después de grabar el emulador llegó a cerrarse solo.
if has interfaz; then
echo "== Recorrido de la interfaz =="
timeout 20 adb shell am force-stop io.trazo || true
settle 1
# El logcat corre en el host durante el recorrido: si el emulador se cae, la causa queda guardada.
timeout 480 adb logcat -v time > "$OUT/logcat-interfaz.txt" 2>&1 &
LOGCAT=$!
timeout 420 adb shell am instrument -w -e class io.trazo.app.GuiTourTest#tourOfTheInterface "$RUNNER" | tee "$OUT/interfaz.txt"
kill $LOGCAT 2>/dev/null || true
# Si el emulador se cae, cada `adb` queda «waiting for device» hasta que se cancela el job y no
# se sube nada: de acá en adelante todo con tope y, sin dispositivo, se informa y se termina.
if [ "$(timeout 20 adb get-state 2>/dev/null)" != "device" ]; then
  echo "FALLÓ: se perdió el emulador durante el recorrido de la interfaz" | tee -a "$OUT/interfaz.txt"
  grep -iE 'FATAL|ANR in|lowmemorykiller|Out of memory|Killing' "$OUT/logcat-interfaz.txt" | tail -n 40
  tail -n 40 "$OUT/logcat-interfaz.txt"
  exit 1
fi
for f in $(timeout 20 adb shell ls /data/local/tmp 2>/dev/null | tr -d '\r' | grep -E '^trazo-gui-.*[.]png$'); do
  timeout 30 adb pull "/data/local/tmp/$f" "$OUT/$f" || true
done

# El selector de color va en su propia corrida: llegó a tumbar el emulador, y así las capturas del
# recorrido ya están bajadas si vuelve a pasar.
echo "== Selector de color =="
timeout 20 adb shell am force-stop io.trazo || true
settle 1
timeout 200 adb logcat -v time > "$OUT/logcat-selector-color.txt" 2>&1 &
LOGCAT=$!
timeout 180 adb shell am instrument -w -e class io.trazo.app.GuiTourTest#colorPicker "$RUNNER" | tee "$OUT/selector-color.txt"
kill $LOGCAT 2>/dev/null || true
if [ "$(timeout 20 adb get-state 2>/dev/null)" != "device" ]; then
  echo "FALLÓ: se perdió el emulador al abrir el selector de color" | tee -a "$OUT/selector-color.txt"
  grep -iE 'FATAL|ANR in|lowmemorykiller|Out of memory|Killing|SIGSEGV|SIGABRT' "$OUT/logcat-selector-color.txt" | tail -n 40
  tail -n 60 "$OUT/logcat-selector-color.txt"
  exit 1
fi
timeout 30 adb pull /data/local/tmp/trazo-gui-13-selector-color.png "$OUT/trazo-gui-13-selector-color.png" || true
fi

if has demo; then
echo "== Demo grabada =="
timeout 20 adb shell am force-stop io.trazo || true
settle 1
adb shell rm -f /sdcard/trazo-demo.mp4
adb shell screenrecord --bit-rate 6000000 --time-limit 175 /sdcard/trazo-demo.mp4 &
REC=$!
sleep 3
timeout 600 adb shell am instrument -w -e class io.trazo.app.DemoExerciseTest "$RUNNER" | tee "$OUT/demo.txt"
diag "tras demo"
sleep 2
adb shell pkill -INT screenrecord || true
wait $REC || true
sleep 4
timeout 120 adb pull /sdcard/trazo-demo.mp4 "$OUT/demo-real-android-emulador.mp4" || true
for f in trazo-demo-1-integral trazo-demo-2-integral-resuelta trazo-demo-3-final trazo-demo-4-ensenar trazo-demo-5-aprendido; do
  timeout 30 adb pull "/data/local/tmp/$f.png" "$OUT/$f.png" || true
done

if [ "$(timeout 20 adb get-state 2>/dev/null)" != "device" ]; then
  echo "FALLÓ: se perdió el emulador durante la demo" | tee -a "$OUT/demo.txt"
  exit 1
fi
fi
timeout 60 adb logcat -d -t 2000 > "$OUT/logcat-$SUF.txt" || true

status=0
logs=()
has pruebas && logs+=(pruebas-instrumentadas.txt)
has interfaz && logs+=(interfaz.txt selector-color.txt)
has demo && logs+=(demo.txt)
for f in "${logs[@]}"; do
  if grep -q "FAILURES!!!" "$OUT/$f" || ! grep -q "^OK (" "$OUT/$f"; then
    echo "FALLÓ: $f"; status=1
  fi
done
# Las pruebas pueden decir OK y aun así faltar la evidencia: sin las 13 capturas o sin video la
# APK tampoco se da por validada.
if has interfaz; then
  gui=$(ls "$OUT"/trazo-gui-*.png 2>/dev/null | wc -l)
  [ "$gui" -ge 13 ] || { echo "FALLÓ: hay $gui capturas de la interfaz, se esperaban 13"; status=1; }
fi
if has demo; then
  [ -s "$OUT/demo-real-android-emulador.mp4" ] || { echo "FALLÓ: falta el video de la demo"; status=1; }
fi
[ $status -ne 0 ] && failure_diag
exit $status
