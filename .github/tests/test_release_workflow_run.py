"""関門を失敗させて公開が止まることを、publish.yml の release ジョブを手元で流して確かめる（設計の追記 R2b-2 §5、I13）。

bare のリモートに版上げの squash 相当のコミットとタグを作り、そのタグの clone で手順を流す。sbt・gpg・gh は偽物、
GitHub の API・Portal・repo1 は手元の HTTP のスタブ。秘密の値はダミー。
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from central_portal_support import Stub, portal
from release_test_support import GITHUB, RELEASE, REPOSITORY, VERSION_FILE, pull, serve_required
from release_workflow_canonical import release_steps
from test_release_rehearsal import FAKE_GH
from workflow_runner import Runner, UnknownExpression, default_environment

gate = portal.gate
RUN_ID = portal.FIRST_COUNTED_RUN_ID + 500
DEPLOYMENT = '44444444-4444-4444-8444-444444444444'
# 秘密の値はどれも明らかな偽物。PGP_SECRET は base64 として読める偽物（central-bundle.sh が base64 -d して偽の gpg に渡す）
SECRETS = {'SONATYPE_USERNAME': 'dummy-sonatype-username', 'SONATYPE_PASSWORD': 'dummy-sonatype-password',
           'PGP_SECRET': 'fakefakefakefake', 'PGP_PASSPHRASE': 'dummy-pgp-passphrase'}
FAKE_SBT = '''#!/usr/bin/env python3
# 偽の sbt。呼ばれた引数を残し、+print version には全プロジェクトの版を、+publishSigned には 12 の成果物の
# 24 ファイル（本体4つと .asc、その全部の .md5・.sha1）を file:// の先に書く。
import hashlib, os, re, sys
from pathlib import Path
with open(os.environ['FAKE_SBT_CALLS'], 'a') as calls:
    calls.write(' '.join(sys.argv[1:]) + '\\n')
version = os.environ['FAKE_SBT_VERSION']
if sys.argv[1:] == ['-batch', '--error', '-Dsbt.supershell=false', '-Dsbt.color=false', '+print version']:
    print('[info] this line goes to standard error', file=sys.stderr)
    for _ in ('2.13', '3'):
        for project in ('core', 'dynamodb', 'spanner', 'bigtable', 'sqlite', 'coreOnly', 'dynamodbOnly'):
            print(f'{project} / version\\n\\t{version}')
        # supershell の消去の制御列（CI の実物で見た ESC[0J）が混じっても読める
        print(f'version\\n\\t{version}\\n\\x1b[0J')
elif sys.argv[-1] == '+publishSigned':
    repository = re.search(r'"file://([^"]+)"', sys.argv[-2]).group(1)
    for artifact in os.environ['FAKE_ARTIFACTS'].split():
        directory = Path(repository, 'io/github/j5ik2o', artifact, version)
        directory.mkdir(parents=True)
        for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom'):
            for extra in ('', '.asc'):
                name = f'{artifact}-{version}{suffix}{extra}'
                (directory / name).write_bytes(b'x')
                (directory / (name + '.md5')).write_text(hashlib.md5(b'x').hexdigest())
                (directory / (name + '.sha1')).write_text(hashlib.sha1(b'x').hexdigest())
else:
    sys.exit('unexpected sbt call')
'''
# 偽の gpg と gpgconf（鍵の読み込みと agent の停止）。読み込んだ鍵の中身は捨てる
FAKE_GPG = '#!/bin/sh\ncat > /dev/null\necho "gpg $*" >> "$FAKE_SBT_CALLS"\n'
FAKE_GPGCONF = '#!/bin/sh\nexit 0\n'
GATE_FAILED = 'Check the tagged commit against the release gate'


class ReleaseWorkflowRunTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)
        self.stub = Stub()
        self.addCleanup(self.stub.close)
        self.bin = self.directory / 'bin'
        self.bin.mkdir()
        for name, script in (('gh', FAKE_GH), ('sbt', FAKE_SBT), ('gpg', FAKE_GPG), ('gpgconf', FAKE_GPGCONF)):
            (self.bin / name).write_text(script)
            (self.bin / name).chmod(0o755)
        self.pulls = self.directory / 'pulls.json'
        self.sbt_calls = self.directory / 'sbt-calls'
        self.sbt_calls.write_text('')
        (self.directory / 'gitconfig').write_text(
            '[user]\n\tname = Test\n\temail = test@example.com\n[init]\n\tdefaultBranch = main\n')
        self.git_env = {**os.environ, 'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'), 'GIT_CONFIG_NOSYSTEM': '1'}
        self.remote = self.directory / 'remote.git'
        self.work = self.directory / 'work'
        self.git(self.directory, 'init', '--bare', str(self.remote))
        self.git(self.directory, 'init', str(self.work))
        shutil.copytree(GITHUB, self.work / '.github', ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        self.uploads = []
        self.github_runs = [self.run_item(RUN_ID, 'v5.3.0')]
        self.jobs = {RUN_ID: []}
        self.statuses = {}
        self.repo1 = 404
        self.required = 200       # 公開先に必要な版（5.2.0）の12の POM。既定では出ている
        self.stub.routes[('GET', f'/repos/{REPOSITORY}/actions/workflows/publish.yml/runs')] = \
            lambda request: (200, {'total_count': len(self.github_runs), 'workflow_runs': self.github_runs})

    # ------------------------------------------------------------ 道具
    def git(self, cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, env=self.git_env, check=True, text=True,
                              capture_output=True).stdout.strip()

    def run_item(self, run_id, tag, attempt=1):
        now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        return {'id': run_id, 'created_at': now, 'head_branch': tag, 'event': 'push', 'run_attempt': attempt}

    def history(self, base='5.2.0', release='5.3.0', tag=None, extra_file=False, head='release/v5.3.0'):
        """scala の形：v5.2.0（軽量タグ、version なし）→ R5（version を base で足す）→ release の版だけを変えた
        squash 相当のコミット。タグ（既定 v<release>）を付けて push する。"""
        (self.work / 'build.sbt').write_text('// v5.2.0\n')
        self.git(self.work, 'add', '.')
        self.git(self.work, 'commit', '-m', 'feat: v5.2.0')
        self.git(self.work, 'tag', 'v5.2.0')
        (self.work / VERSION_FILE).write_text(base + '\n')
        self.git(self.work, 'add', VERSION_FILE)
        self.git(self.work, 'commit', '-m', 'ci(release): R5')
        if release is None:
            return self.push_and_clone(tag, head)
        (self.work / VERSION_FILE).write_text(release + '\n')
        if extra_file:
            (self.work / 'feature.txt').write_text('x\n')
            self.git(self.work, 'add', 'feature.txt')
        self.git(self.work, 'commit', '-am', f'version up to v{release} (#1)')
        return self.push_and_clone(tag or f'v{release}', head)

    def push_and_clone(self, tag, head):
        sha = self.git(self.work, 'rev-parse', 'HEAD')
        self.git(self.work, 'tag', '-a', tag, '-m', tag)
        self.git(self.work, 'remote', 'add', 'origin', str(self.remote))
        self.git(self.work, 'push', 'origin', 'main', '--tags')
        self.pulls.write_text(json.dumps([pull(sha, ref=head)]))
        workspace = self.directory / 'workspace'
        self.git(self.directory, 'clone', str(self.remote), str(workspace))
        self.git(workspace, 'checkout', '-q', tag)
        return workspace, tag

    def portal_routes(self, publish_state='PUBLISHED'):
        def upload(request):
            self.uploads.append(request)
            return 201, DEPLOYMENT

        name = portal.deployment_name('5.3.0', RUN_ID, self.attempt)
        self.stub.routes[('POST', '/api/v1/publisher/upload')] = upload
        self.stub.routes[('POST', '/api/v1/publisher/status')] = \
            lambda request: (200, {'deploymentId': DEPLOYMENT, 'deploymentState': publish_state, 'deploymentName': name})
        for artifact in portal.bundle.ARTIFACTS:
            path = f'/{portal.bundle.GROUP_PATH}/{artifact}/5.3.0/{artifact}-5.3.0.pom'
            self.stub.routes[('HEAD', '/repo1' + path)] = lambda request: (self.repo1, b'')
        serve_required(self.stub, lambda artifact: self.required)
        for run_id, jobs in self.jobs.items():
            self.stub.routes[('GET', f'/repos/{REPOSITORY}/actions/runs/{run_id}/jobs')] = \
                lambda request, jobs=jobs: (200, {'total_count': len(jobs), 'jobs': jobs})
            self.stub.routes[('GET', f'/repos/{REPOSITORY}/actions/runs/{run_id}/artifacts')] = \
                lambda request: (200, {'total_count': 0, 'artifacts': []})

    def run_release(self, workspace, tag, attempt=1, sbt_version='5.3.0', step_env=None, workflow=RELEASE):
        self.attempt = attempt
        self.portal_routes()
        temp = self.directory / 'runner-temp'
        temp.mkdir(exist_ok=True)
        environment = default_environment(workspace, temp, REPOSITORY, RUN_ID, attempt, self.stub.url,
                                          f'{self.bin}{os.pathsep}{os.environ["PATH"]}', self.directory)
        environment.update({
            'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'), 'GIT_CONFIG_NOSYSTEM': '1',
            'FAKE_PULLS': str(self.pulls), 'FAKE_GH_CALLS': str(self.directory / 'gh-calls'), 'PULLS_RETRY_SECONDS': '0',
            'FAKE_SBT_CALLS': str(self.sbt_calls), 'FAKE_SBT_VERSION': sbt_version,
            'FAKE_ARTIFACTS': ' '.join(portal.bundle.ARTIFACTS),
            'CENTRAL_API_URL': self.stub.url, 'REPO1_URL': self.stub.url + '/repo1',
        })
        runner = Runner(workflow, workspace, temp, {'token': 'ghs_dummy', 'ref_name': tag, 'run_attempt': str(attempt)},
                        environment, SECRETS, {'CENTRAL_DEPLOYMENTS': ''}, step_env)
        return runner.run()

    def repo1_calls(self):
        """公開する版の repo1 の照会（公開先に必要な版の照会は除く）。"""
        required = f'/{gate.REQUIRED_PUBLISHED}/'
        return [call for call in self.stub.calls if '/repo1/' in call['path'] and required not in call['path']]

    def portal_calls(self):
        return [call['path'] for call in self.stub.calls if call['path'].startswith('/api/')]

    def assert_stopped_by_the_gate(self, result, message):
        self.assertEqual(result.outcome('release_gate'), 'failure')
        gate = next(step for step in result.steps if step.id == 'release_gate')
        self.assertTrue(any(line.startswith('::error::') and message in line for line in gate.stderr.splitlines()),
                        gate.stderr)
        self.assertEqual(result.ran(), ['Check out the tagged commit', 'Set up Python', 'release_gate'])
        self.assertEqual(self.sbt_calls.read_text(), '')
        self.assertEqual(self.portal_calls(), [])
        self.assertEqual(self.repo1_calls(), [])
        self.assertEqual(result.artifacts, [])
        self.assertTrue(all(not step.secrets for step in result.steps if step.outcome != 'skipped'))
        self.assertFalse(result.succeeded)

    # ------------------------------------------------------------ 関門で止まる
    def test_the_gate_stops_a_commit_that_is_not_the_merge_of_the_version_pull_request(self):
        workspace, tag = self.history(head='feature/x')
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'is not the squash merge of a pull request')

    def test_the_gate_stops_a_commit_that_changes_code_with_the_version(self):
        workspace, tag = self.history(extra_file=True)
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'the commit must change only version')

    def test_the_gate_stops_a_lower_version(self):
        workspace, tag = self.history(base='5.3.1')
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'must be greater than the previous version')

    def test_the_gate_stops_a_tag_that_does_not_match_the_version(self):
        workspace, tag = self.history(tag='v5.3.1')
        self.github_runs = [self.run_item(RUN_ID, 'v5.3.1')]
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), '5.3.0 must be greater than the latest tag v5.3.1')

    def test_the_gate_stops_a_tag_on_the_merge_that_adds_the_version_file(self):
        # B3：R5 のマージ（親に version が無い）に手でタグを打っても公開しない
        workspace, tag = self.history(release=None, tag='v5.2.1', head='j5ik2o/release-path-r5')
        self.github_runs = [self.run_item(RUN_ID, 'v5.2.1')]
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'has no version')

    def test_the_gate_stops_every_version_until_the_required_version_is_on_repo1(self):
        # A1：5.2.0 が repo1 に出るまで、bundle・アップロード・秘密の値を持つ手順に届かない
        workspace, tag = self.history()
        self.required = 404
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), '5.3.0 must wait until 5.2.0 is published')

    def test_the_gate_stops_when_repo1_cannot_be_read(self):
        workspace, tag = self.history()
        self.required = 503
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'could not read whether 5.2.0 is on Maven Central')

    # ------------------------------------------------------------ 関門を通る
    def test_a_release_tag_publishes_once_and_records_the_id(self):
        workspace, tag = self.history()
        result = self.run_release(workspace, tag)
        self.assertTrue(result.succeeded, [(step.id, step.stderr[-500:]) for step in result.steps])
        expected = [item['id'] or item['name'] for item in release_steps(RELEASE)]
        self.assertEqual(result.ran(), expected)
        self.assertEqual(len(result.steps), len(release_steps(RELEASE)))
        calls = self.sbt_calls.read_text().splitlines()
        self.assertEqual(calls[0], '-batch --error -Dsbt.supershell=false -Dsbt.color=false +print version')
        self.assertEqual(sum(line.endswith('+publishSigned') for line in calls), 1)
        self.assertTrue(calls[1].startswith('gpg --batch --import'), calls)
        self.assertEqual(len(self.uploads), 1)
        name = portal.deployment_name('5.3.0', RUN_ID, 1)
        self.assertEqual(self.uploads[0]['query']['name'], [name])
        self.assertIn(f'filename="{name}.zip"'.encode(), self.uploads[0]['body'])
        self.assertEqual(result.artifacts, [(f'central-deployment-1-{DEPLOYMENT}',
                                             str(self.directory / 'runner-temp' / 'central-deployment.txt'))])
        self.assertEqual(result.outputs, {'action': 'upload', 'state': 'PUBLISHED'})
        secrets = {step.id: step.secrets for step in result.steps if step.secrets}
        self.assertEqual(secrets, {'precheck': {'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'},
                                   'bundle': {'PGP_SECRET', 'PGP_PASSPHRASE'},
                                   'upload': {'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'},
                                   'deployment': {'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'}})

    def test_a_published_version_is_not_uploaded_again(self):
        workspace, tag = self.history()
        self.repo1 = 200
        result = self.run_release(workspace, tag)
        self.assertTrue(result.succeeded)
        for step_id in ('bundle', 'upload', 'record', 'deployment'):
            self.assertEqual(result.outcome(step_id), 'skipped', step_id)
        self.assertEqual(result.outputs, {'action': 'published', 'state': ''})
        self.assertEqual(self.uploads, [])
        self.assertNotIn('publishSigned', self.sbt_calls.read_text())

    def test_a_different_build_version_stops_before_the_precheck(self):
        workspace, tag = self.history()
        result = self.run_release(workspace, tag, sbt_version='5.3.1')
        self.assertEqual(result.outcome('build_version'), 'failure')
        self.assertEqual(result.outcome('precheck'), 'skipped')
        self.assertEqual([call for call in self.stub.calls if not call['path'].startswith('/repos/o/r/commits')
                          and f'/{gate.REQUIRED_PUBLISHED}/' not in call['path']], [])
        self.assertEqual(result.artifacts, [])

    def test_an_earlier_attempt_without_an_id_stops_the_precheck(self):
        workspace, tag = self.history()
        upload_step = [{'name': name, 'status': 'completed', 'conclusion': 'success'}
                       for name in sorted(portal.PRE_UPLOAD_STEPS) + [portal.UPLOAD_STEP]]
        self.jobs[RUN_ID] = [{'name': portal.RELEASE_JOB, 'run_attempt': 1, 'started_at': '2026-11-26T00:00:00Z',
                              'completed_at': '2026-11-26T00:10:00Z', 'runner_id': 1, 'runner_name': 'GitHub Actions 1',
                              'conclusion': 'failure', 'steps': upload_step}]
        self.github_runs = [self.run_item(RUN_ID, 'v5.3.0', attempt=2)]
        result = self.run_release(workspace, tag, attempt=2)
        self.assertEqual(result.outcome('precheck'), 'failure')
        self.assertIn(f'{RUN_ID}/1=<deployment ID|none>', next(s for s in result.steps if s.id == 'precheck').stderr)
        for step_id in ('bundle', 'upload', 'record', 'deployment'):
            self.assertEqual(result.outcome(step_id), 'skipped', step_id)
        self.assertEqual(self.uploads, [])
        self.assertNotIn('publishSigned', self.sbt_calls.read_text())

    def test_the_id_is_recorded_when_the_upload_step_fails_after_writing_it(self):
        workspace, tag = self.history()
        broken = self.directory / 'summary-is-a-directory'
        broken.mkdir()
        result = self.run_release(workspace, tag, step_env={'upload': {'GITHUB_STEP_SUMMARY': str(broken)}})
        self.assertEqual(result.outcome('upload'), 'failure')
        self.assertEqual(result.outcome('record'), 'success')
        self.assertEqual(result.outcome('deployment'), 'skipped')
        self.assertEqual(result.artifacts[0][0], f'central-deployment-1-{DEPLOYMENT}')
        self.assertEqual(len(self.uploads), 1)

    # ------------------------------------------------------------ 実行器そのもの
    def test_the_runner_refuses_what_it_does_not_model(self):
        workspace, tag = self.history()
        for old, new in (("if: steps.precheck.outputs.action == 'upload'\n        env:\n          RELEASE_TAG",
                          "if: success()\n        env:\n          RELEASE_TAG"),
                         ('${{ github.ref_name }}', '${{ github.event.ref }}'),
                         ('uses: actions/setup-python@v7', 'uses: some/action@v1'),
                         ('PGP_SECRET: ${{ secrets.PGP_SECRET }}', "PGP_SECRET: ${{ format('{0}', secrets.PGP_SECRET) }}"),
                         # ジョブの env の式も、写していなければ拒否する（#924 の CodeRabbit の指摘）
                         ('      JAVA_OPTS: -Xms2048M', '      JAVA_OPTS: ${{ github.event.ref }} -Xms2048M')):
            with self.subTest(new):
                with self.assertRaises(UnknownExpression):
                    self.run_release(workspace, tag, workflow=RELEASE.replace(old, new, 1))


    def bare_runner(self):
        return Runner(RELEASE, self.directory, self.directory, {}, {}, {}, {})

    def test_the_runner_compares_strings_like_actions(self):
        # Actions の == と != は大文字と小文字を区別しない（#924 の CodeRabbit の指摘）
        runner = self.bare_runner()
        runner.step_outputs = {'precheck': {'action': 'upload'}}
        self.assertTrue(runner.condition("steps.precheck.outputs.action == 'UPLOAD'", False))
        self.assertFalse(runner.condition("steps.precheck.outputs.action != 'Upload'", False))
        self.assertFalse(runner.condition("steps.precheck.outputs.action == 'upload'", True))
        self.assertTrue(runner.condition("always() && steps.precheck.outputs.action == 'upload'", True))

    def test_the_runner_records_an_artifact_only_when_its_file_exists(self):
        # upload-artifact は、ファイルが無ければ error のときだけ失敗し、ほかは何も残さない（#924 の CodeRabbit の指摘）
        missing = self.directory / 'missing.txt'
        for mode, error in (('error', True), ('warn', False), ('ignore', False), (None, False)):
            with self.subTest(mode=mode):
                runner = self.bare_runner()
                runner.artifacts = []
                setting = f'          if-no-files-found: {mode}\n' if mode else ''
                item = {'uses': 'actions/upload-artifact@v7',
                        'text': f'\n        with:\n          name: record\n          path: {missing}\n{setting}'}
                self.assertEqual(bool(runner.uses(item, set())), error)
                self.assertEqual(runner.artifacts, [])
        missing.write_text('x')
        runner.uses(item, set())
        self.assertEqual(runner.artifacts, [('record', str(missing))])


if __name__ == '__main__':
    unittest.main()
