import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from central_portal_support import portal
from release_test_support import BUMP, RELEASE, WORKFLOWS, job, run_blocks, run_body, step, steps
from release_workflow_canonical import CANONICAL, release_has_gate, release_steps

PAT = '${{ secrets.PERSONAL_ACCESS_TOKEN }}'
SECRET_NAME = re.compile(r'\b(?:SONATYPE|SIGNING|PGP)_[A-Z_]+')
PGP = frozenset({'PGP_SECRET', 'PGP_PASSPHRASE'})
SONATYPE = frozenset({'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'})
# 秘密の値を受け取ってよい手順（release ジョブの手順の id）と、その秘密の名前（I12）
SECRET_STEPS = {'precheck': SONATYPE, 'bundle': PGP, 'upload': SONATYPE, 'deployment': SONATYPE}
UPLOAD_IF = "steps.precheck.outputs.action == 'upload'"
RECORD_IF = "always() && steps.upload.outputs.deployment != ''"
PUBLISHED_IF = "needs.release.outputs.action == 'published' || needs.release.outputs.state == 'PUBLISHED'"
STEP_IDS = [None, None, 'release_gate', None, None, 'build_version', 'precheck', 'bundle', 'release_gate_again', 'upload', 'record',
            'deployment']

# 関門の手順の本文（名前の行を除く）。正本の関門の手順は、これと一字一句同じ（test_release_rehearsal.py も使う）。
GATE_STEP = """        id: release_gate
        env:
          GH_TOKEN: ${{ github.token }}
          RELEASE_TAG: ${{ github.ref_name }}
        run: |
          GATE="${RUNNER_TEMP}/release-gate"
          RELEASE_SHA=$(git rev-parse "${RELEASE_TAG}^{commit}")
          bash "${GITHUB_WORKSPACE}"/.github/release-gate-inputs.sh "$RELEASE_SHA" "$GATE"
          python3 "${GITHUB_WORKSPACE}"/.github/release-gate.py check --mode publish --tag "$RELEASE_TAG" \\
            --repository "$GITHUB_REPOSITORY" --sha "$RELEASE_SHA" --on-main "$(cat "${GATE}/on-main")" \\
            --parents "${GATE}/parents" \\
            --parent-version "${GATE}/parent-version" --version-file "${GATE}/version" \\
            --changed-files "${GATE}/changed-files" --pulls "${GATE}/pulls.json" \\
            --remote-tags "${GATE}/remote-tags" --required-published "${GATE}/required-published\""""
# 失敗を越えて動かすもの、公開するコミットを替えるもの、手順の外から本文を変えるもの
SKIP_WORDS = ('always()', 'failure()', 'cancelled()', 'continue-on-error', 'git checkout', 'git switch',
              'git reset', 'ref:', '|| true', 'exit 0', 'shell:', 'defaults:', 'BASH_ENV', 'ENV:', 'working-directory')


def tag_push_allowed(bump, release):
    """PUSH_RELEASE_TAG が1つだけあり、'false' か、'true' で publish.yml が正本と同じか。止める向きは常に許す。"""
    settings = re.findall(r'PUSH_RELEASE_TAG: [^\n]*', bump + release)
    if len(settings) != 1 or settings[0] not in ("PUSH_RELEASE_TAG: 'false'", "PUSH_RELEASE_TAG: 'true'"):
        return False
    return settings[0] == "PUSH_RELEASE_TAG: 'false'" or release_has_gate(release)


def replace_once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new)


def move_step(text, step_id, before_id):
    """正本の手順 step_id を、手順 before_id の前に動かす。"""
    moved = step(text, step_id)
    text = text.replace(moved, '')
    target = step(text, before_id)
    return text.replace(target, moved + target)


class CanonicalJudgeTest(unittest.TestCase):
    """判定は、正本と行ごとに一字一句比べる（違ってよいのは uses: の版だけ）。"""

    def test_the_release_workflow_is_the_canonical_one(self):
        self.assertTrue(release_has_gate(RELEASE))
        self.assertTrue(release_has_gate(CANONICAL))

    def test_only_the_version_of_an_action_may_differ(self):
        for new in ('uses: actions/checkout@v8', 'uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v7'):
            with self.subTest(new):
                self.assertTrue(release_has_gate(CANONICAL.replace('uses: actions/checkout@v7', new)))
        for new in ('uses: actions/checkout@v7 # v7 extra', 'uses: other/checkout@v7', 'uses: actions/checkout@'):
            with self.subTest(new):
                self.assertFalse(release_has_gate(CANONICAL.replace('uses: actions/checkout@v7', new, 1)))

    def test_whitespace_and_line_changes_are_not_the_canonical_workflow(self):
        for text in (CANONICAL + 'x\n', CANONICAL + '\n', CANONICAL.rstrip('\n'), CANONICAL.replace('\njobs:\n', '\njobs: \n'),
                     CANONICAL.replace('    timeout-minutes: 150\n', '    timeout-minutes: 150\n\n'), '\n' + CANONICAL):
            with self.subTest(repr(text[-20:])):
                self.assertFalse(release_has_gate(text))

    def test_ways_around_the_gate_are_not_a_gate(self):
        gate_body = 'bash "${GITHUB_WORKSPACE}"/.github/release-gate-inputs.sh "$RELEASE_SHA" "$GATE"\n'
        upload_if = f"        id: upload\n        if: {UPLOAD_IF}\n"
        first_gate, second_gate = step(CANONICAL, 'release_gate'), step(CANONICAL, 'release_gate_again')

        def in_step(gate_text, old, new):
            # 関門の本文は2つの手順にあるので、壊すのは名指しした手順の中だけ
            return replace_once(CANONICAL, gate_text, replace_once(gate_text, old, new))
        unsafe = {
            # R2a の壊し方（関門の手順）
            'commented': CANONICAL.replace('          python3 "${GITHUB_WORKSPACE}"/.github/release-gate.py',
                                           '          # python3 "${GITHUB_WORKSPACE}"/.github/release-gate.py'),
            'if false': in_step(first_gate, '          ' + gate_body, '          if false; then\n          ' + gate_body),
            '&& false': in_step(first_gate, '          ' + gate_body, '          false && \\\n          ' + gate_body),
            'exit 0 in the gate': in_step(first_gate, '          ' + gate_body, '          exit 0\n          ' + gate_body),
            '|| true in the gate': in_step(first_gate, '"${GATE}/required-published"',
                                           '"${GATE}/required-published" || true'),
            'another tag': in_step(first_gate, 'RELEASE_TAG: ${{ github.ref_name }}\n        run: |\n          GATE=',
                                   'RELEASE_TAG: v5.2.0\n        run: |\n          GATE='),
            # 2つ目の関門も同じように壊せない
            'exit 0 in the second gate': in_step(second_gate, '          ' + gate_body, '          exit 0\n          ' + gate_body),
            '|| true in the second gate': in_step(second_gate, '"${GATE}/required-published"',
                                                  '"${GATE}/required-published" || true'),
            'second gate without if': in_step(second_gate, f'        if: {UPLOAD_IF}\n', ''),
            'no second gate': CANONICAL.replace(second_gate, ''),
            'second gate reuses the inputs': in_step(second_gate, 'GATE="${RUNNER_TEMP}/release-gate-again"',
                                                     'GATE="${RUNNER_TEMP}/release-gate"'),
            'gate if': replace_once(CANONICAL, '        id: release_gate\n', '        id: release_gate\n        if: false\n'),
            'continue-on-error': replace_once(CANONICAL, '        id: release_gate\n',
                                              '        id: release_gate\n        continue-on-error: true\n'),
            'gate shell': replace_once(CANONICAL, '        id: release_gate\n', '        id: release_gate\n        shell: sh {0}\n'),
            'other id': replace_once(CANONICAL, 'id: release_gate\n', 'id: other\n'),
            # 部分レビュー F1 の a〜e
            'a: precheck before the gate': move_step(CANONICAL, 'precheck', 'release_gate'),
            'b: upload without if': replace_once(CANONICAL, upload_if, '        id: upload\n'),
            'b: bundle without if': replace_once(CANONICAL, f'        id: bundle\n        if: {UPLOAD_IF}\n', '        id: bundle\n'),
            'b: precheck with if': replace_once(CANONICAL, '        id: precheck\n', f'        id: precheck\n        if: {UPLOAD_IF}\n'),
            'b: build_version with if': replace_once(CANONICAL, '        id: build_version\n',
                                                     f'        id: build_version\n        if: {UPLOAD_IF}\n'),
            'c: deployment if false': replace_once(CANONICAL, f'        id: deployment\n        if: {UPLOAD_IF}\n',
                                                   '        id: deployment\n        if: false\n'),
            'c: deployment || true': replace_once(CANONICAL, 'wait --id "$DEPLOYMENT"\n', 'wait --id "$DEPLOYMENT" || true\n'),
            'c: no deployment': CANONICAL.replace(step(CANONICAL, 'deployment'), ''),
            'd: precheck || true': replace_once(CANONICAL, 'precheck --version "${RELEASE_TAG#v}"\n',
                                                'precheck --version "${RELEASE_TAG#v}" || true\n'),
            'd: build_version || true': replace_once(CANONICAL, '--tag "$RELEASE_TAG"\n', '--tag "$RELEASE_TAG" || true\n'),
            'e: top-level defaults': replace_once(CANONICAL, '\njobs:\n', '\ndefaults:\n  run:\n    shell: bash {0}\njobs:\n'),
            'e: job defaults': replace_once(CANONICAL, '    timeout-minutes: 150\n',
                                            '    timeout-minutes: 150\n    defaults:\n      run:\n        shell: sh {0}\n'),
            'e: BASH_ENV': replace_once(CANONICAL, '    env:\n      JAVA_OPTS:', '    env:\n      BASH_ENV: .github/x\n      JAVA_OPTS:'),
            # ほか
            'record with a secret': replace_once(CANONICAL, '          if-no-files-found: error\n',
                                                 '          if-no-files-found: error\n        env:\n'
                                                 '          PGP_SECRET: ${{ secrets.PGP_SECRET }}\n'),
            'record with another if': replace_once(CANONICAL, f'if: {RECORD_IF}', 'if: always()'),
            'github release without if': replace_once(CANONICAL, f'    needs: release\n    if: {PUBLISHED_IF}\n    runs-on: ubuntu-latest\n'
                                                      '    permissions:\n      contents: write\n',
                                                      '    needs: release\n    runs-on: ubuntu-latest\n    permissions:\n      contents: write\n'),
            'job renamed': replace_once(CANONICAL, 'name: Publish to Maven Central', 'name: Publish'),
            'checkout ref': replace_once(CANONICAL, '          fetch-depth: 0\n          persist-credentials: false\n      - name: Set up Python',
                                         '          fetch-depth: 0\n          persist-credentials: false\n          ref: main\n'
                                         '      - name: Set up Python'),
            'no concurrency': replace_once(CANONICAL, '    concurrency:\n      group: release-publish\n      cancel-in-progress: false\n', ''),
            'two gates': replace_once(CANONICAL, step(CANONICAL, 'release_gate'), step(CANONICAL, 'release_gate') * 2),
        }
        for name, text in unsafe.items():
            with self.subTest(name):
                self.assertNotEqual(text, CANONICAL)
                self.assertFalse(release_has_gate(text))


class CanonicalShapeTest(unittest.TestCase):
    """正本（= publish.yml）が設計の形を守ること。正本を変えたときの網（判定には使わない）。"""

    def setUp(self):
        self.steps = release_steps(CANONICAL)

    def test_names_match_the_precheck_rules(self):
        self.assertIn('\n    name: ' + portal.RELEASE_JOB + '\n', job(CANONICAL, 'release'))
        names = [item['name'] for item in self.steps]
        ids = [item['id'] for item in self.steps]
        self.assertTrue(all(names))
        self.assertEqual(self.steps[ids.index('upload')]['name'], portal.UPLOAD_STEP)
        self.assertEqual(set(names[:ids.index('upload')]) | {'Set up job'}, portal.PRE_UPLOAD_STEPS)

    def test_step_order_and_conditions(self):
        self.assertEqual([item['id'] for item in self.steps], STEP_IDS)
        expected_if = {'bundle': UPLOAD_IF, 'release_gate_again': UPLOAD_IF, 'upload': UPLOAD_IF, 'deployment': UPLOAD_IF,
                       'record': RECORD_IF}
        for item in self.steps:
            with self.subTest(item['name']):
                self.assertEqual(item['if'], expected_if.get(item['id']))
        for name in ('github-release', 'confirm'):
            self.assertIn(f'\n    needs: release\n    if: {PUBLISHED_IF}\n', job(CANONICAL, name))
        self.assertIn('\n    outputs:\n      action: ${{ steps.precheck.outputs.action }}\n'
                      '      state: ${{ steps.deployment.outputs.state }}\n', job(CANONICAL, 'release'))

    def test_the_gate_step_is_the_fixed_one(self):
        gate = step(CANONICAL, 'release_gate')
        self.assertEqual(gate[gate.index('\n', 1) + 1:].rstrip('\n'), GATE_STEP)

    def test_the_gate_runs_again_right_before_the_upload(self):
        # bundle を作るあいだに増えたタグで止める。入力は別のディレクトリに集める（#934 の CodeRabbit の指摘）
        ids = [item['id'] for item in self.steps]
        self.assertEqual(ids.index('release_gate_again') + 1, ids.index('upload'))
        again = step(CANONICAL, 'release_gate_again')
        body = again[again.index('        env:\n'):].split('\n      # ', 1)[0].rstrip('\n')
        expected = GATE_STEP[GATE_STEP.index('        env:\n'):].replace(
            'GATE="${RUNNER_TEMP}/release-gate"', 'GATE="${RUNNER_TEMP}/release-gate-again"')
        self.assertEqual(body, expected)

    def test_secrets_are_only_in_the_env_of_their_steps(self):
        for item in self.steps:
            with self.subTest(item['name']):
                secrets = {name for name in item['env'] if SECRET_NAME.fullmatch(name)}
                self.assertEqual(secrets, SECRET_STEPS.get(item['id'], frozenset()))
                for name in secrets:
                    self.assertEqual(item['env'][name], f'${{{{ secrets.{name} }}}}')
        for path in sorted(WORKFLOWS.glob('*.yml')):
            lines = [line for line in path.read_text().splitlines() if SECRET_NAME.search(line)]
            with self.subTest(file=path.name):
                self.assertEqual(len(lines), 8 if path.name == portal.WORKFLOW_FILE else 0, lines)

    def test_nothing_bypasses_a_failed_step(self):
        for word in SKIP_WORDS:
            with self.subTest(word):
                self.assertEqual(CANONICAL.count(word), 1 if word == 'always()' else 0)
        self.assertIn(f'if: {RECORD_IF}', step(CANONICAL, 'record'))
        self.assertIn('uses: actions/upload-artifact@', step(CANONICAL, 'record'))
        self.assertNotIn('run:', step(CANONICAL, 'record'))
        self.assertNotIn(PAT, RELEASE)
        self.assertNotIn('persist-credentials: true', RELEASE)

    def test_the_jobs_are_serialized_and_limited(self):
        release = job(CANONICAL, 'release')
        self.assertIn('    concurrency:\n      group: release-publish\n      cancel-in-progress: false\n', release)
        self.assertIn('    permissions:\n      contents: read\n      actions: read\n      pull-requests: read\n', release)
        self.assertIn("    tags:\n      - 'v[0-9]+.[0-9]+.[0-9]+'\n", CANONICAL)
        self.assertEqual(re.findall(r'\n  ([a-z-]+):\n', CANONICAL[CANONICAL.index('\njobs:\n'):]),
                         ['release', 'github-release', 'confirm'])


class BumpWorkflowShapeTest(unittest.TestCase):
    def test_nothing_is_pushed_to_main(self):
        pushes = []
        for path in sorted(WORKFLOWS.glob('*.yml')):
            pushes += re.findall(r'git push [^\n]*', path.read_text())
        self.assertEqual(pushes, [
            'git push origin "HEAD:refs/heads/${BRANCH}"',
            'git push origin "refs/tags/${RELEASE_TAG}"; then',
        ])
        self.assertIn('BRANCH="release/${NEXT_TAG}"', BUMP)
        self.assertNotIn('origin main', BUMP + RELEASE)

    def test_steps_with_a_pipe_set_pipefail(self):
        # shell: を書かない手順は bash -e {0} で動き、pipefail が無い。パイプの前の失敗を落とさないように、
        # パイプを持つ手順は最初の行で set -o pipefail を書く（#924 の Devin の指摘）
        pipe = re.compile(r'(^|\s)\|(\s|$)')
        for name, workflow in (('bump-version.yml', BUMP), (portal.WORKFLOW_FILE, RELEASE)):
            for block in run_blocks(workflow):
                lines = [line.strip() for line in block.splitlines() if line.strip()]
                if any(pipe.search(line) for line in lines):
                    with self.subTest(workflow=name, block=lines[0]):
                        self.assertEqual(lines[0], 'set -o pipefail')
        self.assertNotIn('shell:', BUMP + RELEASE)

    def test_no_snapshot_commit_and_no_github_release_in_the_bump(self):
        self.assertEqual(BUMP.count('git commit'), 1)
        for text in ('snapshot', 'actions/create-release', 'gh release'):
            self.assertFalse(text in BUMP.lower(), text)

    def test_triggers(self):
        self.assertNotIn('pull_request', BUMP + RELEASE)
        self.assertTrue(BUMP.startswith(
            'name: Bump Version\non:\n  push:\n    branches:\n      - main\n'
            '    paths:\n      - version\n  workflow_dispatch:\n'))
        self.assertIn(
            "if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'",
            job(BUMP, 'bump-version'))
        self.assertIn(
            "if: github.event_name == 'push' && github.ref == 'refs/heads/main'"
            " && github.repository == 'j5ik2o/event-store-adapter-scala'",
            job(BUMP, 'tag-release'))
        self.assertIn('      rehearsal:\n        description:', BUMP)
        self.assertIn('        type: boolean\n        default: false\n', BUMP[BUMP.index('      rehearsal:'):])

    def test_tag_push_is_on_only_while_the_release_workflow_is_the_canonical_one(self):
        self.assertTrue(tag_push_allowed(BUMP, RELEASE))
        self.assertIn("\nenv:\n  PUSH_RELEASE_TAG: ", BUMP)
        self.assertIn("if: steps.trigger.outputs.tag != '' && env.PUSH_RELEASE_TAG == 'true'",
                      step(BUMP, 'release_tag'))
        self.assertIn("if: steps.trigger.outputs.tag != '' && env.PUSH_RELEASE_TAG != 'true'",
                      step(BUMP, 'tag_summary'))

    def test_turning_tag_push_off_is_always_allowed(self):
        # 公開を止める1行の PR（'false'）は、publish.yml の形に関わらず通る。'true' は正本のときだけ
        def setting(value):
            text = re.sub(r"\n  PUSH_RELEASE_TAG: [^\n]*\n", f"\n  PUSH_RELEASE_TAG: {value}\n", BUMP)
            self.assertEqual(text.count(f'PUSH_RELEASE_TAG: {value}\n'), 1)
            return text
        on, off = setting("'true'"), setting("'false'")
        changed = RELEASE.replace('name: publish\n', 'name: publish\n# changed\n', 1)
        self.assertTrue(tag_push_allowed(on, RELEASE))
        self.assertTrue(tag_push_allowed(off, RELEASE))
        self.assertTrue(tag_push_allowed(off, changed))
        self.assertFalse(tag_push_allowed(on, changed))
        for value in ("'TRUE'", 'true', "'yes'", "''"):
            with self.subTest(value):
                self.assertFalse(tag_push_allowed(setting(value), RELEASE))
        self.assertFalse(tag_push_allowed(off + "\n  PUSH_RELEASE_TAG: 'false'\n", RELEASE))
        self.assertFalse(tag_push_allowed(BUMP, RELEASE + "      PUSH_RELEASE_TAG: 'false'\n"))

    def test_the_bump_no_longer_refuses_to_run(self):
        self.assertNotIn('id: release_path', BUMP)
        self.assertNotIn('cannot release yet', BUMP)
        self.assertTrue(steps(BUMP, 'bump-version')[0].startswith('\n      - uses: actions/checkout@'))

    def open_pull_request(self, rehearsal):
        with tempfile.TemporaryDirectory() as directory:
            fake_bin = Path(directory) / 'bin'
            fake_bin.mkdir()
            args = Path(directory) / 'gh-args'
            (fake_bin / 'gh').write_text(
                '#!/bin/sh\nprintf \'%s\\n\' "$@" > "$FAKE_GH_ARGS"\necho https://github.com/o/r/pull/7\n')
            (fake_bin / 'gh').chmod(0o755)
            output = Path(directory) / 'outputs'
            output.write_text('')
            result = subprocess.run(
                ['bash', '-e', '-c', run_body(BUMP, 'version_pr')], cwd=directory,
                env={**os.environ, 'PATH': f'{fake_bin}{os.pathsep}{os.environ["PATH"]}',
                     'FAKE_GH_ARGS': str(args), 'GITHUB_OUTPUT': str(output), 'GITHUB_REPOSITORY': 'o/r',
                     'NEXT_TAG': 'v5.3.0', 'BRANCH': 'release/v5.3.0', 'BODY': 'body.md',
                     'REHEARSAL': rehearsal},
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_text(), 'number=7\n')
            return args.read_text().splitlines()

    def test_only_a_rehearsal_opens_a_draft_pull_request(self):
        args = self.open_pull_request('true')
        self.assertIn('--draft', args)
        self.assertEqual(args[args.index('--title') + 1], 'version up to v5.3.0 (rehearsal, do not merge)')
        args = self.open_pull_request('false')
        self.assertNotIn('--draft', args)
        self.assertEqual(args[args.index('--title') + 1], 'version up to v5.3.0')

    def test_pat_is_given_only_to_the_steps_that_push_or_open_the_pull_request(self):
        self.assertEqual(BUMP.count(PAT), 3)
        for step_id in ('version_commit', 'version_pr', 'release_tag'):
            with self.subTest(step=step_id):
                self.assertIn(PAT, step(BUMP, step_id))
        self.assertNotIn('persist-credentials: true', BUMP + RELEASE)
        self.assertNotIn('contents: write', BUMP)

    def test_no_expression_is_expanded_inside_scripts(self):
        for block in run_blocks(BUMP) + run_blocks(RELEASE):
            self.assertNotIn('${{', block)

    def test_tags_are_serialized(self):
        self.assertIn('    concurrency:\n      group: release-tag\n      cancel-in-progress: false\n',
                      job(BUMP, 'tag-release'))

    def test_tag_is_reported_or_pushed_only_after_the_gate(self):
        tag_steps = steps(BUMP, 'tag-release')
        ids = [re.search(r'id: ([a-z_]+)\n', text).group(1) for text in tag_steps if 'id: ' in text]
        self.assertEqual(ids, ['trigger', 'tag_summary', 'release_tag'])
        self.assertIn('--mode tag', step(BUMP, 'trigger'))
        self.assertIn('.github/release-gate-inputs.sh "$RELEASE_SHA" "$GATE"', step(BUMP, 'trigger'))
        # 公開先に必要な版（A1）は、タグのモードでも公開のモードでも関門に渡す
        self.assertIn('--required-published "${GATE}/required-published")', step(BUMP, 'trigger'))
        self.assertNotIn(PAT, step(BUMP, 'trigger'))
        self.assertNotIn(PAT, step(BUMP, 'tag_summary'))

    def test_the_bump_writes_the_version_through_the_gate_script(self):
        self.assertIn('.github/release-gate.py set-version "$NEXT_VERSION"', step(BUMP, 'version_commit'))
        self.assertIn('git add version\n', step(BUMP, 'version_commit'))


class ScalaWorkflowFilesTest(unittest.TestCase):
    """scala の公開のワークフローは publish.yml のまま（v5.2.0 の run 42967443751 と同じ workflow。設計 design-r5.md §1）。"""

    def test_the_publish_workflow_keeps_its_file_and_name(self):
        self.assertEqual(portal.WORKFLOW_FILE, 'publish.yml')
        self.assertTrue(RELEASE.startswith('name: publish\n'))
        self.assertFalse((WORKFLOWS / 'release.yml').exists())

    def test_only_the_publish_workflow_runs_on_tags(self):
        for path in sorted(WORKFLOWS.glob('*.yml')):
            with self.subTest(file=path.name):
                self.assertEqual('tags:' in path.read_text(), path.name == portal.WORKFLOW_FILE)

    def test_tags_are_made_only_by_the_tag_job(self):
        for path in sorted(WORKFLOWS.glob('*.yml')):
            with self.subTest(file=path.name):
                text = path.read_text()
                self.assertNotIn('github-tag-action', text)
                self.assertNotIn('actions/create-release', text)
        # タグを作るのはタグのジョブの1か所だけ（ほかの git tag は一覧の -l）
        self.assertEqual(re.findall(r'git tag (?!-l)\S+', BUMP), ['git tag -f'])


if __name__ == '__main__':
    unittest.main()
