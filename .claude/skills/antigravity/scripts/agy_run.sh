#!/bin/bash
# uso: agy_run.sh <modelo> <esfuerzo> <prompt.txt> [salida.txt] [plan|accept-edits]
set -u
export PATH=$HOME/.local/bin:$PATH
cd "${AGY_DIR:-$(git rev-parse --show-toplevel)}"
out="${4:-/dev/stdout}"
agy -p "$(cat "$3")" --settings "$(dirname "$(readlink -f "$0")")/../permisos.json" --model "$1" --effort "$2" --mode "${5:-plan}" --output-format text >"$out" 2>&1
echo "[agy $1/$2 terminó: $?]" >>"$out"
