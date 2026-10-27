# Coordinación entre agentes (Claude y ChatGPT)

Dos asistentes trabajan sobre este repositorio. No se hablan entre sí: se coordinan por git y por este archivo.

## Ramas
- `claude/imolementa-esto-ag7u18`: rama de Claude. La CI publica aquí la APK y las evidencias (commits de `trazo-ci`).
- ChatGPT trabaja en su propia rama (por ejemplo `chatgpt/<tema>`) y abre un PR hacia la rama que indique el usuario. Si necesita tocar la rama de Claude: `git pull --rebase` antes de cada push y nunca `push --force`.

## Reglas
1. Antes de empezar, leer este archivo y `git log` de la rama de destino.
2. Antes de empezar una tarea, anotarla abajo en "En curso" (quién, qué, archivos) y hacer push de ese cambio.
3. No editar archivos que otro tenga anotados en "En curso"; si hace falta, dejar una nota en "Pedidos".
4. Commits chicos y con mensaje claro. `[skip ci]` para documentación; `[publicar]` hace que la CI publique APK y evidencias.
5. Antes de subir código: `./gradlew -Ptrazo.coreOnly=true :core:test` (motor) y que la CI de Android quede verde.
6. Decisiones de diseño nuevas van numeradas en `docs/DECISIONES.md`.

## Mapa del código
- `core/`: motor en Kotlin puro (trazos, patrones, comparador, signos de partida). Pruebas JVM.
- `app/`: app Android (Compose + vista de tinta propia), pruebas instrumentadas en `app/src/androidTest`.
- `scripts/`, `.github/workflows/android.yml`: CI, emulador y publicación.

## En curso
| Quién | Qué | Archivos |
|---|---|---|
| Claude | Revisión de capturas de la interfaz (CI #13) y pulido visual | `app/src/main/kotlin/io/trazo/app/ui/*`, `docs/test-results.md` |

## Pedidos
(vacío)
