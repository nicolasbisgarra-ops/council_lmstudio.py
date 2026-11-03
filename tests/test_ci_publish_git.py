#!/usr/bin/env python3
"""Real git bare-repo simulation of source/run/attempt release ordering.

The 3 verification subprocesses are replaced with tiny TEST-ONLY deterministic
stubs: these tests exercise publisher git semantics, not evidence validation.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUBLISH = ROOT/'scripts/ci-publish.sh'
STUB = '''#!/usr/bin/env python3
import pathlib,sys
if sys.argv[1] == 'verify':
    path=pathlib.Path(sys.argv[2]); t=(path/'MANIFEST.txt').read_text()
    for k,flag in [('source_sha','--source-sha'),('run_id','--run-id'),('attempt','--attempt')]:
        assert k+'='+sys.argv[sys.argv.index(flag)+1] in t
else:
    assert (pathlib.Path(sys.argv[1])/'evidence.pass').is_file()
'''
ABI_STUB = '''#!/usr/bin/env python3
import pathlib,sys
for s in sys.argv[1:]:
    if s.startswith('--'):continue
    if s.endswith('.apk'):assert pathlib.Path(s).is_file()
'''


def run(cmd, cwd=None, env=None, check=True):
    proc=subprocess.run(cmd,cwd=cwd,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                       timeout=40,check=False)
    if check and proc.returncode!=0:
        raise AssertionError(f'{cmd} returned {proc.returncode}: {proc.stdout}')
    return proc


class PublishGitTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name)
        self.bare=self.base/'remote.git'
        self.repo=self.base/'work'
        run(['git','init','--bare','-q','--initial-branch=main',str(self.bare)])
        run(['git','clone','-q',str(self.bare),str(self.repo)])
        run(['git','config','user.name','QA'],cwd=self.repo)
        run(['git','config','user.email','qa@example.invalid'],cwd=self.repo)
        (self.repo/'scripts').mkdir()
        shutil.copy2(PUBLISH,self.repo/'scripts/ci-publish.sh')
        (self.repo/'scripts/ci-manifest.py').write_text(STUB)
        (self.repo/'scripts/ci-evidence-gate.py').write_text(STUB)
        (self.repo/'scripts/ci-abi-audit.py').write_text(ABI_STUB)
        (self.repo/'app.kt').write_text('// source A\n')
        run(['git','add','.'],cwd=self.repo)
        run(['git','commit','-qm','source'],cwd=self.repo)
        run(['git','push','-q','-u','origin','main'],cwd=self.repo)
        self.source=run(['git','rev-parse','HEAD'],cwd=self.repo).stdout.strip()

    def payload(self,run_id,attempt=1):
        p=self.base/f'payload-{run_id}-{attempt}'; p.mkdir(exist_ok=True)
        (p/'MANIFEST.txt').write_text('format=trazo-ci-manifest-v1\n'
             f'source_sha={self.source}\nrun_id={run_id}\nattempt={attempt}\n')
        (p/'evidence.pass').write_text('test-only gate marker')
        (p/'Trazo-debug.apk').write_bytes(b'fake APK just for git publisher harness')
        (p/'Trazo-debug-androidTest.apk').write_bytes(b'fake androidTest APK')
        return p

    def publish(self,run_id,attempt=1,payload=None):
        env=os.environ.copy()
        env.update(GITHUB_SHA=self.source,GITHUB_RUN_ID=str(run_id),GITHUB_RUN_ATTEMPT=str(attempt))
        return run(['bash','scripts/ci-publish.sh','main',str(payload or self.payload(run_id,attempt)),
                    f'release {run_id} [skip ci]'],cwd=self.repo,env=env)

    def head(self):
        return run(['git','ls-remote','origin','refs/heads/main'],cwd=self.repo).stdout.split()[0]

    def test_first_release_creates_one_complete_commit(self):
        self.publish(200)
        remote=self.head()
        self.assertNotEqual(remote,self.source)
        names=run(['git','ls-tree','-r','--name-only',remote],cwd=self.repo).stdout
        self.assertIn('output/MANIFEST.txt',names)
        self.assertIn('output/Trazo-debug.apk',names)
        self.assertIn('output/Trazo-debug-androidTest.apk',names)

    def test_older_run_cannot_replace_newer(self):
        self.publish(400); after=self.head()
        response=self.publish(300)
        self.assertEqual(self.head(),after)
        self.assertIn('más nuevo',response.stdout)

    def test_retry_same_run_is_idempotent(self):
        self.publish(410); after=self.head()
        response=self.publish(410)
        self.assertEqual(self.head(),after)
        self.assertIn('ya está publicado',response.stdout)

    def test_higher_attempt_can_replace_lower_attempt(self):
        self.publish(500,1); before=self.head()
        self.publish(500,2)
        self.assertNotEqual(self.head(),before)
        self.assertIn('attempt=2',run(['git','show',self.head()+':output/MANIFEST.txt'],cwd=self.repo).stdout)

    def test_source_advanced_blocks_old_release(self):
        self.publish(600)
        (self.repo/'more.kt').write_text('// source B\n')
        run(['git','add','more.kt'],cwd=self.repo)
        run(['git','commit','-qm','new code'],cwd=self.repo)
        run(['git','push','-q','origin','main'],cwd=self.repo)
        after=self.head()
        response=self.publish(700)
        self.assertEqual(self.head(),after)
        self.assertIn('código posterior',response.stdout)

    def test_missing_evidence_rejected_before_git_mutation(self):
        p=self.payload(800); (p/'evidence.pass').unlink()
        before=self.head()
        env=os.environ.copy()
        env.update(GITHUB_SHA=self.source,GITHUB_RUN_ID='800',GITHUB_RUN_ATTEMPT='1')
        response=run(['bash','scripts/ci-publish.sh','main',str(p),'release [skip ci]'],
                     cwd=self.repo,env=env,check=False)
        self.assertNotEqual(response.returncode,0)
        self.assertEqual(self.head(),before)

if __name__=='__main__':unittest.main()
