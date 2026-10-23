#!/usr/bin/env python3
"""Validate CI provenance, APK integrity and permanent certificate before publishing."""
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

m = json.loads(Path('release-assets/release.json').read_text())
assert re.fullmatch(r'\d+\.\d+\.\d+', m['version'])
assert re.fullmatch(r'[a-f0-9]{40}', m['source_sha'])
assert isinstance(m['workflow_run_id'], int) and m['workflow_run_id'] > 0
assert m['file'] == f"Navegador-4.0-v{m['version']}.apk"
apk = Path('release-assets') / m['file']
assert hashlib.sha256(apk.read_bytes()).hexdigest() == m['sha256'], 'APK checksum mismatch'
repository = os.environ['GITHUB_REPOSITORY']
run = json.loads(subprocess.check_output(['gh', 'api', f'/repos/{repository}/actions/runs/{m["workflow_run_id"]}']))
assert run['head_sha'] == m['source_sha'], 'Wrong build commit'
assert run['conclusion'] == 'success' and run['status'] == 'completed', 'Build and device tests must pass'
assert run['path'] == '.github/workflows/android.yml', 'Unexpected workflow'
signers = sorted(glob.glob(os.path.join(os.environ['ANDROID_HOME'], 'build-tools', '*', 'apksigner')))
assert signers, 'apksigner not installed'
verification = subprocess.check_output([signers[-1], 'verify', '--verbose', '--print-certs', str(apk)], text=True)
certificate = re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', verification).group(1)
assert certificate == m['certificate_sha256'] == Path('signing/certificate.sha256').read_text().strip(), 'Signing certificate mismatch'
notes = Path('release-assets/notes.md')
assert notes.is_file()
tag = 'v' + m['version']
exists = subprocess.run(['gh', 'release', 'view', tag, '--repo', repository], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
if not exists:
    subprocess.run(['gh', 'release', 'create', tag, '--repo', repository, '--target', m['source_sha'], '--title', f'Navegador 4.0 · {m["version"]}', '--notes-file', str(notes), str(apk), 'release-assets/SHA256SUMS.txt'], check=True)
else:
    subprocess.run(['gh', 'release', 'upload', tag, '--repo', repository, '--clobber', str(apk), 'release-assets/SHA256SUMS.txt'], check=True)
