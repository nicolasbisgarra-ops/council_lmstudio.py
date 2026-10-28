#!/usr/bin/env bash
# Publica en la rama del run los archivos dados (las APK están en .gitignore: se fuerzan).
# Uso: ci-publish.sh <rama> <mensaje> <ruta>...
set -euo pipefail
branch="$1"; msg="$2"; shift 2
git config user.name "trazo-ci"
git config user.email "trazo-ci@users.noreply.github.com"
fail() {
  echo "::warning::No se pudo publicar en $branch: $1"
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "**Publicación fallida en \`$branch\`:** $1" >> "$GITHUB_STEP_SUMMARY"
  exit 1
}
for p in "$@"; do
  # -A también registra lo que el workflow borró (rm -rf output): sin eso queda como cambio sin
  # preparar y el rebase se niega («You have unstaged changes»).
  if [ -e "$p" ] || git ls-files --error-unmatch -- "$p" >/dev/null 2>&1; then git add -f -A -- "$p"; fi
done
git diff --cached --quiet && { echo "Nada que publicar"; exit 0; }
git commit -q -m "$msg"
# Lo que quede fuera de las rutas publicadas (evidencias de otro trabajo borradas al limpiar) no se
# publica ni debe frenar el rebase.
git reset -q --hard HEAD
# Otra publicación (la de la compilación o un push nuevo) pudo adelantar la rama: se rebasa encima.
for i in 1 2 3 4; do
  if git fetch -q origin "$branch" && git rebase -q FETCH_HEAD && git push -q origin "HEAD:refs/heads/$branch"; then
    echo "Publicado en $branch: $(git rev-parse --short HEAD)"; exit 0
  fi
  git rebase --abort 2>/dev/null || true
  sleep $((i * 5))
done
fail "el rebase o el push fallaron 4 veces"
