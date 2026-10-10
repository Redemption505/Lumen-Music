"""Summarize manually observed startup trials without launching the application.

Usage: python scripts/startup-report.py --input build/startup/runs.csv
Empty cells mean unobserved; they never become zero or a successful trial.
"""
import argparse
import csv
import hashlib
import json
import math
from datetime import datetime
from pathlib import Path
import statistics

FIELDS = (
    'run_id', 'started_at', 'machine', 'boot_id', 'build_id', 'jar_sha256',
    'scenario', 'profile', 'network', 'method', 'outcome', 'window_ms',
    'ui_ready_ms', 'service_ms', 'proxy_state', 'proxy_ms', 'exit_ms',
    'owned_processes_stopped',
)
TIMES = ('window_ms', 'ui_ready_ms', 'service_ms', 'proxy_ms', 'exit_ms')
MINIMUM = {'warm': 5, 'after-reboot': 3, 'first-profile': 3}


def summarize(rows):
    groups = {}
    seen = set()
    cold_boots = set()
    first_profiles = set()
    for line, row in enumerate(rows, 2):
        def reject(message):
            raise ValueError(f'CSV row {line}: {message}')
        row = {k: str(v or '').strip() for k, v in row.items()}
        if set(row) != set(FIELDS):
            reject('columns do not match the template')
        for key in FIELDS:
            if key not in TIMES and not row[key]:
                reject(f'missing {key}')
        if row['run_id'] in seen:
            reject('duplicate run_id')
        seen.add(row['run_id'])
        try:
            started = datetime.fromisoformat(row['started_at'].replace('Z', '+00:00'))
            if started.utcoffset() is None:
                reject('started_at requires a UTC offset')
        except ValueError:
            reject('invalid started_at with UTC offset')
        if len(row['jar_sha256']) != 64 or any(c not in '0123456789abcdef' for c in row['jar_sha256'].lower()):
            reject('jar_sha256 must contain 64 hexadecimal characters')
        row['jar_sha256'] = row['jar_sha256'].lower()
        if row['scenario'] not in MINIMUM:
            reject('scenario must be warm, after-reboot or first-profile')
        if row['outcome'] not in ('ok', 'failed', 'incomplete'):
            reject('outcome must be ok, failed or incomplete')
        if row['owned_processes_stopped'] not in ('yes', 'no', 'unknown'):
            reject('owned_processes_stopped must be yes, no or unknown')
        if row['proxy_state'] not in ('ready', 'fallback', 'failed', 'not-observed'):
            reject('invalid proxy_state')
        for key in TIMES:
            value = row[key]
            if not value:
                row[key] = None
            else:
                try:
                    number = float(value)
                except ValueError:
                    reject(f'invalid {key}')
                if not math.isfinite(number) or number <= 0:
                    reject(f'{key} must be finite and positive')
                row[key] = number
        if row['window_ms'] is not None and row['ui_ready_ms'] is not None and row['ui_ready_ms'] < row['window_ms']:
            reject('ui_ready_ms precedes window_ms')
        if (row['proxy_state'] == 'ready') != (row['proxy_ms'] is not None):
            reject('proxy_ms is required only for a ready proxy')
        if row['outcome'] == 'ok':
            if any(row[key] is None for key in ('window_ms', 'ui_ready_ms', 'service_ms', 'exit_ms')):
                reject('ok requires window, UI, service and exit observations')
            if row['owned_processes_stopped'] != 'yes' or row['proxy_state'] not in ('ready', 'fallback'):
                reject('ok requires normal owned-process cleanup and a known proxy result')
        identity = (row['machine'], row['build_id'], row['jar_sha256'])
        if row['scenario'] == 'after-reboot':
            reboot_key = identity + (row['boot_id'],)
            if reboot_key in cold_boots:
                reject('only the first trial per system boot can be after-reboot')
            cold_boots.add(reboot_key)
        if row['scenario'] == 'first-profile':
            profile_key = identity + (row['profile'],)
            if profile_key in first_profiles:
                reject('first-profile requires a distinct new test profile')
            first_profiles.add(profile_key)
        # Fresh profiles form one scenario, whereas warm trials must reuse one profile.
        profile_group = row['profile'] if row['scenario'] != 'first-profile' else 'distinct-new-profiles'
        key = identity + (row['scenario'], profile_group, row['network'], row['method'])
        groups.setdefault(key, []).append(row)
    if not groups:
        raise ValueError('no trials recorded; the empty template is not evidence')
    summaries = []
    for key, trials in groups.items():
        good = [row for row in trials if row['outcome'] == 'ok']
        times = {}
        for metric in TIMES:
            values = sorted(row[metric] for row in good if row[metric] is not None)
            times[metric] = None if not values else {
                'observed': len(values), 'median': statistics.median(values),
                'p90_nearest_rank': values[math.ceil(len(values) * .9) - 1],
                'maximum': max(values),
            }
        required = MINIMUM[key[3]]
        summaries.append({
            'machine': key[0], 'build_id': key[1], 'jar_sha256': key[2],
            'scenario': key[3], 'profile_group': key[4], 'network': key[5],
            'method': key[6], 'attempts': len(trials), 'successful': len(good),
            'failed': sum(row['outcome'] == 'failed' for row in trials),
            'incomplete': sum(row['outcome'] == 'incomplete' for row in trials),
            'minimum_successful_trials': required,
            'coverage': 'complete' if len(good) >= required else 'insufficient',
            'proxy_fallback_trials': sum(row['proxy_state'] == 'fallback' for row in good),
            'times_ms_successful_trials_only': times,
        })
    return {
        'schema': 1, 'trial_count': len(seen), 'groups': summaries,
        'performance_verdict': 'not-assessed',
        'limitations': [
            'Input is operator evidence; this tool does not verify clocks, boots, UI or process ownership.',
            'Failed and incomplete trials remain in attempt counts; timings summarize successful trials only.',
            'Coverage is not a performance budget or a product release approval.',
            'Different machines, builds, profiles, network conditions and methods are not pooled.',
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    args = parser.parse_args()
    try:
        data = args.input.read_bytes()
        with args.input.open(encoding='utf-8-sig', newline='') as source:
            reader = csv.DictReader(source)
            if reader.fieldnames != list(FIELDS):
                raise ValueError('CSV header does not match docs/templates/startup-runs.csv')
            report = summarize(list(reader))
        report['input_sha256'] = hashlib.sha256(data).hexdigest()
    except (OSError, ValueError, csv.Error) as error:
        parser.exit(2, f'Cannot summarize startup trials: {error}\n')
    print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
