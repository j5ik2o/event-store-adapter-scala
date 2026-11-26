"""central-bundle.py の例と性質（設計の追記の P11・P18。除外は付けない）。

木の形は、sbt の `+publishSigned` を file の resolver へ出したもの（設計 design-r5.md §4 で手元の出力を確かめた）：
成果物ごとに <版>/ の 24 ファイル（4つの本体と .asc、その全部の .md5・.sha1）。maven-metadata は出ない。
"""
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from hypothesis import given, settings
from hypothesis import strategies as st

from release_test_support import GITHUB, load_script

central_bundle = load_script('central-bundle.py', 'central_bundle')
ROOT = GITHUB.parent
VERSION = '5.3.0'
SETTINGS = settings(max_examples=150, deadline=None, database=None)
CORE = 'event-store-adapter-scala-core_2.13'


def body(artifact, name):
    return f'{artifact}/{name}'.encode()


def checksum(data, extension):
    return dict(central_bundle.CHECKSUMS)[extension](data).hexdigest().encode('ascii')


def write_tree(root, version=VERSION, artifacts=central_bundle.ARTIFACTS, drop=(), add=(), wrong=()):
    """sbt が出す形の木を書く。drop は書かない名前、add は足す (成果物, 名前)、wrong は中身を誤らせる checksum の名前。"""
    base = Path(root) / central_bundle.GROUP_PATH
    for artifact in artifacts:
        directory = base / artifact / version
        directory.mkdir(parents=True, exist_ok=True)
        for name in central_bundle.signed_files(artifact, version):
            data = body(artifact, name)
            files = {name: data, **{f'{name}.{extension}': checksum(data, extension)
                                    for extension, _ in central_bundle.CHECKSUMS}}
            for file_name, content in files.items():
                if file_name in drop:
                    continue
                (directory / file_name).write_bytes(b'0' * len(content) if file_name in wrong else content)
    for artifact, name in add:
        directory = base / artifact / version
        directory.mkdir(parents=True, exist_ok=True)
        (directory / name).write_bytes(b'extra')


class CentralBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(GITHUB / 'central-bundle.py'), *args],
                              capture_output=True, text=True)

    def build(self, root):
        return self.run_script('build', '--repository', str(root), '--version', VERSION,
                               '--output', str(root / 'bundle.zip'))

    def test_the_artifact_list_matches_the_sbt_build(self):
        build = (ROOT / 'build.sbt').read_text()
        names = ['event-store-adapter-scala-core'] + [
            f'event-store-adapter-scala-{backend}' for backend in re.findall(r'lazy val \w+ = driverProject\("([a-z]+)"\)', build)]
        self.assertIn('name := "event-store-adapter-scala-core"', build)
        self.assertIn('name := s"event-store-adapter-scala-$backend"', build)
        self.assertIn('    name := "event-store-adapter-scala",\n', build)
        self.assertIn('ThisBuild / organization := "io.github.j5ik2o"', build)
        self.assertEqual(sorted(central_bundle.MODULES), sorted(['event-store-adapter-scala'] + names))
        # 公開しないプロジェクト（coreOnly、dynamodbOnly）は publish / skip
        self.assertEqual(build.count('publish / skip := true'), 2)
        dependencies = (ROOT / 'project' / 'Dependencies.scala').read_text()
        binaries = []
        for name in ('scala213Version', 'scala3Version'):
            version = re.search(rf'val {name}\s*=\s*"([0-9.]+)"', dependencies).group(1)
            major, minor = version.split('.')[:2]
            binaries.append(major if major == '3' else f'{major}.{minor}')
        self.assertIn('ThisBuild / crossScalaVersions := Seq(\n  Versions.scala213Version,\n  Versions.scala3Version,\n)',
                      build)
        self.assertEqual(list(central_bundle.SCALA_BINARY_VERSIONS), binaries)
        self.assertEqual(len(central_bundle.ARTIFACTS), 12)

    def test_a_complete_tree_makes_192_entries(self):
        write_tree(self.directory / 'repo')
        result = self.build(self.directory / 'repo')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('192 files (12 artifacts)', result.stdout)
        output = self.directory / 'repo' / 'bundle.zip'
        with zipfile.ZipFile(output) as bundle:
            names = bundle.namelist()
            pom = f'io/github/j5ik2o/{CORE}/{VERSION}/{CORE}-{VERSION}.pom'
            self.assertEqual(bundle.read(pom + '.sha1'), checksum(body(CORE, f'{CORE}-{VERSION}.pom'), 'sha1'))
        self.assertEqual(len(names), 192)
        # 成果物ごとに 16：4つの本体と .asc と、本体の .md5・.sha1。.asc の checksum は入れない（設計 §11 M2）
        spanner = sorted(name.rsplit('/', 1)[1] for name in names if '/event-store-adapter-scala-spanner_3/' in name)
        self.assertEqual(len(spanner), 16)
        self.assertFalse([name for name in names if re.search(r'\.asc\.(md5|sha1)$', name)])
        self.assertFalse([name for name in names if re.search(r'\.(sha256|sha512)$', name)])
        self.assertEqual(len([name for name in names if name.endswith('.asc')]), 48)
        verified = self.run_script('verify', '--zip', str(output), '--version', VERSION)
        self.assertEqual(verified.returncode, 0, verified.stderr)

    def test_the_bundle_is_the_same_for_the_same_input(self):
        write_tree(self.directory / 'repo')
        outputs = []
        for name in ('a.zip', 'b.zip'):
            self.assertEqual(self.run_script('build', '--repository', str(self.directory / 'repo'), '--version',
                                             VERSION, '--output', str(self.directory / name)).returncode, 0)
            outputs.append((self.directory / name).read_bytes())
        self.assertEqual(outputs[0], outputs[1])

    def test_broken_trees_are_refused(self):
        cases = {
            'missing signature': dict(drop=(f'{CORE}-{VERSION}.pom.asc',)),
            'missing checksum': dict(drop=(f'{CORE}-{VERSION}.jar.md5',)),
            'missing signature checksum': dict(drop=(f'{CORE}-{VERSION}.jar.asc.sha1',)),
            'missing javadoc': dict(drop=(f'{CORE}-{VERSION}-javadoc.jar',)),
            'wrong checksum': dict(wrong=(f'{CORE}-{VERSION}.jar.sha1',)),
            'wrong signature checksum': dict(wrong=(f'{CORE}-{VERSION}.pom.asc.md5',)),
            'extra file': dict(add=((CORE, 'notes.txt'),)),
            'gradle module': dict(add=((CORE, f'{CORE}-{VERSION}.module'),)),
            'missing artifact': dict(artifacts=central_bundle.ARTIFACTS[:-1]),
            'extra artifact': dict(artifacts=central_bundle.ARTIFACTS + ('event-store-adapter-scala-extra_3',)),
            'only one scala version': dict(artifacts=tuple(a for a in central_bundle.ARTIFACTS if a.endswith('_2.13'))),
        }
        for name, options in cases.items():
            with self.subTest(name):
                root = self.directory / name.replace(' ', '-')
                write_tree(root, **options)
                result = self.build(root)
                self.assertEqual(result.returncode, 1)
                self.assertIn('::error::', result.stderr)
                self.assertFalse((root / 'bundle.zip').exists())

    def test_another_version_directory_or_metadata_is_refused(self):
        write_tree(self.directory / 'repo')
        write_tree(self.directory / 'repo', version='5.2.0', artifacts=(CORE,))
        result = self.build(self.directory / 'repo')
        self.assertEqual(result.returncode, 1)
        self.assertIn('expected only the version directory', result.stderr)
        write_tree(self.directory / 'meta')
        (self.directory / 'meta' / central_bundle.GROUP_PATH / CORE / 'maven-metadata.xml').write_text('<metadata/>')
        self.assertEqual(self.build(self.directory / 'meta').returncode, 1)

    def test_a_tampered_zip_is_refused(self):
        write_tree(self.directory / 'repo')
        self.build(self.directory / 'repo')
        output = self.directory / 'repo' / 'bundle.zip'
        cases = {
            'checksum': lambda name, data: b'0' * 40 if name.endswith('.jar.sha1') else data,
            'body': lambda name, data: b'changed' if name.endswith('-sources.jar') else data,
        }
        for label, change in cases.items():
            with self.subTest(label):
                tampered = self.directory / f'{label}.zip'
                with zipfile.ZipFile(output) as source, zipfile.ZipFile(tampered, 'w') as target:
                    for name in source.namelist():
                        target.writestr(name, change(name, source.read(name)))
                result = self.run_script('verify', '--zip', str(tampered), '--version', VERSION)
                self.assertEqual(result.returncode, 1)
                self.assertIn('expected checksum', result.stderr)
        extra = self.directory / 'extra.zip'
        with zipfile.ZipFile(output) as source, zipfile.ZipFile(extra, 'w') as target:
            for name in source.namelist():
                target.writestr(name, source.read(name))
            target.writestr(f'io/github/j5ik2o/{CORE}/{VERSION}/{CORE}-{VERSION}.pom.asc.md5', b'0' * 32)
        result = self.run_script('verify', '--zip', str(extra), '--version', VERSION)
        self.assertEqual(result.returncode, 1)
        self.assertIn('unexpected entries', result.stderr)

    def test_the_layout_of_a_real_sbt_publish(self):
        # 手元の sbt（+publish、署名なし）が出した名前の並び：本体4つと .md5・.sha1 の 12。署名で .asc とその checksum が増える
        names = central_bundle.published_files(CORE, VERSION)
        self.assertEqual(len(names), 24)
        unsigned = sorted(name for name in names if '.asc' not in name)
        self.assertEqual(unsigned, sorted(
            f'{CORE}-{VERSION}{suffix}{extension}' for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom')
            for extension in ('', '.md5', '.sha1')))


# ファイル名に使える文字（macOS の APFS は、未割り当ての文字を名前に使えない。BMP の文字・数字・記号に絞る）
NAME = st.text(alphabet=st.characters(categories=('L', 'N', 'P', 'S'), max_codepoint=0xFFFF, exclude_characters='/'),
               min_size=1, max_size=12).filter(lambda name: name not in ('.', '..'))
FILES = [(artifact, name) for artifact in central_bundle.ARTIFACTS
         for name in central_bundle.published_files(artifact, VERSION)]
CHECKSUM_FILES = [item for item in FILES if item[1].endswith(('.md5', '.sha1'))]


class CentralBundlePropertiesTest(unittest.TestCase):
    """P11・P18：任意の木について、成功するのは 12 の成果物の <版>/ がちょうど 24 ファイルで、checksum が全部
    中身と合うときだけ。成功したら 192 の項目で、.asc の checksum は入らず、verify が通る。"""

    @SETTINGS
    @given(
        drop=st.sets(st.sampled_from(FILES), max_size=2),
        wrong=st.sets(st.sampled_from(CHECKSUM_FILES), max_size=2),
        wrong_case=st.booleans(),
        extra=st.lists(st.tuples(st.sampled_from(central_bundle.ARTIFACTS), NAME), max_size=2),
        missing_artifacts=st.sets(st.sampled_from(central_bundle.ARTIFACTS), max_size=1),
        extra_artifacts=st.lists(NAME.map(lambda name: 'x' + name), max_size=1),
        other_version=st.booleans(),
        empty_files=st.booleans(),
    )
    def test_success_exactly_when_the_tree_is_complete(self, drop, wrong, wrong_case, extra, missing_artifacts,
                                                       extra_artifacts, other_version, empty_files):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'repo'
            base = root / central_bundle.GROUP_PATH
            artifacts = [artifact for artifact in central_bundle.ARTIFACTS if artifact not in missing_artifacts]
            bodies = {}
            broken = False
            for artifact in artifacts + extra_artifacts:
                version_dir = base / artifact / VERSION
                version_dir.mkdir(parents=True, exist_ok=True)
                if artifact not in artifacts:
                    (version_dir / 'a.jar').write_bytes(b'a')
                    continue
                for name in central_bundle.signed_files(artifact, VERSION):
                    data = b'' if empty_files else f'{artifact}:{name}'.encode()
                    if (artifact, name) not in drop:
                        (version_dir / name).write_bytes(data)
                        bodies[f'{central_bundle.GROUP_PATH}/{artifact}/{VERSION}/{name}'] = data
                    for extension, _ in central_bundle.CHECKSUMS:
                        file_name = f'{name}.{extension}'
                        if (artifact, file_name) in drop:
                            continue
                        value = checksum(data, extension)
                        if (artifact, file_name) in wrong:
                            # 大文字の16進（値は同じでも一字一句は違う）か、ほかの値
                            value = value.upper() if wrong_case and not value.isdigit() else b'f' * len(value)
                            broken = broken or value != checksum(data, extension)
                        (version_dir / file_name).write_bytes(value)
            added = set()
            for artifact, name in extra:
                if artifact in artifacts and name not in central_bundle.published_files(artifact, VERSION):
                    (base / artifact / VERSION / name).write_bytes(b'extra')
                    added.add((artifact, name))
            if other_version and artifacts:
                (base / artifacts[0] / '5.2.0').mkdir()
            complete = (not drop and not added and not missing_artifacts and not extra_artifacts and not broken
                        and not (other_version and artifacts))
            output = Path(directory) / 'bundle.zip'
            try:
                entries = central_bundle.collect(root, VERSION)
                central_bundle.write_zip(entries, output)
            except central_bundle.BundleError:
                self.assertFalse(complete)
                return
            self.assertTrue(complete)
            with zipfile.ZipFile(output) as bundle:
                names = set(bundle.namelist())
                self.assertEqual(len(names), central_bundle.expected_count())
                for path, data in bodies.items():
                    self.assertEqual(bundle.read(path), data)
                    if path.endswith('.asc'):
                        self.assertFalse({path + '.md5', path + '.sha1'} & names)
                        continue
                    for extension, _ in central_bundle.CHECKSUMS:
                        self.assertEqual(bundle.read(f'{path}.{extension}'), checksum(data, extension))
                expected = set(bodies) | {f'{path}.{extension}' for path in bodies if not path.endswith('.asc')
                                          for extension, _ in central_bundle.CHECKSUMS}
                self.assertEqual(names, expected)
            self.assertEqual(central_bundle.verify_zip(output, VERSION), central_bundle.expected_count())


if __name__ == '__main__':
    unittest.main()
