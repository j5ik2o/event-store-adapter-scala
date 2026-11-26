import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from release_test_support import GATE_SCRIPT, REPOSITORY, VERSION_FILE, load_gate, pull

gate = load_gate()


class ReleaseGateTest(unittest.TestCase):
    SHA = 'a' * 40
    PARENT = '5.2.1-SNAPSHOT\n'
    TARGET = '5.3.0\n'

    def check(self, mode='tag', parent=PARENT, current=TARGET, changed=(VERSION_FILE,), pulls=None,
              tags=None, on_main=True, tag=None, required=None, parents=1):
        candidate = gate.Candidate(
            repository=REPOSITORY, sha=self.SHA, parent=parent, current=current,
            changed_files=tuple(changed), pulls=tuple([pull(self.SHA)] if pulls is None else pulls),
            tags={'v5.1.0': 'b' * 40, 'v5.2.0': 'c' * 40} if tags is None else tags, on_main=on_main,
            required=required, parents=parents,
        )
        return gate.check_candidate(mode, candidate, tag)

    def assert_rejected(self, message, **kwargs):
        with self.assertRaises(gate.GateError) as raised:
            self.check(**kwargs)
        self.assertIn(message, str(raised.exception))

    def test_squash_of_the_version_pull_request_is_tagged(self):
        self.assertEqual(self.check(), 'v5.3.0')
        self.assertEqual(self.check(parent='5.2.0\n'), 'v5.3.0')
        self.assertEqual(self.check(parent='5.2.1-SNAPSHOT', current='5.3.0'), 'v5.3.0')

    def test_the_merge_that_adds_the_version_file_releases_nothing(self):
        # R5 のマージ（B3）：親に version が無く、5.2.0 を足し、ほかのファイルも変える。v5.2.0 は別のコミットの軽量タグ。
        # タグのモードは何もしないで成功し、公開のモード（そのコミットへの手のタグ）は拒否する
        lightweight = {'v5.2.0': 'a' * 40}
        r5 = dict(parent=None, current='5.2.0\n', changed=(VERSION_FILE, 'build.sbt', 'project/plugins.sbt'), pulls=[],
                  tags=lightweight, required=gate.Required('5.2.0', False))
        self.assertIsNone(self.check(**r5))
        self.assert_rejected('has no version', mode='publish', tag='v5.2.0', **r5)
        self.assert_rejected('has no version', mode='publish', tag='v5.2.1', **r5)

    def test_parent_without_a_version_file_is_not_released(self):
        self.assertIsNone(self.check(parent=None, pulls=[]))
        self.assert_rejected('has no version', mode='publish', parent=None, tag='v5.3.0')

    def test_commit_without_a_version_file_fails(self):
        self.assert_rejected('has no version', current=None)

    def test_unchanged_version_is_skipped(self):
        self.assertIsNone(self.check(parent='5.3.0\n', current='5.3.0', pulls=[]))
        self.assertIsNone(self.check(parent=self.PARENT, current=self.PARENT, pulls=[]))

    def test_malformed_version_file_fails(self):
        for text in ('5.3.0\r\n', '﻿5.3.0\n', ' 5.3.0\n', '5.3.0 \n', '5.3.0\n\n', 'v5.3.0\n',
                     '5.3.0+build\n', '', '\n', '5.3.0\n5.4.0\n', '５.３.０\n'):
            with self.subTest(text=text):
                self.assert_rejected('must hold a single version', current=text)
        self.assert_rejected('must hold a single version', parent='5.2.1-SNAPSHOT\r\n')

    def test_line_ending_change_fails(self):
        self.assert_rejected('not the line ending', current='5.3.0')

    def test_version_changed_by_another_pull_request_fails(self):
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, ref='feature/x')])

    def test_commit_without_an_associated_pull_request_asks_for_a_re_run(self):
        self.assert_rejected('no pull request is associated', pulls=[])
        self.assert_rejected('re-run the job', pulls=[])

    def test_fork_pull_request_fails(self):
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, repo='someone/r')])
        self.assert_rejected('is not the squash merge',
                             pulls=[{**pull(self.SHA), 'head': {'ref': 'release/v5.3.0', 'repo': None}}])

    def test_unmerged_or_other_commit_fails(self):
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, merged=False)])
        self.assert_rejected('is not the squash merge', pulls=[pull('d' * 40)])
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, base='develop')])
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA), pull(self.SHA)])

    def test_other_changes_fail(self):
        self.assert_rejected('must change only version', changed=(VERSION_FILE, 'core/build.sbt'))
        self.assert_rejected('must change only version', changed=('build.sbt',))

    def test_downgrade_fails(self):
        self.assert_rejected('must be greater than the previous version 5.3.1', parent='5.3.1\n')
        self.assert_rejected('must be greater than the previous version 5.3.0-SNAPSHOT',
                             parent='5.3.0-SNAPSHOT\n', current='5.2.9\n',
                             pulls=[pull(self.SHA, ref='release/v5.2.9')])

    def test_version_not_above_the_latest_tag_fails(self):
        self.assert_rejected('must be greater than the latest tag v5.4.0', tags={'v5.4.0': 'b' * 40})

    def test_non_release_target_is_not_tagged(self):
        for version in ('5.3.1-SNAPSHOT', '5.4.0-rc.1'):
            with self.subTest(version=version):
                self.assertIsNone(self.check(parent='5.3.0\n', current=version + '\n', pulls=[]))
        self.assert_rejected('is not a release version', mode='publish', current='5.4.0-rc.1\n',
                             tag='v5.4.0-rc.1')

    def test_commit_off_main_fails(self):
        self.assert_rejected('is not reachable from main', on_main=False)

    def test_merge_commit_fails(self):
        self.assert_rejected('has 2 parents; only a squash merge is released', parents=2)
        self.assert_rejected('has 2 parents', mode='publish', parents=2, tags={'v5.3.0': self.SHA}, tag='v5.3.0')

    def test_existing_tag(self):
        self.assertIsNone(self.check(tags={'v5.2.0': 'c' * 40, 'v5.3.0': self.SHA}))
        self.assertIsNone(self.check(tags={'v5.3.0': self.SHA, 'v5.4.0': 'f' * 40}))
        self.assert_rejected('already exists on another commit', tags={'v5.3.0': 'e' * 40})

    def test_required_version_must_be_published_first(self):
        waiting = gate.Required('5.2.0', published=False)
        self.assert_rejected('must wait until 5.2.0 is published', required=waiting)
        self.assert_rejected('must wait until 5.2.0 is published', mode='publish', required=waiting,
                             tags={'v5.2.0': 'c' * 40, 'v5.3.0': self.SHA}, tag='v5.3.0')
        self.assertEqual(self.check(required=gate.Required('5.2.0', published=True)), 'v5.3.0')
        unknown = gate.Required('5.2.0', published=None)
        self.assert_rejected('could not read whether 5.2.0 is on Maven Central', required=unknown)
        # 照会が要らない判定は、読めなくても変わらない（設計レビュー M2）
        self.assertIsNone(self.check(current=self.PARENT, required=unknown))
        self.assertEqual(self.check(parent='5.1.0\n', current='5.2.0\n', required=unknown,
                                    pulls=[pull(self.SHA, ref='release/v5.2.0')], tags={'v5.1.0': 'b' * 40}),
                         'v5.2.0')

    def test_the_required_version_is_5_2_0_until_it_is_published(self):
        # R10 でこの値を None にする（design-r4.md §2）
        self.assertEqual(gate.REQUIRED_PUBLISHED, '5.2.0')

    def test_publish_mode(self):
        tags = {'v5.2.0': 'c' * 40, 'v5.3.0': self.SHA}
        self.assertEqual(self.check(mode='publish', tags=tags, tag='v5.3.0'), 'v5.3.0')
        self.assert_rejected('does not point at', mode='publish', tags={'v5.3.0': 'e' * 40}, tag='v5.3.0')
        self.assert_rejected('does not point at', mode='publish', tags={'v5.2.0': 'c' * 40}, tag='v5.3.0')
        self.assert_rejected('does not match the version', mode='publish', tags=tags, tag='v5.3.1')
        self.assert_rejected('must be greater than the latest tag v5.4.0', mode='publish',
                             tags={**tags, 'v5.4.0': 'f' * 40}, tag='v5.3.0')
        self.assert_rejected('is not the squash merge', mode='publish', tags=tags, tag='v5.3.0',
                             pulls=[pull(self.SHA, ref='feature/x')])

    def test_latest_tag(self):
        names = ['v5.2.0', 'v5.10.0', 'v5.9.1', 'v6.0.0-rc.1', 'v$(id)', 'release', '5.11.0', 'v05.0.0',
                 'V7.0.0', 'v5.11.0+build']
        self.assertEqual(gate.latest_tag(names), 'v5.10.0')
        self.assertIsNone(gate.latest_tag(['V5.2.0', 'v05.2.0']))

    def test_remote_tags_use_the_peeled_commit(self):
        self.assertEqual(
            gate.remote_tags('1' * 40 + '\trefs/tags/v5.3.0\n' + '2' * 40 + '\trefs/tags/v5.3.0^{}\n'
                             + '3' * 40 + '\trefs/tags/v5.2.0\n'),
            {'v5.3.0': '2' * 40, 'v5.2.0': '3' * 40})

    def test_the_lightweight_tags_of_this_repository(self):
        # `git ls-remote --tags origin` の実物の行（v5.1.0 と v5.2.0 は軽量タグで、^{} の行が無い）
        listing = ('f36b293ce0575d2e317b9c808ed1aea88995e627\trefs/tags/v5.1.0\n'
                   'a68723eb91dfc2929a5948115379d5f72515a004\trefs/tags/v5.2.0\n')
        tags = gate.remote_tags(listing)
        self.assertEqual(tags['v5.2.0'], 'a68723eb91dfc2929a5948115379d5f72515a004')
        self.assertEqual(gate.latest_tag(list(tags)), 'v5.2.0')

    def test_set_version_keeps_the_line_ending(self):
        self.assertEqual(gate.set_version('5.2.1-SNAPSHOT\n', '5.3.0'), '5.3.0\n')
        self.assertEqual(gate.set_version('5.2.1-SNAPSHOT', '5.3.0'), '5.3.0')
        with self.assertRaises(gate.GateError):
            gate.set_version('5.2.1-SNAPSHOT\r\n', '5.3.0')
        with self.assertRaises(gate.GateError):
            gate.set_version('5.2.1-SNAPSHOT\n', '5.3.0-rc.1')

    def cli(self, *args, stdin='', cwd=None):
        return subprocess.run([sys.executable, str(GATE_SCRIPT), *args], input=stdin, cwd=cwd,
                              text=True, capture_output=True, check=False)

    def test_cli_latest_tag(self):
        self.assertEqual(self.cli('latest-tag', stdin='v5.1.0\nv5.2.0\nv6.0.0-rc.1\n').stdout, 'v5.2.0\n')
        self.assertEqual(self.cli('latest-tag', stdin='').stdout, '')

    def test_cli_set_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'version'
            path.write_bytes(b'5.2.1-SNAPSHOT\n')
            result = self.cli('set-version', '5.3.0', cwd=directory)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(path.read_bytes(), b'5.3.0\n')
            path.write_bytes(b'5.2.1-SNAPSHOT\r\n')
            result = self.cli('set-version', '5.3.0', cwd=directory)
            self.assertEqual(result.returncode, 1)
            self.assertIn('::error::', result.stderr)
            self.assertEqual(path.read_bytes(), b'5.2.1-SNAPSHOT\r\n')

    def check_cli(self, directory, *extra, parent=b'5.2.1-SNAPSHOT\n', required=b'true\n'):
        root = Path(directory)
        if required is not None:
            (root / 'required-published').write_bytes(required)
        else:
            (root / 'required-published').unlink(missing_ok=True)
        if parent is not None:
            (root / 'parent-version').write_bytes(parent)
        (root / 'version').write_bytes(b'5.3.0\n')
        (root / 'changed-files').write_text('version\n')
        (root / 'pulls.json').write_text(json.dumps([pull(self.SHA)]))
        (root / 'remote-tags').write_text('c' * 40 + '\trefs/tags/v5.2.0\n')
        (root / 'parents').write_text('1\n')
        return self.cli('check', '--mode', 'tag', '--repository', REPOSITORY, '--sha', self.SHA,
                        '--on-main', 'true', '--parents', str(root / 'parents'), '--parent-version', str(root / 'parent-version'),
                        '--version-file', str(root / 'version'), '--changed-files', str(root / 'changed-files'),
                        '--pulls', str(root / 'pulls.json'), '--remote-tags', str(root / 'remote-tags'),
                        '--required-published', str(root / 'required-published'), *extra)

    def test_cli_check(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.check_cli(directory)
            self.assertEqual((result.returncode, result.stdout), (0, 'v5.3.0\n'), result.stderr)
            result = self.check_cli(directory, required=b'false\n')
            self.assertEqual((result.returncode, result.stdout), (1, ''))
            self.assertIn('::error::5.3.0 must wait until 5.2.0 is published', result.stderr)
            result = self.check_cli(directory, required=None)
            self.assertEqual((result.returncode, result.stdout), (1, ''))
            self.assertIn('::error::could not read whether 5.2.0 is on Maven Central', result.stderr)
            for broken in (b'', b'yes\n', b'True\n', b'true\n\n', b' true', b'true\r\n'):
                with self.subTest(broken=broken):
                    result = self.check_cli(directory, required=broken)
                    self.assertEqual((result.returncode, result.stdout), (1, ''))
                    self.assertIn('must hold true or false', result.stderr)
        with tempfile.TemporaryDirectory() as directory:
            result = self.check_cli(directory, parent=None)
            self.assertEqual((result.returncode, result.stdout), (0, ''), result.stderr)
            self.assertIn('has no version', result.stderr)
        with tempfile.TemporaryDirectory() as directory:
            result = self.check_cli(directory, parent=b'\xff5.2.1\n')
            self.assertEqual(result.returncode, 1)
            self.assertIn('is not UTF-8', result.stderr)

    def test_cli_required_version(self):
        self.assertEqual(self.cli('required-version').stdout, '5.2.0\n')
        with mock.patch.object(gate, 'REQUIRED_PUBLISHED', None), mock.patch('builtins.print') as printed:
            self.assertEqual(gate.main(['release-gate.py', 'required-version']), 0)
        printed.assert_not_called()

    def test_without_a_required_version_the_file_is_not_read(self):
        # R10 のあと：設定が無ければ、required-published のファイルが無くても形が違っても関門は読まない
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(gate, 'REQUIRED_PUBLISHED', None):
            path = os.path.join(directory, 'required-published')
            self.assertIsNone(gate.read_required(path))
            Path(path).write_text('garbage')
            self.assertIsNone(gate.read_required(path))
        with mock.patch.object(gate, 'REQUIRED_PUBLISHED', '5.2'):
            with self.assertRaises(gate.GateError):
                gate.read_required('/nonexistent')


if __name__ == '__main__':
    unittest.main()
