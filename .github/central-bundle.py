#! /usr/bin/env python3
"""Maven Central に上げる bundle（zip）を、sbt の `+publishSigned` の出力（Maven の形のディレクトリ）から作る。

build  --repository <dir> --version <X.Y.Z> --output <zip>
    <dir> の io/github/j5ik2o/<成果物>/<版>/ に、成果物ごとにちょうど 24 ファイルがあることを確かめる。
    4つ（.jar・-sources.jar・-javadoc.jar・.pom）と、その .asc、その全部の .md5・.sha1（sbt が作る）。
    .md5・.sha1 の中身が本体から求めた値（小文字の16進、改行なし）と同じかも確かめる。
    zip には .asc の .md5・.sha1 を入れない（Central の要件に無く、月のファイルの数を使うだけ。設計 design-r5.md §11 M2）。
    成果物ごとに 16、12 の成果物で合わせて 192 項目。足りない・余るファイルや成果物、合わない checksum があれば失敗する。
verify --zip <zip> --version <X.Y.Z>
    zip の中身が、build が作る形と同じかを確かめる（CI で使う）。
"""
import argparse
import hashlib
import sys
import zipfile
from pathlib import Path, PurePosixPath

GROUP_PATH = 'io/github/j5ik2o'
# 公開する sbt のプロジェクト（id。'' は根）と、その成果物の名前。公開するモジュールの一覧はここだけに置く
# （bundle、repo1 の確かめ、sbt の版の照合が使う。.github/tests/test_central_bundle.py が build.sbt と照らし合わせる）
PROJECTS = ('', 'core', 'dynamodb', 'spanner', 'bigtable', 'sqlite')
MODULES = tuple('event-store-adapter-scala' + (f'-{project}' if project else '') for project in PROJECTS)
# build.sbt の crossScalaVersions の二進の版
SCALA_BINARY_VERSIONS = ('2.13', '3')
ARTIFACTS = tuple(f'{module}_{binary}' for module in MODULES for binary in SCALA_BINARY_VERSIONS)
SUFFIXES = ('.jar', '-sources.jar', '-javadoc.jar', '.pom')
CHECKSUMS = (('md5', hashlib.md5), ('sha1', hashlib.sha1))
# zip の項目の時刻を固定する（同じ入力から同じ zip を作る）
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class BundleError(Exception):
    pass


def signed_files(artifact, version):
    """成果物の <版>/ の本体と .asc（8つ）。"""
    names = [f'{artifact}-{version}{suffix}' for suffix in SUFFIXES]
    return names + [name + '.asc' for name in names]


def published_files(artifact, version):
    """sbt が <版>/ に出すファイルの名前（24個）。本体と .asc の全部に .md5・.sha1 が付く。"""
    names = signed_files(artifact, version)
    return names + [f'{name}.{extension}' for name in names for extension, _ in CHECKSUMS]


def bundle_names(artifact, version):
    """bundle に入れる名前（16個）。.asc の checksum は入れない。"""
    names = signed_files(artifact, version)
    return names + [f'{name}.{extension}' for name in names if not name.endswith('.asc')
                    for extension, _ in CHECKSUMS]


def checksum_errors(directory, read, names):
    """names の中の本体（.asc を含む）ごとに、sbt が書いた .md5・.sha1 が中身と合うかを確かめ、合わないものを返す。"""
    errors = []
    for name in names:
        if name.endswith(tuple('.' + extension for extension, _ in CHECKSUMS)):
            continue
        data = read(name)
        for extension, algorithm in CHECKSUMS:
            if read(f'{name}.{extension}') != algorithm(data).hexdigest().encode('ascii'):
                errors.append(f'{directory}/{name}.{extension}')
    return errors


def expected_count():
    return len(ARTIFACTS) * len(SUFFIXES) * (2 + len(CHECKSUMS))


def collect(repository, version):
    """sbt の出力のディレクトリを確かめて、bundle の項目 {zip の中のパス: 中身} を返す。"""
    root = Path(repository) / GROUP_PATH
    if not root.is_dir():
        raise BundleError(f'{root} does not exist; did +publishSigned publish to {repository}?')
    found = sorted(path.name for path in root.iterdir())
    if found != sorted(ARTIFACTS):
        raise BundleError(f'artifacts under {root} are {found}, expected {sorted(ARTIFACTS)}')
    entries = {}
    for artifact in ARTIFACTS:
        artifact_dir = root / artifact
        others = sorted(path.name for path in artifact_dir.iterdir())
        if others != [version]:
            raise BundleError(f'{artifact_dir} holds {others}, expected only the version directory {version}')
        version_dir = artifact_dir / version
        names = sorted(path.name for path in version_dir.iterdir())
        expected = sorted(published_files(artifact, version))
        if names != expected:
            missing = sorted(set(expected) - set(names))
            extra = sorted(set(names) - set(expected))
            raise BundleError(f'{version_dir}: missing {missing}, unexpected {extra}')
        for name in names:
            if not (version_dir / name).is_file():
                raise BundleError(f'{version_dir / name} is not a regular file')

        def read(name, version_dir=version_dir):
            return (version_dir / name).read_bytes()

        wrong = checksum_errors(str(version_dir), read, names)
        if wrong:
            raise BundleError(f'checksums do not match the files: {wrong}')
        directory = f'{GROUP_PATH}/{artifact}/{version}'
        entries.update({f'{directory}/{name}': read(name) for name in bundle_names(artifact, version)})
    return entries


def write_zip(entries, output):
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(entries):
            info = zipfile.ZipInfo(path, date_time=ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            bundle.writestr(info, entries[path])


def verify_zip(path, version):
    """zip の中身が build の作る形と同じかを確かめ、項目の数を返す。"""
    with zipfile.ZipFile(path) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)):
            raise BundleError('the bundle has duplicate entries')
        contents = {name: bundle.read(name) for name in names}
    for name in names:
        if PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
            raise BundleError(f'{name} is not a relative path')
    expected = set()
    for artifact in ARTIFACTS:
        directory = f'{GROUP_PATH}/{artifact}/{version}'
        artifact_names = bundle_names(artifact, version)
        expected.update(f'{directory}/{name}' for name in artifact_names)
        missing = sorted(f'{directory}/{name}' for name in artifact_names if f'{directory}/{name}' not in contents)
        if missing:
            raise BundleError(f'the bundle has no {missing}')

        def read(name, directory=directory):
            return contents[f'{directory}/{name}']

        wrong = checksum_errors(directory, read, [name for name in artifact_names if not name.endswith('.asc')])
        if wrong:
            raise BundleError(f'{wrong} do not hold the expected checksum')
    if set(contents) != expected:
        raise BundleError(f'unexpected entries {sorted(set(contents) - expected)}')
    return len(names)


def main(argv):
    parser = argparse.ArgumentParser(prog='central-bundle.py')
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('build')
    build.add_argument('--repository', required=True)
    build.add_argument('--version', required=True)
    build.add_argument('--output', required=True)
    check = commands.add_parser('verify')
    check.add_argument('--zip', required=True)
    check.add_argument('--version', required=True)
    args = parser.parse_args(argv[1:])
    try:
        if args.command == 'build':
            entries = collect(args.repository, args.version)
            write_zip(entries, args.output)
            count = len(entries)
        else:
            count = verify_zip(args.zip, args.version)
        if count != expected_count():
            raise BundleError(f'the bundle has {count} entries, expected {expected_count()}')
    except (BundleError, OSError, zipfile.BadZipFile) as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1
    print(f'bundle for {args.version}: {count} files ({len(ARTIFACTS)} artifacts)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
