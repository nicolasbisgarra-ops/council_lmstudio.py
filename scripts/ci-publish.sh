#!/usr/bin/env bash
# Publica en la rama del run los archivos dados (las APK están en .gitignore: se fuerzan).
# Uso: ci-publish.sh <rama> <mensaje> <ruta>...
set -euo pipefail
branch="$1"; msg="$2"; shift 2
git config user.name "trazo-ci"
git config user.email "trazo-ci@users.noreply.github.com"
for p in "$@"; do
  if [ -e "$p" ]; then git add -f "$p"; fi
done
git diff --cached --quiet && { echo "Nada que publicar"; exit 0; }
git commit -q -m "$msg"
# Otra publicación (la de la compilación o un push nuevo) pudo adelantar la rama: se rebasa encima.
for i in 1 2 3 4; do
  git fetch -q origin "$branch" && git rebase -q FETCH_HEAD && git push origin "HEAD:refs/heads/$branch" && exit 0
  git rebase --abort 2>/dev/null || true
  sleep $((i * 5))
done
echo "No se pudo publicar en $branch"; exit 1
