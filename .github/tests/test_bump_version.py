import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows/bump-version.yml"
# 実際の workflow の読み取り専用ステップを試す。action や公開処理は実行しない。
SCRIPT = textwrap.dedent(
    WORKFLOW.read_text().split("        run: |\n", 1)[1].split("      - name:", 1)[0]
)


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

    def selection(self, level="auto", force=False, version=""):
        output = self.root / "output"
        output.write_text("")
        subprocess.run(
            ["bash", "-euo", "pipefail", "-c", SCRIPT],
            cwd=self.root,
            env={**os.environ, "LEVEL": level, "FORCE_BUMP": str(force).lower(), "VERSION": version,
                 "GITHUB_OUTPUT": str(output)},
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


if __name__ == "__main__":
    unittest.main()
