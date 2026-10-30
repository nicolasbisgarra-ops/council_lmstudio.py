#!/usr/bin/env python3
"""Crea y verifica output/MANIFEST.txt: la procedencia y el SHA-256 de cada archivo publicado.

Uso: ci-manifest.py create|verify <dir> --source-sha S --run-id N --attempt N [--phase NOMBRE=PASS]...
"""
import argparse
import hashlib
import re
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

FORMAT = "trazo-ci-manifest-v1"
IDENTITY = ("source_sha", "run_id", "attempt")
PHASES = ("build", "emulator_pruebas_demo", "emulator_interfaz")
APKS = ("Trazo-debug.apk", "Trazo-debug-androidTest.apk")
PROVENANCE = {
    "build-provenance.txt",
    "jvm-provenance.txt",
    "emulator/pruebas-demo/build-provenance-pruebas-demo.txt",
    "emulator/interfaz/build-provenance-interfaz.txt",
}
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def safe(rel):
    p = PurePosixPath(rel)
    return rel and not p.is_absolute() and ".." not in p.parts


def files_under(root):
    out = {}
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            raise ValueError(f"no se admiten enlaces simbólicos: {rel}")
        if path.is_file() and rel != "MANIFEST.txt":
            out[rel] = digest(path)
    return out


def key_values(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            values.setdefault(k, v)
    return values


def create(root, ident, phases):
    if set(phases) != set(PHASES) or any(v != "PASS" for v in phases.values()):
        raise ValueError(f"todas las fases deben ser PASS: {', '.join(PHASES)}")
    files = files_under(root)
    if not files:
        raise ValueError("el directorio está vacío")
    lines = [f"format={FORMAT}", *(f"{k}={ident[k]}" for k in IDENTITY),
             f"fecha={datetime.now(timezone.utc).isoformat(timespec='seconds')}",
             *(f"phase.{p}={phases[p]}" for p in PHASES),
             *(f"sha256 {h} {name}" for name, h in files.items())]
    (root / "MANIFEST.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse(path):
    if not path.is_file():
        raise ValueError("falta MANIFEST.txt")
    fields, hashes = {}, {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("sha256 "):
            parts = line.split(" ", 2)
            if len(parts) != 3 or not HEX64.fullmatch(parts[1]) or not safe(parts[2]) or parts[2] in hashes:
                raise ValueError(f"línea sha256 inválida: {line}")
            hashes[parts[2]] = parts[1]
        elif "=" in line:
            k, v = line.split("=", 1)
            if not k or k in fields:
                raise ValueError(f"clave vacía o repetida: {k}")
            fields[k] = v
        else:
            raise ValueError(f"línea desconocida: {line}")
    required = {"format", "fecha", *IDENTITY, *(f"phase.{p}" for p in PHASES)}
    if set(fields) != required or fields["format"] != FORMAT:
        raise ValueError("claves o formato del manifiesto inválidos")
    datetime.fromisoformat(fields["fecha"].replace("Z", "+00:00"))
    if any(fields[f"phase.{p}"] != "PASS" for p in PHASES):
        raise ValueError("una fase no pasó")
    return fields, hashes


def verify(root, ident):
    fields, listed = parse(root / "MANIFEST.txt")
    for k, v in ident.items():
        if fields[k] != v:
            raise ValueError(f"{k} no coincide: manifiesto={fields[k]} esperado={v}")
    actual = files_under(root)
    if set(actual) != set(listed):
        raise ValueError(f"archivos fuera del manifiesto={sorted(set(actual) - set(listed))}, "
                         f"faltantes={sorted(set(listed) - set(actual))}")
    for name, h in actual.items():
        if listed[name] != h:
            raise ValueError(f"sha256 distinto: {name}")
    # Toda procedencia debe nombrar este mismo commit, run e intento.
    found = {p.relative_to(root).as_posix() for p in root.rglob("*provenance*.txt") if p.is_file()}
    if found != PROVENANCE:
        raise ValueError(f"procedencias esperadas {sorted(PROVENANCE)}, encontradas {sorted(found)}")
    for name in sorted(PROVENANCE):
        values = key_values(root / name)
        for k, v in ident.items():
            key = "run_attempt" if k == "attempt" else k
            if values.get(key) != v:
                raise ValueError(f"{name}: {key} no coincide")
    # Las sumas de las APK coinciden en SHA256SUMS, en la procedencia de la compilación y en los bytes.
    sums = {}
    for line in (root / "SHA256SUMS-apk.txt").read_text(encoding="utf-8").splitlines():
        m = re.fullmatch(r"([0-9a-f]{64})  (?:output/)?(Trazo-debug(?:-androidTest)?\.apk)", line)
        if not m or m.group(2) in sums:
            raise ValueError(f"línea inválida en SHA256SUMS-apk.txt: {line}")
        sums[m.group(2)] = m.group(1)
    built = {}
    for line in (root / "build-provenance.txt").read_text(encoding="utf-8").splitlines():
        m = re.fullmatch(r"sha256 ([0-9a-f]{64})  output/(Trazo-debug(?:-androidTest)?\.apk)", line)
        if m:
            built[m.group(2)] = m.group(1)
    if set(sums) != set(APKS) or built != sums:
        raise ValueError("las sumas de las APK no coinciden entre SHA256SUMS y la procedencia")
    for name in APKS:
        if actual[name] != sums[name]:
            raise ValueError(f"los bytes de {name} no coinciden con SHA256SUMS")
    return len(actual)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=("create", "verify"))
    ap.add_argument("root", type=Path)
    ap.add_argument("--source-sha", required=True)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--attempt", required=True)
    ap.add_argument("--phase", action="append", default=[])
    a = ap.parse_args()
    try:
        root = a.root.resolve(strict=True)
        if not root.is_dir():
            raise ValueError("no es un directorio")
        if not HEX40.fullmatch(a.source_sha) or not a.run_id.isdecimal() or not a.attempt.isdecimal():
            raise ValueError("source_sha, run_id o attempt inválidos")
        ident = {"source_sha": a.source_sha, "run_id": a.run_id, "attempt": a.attempt}
        if a.command == "create":
            phases = {}
            for item in a.phase:
                k, sep, v = item.partition("=")
                if not sep or k in phases:
                    raise ValueError(f"fase inválida o repetida: {item}")
                phases[k] = v
            create(root, ident, phases)
        n = verify(root, ident)
        print(f"OK: {n} archivos; source_sha={a.source_sha} run_id={a.run_id} attempt={a.attempt}")
    except (OSError, ValueError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
