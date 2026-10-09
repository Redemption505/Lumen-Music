"""Inventory only reviewed source trees; use real Git for ignore/index checks.

Run with a configured Python interpreter, for example:
  python scripts/repository-scope.py
  python scripts/repository-scope.py --check-index
Reports contain paths, categories and hashes, never matched text. Nothing is staged.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import uuid

TREES = ('desktop/src', 'desktop/.mvn', 'service/lumen_service', 'service/tests',
         'stream-proxy/src', 'scripts', 'release', 'docs', '.github/workflows')
FILES = ('README.md', '第四阶段.md', 'LICENSE.txt', '.gitignore', 'service/README.md',
         'desktop/pom.xml', 'desktop/mvnw', 'desktop/mvnw.cmd',
         'stream-proxy/Cargo.toml', 'stream-proxy/Cargo.lock')
PRIVATE_DIRS = {'.git', 'build', '.planning', '.codex', '.agents', 'userdata',
                'personal-data', 'signing', 'certificates', '__pycache__', '.venv',
                '.pytest_cache', '.cache', '.idea', 'target', 'node_modules'}
PRIVATE_NAME = re.compile(r'(?i)^(?:ai-secret\.json.*|ai-settings\.json.*|'
    r'\.ai-settings-recovery\.json.*|credentials\.json.*|\.env(?:\..*)?|'
    r'cookies?\.(?:txt|json)|appearance\.properties.*|playback-state\.xml.*|diagnostics\.xml.*)$')
PRIVATE_EXT = {'.pyc', '.log', '.pfx', '.p12', '.key', '.pem'}
TEXT = {'.md', '.txt', '.py', '.java', '.rs', '.toml', '.lock', '.xml',
        '.properties', '.ps1', '.cmd', '.yml', '.yaml', '.css', '.svg',
        '.wxi', '.wxs', '.cpp', '.h', '.json', '.sh', '.cs', '.pth', '._pth', '.config',
        '.psm1', '.psd1', '.bat', '.ini', '.csv', '.html', '.htm', '.js', '.ts'}
PATTERNS = {
    'private-key-header': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'service-key-shape': re.compile(r'(?<![\w-])(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|AKIA[A-Z0-9]{16})(?![\w-])'),
    'credential-literal-review': re.compile(r'''(?i)(?:api[_-]?key|password|secret|SESSDATA|access[_-]?token)\s*[=:]\s*["']([^"'\r\n]{8,})["']'''),
    'personal-path-review': re.compile(r'(?i)(?:C:[/\\]Users[/\\][A-Za-z0-9_.-]+|D:[/\\]ComputerLearning[/\\])'),
}
REVIEWED_LITERALS = {'service/tests/test_ai_boundaries.py': 2,
    'service/tests/test_ai_settings.py': 2, 'service/tests/test_discovery_http.py': 1,
    'service/tests/update_feedback_smoke_fixture.py': 1}
# Whole-source hashes bind the six literal classifications to reviewed C/D bytes.
# Changed fixtures require source-context review before this baseline is updated.
REVIEWED_FIXTURE_HASHES = {
    'service/tests/test_ai_boundaries.py': 'eee3cf3f3a546956b4201da1023cbe1e0af0ddfe3a35eddab4a4fd42cfb78bbe',
    'service/tests/test_ai_settings.py': '240b875035114981f75131bff5afeba5ba7c82e2af1251230a2ef515428f7a90',
    'service/tests/test_discovery_http.py': '39c6bb24a72de182724ff0db2ada1033f556e650aadd1c9e6d2ecb04aa087ef8',
    'service/tests/update_feedback_smoke_fixture.py': '9b34235dfddef53fd0ce24d9a1defaffcce789fd6a3b2606f71031b8bc87d74d',
}
REVIEWED_PATH_FIXTURE = {
    'path': 'desktop/src/test/java/com/lumenmusic/RadioClientTest.java',
    'sha256': '39a0afecde8213109ba7db7e9df1e0f6e4401ca661c11fb105390be0cfafa40a',
    'category': 'synthetic-url-encoding-fixture',
    'basis': 'Directly constructed example URI; local HTTP requests and exact encoded path assertions; no local file read.',
}
PROBES = ('.planning/private-canary.md', 'build/private-canary.json',
    '.codex/state.json', '.agents/state.json', 'userdata/library.xml',
    'personal-data/history.xml', 'service/tests/userdata/library.xml',
    'service/lumen_service/ai-secret.json', 'service/tests/ai-secret.json.backup',
    'service/tests/.ai-settings-recovery.json', 'service/tests/ai-settings.json',
    '.env', 'service/tests/.env.local', 'service/tests/credentials.json.backup',
    'service/tests/cookies.txt', 'signing/material.pfx', 'certificates/material.dat',
    'desktop/src/main/resources/material.pem', 'desktop/target/output.jar',
    'stream-proxy/target/output.exe', 'service/tests/__pycache__/fixture.pyc',
    'service/tests/runtime.log')

def private(path):
    parts = Path(path).parts
    return (any(p.lower() in PRIVATE_DIRS for p in parts[:-1]) or
            bool(PRIVATE_NAME.match(parts[-1])) or Path(path).suffix.lower() in PRIVATE_EXT)

def run_git(root, args, data=None, git_dir=None):
    command = ['git', '-c', 'core.excludesFile=', '-c', 'core.quotePath=false']
    if git_dir:
        command += ['--git-dir=' + str(git_dir), '--work-tree=' + str(root)]
    result = subprocess.run(command + args, cwd=root, input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode not in (0, 1):
        raise RuntimeError('Git failed: ' + args[0] + ' (code ' + str(result.returncode) + ')')
    return result

def check_ignored(root, paths, git_dir):
    if not paths:
        return set()
    result = run_git(root, ['check-ignore', '--no-index', '-z', '--stdin'],
                     b''.join(p.encode('utf-8') + b'\0' for p in paths), git_dir)
    return {p.decode('utf-8') for p in result.stdout.split(b'\0') if p}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--check-index', action='store_true', help='Verify exact candidate paths and staged byte hashes; do not stage')
    args = parser.parse_args()
    root = args.root.resolve()
    out = root / 'build/phase4-public-e/scope'
    out.mkdir(parents=True, exist_ok=True)
    rehearsal = out / ('git-rehearsal-' + uuid.uuid4().hex)
    rehearsal.mkdir()
    subprocess.run(['git', 'init', '--quiet', str(rehearsal)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    git_dir = rehearsal / '.git'
    paths, excluded = [], []
    for tree in TREES:
        for base, dirs, files in os.walk(root / tree, followlinks=False):
            dirs[:] = [d for d in dirs if d.lower() not in PRIVATE_DIRS
                       and not (Path(base) / d).is_symlink()]
            for name in files:
                file = Path(base) / name
                path = file.relative_to(root).as_posix()
                if file.is_symlink() or private(path):
                    excluded.append(path)
                elif tree != 'service/lumen_service' or file.parent == root / tree and file.suffix == '.py':
                    paths.append(path)
                else:
                    excluded.append(path)
    paths += [p for p in FILES if (root / p).is_file()]
    ignored = check_ignored(root, sorted(set(paths + list(PROBES))), git_dir)
    selected = sorted(set(paths) - ignored)
    excluded += sorted(set(paths) & ignored)
    inventory, findings = [], []
    for path in selected:
        data = (root / path).read_bytes()
        inventory.append({'path': path, 'bytes': len(data), 'sha256': digest(data)})
        if Path(path).suffix.lower() in TEXT or Path(path).name in ('mvnw', '.gitignore'):
            value = data.decode('utf-8-sig', errors='replace')
            for category, pattern in PATTERNS.items():
                matches = list(pattern.finditer(value))
                if matches:
                    findings.append({'path': path, 'category': category, 'count': len(matches),
                        'lines': sorted({value.count('\n', 0, m.start()) + 1 for m in matches})})
    literals = {f['path']: f['count'] for f in findings if f['category'] == 'credential-literal-review'}
    hashes = {item['path']: item['sha256'] for item in inventory}
    fixture_hashes_match = all(hashes.get(p) == h for p, h in REVIEWED_FIXTURE_HASHES.items())
    path_fixture_matches = hashes.get(REVIEWED_PATH_FIXTURE['path']) == REVIEWED_PATH_FIXTURE['sha256']
    unreviewed_path_findings = [f for f in findings if f['category'] == 'personal-path-review'
                               and not (f['path'] == REVIEWED_PATH_FIXTURE['path'] and path_fixture_matches)]
    strong = [f for f in findings if f['category'] in ('private-key-header', 'service-key-shape')]
    failed_probes = sorted(set(PROBES) - ignored)
    errors = (['ignore probes not excluded'] if failed_probes else [])
    if unreviewed_path_findings:
        errors.append('unreviewed personal path findings')
    if strong:
        errors.append('strong credential shape requires review')
    if literals != REVIEWED_LITERALS or not fixture_hashes_match:
        errors.append('credential literal findings or source bytes differ from six reviewed test fixtures')
    scanner_probes = {
        'private-key-header': '-----BEGIN ' + 'PRIVATE KEY-----',
        'service-key-shape': 'sk-' + 'z' * 24,
        'credential-literal-review': 'api_' + 'key = "' + 'synthetic-input' + '"',
    }
    scanner_probe_results = {category: bool(PATTERNS[category].search(value))
                             for category, value in scanner_probes.items()}
    if not all(scanner_probe_results.values()):
        errors.append('in-memory scanner probe failed')
    index = {'checked': False}
    if args.check_index:
        if not (root / '.git').exists():
            errors.append('main repository not initialized')
        else:
            result = run_git(root, ['ls-files', '--stage', '-z'])
            entries = {}
            for entry in result.stdout.split(b'\0'):
                if entry:
                    metadata, raw_path = entry.split(b'\t', 1)
                    mode, blob, stage = metadata.decode('ascii').split()
                    entries[raw_path.decode('utf-8')] = (mode, blob, stage)
            missing = sorted(set(selected) - entries.keys())
            extra = sorted(entries.keys() - set(selected))
            different = []
            for item in inventory:
                path = item['path']
                if path in entries:
                    mode, blob, stage = entries[path]
                    if stage != '0' or mode not in ('100644', '100755'):
                        different.append(path)
                    elif digest(run_git(root, ['cat-file', 'blob', blob]).stdout) != item['sha256']:
                        different.append(path)
            index = {'checked': True, 'stagedCount': len(entries), 'missingPaths': missing,
                     'extraPaths': extra, 'differentBytePaths': different}
            if missing or extra or different:
                errors.append('index differs from current reviewed candidate inventory')
    report = {'status': 'passed' if not errors else 'failed', 'candidateCount': len(selected),
        'textFileCount': sum(Path(p).suffix.lower() in TEXT or Path(p).name in ('mvnw', '.gitignore') for p in selected),
        'excludedPaths': sorted(set(excluded)), 'findings': findings,
        'credentialLiteralClassification': 'Same six C/D synthetic test literals' if literals == REVIEWED_LITERALS and fixture_hashes_match else 'review required',
        'reviewedFixtureHashesMatch': fixture_hashes_match,
        'personalPathReviewRequired': bool(unreviewed_path_findings),
        'reviewedPathFixtures': [REVIEWED_PATH_FIXTURE] if path_fixture_matches else [],
        'inMemoryScannerProbes': scanner_probe_results,
        'gitIgnore': {'engine': run_git(root, ['--version']).stdout.decode().strip(),
            'command': 'git check-ignore --no-index -z --stdin',
            'scope': 'actual worktree .gitignore files; isolated build Git metadata; global excludes disabled',
            'probeCount': len(PROBES), 'failedPaths': failed_probes,
            'rehearsalPath': rehearsal.relative_to(root).as_posix()},
        'index': index, 'errors': errors,
        'limitations': ['Candidate whitelist only; private directories and payloads are not read.',
            'Heuristic UTF-8 text scan; unknown encodings, binary embedded secrets and nested archives are not scanned.',
            'Binary assets are hashed; C/D screenshot privacy/source review remains the visual evidence.',
            'Personal path findings still require context review; passed does not authorize publishing.',
            'No staging, main Git mutation, network, release rebuild or installed-app changes.']}
    for name, value in [('candidate-inventory.json', inventory), ('scope-scan.json', report)]:
        (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (out / 'candidate-paths.txt').write_text('\n'.join(selected) + '\n', encoding='utf-8')
    (out / 'candidate-paths.nul').write_bytes(b''.join(p.encode('utf-8') + b'\0' for p in selected))
    print(json.dumps({'status': report['status'], 'candidateCount': len(selected),
        'findingsCount': len(findings), 'ignoredProbeCount': len(PROBES) - len(failed_probes),
        'probeCount': len(PROBES), 'personalPathReviewRequired': report['personalPathReviewRequired'],
        'indexChecked': index['checked'], 'errors': errors}))
    return bool(errors)

if __name__ == '__main__':
    raise SystemExit(main())
