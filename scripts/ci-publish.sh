#!/usr/bin/env bash
# Publica en la rama un output/ completo y ya validado (con MANIFEST.txt) en un único commit.
# Cada intento parte de la punta remota y reemplaza output/ entero: sin rebase ni force push, y sin
# mezclar evidencias de otro run. Uso: ci-publish.sh <rama> <dir output validado> <mensaje>
set -euo pipefail
branch="$1"; src="$2"; msg="$3"
: "${GITHUB_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
[[ "$msg" == *"[skip ci]"* ]] || { echo "el mensaje debe llevar [skip ci]" >&2; exit 2; }
git config user.name "trazo-ci"
git config user.email "trazo-ci@users.noreply.github.com"
# La copia vive fuera del árbol de trabajo: cada reintento publica exactamente lo mismo.
payload="$(mktemp -d)"
trap 'rm -rf "$payload"' EXIT
cp -a "$src/." "$payload/"
python3 -I scripts/ci-manifest.py verify "$payload" \
  --source-sha "$GITHUB_SHA" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT"
# GitHub rechaza archivos de más de 100 MiB.
big="$(find "$payload" -type f -size +90M -print -quit)"
[[ -z "$big" ]] || { echo "archivo de más de 90 MiB: $big" >&2; exit 1; }
(( $(du -sb "$payload" | cut -f1) <= 419430400 )) || { echo "la publicación supera 400 MiB" >&2; exit 1; }
skip() { echo "::warning::No se publica en $branch: $1"; exit 0; }
for i in 1 2 3 4; do
  git fetch -q origin "refs/heads/$branch"
  tip="$(git rev-parse FETCH_HEAD)"
  if manifest="$(git show "$tip:output/MANIFEST.txt" 2>/dev/null)"; then
    rid="$(sed -n 's/^run_id=//p' <<<"$manifest" | head -n1)"
    rat="$(sed -n 's/^attempt=//p' <<<"$manifest" | head -n1)"
    rsha="$(sed -n 's/^source_sha=//p' <<<"$manifest" | head -n1)"
    if [[ "$rid" =~ ^[0-9]+$ && "$rat" =~ ^[0-9]+$ ]]; then
      if (( rid > GITHUB_RUN_ID || (rid == GITHUB_RUN_ID && rat > GITHUB_RUN_ATTEMPT) )); then
        skip "ya está publicado el run $rid/$rat, más nuevo"
      fi
      # Un push anterior que llegó aunque su respuesta se perdió.
      if (( rid == GITHUB_RUN_ID && rat == GITHUB_RUN_ATTEMPT )) && [[ "$rsha" == "$GITHUB_SHA" ]]; then
        echo "Este run ya está publicado en $branch"; exit 0
      fi
    fi
  fi
  # Si la rama avanzó con código nuevo, estas evidencias ya no describen la punta.
  git merge-base --is-ancestor "$GITHUB_SHA" "$tip" || skip "el commit $GITHUB_SHA ya no está en la rama"
  git diff --quiet "$GITHUB_SHA" "$tip" -- . ':(exclude)output' || skip "la rama tiene código posterior a $GITHUB_SHA"
  git reset -q --hard "$tip"
  git rm -r -q --cached --ignore-unmatch -- output
  rm -rf output
  mkdir -p output
  cp -a "$payload/." output/
  git add -f -A -- output
  git diff --cached --quiet && { echo "Nada que publicar"; exit 0; }
  git commit -q -m "$msg"
  if git push -q origin "HEAD:refs/heads/$branch"; then
    echo "Publicado en $branch: $(git rev-parse --short HEAD)"; exit 0
  fi
  sleep $((i * 5))
done
echo "::error::No se pudo publicar en $branch tras 4 intentos"
exit 1
