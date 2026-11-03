#!/usr/bin/env python3
"""Static validation of ELF libraries inside Android APKs, no Android SDK needed.

Run after assembleDebug and before staging; standard library only. Without
--require-* this audits libraries present, not proof of llama.cpp integration.
When Agent 1 agrees on the native library name, enable --require-arm64 and
--require-library for the application APK (NOT the androidTest APK).
"""
from __future__ import annotations

import argparse
import re
import stat
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath

# abi -> (ELF class, e_machine)
ABI = {
    "arm64-v8a": (2, 183), "armeabi-v7a": (1, 40),
    "x86_64": (2, 62), "x86": (1, 3), "riscv64": (2, 243),
}
MAX_SINGLE_LIBRARY = 200 * 1024 * 1024
MAX_TOTAL_NATIVE = 400 * 1024 * 1024


def audit(path: Path, required_abi: str | None = None, required_library: str | None = None):
    if not path.is_file():
        raise ValueError(f"APK faltante: {path}")
    libraries: dict[tuple[str, str], str] = {}
    seen = set()
    total = 0
    try:
        with zipfile.ZipFile(path, 'r') as z:
            for info in z.infolist():
                # Reject unsafe or ambiguous library entries; do not try to extract.
                p = PurePosixPath(info.filename)
                if p.is_absolute() or '..' in p.parts or '\\' in info.filename:
                    raise ValueError(f"ruta ZIP insegura: {info.filename}")
                if stat.S_ISLNK(info.external_attr >> 16):
                    raise ValueError(f'ZIP no admite enlaces simbólicos: {info.filename}')
                if info.is_dir():
                    continue
                if info.filename in seen:
                    raise ValueError(f"entrada ZIP duplicada: {info.filename}")
                seen.add(info.filename)
                if len(p.parts) < 2 or p.parts[0] != 'lib':
                    continue
                if len(p.parts) != 3 or not re.fullmatch(r'lib[^/]+\.so', p.name):
                    raise ValueError(f"ruta de biblioteca nativa inválida: {info.filename}")
                if p.parts[1] not in ABI:
                    raise ValueError(f"ABI no admitida: {info.filename}")
                if info.file_size < 20 or info.file_size > MAX_SINGLE_LIBRARY:
                    raise ValueError(f"tamaño ELF fuera de límites: {info.filename}")
                total += info.file_size
                if total > MAX_TOTAL_NATIVE:
                    raise ValueError("bibliotecas nativas superan 400 MiB")
                with z.open(info) as file:
                    head = file.read(20)
                if head[:4] != b'\x7fELF' or head[5] != 1 or head[6] != 1:
                    raise ValueError(f"encabezado ELF inválido: {info.filename}")
                expected_class, expected_machine = ABI[p.parts[1]]
                if head[4] != expected_class or struct.unpack('<H', head[18:20])[0] != expected_machine:
                    raise ValueError(f"ELF no coincide con ABI {p.parts[1]}: {info.filename}")
                libraries[(p.parts[1], p.name)] = info.filename
    except (zipfile.BadZipFile, EOFError, RuntimeError) as exc:
        raise ValueError(f"APK ZIP inválido: {path}: {exc}") from exc
    if required_abi and not any(abi == required_abi for abi, _ in libraries):
        raise ValueError(f"no hay bibliotecas para ABI obligatoria {required_abi}")
    if required_library:
        if required_abi:
            expected = (required_abi, required_library)
            if expected not in libraries:
                raise ValueError(f"falta biblioteca obligatoria {expected}")
        elif not any(name == required_library for _, name in libraries):
            raise ValueError(f"falta biblioteca obligatoria {required_library}")
    return libraries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('apks', nargs='+', type=Path)
    parser.add_argument('--require-abi', choices=tuple(ABI))
    parser.add_argument('--require-library')
    args = parser.parse_args()
    try:
        for index, apk in enumerate(args.apks):
            # Explicit required ABI/lib apply to first APK only: test APK may be Java-only.
            libs = audit(apk, args.require_abi if index == 0 else None,
                         args.require_library if index == 0 else None)
            print(f'OK: {apk.name}, {len(libs)} bibliotecas ELF validadas')
    except (ValueError, OSError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
