# Contributing

## Overview

This guide is for developers who change event-store-adapter-scala. For how to use the library, see [README](README.md).

[日本語](CONTRIBUTING.ja.md)

## Shared contract and decisions

The specification and design decisions shared by every language implementation live in the hub repository:

- Specification: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec>
- Architecture decision records: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr>

Read them before changing public behavior. This repository does not redefine them.

## Local setup

The facts below come from `build.sbt`, `project/build.properties` and `.github/workflows/ci.yml`.

- sbt (`sbt.version = 1.13.0` in `project/build.properties`) and a JDK. CI runs the tests on JDK 11, 17 and 19, and the `lint` job on JDK 25 (Temurin).
- The project is cross-built for Scala 2.13.18 and 3.6.4. Select a version with `sbt ++2.13.18 ...` or `sbt ++3.6.4 ...`, or run every version with the `+` prefix.
- Python 3 is needed to run the documentation and conformance data checks. `tools/user-docs/check.py` requires Python 3.10 or later.
- Docker is needed for the DynamoDB Local, Cloud Spanner emulator and Cloud Bigtable emulator tests, which use pinned images (see [README](README.md#conformance)). The SQLite tests use a temporary local file and need no Docker. Pull the two emulator images before running `sbt test` to keep the first run short.
- Tests run with `TEST_TIME_FACTOR: 3` in CI.

## Checks

CI (`.github/workflows/ci.yml`) runs the following. Run the same commands locally before opening a pull request.

```sh
sbt lint
sbt ++2.13.18 test
sbt ++3.6.4 test
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m unittest discover -s .github/tests -v
```

- `lint` is the sbt alias for `scalafmtCheck`, `test:scalafmtCheck`, `scalafmtSbtCheck` and `scalafixAll --check`. `sbt fmt` applies the formatting (`scalafmtAll`, `scalafmtSbt`, `scalafix RemoveUnused`).
- `python3 tools/conformance/manifest.py verify` verifies the conformance data.
- `python3 tools/user-docs/check.py` verifies the user documents.
- `python3 -m unittest discover -s .github/tests -v` tests the version bump selection used by the release workflow.
- CI also checks the fixed hashes of `conformance/manifest.json`, `api-surface/surface.json`, `user-docs/template.json` and `tools/user-docs/check.py`, and lints the workflows with actionlint. Do not edit those files without a matching decision.

## Design history

`docs/design/next-major.md` records the design used when v5 was built. It is history, not current documentation. For current usage, read the [README](README.md) and the current code. The original Japanese text is kept in [next-major.ja.md](docs/design/next-major.ja.md).

## Changes and releases

- Open a pull request against `main`. CI runs on pull requests and the `CI Success` job must pass.
- Versions are derived from Git tags by sbt-dynver. Do not edit versions by hand in a pull request.
- Releases are created by the `Bump Version` workflow (`.github/workflows/bump-version.yml`), which tags the version and creates the GitHub release. It selects the level from Conventional Commits-style commit messages since the latest tag, or from the level or version given when it is started.
