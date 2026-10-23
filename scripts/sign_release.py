#!/usr/bin/env python3
"""Sign an unsigned CI APK with a private local key. No signing secrets enter GitHub."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--input', required=True)
p.add_argument('--signer', required=True)
p.add_argument('--credentials', required=True)
p.add_argument('--version', required=True)
p.add_argument('--source-sha', required=True)
p.add_argument('--run-id', required=True, type=int)
p.add_argument('--output', required=True)
a = p.parse_args()
assert re.fullmatch(r'\d+\.\d+\.\d+', a.version), 'Invalid version'
assert re.fullmatch(r'[a-f0-9]{40}', a.source_sha), 'Invalid commit'
credentials_path = Path(a.credentials).resolve()
c = json.loads(credentials_path.read_text())
key = credentials_path.parent / c['keystore']
output = Path(a.output).resolve()
output.mkdir(parents=True, exist_ok=True)
apk = output / f'Navegador-4.0-v{a.version}.apk'
env = dict(os.environ, N4_STORE_PASSWORD=c['password'])
subprocess.run(['java', '-jar', a.signer, 'sign', '--ks', str(key), '--ks-key-alias', c['alias'], '--ks-pass', 'env:N4_STORE_PASSWORD', '--key-pass', 'env:N4_STORE_PASSWORD', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', str(apk), a.input], env=env, check=True)
verification = subprocess.check_output(['java', '-jar', a.signer, 'verify', '--verbose', '--print-certs', str(apk)], text=True)
certificate = re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', verification).group(1)
expected = Path('signing/certificate.sha256').read_text().strip()
assert certificate == expected, 'Unexpected signing certificate'
metadata = {'version': a.version, 'file': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'certificate_sha256': certificate, 'source_sha': a.source_sha, 'workflow_run_id': a.run_id}
(output / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
(output / 'SHA256SUMS.txt').write_text(f"{metadata['sha256']}  {apk.name}\n")
print(json.dumps({'file': str(apk), 'bytes': apk.stat().st_size, 'sha256': metadata['sha256'], 'certificate_sha256': certificate}))
