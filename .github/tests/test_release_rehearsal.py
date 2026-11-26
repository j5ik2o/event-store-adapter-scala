import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from central_portal_support import Stub
from release_test_support import BUMP, GITHUB, LOOPBACK, REPOSITORY, VERSION_FILE, outputs, pull, run_body, serve_required
from test_release_workflow_shape import GATE_STEP

FAKE_GH = '''#!/bin/sh
# 偽の gh。api は FAKE_GH_FAIL 回だけ失敗し、続く FAKE_GH_EMPTY 回は [] を返し、その後に $FAKE_PULLS を返す。
calls=$(cat "$FAKE_GH_CALLS" 2>/dev/null || echo 0)
calls=$((calls + 1))
echo "$calls" > "$FAKE_GH_CALLS"
if [ "$calls" -le "${FAKE_GH_FAIL:-0}" ]; then
  echo "HTTP 502" >&2
  exit 1
fi
if [ "$calls" -le $(( ${FAKE_GH_FAIL:-0} + ${FAKE_GH_EMPTY:-0} )) ]; then
  echo '[]'
  exit 0
fi
cat "$FAKE_PULLS"
'''


class ReleaseRehearsalTest(unittest.TestCase):
    """bare のリモートに対して、版の枝 → squash 相当のマージ → 関門 → 打つはずのタグの報告までを手元で通す。"""

    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)
        self.remote = self.directory / 'remote.git'
        self.work = self.directory / 'work'
        fake_bin = self.directory / 'bin'
        fake_bin.mkdir()
        (fake_bin / 'gh').write_text(FAKE_GH)
        (fake_bin / 'gh').chmod(0o755)
        self.pulls = self.directory / 'pulls.json'
        self.pulls.write_text('[]')
        self.summary = self.directory / 'summary.md'
        # repo1 のスタブ。公開先に必要な版（5.2.0）は、既定では12とも出ている
        self.stub = Stub()
        self.addCleanup(self.stub.close)
        self.required = 200
        serve_required(self.stub, lambda artifact: self.required)
        self.env = {
            **os.environ,
            'REPO1_URL': self.stub.url + '/repo1', 'no_proxy': LOOPBACK, 'NO_PROXY': LOOPBACK,
            'PATH': f'{fake_bin}{os.pathsep}{os.environ["PATH"]}',
            'FAKE_PULLS': str(self.pulls),
            'FAKE_GH_CALLS': str(self.directory / 'gh-calls'),
            'PULLS_RETRY_SECONDS': '0',
            'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'),
            'GIT_CONFIG_NOSYSTEM': '1',
            'RUNNER_TEMP': str(self.directory),
            'GITHUB_STEP_SUMMARY': str(self.summary),
        }
        (self.directory / 'gitconfig').write_text(
            '[user]\n\tname = Test\n\temail = test@example.com\n[init]\n\tdefaultBranch = main\n')
        self.git(self.directory, 'init', '--bare', str(self.remote))
        self.git(self.directory, 'init', str(self.work))
        shutil.copytree(GITHUB, self.work / '.github', ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        # scala の形：v5.2.0 は version の無いコミットの軽量タグ。R5 が version（5.2.0）を足す
        (self.work / 'build.sbt').write_text('// v5.2.0\n')
        self.git(self.work, 'add', '.')
        self.git(self.work, 'commit', '-m', 'feat: v5.2.0')
        self.git(self.work, 'tag', 'v5.2.0')
        (self.work / VERSION_FILE).write_text('5.2.0\n')
        self.git(self.work, 'add', VERSION_FILE)
        self.git(self.work, 'commit', '-m', 'ci(release): add the version file (R5)')
        (self.work / 'feature.txt').write_text('x\n')
        self.git(self.work, 'add', 'feature.txt')
        self.git(self.work, 'commit', '-m', 'feat: add a feature')
        self.git(self.work, 'remote', 'add', 'origin', str(self.remote))
        self.git(self.work, 'push', 'origin', 'main', '--tags')

    def git(self, cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, env=self.env, check=True, text=True,
                              capture_output=True).stdout.strip()

    def step(self, step_id, cwd, workflow=BUMP, **env):
        output = self.directory / ('output-' + step_id)
        output.write_text('')
        result = subprocess.run(
            ['bash', '-e', '-c', run_body(workflow, step_id)], cwd=cwd,
            env={**self.env, 'GITHUB_WORKSPACE': str(cwd), 'GITHUB_OUTPUT': str(output),
                 'GITHUB_REPOSITORY': REPOSITORY, 'SERVER_URL': 'https://github.com',
                 'PUSH_TOKEN': 'dummy', **env},
            capture_output=True, text=True,
        )
        return result, outputs(output)

    def remote_ref(self, ref):
        return self.git(self.remote, 'rev-parse', ref)

    def remote_tags(self):
        return self.git(self.remote, 'tag', '-l')

    def clone(self, ref='main'):
        target = Path(tempfile.mkdtemp(dir=self.directory)) / 'clone'
        self.git(self.directory, 'clone', str(self.remote), str(target))
        self.git(target, 'checkout', ref)
        return target

    def open_version_branch(self):
        result, out = self.step('version_branch', self.work, NEXT_TAG='v5.3.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out, {'branch': 'release/v5.3.0'})
        result, _ = self.step('version_commit', self.work, NEXT_VERSION='5.3.0', NEXT_TAG='v5.3.0',
                              BRANCH=out['branch'])
        self.assertEqual(result.returncode, 0, result.stderr)

    def squash_merge(self, ref='release/v5.3.0'):
        merged = self.clone()
        self.git(merged, 'merge', '--squash', 'origin/' + ref)
        self.git(merged, 'commit', '-m', 'version up to v5.3.0 (#1)')
        self.git(merged, 'push', 'origin', 'main')
        sha = self.git(merged, 'rev-parse', 'HEAD')
        self.pulls.write_text(json.dumps([pull(sha, ref=ref)]))
        return sha

    def trigger(self, sha, **env):
        (self.directory / 'gh-calls').unlink(missing_ok=True)
        return self.step('trigger', self.clone(sha), RELEASE_SHA=sha, **env)

    def merged_release(self):
        self.open_version_branch()
        return self.squash_merge()

    def test_version_branch_changes_only_the_version_and_leaves_main(self):
        main_before = self.remote_ref('main')
        self.open_version_branch()
        self.assertEqual(self.remote_ref('main'), main_before)
        branch = self.remote_ref('release/v5.3.0')
        self.assertEqual(self.git(self.remote, 'rev-parse', branch + '^'), main_before)
        self.assertEqual(self.git(self.remote, 'diff', '--name-only', main_before, branch), VERSION_FILE)
        self.assertEqual(self.git(self.remote, 'cat-file', 'blob', branch + ':' + VERSION_FILE), '5.3.0')
        blob = subprocess.run(['git', 'cat-file', 'blob', branch + ':' + VERSION_FILE], cwd=self.remote,
                              env=self.env, check=True, capture_output=True).stdout
        self.assertEqual(blob, b'5.3.0\n')
        self.assertEqual(self.git(self.remote, 'log', '-1', '--format=%s', branch), 'version up to v5.3.0')
        self.assertEqual(self.remote_tags(), 'v5.2.0')

    def test_existing_version_branch_stops_a_second_run(self):
        self.open_version_branch()
        result, out = self.step('version_branch', self.work, NEXT_TAG='v5.3.0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already exists', result.stdout)
        self.assertEqual(out, {})

    def test_pull_request_body_previews_the_release_notes(self):
        for rehearsal in ('true', 'false'):
            with self.subTest(rehearsal=rehearsal):
                result, out = self.step('pr_body', self.work, PREV_TAG='v5.2.0', NEXT_VERSION='5.3.0',
                                        NEXT_TAG='v5.3.0', REHEARSAL=rehearsal)
                self.assertEqual(result.returncode, 0, result.stderr)
                body = Path(out['path']).read_text()
                self.assertIn('Bump `version` to `5.3.0`', body)
                self.assertIn('(https://github.com/o/r/compare/v5.2.0...release/v5.3.0)', body)
                self.assertIn('add a feature', body)
                self.assertEqual('**Rehearsal. Do not merge.**' in body, rehearsal == 'true')

    def commit(self, subject, body=''):
        (self.work / 'feature.txt').write_text(subject + body + '\n')
        message = subject + ('\n\n' + body if body else '')
        self.git(self.work, 'commit', '-am', message)

    def test_a_breaking_change_footer_makes_a_major_version(self):
        # 件名が Conventional Commits の型でなくても、本文の BREAKING CHANGE: / BREAKING-CHANGE: は major（java#866 の穴）
        for footer in ('BREAKING CHANGE', 'BREAKING-CHANGE'):
            with self.subTest(footer=footer):
                self.commit('Remove the old API', f'{footer}: the old API is gone')
                result, out = self.step('changes', self.work, FORCE_BUMP='false', LEVEL='auto', VERSION='')
                self.assertEqual(result.returncode, 0, result.stderr)
                # R5 の ci: と feat: と、この footer のコミット
                self.assertEqual(out, {'count': '3', 'bump': 'major'})
                result, out = self.step('semver_level', self.work, BUMP=out['bump'], PREV_TAG='v5.2.0')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(out, {'semver_level': 'major'})
                self.git(self.work, 'reset', '-q', '--hard', 'HEAD^')

    def test_an_explicit_level_alone_does_not_bump_and_says_so(self):
        # この repository の規則（R5 の前から）：数えるコミットが無ければ、明示の level だけでは上げない。
        # force_bump か version で上げる。上げないときはログに書く（java#868 の 1）
        self.commit('Update the readme')
        self.git(self.work, 'tag', 'v5.2.1')
        for level, force, count in (('auto', 'false', '0'), ('patch', 'false', '0'), ('major', 'false', '0'),
                                    ('minor', 'true', '1')):
            with self.subTest(level=level, force=force):
                result, out = self.step('changes', self.work, FORCE_BUMP=force, LEVEL=level, VERSION='')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(out['count'], count)
                self.assertEqual('nothing to release' in result.stdout, count == '0')

    def test_the_level_and_the_next_version_follow_the_commits(self):
        result, out = self.step('changes', self.work, FORCE_BUMP='false', LEVEL='auto', VERSION='')
        self.assertEqual(out, {'count': '2', 'bump': ''})
        result, out = self.step('semver_level', self.work, BUMP='', PREV_TAG='v5.2.0')
        self.assertEqual((result.returncode, out), (0, {'semver_level': 'minor'}), result.stderr)
        result, out = self.step('versions', self.work, REQUESTED_VERSION='', PREV_TAG='v5.2.0', SEMVER_LEVEL='minor')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out, {'next_version': '5.3.0', 'next_tag': 'v5.3.0'})

    def test_the_merge_that_adds_the_version_file_tags_nothing(self):
        # B3：R5 のマージ（親に version が無い）でタグのジョブが走っても、何もしないで成功する。5.2.0 が repo1 に無くても同じ
        r5 = self.git(self.work, 'rev-parse', 'HEAD^')
        self.required = 404
        result, out = self.trigger(r5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out, {'tag': ''})
        self.assertIn('has no version', result.stderr)
        self.assertEqual(self.remote_tags(), 'v5.2.0')

    def test_previous_tag_is_the_latest_stable_tag(self):
        self.git(self.work, 'tag', 'v9.0.0-rc.1')
        self.git(self.work, 'tag', 'not-a-version')
        result, out = self.step('defines', self.work)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out, {'prev_tag': 'v5.2.0'})

    def test_merged_version_pull_request_passes_the_gate_and_the_tag_is_only_reported(self):
        self.open_version_branch()
        branch_commit = self.remote_ref('release/v5.3.0')
        after = self.squash_merge()
        self.assertNotEqual(after, branch_commit)

        result, out = self.trigger(after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], 'v5.3.0')
        result, _ = self.step('tag_summary', self.clone(after), RELEASE_TAG='v5.3.0', RELEASE_SHA=after)
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = self.summary.read_text()
        self.assertIn(f'would push `v5.3.0` on `{after}`', summary)
        self.assertIn("`PUSH_RELEASE_TAG` is `'false'`", summary)
        self.assertEqual(self.remote_tags(), 'v5.2.0')

    def test_tag_push_step_tags_the_merged_commit_once_it_is_switched_on(self):
        after = self.merged_release()
        result, _ = self.step('release_tag', self.clone(after), RELEASE_TAG='v5.3.0', RELEASE_SHA=after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.remote_ref('v5.3.0^{commit}'), after)
        result, out = self.trigger(after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')

    def publish_gate(self, tag):
        """R2b の release.yml に入れる関門の手順（形のテストで固定した本文）を、そのまま動かす。"""
        result, _ = self.step('release_gate', self.clone(tag), workflow=GATE_STEP + '\n', RELEASE_TAG=tag)
        return result

    def test_the_fixed_publish_gate_step_passes_only_the_tagged_version_commit(self):
        after = self.merged_release()
        result, _ = self.step('release_tag', self.clone(after), RELEASE_TAG='v5.3.0', RELEASE_SHA=after)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.publish_gate('v5.3.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[-1], 'v5.3.0')

    def test_the_fixed_publish_gate_step_fails_on_a_hand_made_tag(self):
        work = self.clone()
        (work / VERSION_FILE).write_text('5.3.0\n')
        self.git(work, 'commit', '-am', 'version up to v5.3.0')
        self.git(work, 'tag', '-a', 'v5.3.0', '-m', 'v5.3.0')
        self.git(work, 'push', 'origin', 'v5.3.0')
        result = self.publish_gate('v5.3.0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('is not reachable from main', result.stderr)

    def test_versions_wait_until_the_required_version_is_on_repo1(self):
        # 5.2.0 が repo1 に出る前に版上げの PR をマージしても、タグは付かない。出たあとの再実行で付く（A1、I8b）
        after = self.merged_release()
        self.required = 404
        result, out = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('5.3.0 must wait until 5.2.0 is published on Maven Central', result.stderr)
        self.assertEqual(out, {})
        self.required = 200
        result, out = self.trigger(after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], 'v5.3.0')

    def test_some_artifacts_of_the_required_version_are_not_enough(self):
        after = self.merged_release()
        self.required = None
        serve_required(self.stub, lambda artifact: 404 if artifact.endswith('-sqlite_3') else 200)
        result, out = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must wait until 5.2.0', result.stderr)

    def test_an_unreadable_repo1_refuses_newer_versions(self):
        after = self.merged_release()
        self.required = 503
        result, out = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('could not read whether 5.2.0 is on Maven Central', result.stderr)
        self.assertEqual(out, {})

    def test_an_unreadable_repo1_does_not_fail_a_push_that_releases_nothing(self):
        # 照会が要らない判定（版が変わっていない）は、repo1 が読めなくても成功で終わる（設計レビュー M2）
        (self.work / 'feature.txt').write_text('z\n')
        self.git(self.work, 'commit', '-am', 'fix: change the feature')
        self.git(self.work, 'push', 'origin', 'main')
        self.required = 503
        result, out = self.trigger(self.remote_ref('main'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')

    def test_the_fixed_publish_gate_step_waits_for_the_required_version(self):
        after = self.merged_release()
        result, _ = self.step('release_tag', self.clone(after), RELEASE_TAG='v5.3.0', RELEASE_SHA=after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.required = 404
        result = self.publish_gate('v5.3.0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must wait until 5.2.0', result.stderr)

    def test_tag_on_another_commit_fails(self):
        self.open_version_branch()
        before = self.remote_ref('main')
        after = self.squash_merge()
        other = self.clone(before)
        self.git(other, 'tag', '-a', 'v5.3.0', '-m', 'v5.3.0')
        self.git(other, 'push', 'origin', 'v5.3.0')
        result, _ = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already exists on another commit', result.stderr)

    def test_version_changed_by_another_pull_request_is_not_tagged(self):
        work = self.clone()
        self.git(work, 'switch', '-c', 'feature/x')
        (work / VERSION_FILE).write_text('5.3.0\n')
        self.git(work, 'commit', '-am', 'feat: bump by hand')
        self.git(work, 'push', 'origin', 'feature/x')
        after = self.squash_merge('feature/x')
        result, out = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('is not the squash merge', result.stderr)
        self.assertEqual(out, {})

    def test_merge_commit_of_the_version_branch_is_not_tagged(self):
        self.open_version_branch()
        merged = self.clone()
        self.git(merged, 'merge', '--no-ff', '-m', 'Merge release/v5.3.0', 'origin/release/v5.3.0')
        self.git(merged, 'push', 'origin', 'main')
        sha = self.git(merged, 'rev-parse', 'HEAD')
        self.pulls.write_text(json.dumps([pull(sha)]))
        result, out = self.trigger(sha)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('has 2 parents', result.stderr)
        self.assertEqual(out, {})

    def test_version_changed_with_code_is_not_tagged(self):
        work = self.clone()
        self.git(work, 'switch', '-c', 'release/v5.3.0')
        (work / VERSION_FILE).write_text('5.3.0\n')
        (work / 'feature.txt').write_text('y\n')
        self.git(work, 'commit', '-am', 'version up to v5.3.0')
        self.git(work, 'push', 'origin', 'release/v5.3.0')
        after = self.squash_merge()
        result, _ = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must change only version', result.stderr)

    def test_pull_request_lookup_is_retried_while_github_links_it(self):
        after = self.merged_release()
        result, out = self.trigger(after, FAKE_GH_FAIL='1', FAKE_GH_EMPTY='2')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], 'v5.3.0')
        self.assertEqual((self.directory / 'gh-calls').read_text().strip(), '4')

    def test_pull_request_that_never_appears_stops_without_a_tag(self):
        after = self.merged_release()
        result, out = self.trigger(after, FAKE_GH_EMPTY='9')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('no pull request is associated', result.stderr)
        self.assertEqual(out, {})
        self.assertEqual((self.directory / 'gh-calls').read_text().strip(), '5')

    def test_pull_request_lookup_that_keeps_failing_stops_without_a_tag(self):
        after = self.merged_release()
        result, out = self.trigger(after, FAKE_GH_FAIL='9')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('could not read the pull requests', result.stdout)
        self.assertEqual(out, {})

    def test_push_without_a_version_change_is_not_tagged(self):
        (self.work / 'feature.txt').write_text('z\n')
        self.git(self.work, 'commit', '-am', 'fix: change the feature')
        self.git(self.work, 'push', 'origin', 'main')
        result, out = self.trigger(self.remote_ref('main'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')

    def test_parent_without_a_version_file_is_not_released(self):
        work = self.clone()
        self.git(work, 'rm', '-q', VERSION_FILE)
        self.git(work, 'commit', '-m', 'chore: drop the version file')
        self.git(work, 'push', 'origin', 'main')
        (work / VERSION_FILE).write_text('5.3.0\n')
        self.git(work, 'add', VERSION_FILE)
        self.git(work, 'commit', '-m', 'version up to v5.3.0')
        self.git(work, 'push', 'origin', 'main')
        sha = self.git(work, 'rev-parse', 'HEAD')
        self.pulls.write_text(json.dumps([pull(sha)]))
        result, out = self.trigger(sha)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')
        self.assertIn('has no version', result.stderr)


if __name__ == '__main__':
    unittest.main()
