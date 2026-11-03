"""Regression checks for the CI wiring in this checkout."""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class IntegrationTests(unittest.TestCase):
    def test_stage_requires_mp4_and_runs_both_release_gates(self):
        text = (ROOT / 'scripts/ci-stage.sh').read_text()
        self.assertIn('mv "$out/emulator/pruebas-demo/demo-real-android-emulador.mp4" "$out/demo-real-android.mp4"', text)
        self.assertIn('python3 -I scripts/ci-abi-audit.py', text)
        self.assertIn('python3 -I scripts/ci-evidence-gate.py "$out"', text)

    def test_publish_runs_gates_before_remote_mutation(self):
        text = (ROOT / 'scripts/ci-publish.sh').read_text()
        self.assertLess(text.index('scripts/ci-evidence-gate.py'), text.index('git fetch -q origin'))
        self.assertIn('scripts/ci-abi-audit.py', text)

    def test_emulator_preserves_all_instrumentation_exit_codes(self):
        text = (ROOT / 'scripts/ci-emulator.sh').read_text()
        self.assertEqual(text.count('instrument_rc=${PIPESTATUS[0]}'), 4)
        self.assertEqual(text.count('format_rc=${PIPESTATUS[0]}'), 1)
        self.assertIn('status=$((instrument_fail | format_fail))', text)
        self.assertIn('formato-$ff.txt', text)
        self.assertIn('format_fail=1; break;', text)

    def test_pipeline_status_survives_ok_text(self):
        code = '''set -o pipefail
bash -c 'echo "OK (1 test)"; exit 124' | tee /dev/null >/dev/null
format_rc=${PIPESTATUS[0]}
[ "$format_rc" = "124" ]
'''
        run = subprocess.run(['bash', '-c', code], capture_output=True, text=True, timeout=5)
        self.assertEqual(run.returncode, 0, run.stderr)

    def test_required_native_library_matches_cmake_and_loader(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        cmake = (ROOT / 'app/src/main/cpp/CMakeLists.txt').read_text()
        loader = (ROOT / 'app/src/main/kotlin/io/trazo/app/ai/LlamaCppInference.kt').read_text()
        match = re.search(r'add_library\(([^ ]+) SHARED', cmake)
        self.assertIsNotNone(match)
        target = match.group(1)
        self.assertIn(f'loadLibrary("{target}")', loader)
        self.assertIn(f'TRAZO_NATIVE_REQUIRED_LIB: lib{target}.so', workflow)
        self.assertIn('--require-abi arm64-v8a --require-library "$TRAZO_NATIVE_REQUIRED_LIB"', workflow)


if __name__ == '__main__':
    unittest.main()
