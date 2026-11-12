#!/usr/bin/env bash
# Rearma docs/alineado/CODIGO_FUENTE.txt con el código actual del auto-alineado.
# Uso: docs/alineado/generar_fuente.sh   (desde la raíz del repo)
set -euo pipefail
out=docs/alineado/CODIGO_FUENTE.txt
core=core/src/main/kotlin/io/trazo/core
app=app/src/main/kotlin/io/trazo/app
tests=core/src/test/kotlin/io/trazo/core

entero() { # archivo completo, con números de línea
  printf '\n\n%s\n== %s  (completo)\n%s\n' "$(printf '=%.0s' {1..100})" "$1" "$(printf '=%.0s' {1..100})"
  cat -n "$1"
}
parte() { # archivo, desde, hasta, título
  printf '\n\n%s\n== %s  líneas %s-%s  ·  %s\n%s\n' "$(printf '=%.0s' {1..100})" "$1" "$2" "$3" "$4" "$(printf '=%.0s' {1..100})"
  awk -v a="$2" -v b="$3" 'NR>=a && NR<=b { printf "%6d\t%s\n", NR, $0 }' "$1"
}
bloque() { # archivo, patrón de inicio, patrón de fin (línea que cierra), título
  local a b
  a=$(grep -n -m1 -E "$2" "$1" | cut -d: -f1)
  b=$(awk -v a="$a" -v re="$3" 'NR>a && $0 ~ re { print NR; exit }' "$1")
  parte "$1" "$a" "$b" "$4"
}
buscar() { # archivo, patrón: las líneas donde aparece
  printf '\n\n%s\n== %s  ·  líneas que nombran el alineado\n%s\n' "$(printf '=%.0s' {1..100})" "$1" "$(printf '=%.0s' {1..100})"
  grep -n -E "$2" "$1" || true
}

{
  echo "TRAZO - AUTO-ALINEADO: CÓDIGO FUENTE"
  echo "Generado con docs/alineado/generar_fuente.sh desde el commit $(git rev-parse --short HEAD)."
  echo "Las reglas en texto plano están en REGLAS.txt. No editar este archivo: se regenera."
  echo
  echo "ÍNDICE"
  echo " 1. AutoAlign.kt            el alineado (reglas 2 a 6)"
  echo " 2. PaperRows.kt            renglones del papel (regla 7)"
  echo " 3. InkController.kt        dónde se llama al convertir (regla 1)"
  echo " 4. Patterns.kt             datos del patrón, núcleo y dibujo de la salida (regla 8)"
  echo " 5. RootFit.kt              la raíz sigue el trazo (regla 8.6)"
  echo " 6. Otros que usan floor() y los signos: TexInk.kt, MathNotes.kt (regla 9)"
  echo " 7. La app: interruptor, ajuste guardado y línea de piso del editor de patrones (regla 9)"
  echo " 8. Pruebas: AutoAlignTest.kt, AutoAlignBracketTest.kt"

  entero $core/AutoAlign.kt
  entero $core/PaperRows.kt
  buscar $core/InkController.kt 'autoAlign|AutoAlign|alignedAnchor|PaperRows|outputFor'
  bloque $core/InkController.kt 'private fun outputFor' '^    }' 'outputFor: raíz o plantilla'
  bloque $core/InkController.kt 'fun convertWith' '^    }' 'convertWith: conversión elegida a mano'
  bloque $core/InkController.kt 'private fun alignedAnchor' '^    }' 'alignedAnchor: el orden de decisión'
  bloque $core/InkController.kt 'private fun convert\(r: Recognition.Convert\)' '^    }' 'convert: conversión automática'
  bloque $core/Patterns.kt '^data class InkPattern' '^\)' 'InkPattern: los datos de un patrón'
  bloque $core/Patterns.kt '^object PatternCore' '^}' 'PatternCore: el núcleo'
  bloque $core/Patterns.kt '^object InkReplacement' '^}' 'InkReplacement: dibujo de la salida en el anclaje'
  entero $core/RootFit.kt
  buscar $core/math/tex/TexInk.kt 'AutoAlign'
  bloque $core/math/tex/TexInk.kt 'private fun glyph\(' '^    }' 'glyph: letras y cifras en las fórmulas del tutor'
  buscar $core/MathNotes.kt 'AutoAlign'
  buscar $app/data/SettingsStore.kt 'autoAlign'
  buscar $app/ui/EditorViewModel.kt 'autoAlign'
  buscar $app/ui/EditorToolbar.kt 'autoAlign|TrazoIcons.Align'
  buscar $app/ui/EditorScreen.kt 'autoAlign'
  buscar $app/ui/SettingsScreen.kt 'autoAlign'
  buscar $app/ui/PatternScreens.kt 'AutoAlign|vm\.floor|customFloor'
  buscar $app/ui/PatternEditorViewModel.kt 'AutoAlign|floor'
  entero $tests/AutoAlignTest.kt
  entero $tests/AutoAlignBracketTest.kt
} > "$out"
echo "$out: $(wc -l < "$out") líneas"
