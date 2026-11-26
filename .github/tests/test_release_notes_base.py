"""publish.yml の Create the GitHub release：ノートの基点は、前の正式版のタグのうち GitHub のリリースがある（公開を
確かめた）もの。公開されずに飛んだタグの変更も、このリリースのノートに入れる（#934 の CodeRabbit の指摘）。"""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from release_test_support import GITHUB, RELEASE, steps

FAKE_GH = '''#!/bin/sh
# 偽の gh。release view は FAKE_RELEASES にあるタグだけ成功する。release create は引数を残す
if [ "$1 $2" = "release view" ]; then
  for tag in $FAKE_RELEASES; do
    [ "$tag" = "$3" ] && exit 0
  done
  exit 1
fi
if [ "$1 $2" = "release create" ]; then
  printf '%s\\n' "$@" > "$FAKE_GH_CREATE"
  exit 0
fi
exit 2
'''


def release_step_body():
    text = next(item for item in steps(RELEASE, 'github-release') if 'name: Create the GitHub release' in item)
    body = re.search(r'        run: \|\n((?:          [^\n]*\n|\n)+)', text + '\n').group(1)
    return '\n'.join(line[10:] for line in body.splitlines())


class ReleaseNotesBaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.work = self.root / 'work'
        self.work.mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text(FAKE_GH)
        (self.bin / 'gh').chmod(0o755)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        for subject, tag in (('feat: first', 'v5.2.0'), ('fix: skipped change', 'v5.3.0'), ('feat: latest change', 'v5.4.0')):
            self.git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', subject)
            self.git('tag', tag)

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.work, check=True, capture_output=True, text=True).stdout

    def release(self, releases):
        create = self.root / 'gh-create'
        result = subprocess.run(['bash', '-e', '-c', release_step_body()], cwd=self.work, capture_output=True, text=True,
                                env={**os.environ, 'PATH': f'{self.bin}{os.pathsep}{os.environ["PATH"]}',
                                     'FAKE_RELEASES': releases, 'FAKE_GH_CREATE': str(create),
                                     'GH_TOKEN': 'unused', 'RELEASE_TAG': 'v5.4.0', 'SERVER_URL': 'https://github.com',
                                     'GITHUB_REPOSITORY': 'o/r', 'GITHUB_WORKSPACE': str(GITHUB.parent),
                                     'RUNNER_TEMP': str(self.root)})
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout, (self.root / 'release-notes.md').read_text()

    def test_a_skipped_tag_is_not_the_base(self):
        # v5.3.0 はタグだけで公開されていない（リリースが無い）→ 基点は v5.2.0。v5.3.0 の変更もノートに入る
        out, notes = self.release('v5.2.0')
        self.assertIn('release notes from v5.2.0', out)
        self.assertIn('v5.2.0...v5.4.0', notes)
        self.assertIn('skipped change', notes)
        self.assertIn('latest change', notes)

    def test_the_nearest_published_tag_is_the_base(self):
        out, notes = self.release('v5.2.0 v5.3.0')
        self.assertIn('release notes from v5.3.0', out)
        self.assertNotIn('skipped change', notes)
        self.assertIn('latest change', notes)

    def test_without_any_release_the_previous_tag_is_used(self):
        out, _ = self.release('')
        self.assertIn('release notes from v5.3.0', out)

    def test_prerelease_and_other_tags_are_not_the_base(self):
        self.git('tag', 'v5.3.1-rc.1', 'v5.3.0')
        self.git('tag', 'v5.3.9x', 'v5.3.0')
        out, _ = self.release('v5.2.0 v5.3.1-rc.1 v5.3.9x')
        self.assertIn('release notes from v5.2.0', out)


if __name__ == '__main__':
    unittest.main()
