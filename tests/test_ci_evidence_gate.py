#!/usr/bin/env python3
"""Adversarial artifact tests: no emulator, network, tokens, or mocked success claims."""
from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ci_evidence_gate', ROOT/'scripts'/'ci-evidence-gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def chunk(name: bytes, data: bytes) -> bytes:
    return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data))


def sample_png() -> bytes:
    ihdr = struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)
    pixels = zlib.compress(b'\x00\x00\x00\x00\xff')
    return gate.PNG_MAGIC + chunk(b'IHDR', ihdr) + chunk(b'IDAT', pixels) + chunk(b'IEND', b'')


PNG = sample_png()
MP4 = struct.pack('>I', 24) + b'ftypisom\0\0\0\0isommp42' + b'\0\0\0\x08free'


class EvidenceGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name)/'output'
        self.out.mkdir()
        self.put('Trazo-debug.apk', b'apk')
        self.put('Trazo-debug-androidTest.apk', b'testapk')
        self.put('SHA256SUMS-apk.txt', b'sums')
        self.put('MANIFEST.txt', b'manifest')
        self.put('build-provenance.txt', b'identity')
        self.put('demo-real-android.mp4', MP4)
        self.put('jvm-provenance.txt', 'tests_started_at=2026-10-30T00:00:00Z\n'
                 'tests_finished_at=2026-10-30T00:01:00Z\n'
                 'task :core:test\ntask :ai:test\ntask :app:testDebugUnitTest\n')
        for rel in ('emulator/pruebas-demo/pruebas-instrumentadas.txt',
                    'emulator/pruebas-demo/demo.txt', 'emulator/interfaz/interfaz.txt',
                    'emulator/interfaz/selector-color.txt'):
            self.put(rel, 'INSTRUMENTATION_STATUS: 0\nOK (1 test)\ninstrumentation exit_status=0\n')
        section = []
        for name in gate.FORMATS:
            entry = f'-- {name}: 123 @ 456 dpi\nINSTRUMENTATION_STATUS: 0\nOK (1 test)\nformato exit_status=0\n'
            section.append(entry)
            self.put(f'emulator/interfaz/formato-{name}.txt', entry)
            for view in gate.VIEWS:
                self.put(f'emulator/interfaz/trazo-formato-{name}-{view}.png', PNG)
        self.put('emulator/interfaz/formatos.txt', ''.join(section))
        for stem in gate.GUI:
            self.put(f'emulator/interfaz/trazo-gui-{stem}.png', PNG)
        for stem in gate.DEMO:
            self.put(f'emulator/pruebas-demo/{stem}.png', PNG)

    def put(self, relative, data):
        path = self.out/relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data.encode('utf-8') if isinstance(data, str) else data)

    def fails(self, message):
        with self.assertRaisesRegex(ValueError, message):
            gate.validate(self.out)

    def test_clean_complete_fixture(self): gate.validate(self.out)
    def test_video_absent(self):
        (self.out/'demo-real-android.mp4').unlink(); self.fails('demo-real-android.mp4')
    def test_video_fake(self):
        self.put('demo-real-android.mp4', b'not an MP4 file'); self.fails('video MP4')
    def test_missing_gui(self):
        (self.out/'emulator/interfaz/trazo-gui-13-selector-color.png').unlink(); self.fails('13-selector')
    def test_missing_form_png(self):
        (self.out/'emulator/interfaz/trazo-formato-pc-3-pluma.png').unlink(); self.fails('formato-pc')
    def test_png_only_signature_rejected(self):
        self.put('emulator/interfaz/trazo-gui-1-cuadernos.png', gate.PNG_MAGIC); self.fails('IHDR')
    def test_png_wrong_ihdr_crc_rejected(self):
        self.put('emulator/interfaz/trazo-gui-2-editor.png', PNG[:29]+b'zzzz'+PNG[33:]); self.fails('CRC')
    def test_missing_demo_png(self):
        (self.out/'emulator/pruebas-demo/trazo-demo-5-aprendido.png').unlink(); self.fails('aprendido')
    def test_failing_gui_instrumentation(self):
        self.put('emulator/interfaz/interfaz.txt', 'FAILURES!!!\n'); self.fails('instrumentación')
    def test_instrumentation_false_ok(self):
        self.put('emulator/pruebas-demo/demo.txt', 'INSTRUMENTATION_FAILED: crash\nOK (2 tests)\n'); self.fails('instrumentación')
    def test_general_rc_nonzero_even_with_ok(self):
        self.put('emulator/pruebas-demo/demo.txt', 'OK (1 test)\ninstrumentation exit_status=124\n')
        self.fails('status no cero')
    def test_general_missing_status_receipt(self):
        self.put('emulator/pruebas-demo/pruebas-instrumentadas.txt','OK (1 test)\n')
        self.fails('status no cero')
    def test_jvm_from_cache(self):
        p=self.out/'jvm-provenance.txt'; self.put('jvm-provenance.txt', p.read_text().replace('task :core:test\n', 'task :core:test FROM-CACHE\n')); self.fails('JVM')
    def test_jvm_missing_task(self):
        p=self.out/'jvm-provenance.txt'; self.put('jvm-provenance.txt', p.read_text().replace('task :ai:test\n','')); self.fails('JVM')
    def test_jvm_duplicate_task(self):
        p=self.out/'jvm-provenance.txt'; self.put('jvm-provenance.txt', p.read_text()+'task :core:test\n'); self.fails('JVM')
    def test_format_no_status_receipt(self):
        p=self.out/'emulator/interfaz/formato-pc.txt'; self.put(str(p.relative_to(self.out)), p.read_text().replace('formato exit_status=0\n','')); self.fails('status')
    def test_format_nonzero_status_even_with_ok(self):
        for rel in ('emulator/interfaz/formato-tableta.txt','emulator/interfaz/formatos.txt'):
            p=self.out/rel; self.put(rel, p.read_text().replace('formato exit_status=0','formato exit_status=124',1 if rel.endswith('tableta.txt') else 3))
        self.fails('status')
    def test_format_fault_with_pngs(self):
        p=self.out/'emulator/interfaz/formatos.txt'; self.put(str(p.relative_to(self.out)), p.read_text()+'FAILURES!!!\n'); self.fails('falla')
    def test_format_missing_individual_log(self):
        (self.out/'emulator/interfaz/formato-tableta-horizontal.txt').unlink(); self.fails('formato-tableta-horizontal')
    def test_format_duplicate_section(self):
        p=self.out/'emulator/interfaz/formatos.txt'; self.put(str(p.relative_to(self.out)),p.read_text()+'-- pc: 123 @ 456 dpi\nOK (1 test)\n'); self.fails('duplicado')
    def test_format_missing_section(self):
        p=self.out/'emulator/interfaz/formatos.txt'; self.put(str(p.relative_to(self.out)),p.read_text().split('-- pc:')[0]); self.fails('faltan formatos')
    def test_format_missing_ok(self):
        p=self.out/'emulator/interfaz/formato-pc.txt'; self.put(str(p.relative_to(self.out)),p.read_text().replace('OK (1 test)','')); self.fails('instrumentación')
    def test_corrupt_empty_log(self):
        self.put('emulator/interfaz/selector-color.txt',b''); self.fails('vacía')

if __name__=='__main__': unittest.main()
