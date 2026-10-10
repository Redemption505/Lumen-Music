"""Behavior checks for recording failures, isolation and incomplete evidence."""
import importlib.util
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('startup_report', Path(__file__).resolve().parents[1] / 'startup-report.py')
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def trial(index=1, **changes):
    row = dict(zip(REPORT.FIELDS, (
        f'run-{index}', '2026-10-09T18:00:00+08:00', 'test-machine', 'boot-1',
        'synthetic-build', 'a' * 64, 'warm', 'test-profile', 'available',
        'operator-stopwatch', 'ok', '1000', '1200', '1500', 'ready', '1600', '800', 'yes',
    )))
    row.update(changes)
    return row


class StartupReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_root = Path(__file__).resolve().parents[2] / 'build/startup-report-tests'
        cls.temp_root.mkdir(parents=True, exist_ok=True)

    def test_warm_distribution_and_sample_requirement(self):
        summary = REPORT.summarize([trial(i, window_ms=str(1000 + i * 10)) for i in range(1, 6)])['groups'][0]
        self.assertEqual(summary['coverage'], 'complete')
        self.assertEqual(summary['times_ms_successful_trials_only']['window_ms']['median'], 1030)
        self.assertEqual(summary['times_ms_successful_trials_only']['window_ms']['p90_nearest_rank'], 1050)

    def test_failures_and_missing_observations_do_not_become_success(self):
        failed = trial(2, outcome='failed', window_ms='', ui_ready_ms='', service_ms='',
                       proxy_state='not-observed', proxy_ms='', exit_ms='', owned_processes_stopped='unknown')
        incomplete = dict(failed, run_id='run-3', outcome='incomplete')
        result = REPORT.summarize([trial(), failed, incomplete])
        group = result['groups'][0]
        self.assertEqual((group['attempts'], group['successful'], group['failed'], group['incomplete']), (3, 1, 1, 1))
        self.assertEqual(group['coverage'], 'insufficient')
        self.assertEqual(group['times_ms_successful_trials_only']['window_ms']['observed'], 1)
        self.assertEqual(result['performance_verdict'], 'not-assessed')

    def test_environment_build_and_profile_groups_remain_separate(self):
        rows = [trial(), trial(2, network='disconnected'), trial(3, build_id='other-build'),
                trial(4, machine='other-machine'), trial(5, profile='other-profile'),
                trial(6, method='native-probe'), trial(7, jar_sha256='b' * 64)]
        self.assertEqual(len(REPORT.summarize(rows)['groups']), 7)

    def test_reboot_and_new_profile_trials_cannot_be_relabelled_repeats(self):
        with self.assertRaises(ValueError):
            REPORT.summarize([trial(scenario='after-reboot'), trial(2, scenario='after-reboot')])
        with self.assertRaises(ValueError):
            REPORT.summarize([trial(scenario='first-profile'), trial(2, scenario='first-profile')])
        rows = [trial(i, scenario='first-profile', profile=f'fresh-{i}') for i in range(1, 4)]
        self.assertEqual(REPORT.summarize(rows)['groups'][0]['coverage'], 'complete')

    def test_invalid_and_unobserved_success_values_are_rejected(self):
        cases = ({'window_ms': 'NaN'}, {'window_ms': '-1'}, {'service_ms': ''},
                 {'ui_ready_ms': '900'}, {'owned_processes_stopped': 'no'},
                 {'jar_sha256': 'bad'}, {'started_at': '2026-10-09T18:00:00'},
                 {'proxy_state': 'fallback'}, {'window_ms': '0'})
        for changes in cases:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                REPORT.summarize([trial(**changes)])
        with self.assertRaises(ValueError):
            REPORT.summarize([])
        with self.assertRaises(ValueError):
            REPORT.summarize([trial(), trial()])

    def test_proxy_fallback_is_visible_without_fake_proxy_timing(self):
        group = REPORT.summarize([trial(proxy_state='fallback', proxy_ms='')])['groups'][0]
        self.assertEqual(group['proxy_fallback_trials'], 1)
        self.assertIsNone(group['times_ms_successful_trials_only']['proxy_ms'])

    def test_cli_reads_csv_and_binds_report_to_exact_input(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as folder:
            source = Path(folder) / 'runs.csv'
            with source.open('w', encoding='utf-8-sig', newline='') as target:
                writer = csv.DictWriter(target, fieldnames=REPORT.FIELDS)
                writer.writeheader()
                writer.writerow(trial())
            result = subprocess.run([sys.executable, str(SPEC.origin), '--input', str(source)],
                                    capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report['input_sha256'], hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(report['trial_count'], 1)
            self.assertEqual(report['groups'][0]['coverage'], 'insufficient')

    def test_cli_rejects_empty_template_and_invalid_header(self):
        template = Path(__file__).resolve().parents[2] / 'docs/templates/startup-runs.csv'
        result = subprocess.run([sys.executable, str(SPEC.origin), '--input', str(template)], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b'')
        with tempfile.TemporaryDirectory(dir=self.temp_root) as folder:
            source = Path(folder) / 'invalid.csv'
            source.write_bytes(b'run_id,outcome\none,ok\n')
            result = subprocess.run([sys.executable, str(SPEC.origin), '--input', str(source)], capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, b'')


if __name__ == '__main__':
    unittest.main()
