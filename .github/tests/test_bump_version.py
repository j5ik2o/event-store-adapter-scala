import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


from release_test_support import BUMP, run_body

# 実際の workflow の読み取り専用ステップ（id: changes）を試す。action や公開処理は実行しない。
SCRIPT = textwrap.dedent(run_body(BUMP, "changes"))


class BumpVersionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Workflow Test")
        self.git("config", "user.email", "workflow-test@example.invalid")
        self.commit("chore: initial commit")
        self.git("tag", "v1.2.3")

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout

    def commit(self, message):
        self.git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-qm", message)

    def selection(self, level="auto", force=False, version="", path=None):
        output = self.root / "output"
        output.write_text("")
        subprocess.run(
            # Actions と同じく bash -e で流す（pipefail は手順の中の set -o pipefail に任せる）
            ["bash", "-e", "-c", SCRIPT],
            cwd=self.root,
            env={**os.environ, "LEVEL": level, "FORCE_BUMP": str(force).lower(), "VERSION": version,
                 "GITHUB_OUTPUT": str(output), **({"PATH": path} if path else {})},
            check=True,
            capture_output=True,
            text=True,
        )
        return dict(line.split("=", 1) for line in output.read_text().splitlines())

    def test_breaking_subjects(self):
        for subject in ("feat!: change API", "fix(storage)!: change API", "perf!: change API",
                        "revert!: change API", "refactor(storage)!: change API"):
            with self.subTest(subject=subject):
                self.git("reset", "--hard", "v1.2.3")
                self.commit(subject)
                self.assertEqual(self.selection(), {"count": "1", "bump": "major"})

    def test_breaking_body(self):
        for marker in ("BREAKING CHANGE:", "BREAKING-CHANGE:"):
            for subject in ("fix: change API", "revert: restore API", "change API"):
                with self.subTest(marker=marker, subject=subject):
                    self.git("reset", "--hard", "v1.2.3")
                    self.commit(f"{subject}\n\nDetails\twith tabs\nand newlines.\n\n"
                                f"{marker} use the new API\ncontinued details\tend")
                    self.assertEqual(self.selection(), {"count": "1", "bump": "major"})

    def test_nonbreaking_subjects_delegate_to_action(self):
        for subject in ("perf: speed up reads", "feat: add support", "revert: restore behavior",
                        "fix(storage): correct reads", "ci: check workflows"):
            with self.subTest(subject=subject):
                self.git("reset", "--hard", "v1.2.3")
                self.commit(subject)
                self.assertEqual(self.selection(), {"count": "1", "bump": ""})

    def test_mixed_commits_keep_major_and_count_commits(self):
        self.commit("feat: add support\n\nMultiple\nlines\tand tabs")
        self.commit("perf: speed up reads")
        self.commit("fix(storage)!: change API")
        self.commit("revert: restore behavior")
        self.assertEqual(self.selection(), {"count": "4", "bump": "major"})

    def test_empty_and_nonconventional_history(self):
        self.assertEqual(self.selection(), {"count": "0", "bump": ""})
        self.commit("Update documentation")
        self.assertEqual(self.selection(), {"count": "0", "bump": ""})

    def test_force_bump_keeps_count_gate(self):
        self.assertEqual(self.selection(force=True), {"count": "1", "bump": ""})
        self.commit("feat!: change API")
        self.assertEqual(self.selection(force=True), {"count": "1", "bump": "major"})

    def test_manual_level_overrides_breaking(self):
        self.commit("feat!: change API")
        for level in ("patch", "minor", "major"):
            with self.subTest(level=level):
                self.assertEqual(self.selection(level), {"count": "1", "bump": level})

    def test_manual_level_preserves_force_bump_gate(self):
        self.assertEqual(self.selection("minor"), {"count": "0", "bump": "minor"})
        self.assertEqual(self.selection("minor", force=True), {"count": "1", "bump": "minor"})

    def test_initial_history_without_tag(self):
        self.git("tag", "-d", "v1.2.3")
        self.assertEqual(self.selection(), {"count": "1", "bump": ""})
        self.commit("fix!: change API")
        self.assertEqual(self.selection(), {"count": "2", "bump": "major"})

    def assert_rejected(self, version, **kwargs):
        with self.assertRaises(subprocess.CalledProcessError):
            self.selection(version=version, **kwargs)
        self.assertEqual((self.root / "output").read_text(), "")

    def test_explicit_version_ignores_level_and_counts_one(self):
        for level in ("auto", "patch", "major"):
            with self.subTest(level=level):
                self.assertEqual(self.selection(level, version="5.0.0"),
                                 {"count": "1", "bump": ""})

    def test_explicit_version_accepts_greater_version_without_commits(self):
        self.git("tag", "v1.10.0")
        self.assertEqual(self.selection(version="1.10.1"), {"count": "1", "bump": ""})

    def test_explicit_version_accepted_without_tags(self):
        self.git("tag", "-d", "v1.2.3")
        self.assertEqual(self.selection(version="0.0.1"), {"count": "1", "bump": ""})

    def test_explicit_version_rejects_invalid_format(self):
        for version in ("v5.0.0", "5.0", "5.0.0-rc.1", "5.0.0+build", "5.0.x", " 5.0.0"):
            with self.subTest(version=version):
                self.assert_rejected(version)

    def test_explicit_version_must_exceed_latest_tag_by_version_sort(self):
        self.git("tag", "v1.10.0")
        for version in ("1.9.9", "1.10.0", "1.2.3"):
            with self.subTest(version=version):
                self.assert_rejected(version)

    def test_explicit_version_rejects_existing_tag(self):
        self.git("tag", "v9.0.0")
        self.assert_rejected("1.2.3")
        self.assert_rejected("9.0.0")

    def test_a_failing_git_log_fails_the_step(self):
        # git log が失敗したら、件数 0 の「上げるものが無い」で成功させずに止める（設計 design-r5.md §11 L1）
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        real_git = subprocess.run(["which", "git"], check=True, capture_output=True, text=True).stdout.strip()
        (fake_bin / "git").write_text(
            "#!/bin/sh\n"
            "if [ \"$1\" = log ]; then echo 'fatal: cannot use Perl-compatible regexes' >&2; exit 128; fi\n"
            f"exec {real_git} \"$@\"\n")
        (fake_bin / "git").chmod(0o755)
        self.commit("feat: add support")
        with self.assertRaises(subprocess.CalledProcessError):
            self.selection(path=f"{fake_bin}{os.pathsep}{os.environ['PATH']}")
        self.assertEqual((self.root / "output").read_text(), "")

    def test_many_tags_still_select_the_range_from_the_latest_tag(self):
        # pipefail の下でタグの有無をパイプで調べると、SIGPIPE で「タグなし」に化けうる。多くのタグでも範囲は最新のタグから
        head = self.git("rev-parse", "HEAD").strip()
        refs = "".join(f"create refs/tags/v0.0.{number} {head}\n" for number in range(20000))
        subprocess.run(["git", "update-ref", "--stdin"], cwd=self.root, input=refs, check=True, text=True)
        self.git("tag", "v1.2.4")
        self.assertEqual(self.selection(), {"count": "0", "bump": ""})


if __name__ == "__main__":
    unittest.main()
