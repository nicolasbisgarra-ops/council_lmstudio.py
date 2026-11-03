#!/usr/bin/env python3
"""ELF/ZIP validation fixtures; NOT compiled native engine tests."""
import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('ci_abi_audit',ROOT/'scripts'/'ci-abi-audit.py')
abi=importlib.util.module_from_spec(spec)
spec.loader.exec_module(abi)


def elf(cls, machine):
    header=bytearray(64); header[:4]=b'\x7fELF'; header[4]=cls; header[5]=1; header[6]=1
    header[18:20]=struct.pack('<H',machine)
    return bytes(header)


class AbiTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.apk=Path(self.tmp.name)/'app-debug.apk'

    def make(self, pairs):
        with zipfile.ZipFile(self.apk,'w') as z:
            for name,data in pairs: z.writestr(name,data)

    def test_pure_java_apk_ok(self):
        self.make([('classes.dex',b'dex')]); self.assertEqual(abi.audit(self.apk),{})
    def test_valid_arm64_and_x86_64(self):
        self.make([('lib/arm64-v8a/libengine.so',elf(2,183)),('lib/x86_64/libengine.so',elf(2,62))]);
        self.assertEqual(len(abi.audit(self.apk,'arm64-v8a','libengine.so')),2)
    def test_missing_required_abi(self):
        self.make([('classes.dex',b'dex')]);
        with self.assertRaisesRegex(ValueError,'ABI obligatoria'):abi.audit(self.apk,'arm64-v8a')
    def test_missing_required_library(self):
        self.make([('lib/arm64-v8a/libx.so',elf(2,183))]);
        with self.assertRaisesRegex(ValueError,'falta biblioteca'):abi.audit(self.apk,'arm64-v8a','libengine.so')
    def test_directory_zip_entries_are_accepted(self):
        self.make([('lib/',b''),('lib/arm64-v8a/',b''),('lib/arm64-v8a/libx.so',elf(2,183))])
        self.assertEqual(len(abi.audit(self.apk)),1)
    def test_wrong_machine(self):
        self.make([('lib/arm64-v8a/libengine.so',elf(2,62))]);
        with self.assertRaisesRegex(ValueError,'no coincide'):abi.audit(self.apk)
    def test_bad_elf(self):
        self.make([('lib/arm64-v8a/libengine.so',b'not an elf header')]);
        with self.assertRaisesRegex(ValueError,'fuera de límites|inválido'):abi.audit(self.apk)
    def test_zip_path_traversal(self):
        self.make([('../lib/arm64-v8a/libevil.so',elf(2,183))]);
        with self.assertRaisesRegex(ValueError,'insegura'):abi.audit(self.apk)
    def test_duplicate_lib(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            self.make([('lib/arm64-v8a/libx.so',elf(2,183)),('lib/arm64-v8a/libx.so',elf(2,183))])
        with self.assertRaisesRegex(ValueError,'duplicada'):abi.audit(self.apk)
    def test_symlink_in_apk_rejected(self):
        with zipfile.ZipFile(self.apk,'w') as z:
            entry=zipfile.ZipInfo('lib/arm64-v8a/libx.so')
            entry.create_system=3
            entry.external_attr=(0o120777 << 16)
            z.writestr(entry,elf(2,183))
        with self.assertRaisesRegex(ValueError,'enlaces simbólicos'):abi.audit(self.apk)
    def test_fake_zip(self):
        self.apk.write_bytes(b'not zip');
        with self.assertRaisesRegex(ValueError,'APK ZIP inválido'):abi.audit(self.apk)
    def test_wrong_abi(self):
        self.make([('lib/sparc/libx.so',elf(2,183))]);
        with self.assertRaisesRegex(ValueError,'ABI no admitida'):abi.audit(self.apk)
    def test_zip_declared_size_excess(self):
        self.make([('lib/arm64-v8a/libhuge.so',elf(2,183))]);
        # No allocation: modify ZipInfo fixture in memory by patching threshold.
        saved=abi.MAX_SINGLE_LIBRARY
        try:
            abi.MAX_SINGLE_LIBRARY=32
            with self.assertRaisesRegex(ValueError,'fuera de límites'): abi.audit(self.apk)
        finally: abi.MAX_SINGLE_LIBRARY=saved

if __name__=='__main__':unittest.main()
