#!/usr/bin/env bash
# Compila Trazo en una VM Linux x86_64 con internet (instala JDK 17 y Android SDK si faltan).
#   bash scripts/build-on-vm.sh              → pruebas JVM + output/Trazo-debug.apk
#   bash scripts/build-on-vm.sh --emulator   → además pruebas instrumentadas y demo grabada (requiere /dev/kvm)
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/android-sdk}"

if ! java -version 2>&1 | grep -qE 'version "(17|21)'; then
  sudo apt-get update -q && sudo apt-get install -y -q openjdk-17-jdk-headless unzip curl
fi
command -v unzip >/dev/null || sudo apt-get install -y -q unzip

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
  unzip -q /tmp/clt.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$SDK" > local.properties

./gradlew --console=plain :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -Ptrazo.frames=true
mkdir -p output
cp app/build/outputs/apk/debug/app-debug.apk output/Trazo-debug.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk output/Trazo-debug-androidTest.apk
sha256sum output/*.apk | tee output/SHA256SUMS-apk.txt

if [ "${1:-}" = "--emulator" ]; then
  [ -e /dev/kvm ] || { echo "Sin /dev/kvm: esta VM no puede correr el emulador."; exit 1; }
  IMG="system-images;android-34;google_apis;x86_64"
  sdkmanager --install "emulator" "$IMG"
  echo no | avdmanager create avd -n trazo -k "$IMG" -d pixel_6 --force
  emulator -avd trazo -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -camera-back none &
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global "$k" 0; done
  bash scripts/ci-emulator.sh output output/emulator
  adb emu kill || true
fi
echo "Listo: output/Trazo-debug.apk"
