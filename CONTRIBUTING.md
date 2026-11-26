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
bash .github/quint/run-quint.sh ci
```

- `lint` is the sbt alias for `scalafmtCheck`, `test:scalafmtCheck`, `scalafmtSbtCheck` and `scalafixAll --check`. `sbt fmt` applies the formatting (`scalafmtAll`, `scalafmtSbt`, `scalafix RemoveUnused`).
- `python3 tools/conformance/manifest.py verify` verifies the conformance data.
- `python3 tools/user-docs/check.py` verifies the user documents.
- `python3 -m unittest discover -s .github/tests -v` tests the release tooling (the release gate, the version bump, the bundle and the checks of Maven Central). Install its dependencies first: `python3 -m pip install -r .github/tests/requirements.txt`.
- `bash .github/quint/run-quint.sh ci` checks the Quint model of the release path (`.github/quint/README.md`).
- CI also checks the fixed hashes of `conformance/manifest.json`, `api-surface/surface.json`, `user-docs/template.json` and `tools/user-docs/check.py`, and lints the workflows with actionlint. Do not edit those files without a matching decision.

## Design history

`docs/design/next-major.md` records the design used when v5 was built. It is history, not current documentation. For current usage, read the [README](README.md) and the current code. The original Japanese text is kept in [next-major.ja.md](docs/design/next-major.ja.md).

## Changes and releases

- Open a pull request against `main`. CI runs on pull requests and the `CI Success` job must pass. It summarizes the results of lint, test, release-workflow, release-bundle, release-model and aislop.
- The version is kept in the `version` file at the root (one line, `X.Y.Z`), which `build.sbt` reads for every project. Do not edit it by hand: only the version pull request of Bump Version changes it. After a release, `version` keeps the released version; it is not set back to a SNAPSHOT version. A local `sbt publishLocal` on `main` therefore publishes the released version number into `~/.ivy2/local`, which then shadows the artifacts from Maven Central on that machine. Remove it from `~/.ivy2/local` when you are done.
- The Bump Version workflow is started manually, with the inputs `level` (`auto`, `patch`, `minor` or `major`), `force_bump`, `version` and `rehearsal`. It counts the commits since the latest tag whose subject has a Conventional Commits type or whose message has a `BREAKING CHANGE:` or `BREAKING-CHANGE:` footer. With no such commit it does nothing, even when `level` is given (it says so in the job log); set `force_bump` or `version` to bump anyway. A `!` after the type or a breaking footer is major. Otherwise an explicit `level` is used directly, and with `level=auto` a commit of type `feat` or `revert` makes a minor version and anything else a patch version (`.github/semver-level.py`, the rules the earlier `github-tag-action` step used). An explicit `version` bypasses the level and must be greater than every tag.
- Bump Version does not push to `main`. It commits the new version to `version` on a branch `release/vX.Y.Z` and opens a pull request into `main`. It does not create a GitHub release.
- When a commit that changes `version` lands on `main`, the tag job of Bump Version checks it with the release gate (`.github/release-gate.py`). The commit must be the squash merge of a pull request from `release/vX.Y.Z` of this repository, it must change only the version in `version`, and the version must be greater than the previous version and every `vX.Y.Z` tag. A commit whose parent has no `version` file is not released (the commit that added the file).
- Pushing a `vX.Y.Z` tag starts the `publish` workflow (`.github/workflows/publish.yml`), which checks the tagged commit against the release gate again and publishes the version to Maven Central (see [Releasing](#releasing)). Snapshot builds are not published; use a released version.
- Dependency updates come from Renovate with the `chore(deps):` commit prefix.

### Releasing

1. Run Bump Version (`rehearsal: false`). Set `version` to choose the version, or leave it empty to use the bump level.
2. Check the pull request `version up to vX.Y.Z`. It must change only `version`, and Check unresolved comments must pass. Merge it with a squash merge when the release should go out. To cancel, close it and delete the branch.
3. The tag job (`Tag the merged version`) checks the merged commit against the release gate and pushes `vX.Y.Z`.
4. The `publish` workflow runs the job `Publish to Maven Central`:
   1. It checks the tagged commit against the release gate.
   2. It checks that sbt builds the version of the tag for every project and both Scala versions (`sbt '+print version'`).
   3. It checks Maven Central and the earlier publish runs (`.github/central-portal.py precheck`). It waits while a deployment is in progress (up to 60 minutes). When the version is already published it skips the upload, and when an earlier attempt is unaccounted for it stops.
   4. It builds the bundle with `sbt +publishSigned` and uploads it to the Central Portal (`publishingType=AUTOMATIC`). The bundle has 192 files: twelve artifacts (six modules for Scala 2.13 and 3), each with the jar, sources, javadoc and POM, their signatures, and the MD5 and SHA-1 checksums of the four files. Signatures get no checksums; Maven Central does not require them.
   5. It records the deployment ID as the artifact `central-deployment-<attempt>-<ID>` (kept for 90 days).
   6. It waits until the deployment is `PUBLISHED` (up to 60 minutes).
5. When the deployment is `PUBLISHED`, `Create the GitHub release` creates the GitHub release and `Check Maven Central` waits until repo1 serves the twelve POMs (up to 60 minutes).

A rehearsal (`rehearsal: true`) opens a draft pull request titled `version up to vX.Y.Z (rehearsal, do not merge)`. Close it without merging and delete the branch.

The signing secrets (`PGP_SECRET`, `PGP_PASSPHRASE`) are given only to the bundle step, which imports the key into a temporary GnuPG home and stops its agent afterwards. The Sonatype secrets are given only to the precheck, upload and wait steps. `publish.yml` has no personal access token, writes no credentials file and uses no dependency cache.

Rules that keep the checks working:

- Do not create `vX.Y.Z` tags by hand, including tags created on the GitHub release page. A tag runs the `publish` workflow of the commit it points at. Do not tag commits from before the release gate was added (`v5.2.0`'s commit `a68723e` and earlier): their `publish.yml` runs on any `v*` tag and publishes with no gate and no precheck.
- Do not rename `publish.yml` or its `name`, and do not delete publish runs or their `central-` artifacts. Keep the artifact retention at 90 days. The precheck reads the runs of `publish.yml` to find deployments of earlier attempts.
- Do not rename the jobs or steps of `publish.yml`. The precheck finds the upload in earlier runs by the names in `.github/central-rules.py` (`RELEASE_JOB`, `UPLOAD_STEP`, `PRE_UPLOAD_STEPS`). If a name has to change, keep the old name in those sets as well.
- A pull request that changes `publish.yml` changes `.github/tests/fixtures/release-workflow.yml` (the reviewed copy) in the same way. `.github/tests/test_release_workflow_shape.py` allows `PUSH_RELEASE_TAG: 'true'` only while `publish.yml` is the same as that copy, line for line; only the versions in `uses:` lines may differ.
- A pull request that adds or removes a published project or a Scala version changes `ARTIFACTS` in `.github/central-bundle.py`; `.github/tests/test_central_bundle.py` compares it with `build.sbt`.
- A failed or cancelled run can be re-run for 30 days after it started.

To stop releases, merge a pull request that sets `PUSH_RELEASE_TAG` to `'false'` in `bump-version.yml`. While it is `'false'`, the tag job writes the tag it would push to the job summary and stops. A version pull request merged during that time leaves its version on `main` without a tag: set `version` back to the previous version with a pull request, then run Bump Version again with `version` set to the version you want.

A re-run uses the workflow files of the commit it ran on, not those of `main`. While releases are stopped, do not re-run a tag job (`Tag the merged version`) that ran before the stop: its `bump-version.yml` still has `PUSH_RELEASE_TAG: 'true'`, so it pushes the tag. The same holds for a publish run of a tag pushed before the stop. Re-run them only after releases are started again.

### Until 5.2.0 is on Maven Central

The tag `v5.2.0` and its GitHub release exist, but 5.2.0 is not on Maven Central: the deployment of 2026-11-11 was `FAILED`, although the publish run succeeded ([#920](https://github.com/j5ik2o/event-store-adapter-scala/issues/920)). That run's `publish.yml` uploads the bundle and does not read the state of the deployment. 5.2.0 is published by re-running all jobs of that publish run ([42967443751](https://github.com/j5ik2o/event-store-adapter-scala/actions/runs/42967443751)); the run succeeded, so "re-run failed jobs" is not offered. The re-run uses the `publish.yml`, `build.sbt` and `project/` of the tagged commit `a68723e`, which has no release gate and takes its version from the tag. The precheck does not count that run (`FIRST_COUNTED_RUN_ID` in `.github/central-rules.py`). A successful re-run does not prove that 5.2.0 is published: check the twelve POMs on repo1.

- Bump Version stays disabled until repo1 serves the twelve POMs of 5.2.0, and is enabled only after that. Do not merge a version pull request before then.
- Until repo1 serves the twelve POMs of 5.2.0, the release gate refuses every newer version, in the tag job and in the `publish` workflow (`REQUIRED_PUBLISHED = '5.2.0'` in `.github/release-gate.py`). A version that is `PUBLISHED` in the Portal but not yet on repo1 is still refused.
- If a version pull request was merged anyway, the tag job fails at the release gate: nothing is tagged and nothing is published. The merged version does not block the 5.2.0 re-run, because the re-run uses the workflow of `v5.2.0` and the release gate holds the merged version back. After repo1 serves the twelve POMs of 5.2.0, re-run the failed tag job.
- repo1 remembers a 404 for a while. Before you re-run a job that waits for 5.2.0, check the twelve POMs (`https://repo1.maven.org/maven2/io/github/j5ik2o/<artifact>/5.2.0/<artifact>-5.2.0.pom` for each artifact in `.github/central-bundle.py`).
- A failed tag job can be re-run for 30 days after it started. After that, the merged version is skipped: run Bump Version with the next version (for example `version=5.2.2` when `5.2.1` is on `main` without a tag).
- When repo1 serves 5.2.0, open a pull request that sets `REQUIRED_PUBLISHED = None`. Until then, every gate run reads the twelve POMs of 5.2.0 from repo1.

### When a release stops

Most stops are recovered by re-running the failed jobs of the run (Re-run failed jobs). Each re-run checks Maven Central and the earlier attempts again before it uploads, so it never uploads a version twice.

Some stops need a line in the repository variable `CENTRAL_DEPLOYMENTS` (Settings, Secrets and variables, Actions, Variables). Write one line per item:

- `<run_id>/<attempt>=<deployment ID>`: the deployment that attempt `<attempt>` of run `<run_id>` uploaded.
- `<run_id>/<attempt>=none`: that attempt uploaded nothing, or its deployment was dropped in the Portal.
- `tag/vX.Y.Z=checked`: you checked in the Portal that no deployment of that version is in progress.

When the precheck stops for a missing ID, its error message lists every line to add, for every attempt from `<run_id>/1` to `<run_id>/<run_attempt>` that lacks one. The deployment of an attempt is named `event-store-adapter-scala-<version>-run<run_id>-<attempt>` in the Portal.

| What happened | What the workflow does | How to recover |
|---|---|---|
| 5.2.0 is not on repo1 yet (`must wait until 5.2.0 is published on Maven Central`) | The release gate fails in the tag job or the `publish` workflow. Nothing is tagged, built or uploaded | Publish 5.2.0 first (see [Until 5.2.0 is on Maven Central](#until-520-is-on-maven-central)). When repo1 serves the twelve POMs, re-run the failed job within 30 days |
| repo1 could not be read for 5.2.0 (`could not read whether 5.2.0 is on Maven Central`) | The release gate fails | Re-run the failed job |
| The tag is not on the squash merge of a version pull request, the commit changes code with the version, the version is lower, the tag does not match the version, or the parent of the commit has no `version` | The release gate step fails. Nothing is built or uploaded | Delete the tag. Fix `version` with a pull request |
| sbt builds another version than the tag for some project (`sbt builds [...]`) | `Check the version sbt builds` fails | Fix `version` or `build.sbt` with a pull request and release the next version |
| A query to GitHub, the Portal or repo1 fails (5xx, timeout) | The precheck stops | Re-run the failed jobs |
| A deployment of another run is in progress | The precheck waits up to 60 minutes, then fails | Re-run the failed jobs |
| An attempt may have uploaded but left no deployment ID (including step lists that are cut, runs without jobs, and attempts without a release job) | The precheck stops and lists the lines to add | Find each ID in the job summary or log of the attempt, or in the Portal by its name. Add `<run_id>/<attempt>=<ID>` or `=none` for every listed attempt, then re-run |
| The upload does not return 201 and a deployment ID | The upload step fails. The next attempt stops as in the row above | As in the row above |
| The bundle does not have the expected files, or a checksum written by sbt does not match its file | The bundle step fails. Nothing is uploaded | Fix it with a pull request and release the next version. A re-run uses the scripts of the tagged commit, so it does not pick up the fix |
| The signing key is missing or wrong | Signing fails in the bundle step. Nothing is uploaded | Fix the secrets and re-run |
| The deployment is `FAILED` | The wait step fails with the Portal errors. No GitHub release is created | Re-run the failed jobs (the precheck sees `FAILED` and uploads again). If the content is wrong, fix it in the next version. If the monthly limit of Maven Central was reached, re-run after it resets |
| A `FAILED` deployment was dropped in the Portal | The precheck stops: the ID is not found | After the drop, add `<run_id>/<attempt>=none` and re-run |
| The deployment is still in progress after 60 minutes | The wait step fails | Re-run the failed jobs. The precheck waits for the same deployment and does not upload again |
| repo1 does not serve the version after 60 minutes | `Check Maven Central` fails | Re-run only `Check Maven Central` |
| Creating the GitHub release fails | `Create the GitHub release` fails | Re-run only `Create the GitHub release` |
| A newer tag exists (the version was overtaken) | The release gate stops (`must be greater than the latest tag`); the version is not published | Leave it: the version is skipped. If the version was already `PUBLISHED` (its wait timed out, and the newer tag came before the re-run), see [A version published before a newer tag](#a-version-published-before-a-newer-tag) |
| The waiting publish job was replaced by a later arrival in the `release-publish` group (another tag push, or a re-run of another version) | That run is cancelled before it starts; it publishes nothing | If its version is the latest `vX.Y.Z` tag, re-run all jobs of the cancelled run (its cancelled job is not counted as an upload). Otherwise a re-run stops at the release gate because a newer tag exists: if an earlier attempt already published the version (for example, only the GitHub release had failed), see [A version published before a newer tag](#a-version-published-before-a-newer-tag); if not, leave it (the version is skipped) |
| The tag job (`Tag the merged version`) was replaced by a later arrival in the `release-tag` group | That run is cancelled; the version gets no tag | If its version is greater than the latest `vX.Y.Z` tag, re-run the cancelled run, also when other pull requests were merged after it (the release gate does not require the head of `main`). Otherwise leave it: the release gate refuses it |
| A line of `CENTRAL_DEPLOYMENTS` is malformed, gives another ID than the artifact, or says `none` for a deployment the Portal still knows | The precheck stops | Correct or remove the line and re-run |
| A publish run was deleted, or a tag push started no publish run (more than three tags in one push, or an outage) | The precheck stops: a tag has no publish run (`tag vX.Y.Z has no publish.yml run`) | Check in the Portal that no deployment of that version is in progress, add `tag/vX.Y.Z=checked` and re-run. Do not move the tag |
| The publish run is still there, but the `central-deployment-<attempt>-<ID>` artifact of an attempt was deleted | The precheck stops: that attempt may have uploaded but has no deployment ID, and it lists the lines to add. `tag/vX.Y.Z=checked` does not help here: it only covers a tag without a run | Find the ID in the job summary or log of that attempt, or in the Portal by the name `event-store-adapter-scala-<version>-run<run_id>-<attempt>`. Add `<run_id>/<attempt>=<ID>`, or `<run_id>/<attempt>=none` if the Portal has no such deployment, then re-run |
| A job or step of `publish.yml` was renamed | The precheck stops on the earlier runs | Do not rename them. If you have to, keep the old names in `.github/central-rules.py` |
| The artifact retention was set below 90 days | The precheck stops | Set it back to 90 days |
| More than 30 days passed, so the run cannot be re-run | | Treat the version as skipped and release the next one. Before that, add lines for every attempt that is still unaccounted for |
| A run is older than 80 days | The precheck does not count it | |

#### A version published before a newer tag

When a version is on Maven Central but a newer `vX.Y.Z` tag was pushed before its run finished, the release gate stops every re-run of the older version. This happens when the wait for its deployment timed out and the Portal published it later, or when an earlier attempt published it, a later step failed (for example, creating the GitHub release), and the re-run was replaced in the `release-publish` group. The GitHub release and the repo1 check are then missing. Recover them by hand:

1. Check that repo1 serves the version: `https://repo1.maven.org/maven2/io/github/j5ik2o/<artifact>/<version>/<artifact>-<version>.pom` for each of the twelve artifacts in `.github/central-bundle.py` (`ARTIFACTS`).
2. Write the release description from the previous release tag `<previous>`:

   ```sh
   python3 .github/create-release-note-header.py https://github.com j5ik2o/event-store-adapter-scala <previous> vX.Y.Z > release-body.md
   git log <previous>..vX.Y.Z --no-merges --format='%H%x1f%s%x1f%b%x1e' \
     | python3 .github/create-release-note-body.py https://github.com j5ik2o/event-store-adapter-scala >> release-body.md
   ```

3. Create the release: `gh release create vX.Y.Z --verify-tag --title "Release vX.Y.Z" -F release-body.md`.

The release gate is not relaxed for this case: it keeps checking, without any secret, that a version is greater than every other tag before the precheck runs.
