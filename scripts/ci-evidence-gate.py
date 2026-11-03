#!/usr/bin/env python3
"""Mandatory Trazo Android artifact release gate.

Complements ci-manifest.py's identity + hashes. Assumes QA59's per-form-factor
exit receipts are emitted by ci-emulator.sh. Runs in BOTH stage and publish to
block incomplete releases. Only Python stdlib, no model downloads or network.
"""
from __future__ import annotations

import re
import struct
import sys
import zlib
from pathlib import Path

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
FORMATS = ("telefono", "telefono-horizontal", "tableta", "tableta-horizontal", "pc")
VIEWS = ("1-cuadernos", "2-editor", "3-pluma")
GUI = (
    "1-cuadernos", "2-editor", "3-pluma", "4-goma", "5-color",
    "6-insertar", "7-biblioteca", "8-patron", "9-preferencias",
    "10-oscuro", "11-oscuro-editor", "12-oscuro-cuadernos",
    "13-selector-color",
)
DEMO = (
    "trazo-demo-1-integral", "trazo-demo-2-integral-resuelta",
    "trazo-demo-3-final", "trazo-demo-4-ensenar", "trazo-demo-5-aprendido",
)
JVM_TASKS = (":core:test", ":ai:test", ":app:testDebugUnitTest")
ERROR_PATTERN = re.compile(
    r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT:\s*shortMsg|"
    r"Process crashed|\bFATAL EXCEPTION\b|\bINSTRUMENTATION_CODE:\s*-[0-9]+\b",
    re.IGNORECASE,
)
OK_PATTERN = re.compile(r"(?m)^OK \([1-9][0-9]* tests?\)\s*$")
STATUS_PATTERN = re.compile(r"(?m)^formato exit_status=([0-9]+)$")
GENERAL_STATUS_PATTERN = re.compile(r"(?m)^instrumentation exit_status=([0-9]+)$")


def required(root: Path, relative: str) -> Path:
    path = root / relative
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"evidencia faltante o vacía: {relative}")
    return path


def valid_png(root: Path, relative: str) -> None:
    path = required(root, relative)
    with path.open("rb") as stream:
        # Validate signature, IHDR size, dimensions and CRC. A truncated image or
        # a PNG-signature-only file is not evidence of a rendered screenshot.
        header = stream.read(33)
    if len(header) != 33 or header[:8] != PNG_MAGIC or header[8:16] != b"\0\0\0\rIHDR":
        raise ValueError(f"captura sin cabecera PNG IHDR: {relative}")
    width, height = struct.unpack(">II", header[16:24])
    if not (1 <= width <= 32768 and 1 <= height <= 32768):
        raise ValueError(f"dimensiones PNG inválidas: {relative}")
    expected_crc = struct.unpack(">I", header[29:33])[0]
    if zlib.crc32(header[12:29]) != expected_crc:
        raise ValueError(f"CRC PNG IHDR inválido: {relative}")
    if path.stat().st_size <= 45:
        raise ValueError(f"captura PNG truncada: {relative}")


def valid_mp4(root: Path, relative: str) -> None:
    path = required(root, relative)
    with path.open("rb") as stream:
        head = stream.read(16)
    if len(head) < 16 or head[4:8] != b"ftyp" or path.stat().st_size <= 24:
        raise ValueError(f"video MP4 sin encabezado: {relative}")


def log_ok(root: Path, relative: str, *, expected_ok: int = 1) -> None:
    content = required(root, relative).read_text(encoding="utf-8")
    if (ERROR_PATTERN.search(content) or len(OK_PATTERN.findall(content)) != expected_ok
            or GENERAL_STATUS_PATTERN.findall(content) != ["0"]):
        raise ValueError(f"instrumentación no aprobada/status no cero: {relative}")


def formats_ok(root: Path) -> None:
    text = required(root, "emulator/interfaz/formatos.txt").read_text(encoding="utf-8")
    if ERROR_PATTERN.search(text):
        raise ValueError("formatos.txt contiene una falla de instrumentación")
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        match = re.match(r"^-- ([\w-]+): ", line)
        if match:
            current = match.group(1)
            if current not in FORMATS or current in sections:
                raise ValueError(f"formato desconocido o duplicado: {current}")
            sections[current] = []
        elif current is not None:
            sections[current].append(line)
    if set(sections) != set(FORMATS):
        raise ValueError(f"faltan formatos: {sorted(set(FORMATS) - set(sections))}")
    for name in FORMATS:
        section = "\n".join(sections[name])
        if len(OK_PATTERN.findall(section)) != 1:
            raise ValueError(f"formato sin un único OK: {name}")
        receipt = f"emulator/interfaz/formato-{name}.txt"
        content = required(root, receipt).read_text(encoding="utf-8")
        if ERROR_PATTERN.search(content) or len(OK_PATTERN.findall(content)) != 1:
            raise ValueError(f"instrumentación no aprobada: {receipt}")
        statuses = STATUS_PATTERN.findall(content)
        if statuses != ["0"] or STATUS_PATTERN.findall(section) != ["0"]:
            raise ValueError(f"instrumentación con status desconocido/no cero: {name}")


def jvm_fresh(root: Path) -> None:
    text = required(root, "jvm-provenance.txt").read_text(encoding="utf-8")
    for label in ("tests_started_at=", "tests_finished_at="):
        if not any(line.startswith(label) and len(line) > len(label) for line in text.splitlines()):
            raise ValueError(f"marca de pruebas JVM faltante: {label}")
    for name in JVM_TASKS:
        lines = [line for line in text.splitlines() if re.match(rf"^task {re.escape(name)}(?:\s|$)", line)]
        if len(lines) != 1 or any(tag in lines[0] for tag in ("FROM-CACHE", "UP-TO-DATE", "SKIPPED", "NO-SOURCE")):
            raise ValueError(f"JVM no ejecutó nuevamente {name}")


def validate(root: Path) -> None:
    if not root.is_dir() or root.is_symlink():
        raise ValueError("output inválido")
    for filename in (
        "Trazo-debug.apk", "Trazo-debug-androidTest.apk", "SHA256SUMS-apk.txt",
        "build-provenance.txt", "jvm-provenance.txt", "MANIFEST.txt",
        "emulator/pruebas-demo/pruebas-instrumentadas.txt",
        "emulator/pruebas-demo/demo.txt",
        "emulator/interfaz/interfaz.txt",
        "emulator/interfaz/selector-color.txt",
        "emulator/interfaz/formatos.txt",
    ):
        required(root, filename)
    valid_mp4(root, "demo-real-android.mp4")
    for stem in GUI:
        valid_png(root, f"emulator/interfaz/trazo-gui-{stem}.png")
    for name in FORMATS:
        for view in VIEWS:
            valid_png(root, f"emulator/interfaz/trazo-formato-{name}-{view}.png")
    for stem in DEMO:
        valid_png(root, f"emulator/pruebas-demo/{stem}.png")
    for logfile in (
        "emulator/pruebas-demo/pruebas-instrumentadas.txt",
        "emulator/pruebas-demo/demo.txt",
        "emulator/interfaz/interfaz.txt",
        "emulator/interfaz/selector-color.txt",
    ):
        log_ok(root, logfile)
    formats_ok(root)
    jvm_fresh(root)


def main() -> int:
    if len(sys.argv) != 2:
        print("Uso: ci-evidence-gate.py <output>", file=sys.stderr)
        return 2
    try:
        validate(Path(sys.argv[1]))
    except (ValueError, OSError, UnicodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print("OK: evidencias obligatorias, salidas de instrumentación y JVM frescas")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
