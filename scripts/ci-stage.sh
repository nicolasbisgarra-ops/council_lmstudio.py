#!/usr/bin/env bash
# Arma output/ desde cero con los artifacts de este run (compilación y las dos fases del emulador) y
# le agrega MANIFEST.txt. Nada heredado de la rama entra en la publicación.
# Uso: ci-stage.sh <directorio con build/ emulator-demo/ emulator-ui/> <destino>
set -euo pipefail
bundle="$1"; out="$2"
: "${GITHUB_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
rm -rf "$out"
mkdir -p "$out/jvm" "$out/emulator/pruebas-demo" "$out/emulator/interfaz"
b="$bundle/build/output"
cp "$b/Trazo-debug.apk" "$b/Trazo-debug-androidTest.apk" "$b/SHA256SUMS-apk.txt" \
  "$b/build-provenance.txt" "$b/jvm-provenance.txt" "$out/"
cp -a "$b/jvm/." "$out/jvm/" 2>/dev/null || true
cp -a "$bundle/emulator-demo/." "$out/emulator/pruebas-demo/"
cp -a "$bundle/emulator-ui/." "$out/emulator/interfaz/"
test -f "$out/emulator/pruebas-demo/build-provenance-pruebas-demo.txt"
test -f "$out/emulator/interfaz/build-provenance-interfaz.txt"
mv "$out/emulator/pruebas-demo/demo-real-android-emulador.mp4" "$out/demo-real-android.mp4" 2>/dev/null || true
id=(--source-sha "$GITHUB_SHA" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT")
python3 -I scripts/ci-manifest.py create "$out" "${id[@]}" \
  --phase build=PASS --phase emulator_pruebas_demo=PASS --phase emulator_interfaz=PASS
