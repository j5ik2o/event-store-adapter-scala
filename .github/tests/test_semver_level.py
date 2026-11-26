"""semver-level.py：R5 の前の github-tag-action（conventionalcommits、revert:minor、default_bump: patch）と同じ level
（major を除く）を決めること（設計 design-r5.md §3、P17）。"""
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from hypothesis import given, settings, strategies as st

from release_test_support import BUMP, GITHUB, load_script, run_body

level = load_script('semver-level.py', 'semver_level')
HEADER = re.compile(r'^(\w*)(?:\((.*)\))?!?: (.*)$', re.ASCII)


def records(*subjects, body=''):
    return ''.join(f'{subject}\x1f{body}\x1e' for subject in subjects)


class SemverLevelTest(unittest.TestCase):
    def test_feat_and_revert_make_a_minor_version(self):
        for subject in ('feat: add support', 'feat(storage): add support', 'revert: restore behavior',
                        'revert(api): restore behavior', 'feat(a)(b): nested scope'):
            with self.subTest(subject):
                self.assertEqual(level.semver_level(records(subject)), 'minor')

    def test_other_types_make_a_patch_version(self):
        for subject in ('fix: correct reads', 'perf: speed up reads', 'ci: check workflows', 'chore: tidy',
                        'docs: explain', 'build: pin', 'refactor: move', 'style: format', 'test: cover'):
            with self.subTest(subject):
                self.assertEqual(level.semver_level(records(subject)), 'patch')

    def test_subjects_that_are_not_conventional_headers_make_a_patch_version(self):
        # 件数の grep（`type:`）には入るが、action の見出し（`type: `）には合わない件名、大文字の型、非 ASCII の型
        for subject in ('feat:no space', 'Feat: add support', 'FEAT: add support', 'féat: add support',
                        'Update documentation', 'Revert "feat: add support"', ''):
            with self.subTest(subject):
                self.assertEqual(level.semver_level(records(subject)), 'patch')

    def test_mixed_commits_and_no_commits(self):
        self.assertEqual(level.semver_level(records('fix: a', 'chore: b', 'feat: c', 'docs: d')), 'minor')
        self.assertEqual(level.semver_level(records('fix: a', 'perf: b')), 'patch')
        # force_bump でコミットが無いとき（R5 の前は action が何も作らなかった。今は patch。設計 §11 L2）
        self.assertEqual(level.semver_level(''), 'patch')

    def test_only_the_subject_counts(self):
        self.assertEqual(level.semver_level(records('fix: a', body='feat: in the body\n')), 'patch')
        self.assertEqual(level.semver_level('\nfeat: after a newline\x1f\x1e'), 'minor')

    @settings(max_examples=300, deadline=None)
    @given(st.lists(st.tuples(
        st.sampled_from(['feat', 'revert', 'fix', 'perf', 'ci', 'chore', 'Feat', 'féat', '', 'feat ', 'BREAKING CHANGE']),
        st.sampled_from(['', '(x)', '(a b)', '!']),
        st.sampled_from([': ', ':', ' : ']),
        st.text(alphabet=st.characters(blacklist_characters='\x1e\x1f\n\r'), max_size=12),
        st.text(alphabet=st.characters(blacklist_characters='\x1e\x1f'), max_size=20),
    ), max_size=6))
    def test_p17_minor_exactly_when_a_conventional_header_is_feat_or_revert(self, commits):
        text = ''.join(f'{kind}{scope}{colon}{rest}\x1f{body}\x1e' for kind, scope, colon, rest, body in commits)
        subjects = [f'{kind}{scope}{colon}{rest}' for kind, scope, colon, rest, _ in commits]
        minor = any((match := HEADER.match(subject)) and match.group(1) in ('feat', 'revert') for subject in subjects)
        self.assertEqual(level.semver_level(text), 'minor' if minor else 'patch')


class SemverLevelStepTest(unittest.TestCase):
    """bump-version.yml の semver_level の手順を、手元の git で流す。"""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Workflow Test')
        self.git('config', 'user.email', 'workflow-test@example.invalid')
        self.commit('chore: initial commit')
        self.git('tag', 'v1.2.3')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def commit(self, message):
        self.git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', message)

    def level(self, bump=''):
        output = self.root / 'output'
        output.write_text('')
        subprocess.run(['bash', '-e', '-c', run_body(BUMP, 'semver_level')], cwd=self.root, check=True,
                       capture_output=True, text=True,
                       env={**os.environ, 'BUMP': bump, 'PREV_TAG': 'v1.2.3', 'GITHUB_OUTPUT': str(output),
                            'GITHUB_WORKSPACE': str(GITHUB.parent), 'PATH': os.environ['PATH']})
        return output.read_text()

    def test_the_bump_of_the_changes_step_wins(self):
        self.commit('feat: add support')
        for bump in ('major', 'minor', 'patch'):
            with self.subTest(bump):
                self.assertEqual(self.level(bump), f'semver_level={bump}\n')

    def test_without_a_bump_the_commits_decide(self):
        self.commit('fix: correct reads')
        self.assertEqual(self.level(), 'semver_level=patch\n')
        self.commit('feat(storage): add support')
        self.assertEqual(self.level(), 'semver_level=minor\n')

    def test_merge_commits_are_not_read(self):
        # changes の手順と同じく --no-merges（main は squash だけなので、main にマージのコミットは無い）
        self.git('switch', '-q', '-c', 'topic')
        self.commit('fix: correct reads')
        self.git('switch', '-q', '-')
        self.git('-c', 'commit.gpgsign=false', 'merge', '--no-ff', '-q', '-m', 'feat: merge topic', 'topic')
        self.assertEqual(self.level(), 'semver_level=patch\n')



class NextVersionStepTest(unittest.TestCase):
    """bump-version.yml の versions の手順（next-semver.py）。タグの先頭の v だけを外して SemVer として読む（#934 の Devin の指摘）。"""

    def next_version(self, prev_tag, level, requested='', current='0.0.1\n'):
        with tempfile.TemporaryDirectory() as directory:
            if current is not None:
                (Path(directory) / 'version').write_text(current)
            output = Path(directory) / 'output'
            output.write_text('')
            result = subprocess.run(['bash', '-e', '-c', run_body(BUMP, 'versions')], cwd=directory,
                                    capture_output=True, text=True,
                                    env={**os.environ, 'PREV_TAG': prev_tag, 'SEMVER_LEVEL': level,
                                         'REQUESTED_VERSION': requested, 'GITHUB_OUTPUT': str(output),
                                         'GITHUB_WORKSPACE': str(GITHUB.parent)})
            return result, output.read_text()

    def test_multi_digit_versions_are_read_whole(self):
        cases = {('v10.2.0', 'patch'): '10.2.1', ('v10.2.0', 'minor'): '10.3.0', ('v10.2.0', 'major'): '11.0.0',
                 ('v10.2.3', 'minor'): '10.3.0', ('v10.2.3', 'patch'): '10.2.4',
                 ('v5.12.34', 'patch'): '5.12.35', ('v5.2.0', 'minor'): '5.3.0', ('v0.9.9', 'patch'): '0.9.10',
                 ('v123.45.678', 'major'): '124.0.0'}
        for (tag, level), expected in cases.items():
            with self.subTest(tag=tag, level=level):
                result, output = self.next_version(tag, level)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(output, f'next_version={expected}\nnext_tag=v{expected}\n')

    def test_the_next_version_must_be_greater_than_the_version_file(self):
        # 5.3.0 の版上げをマージしてタグがまだ無いとき、最新のタグ v5.2.0 から 5.2.1 を出すと版を下げる PR になる
        for prev_tag, level, requested, current in (('v5.2.0', 'patch', '', '5.3.0\n'), ('v5.2.0', '', '5.2.1', '5.3.0\n'),
                                                    ('v5.2.0', 'patch', '', '5.2.1\n'), ('v5.2.0', 'patch', '', None)):
            with self.subTest(requested=requested, current=current):
                result, output = self.next_version(prev_tag, level, requested=requested, current=current)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('::error::', result.stderr)
                self.assertEqual(output, '')
        for current in ('5.2.0\n', '5.2.1-SNAPSHOT\n', '5.2.0'):
            with self.subTest(current=current):
                result, output = self.next_version('v5.2.0', 'patch', current=current)
                self.assertEqual((result.returncode, output), (0, 'next_version=5.2.1\nnext_tag=v5.2.1\n'), result.stderr)

    def test_a_requested_version_is_used_as_is(self):
        result, output = self.next_version('v10.2.0', '', requested='10.4.0')
        self.assertEqual(output, 'next_version=10.4.0\nnext_tag=v10.4.0\n')

    def test_a_tag_that_is_not_a_version_fails_the_step(self):
        # 読めないタグを読み飛ばして空の版の PR を作らない
        for tag in ('vX', 'release-10.2.0', 'v10.2', ''):
            with self.subTest(tag=tag):
                result, output = self.next_version(tag, 'patch')
                if tag:
                    self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('next_version=1', output)

    @settings(max_examples=100, deadline=None)
    @given(st.integers(0, 10**6), st.integers(0, 10**6), st.integers(0, 10**6),
           st.sampled_from(('patch', 'minor', 'major')), st.booleans())
    def test_p17b_the_next_version_bumps_the_whole_tag(self, major, minor, patch, level, prefix):
        tag = ('v' if prefix else '') + f'{major}.{minor}.{patch}'
        result = subprocess.run([sys.executable, str(GITHUB / 'next-semver.py'), level], input=tag + '\n',
                                capture_output=True, text=True)
        expected = {'patch': (major, minor, patch + 1), 'minor': (major, minor + 1, 0), 'major': (major + 1, 0, 0)}[level]
        self.assertEqual(result.stdout, '.'.join(map(str, expected)) + '\n', result.stderr)


if __name__ == '__main__':
    unittest.main()
