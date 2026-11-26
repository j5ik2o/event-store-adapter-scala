# Release path model (Quint)

`release_path.qnt` models the release path: the version pull request, the merge, the release gate, the tag, the publish job, the Maven Central deployment (accepted, then `PUBLISHED` or `FAILED`), the checks after publishing and the GitHub release. `release_path_test.qnt` runs fixed paths through the model.

The model was written for the release path design of the event-store-adapter repositories (the js shape of j5ik2o/event-store-adapter-js#1158 applied to java, kotlin, scala, rs and go). The R2b part models what `.github/central-rules.py` decides before an upload:

- `RECORD_VIEW`: the precheck sees a deployment only through its record (the `central-deployment-<attempt>-<ID>` artifact, or a line in the `CENTRAL_DEPLOYMENTS` variable). A job can fail after the upload step starts and before the ID is recorded.
- `UNKNOWN_DETECTED`: such an attempt is always seen as started (`may_have_uploaded` in `central-rules.py` counts a job as uploaded unless it proves otherwise).
- `UNKNOWN_STOPS`: an attempt that may have uploaded without a record stops every later upload.
- `NONE_SKIPS_QUERY`: the rejected rule where a `=none` line skipped the status query. The instance `noneSkipsQuery` shows that rule breaks the invariants.
- `PRECHECK_LATEST_TAG`: the precheck checks that this version is still the latest tag. The instance `noLatestTagCheck` drops the check and breaks I2 (a `FAILED` version re-run after a later version is `PUBLISHED`), which shows the check is needed. The model decides and uploads in one action, so it always reads the tags at the moment it decides; that `central-portal.py` reads the tags again after a wait is checked by `test_precheck_rereads_the_tags_after_waiting`.

- Concurrency groups (`release-tag` for the tag job, `release-publish` for the publish job): a job that arrives in a group replaces the job waiting there. Every arrival counts, the first run and every re-run (of a failed job or of a cancelled run), whatever its version or commit, so a re-run of an older version can replace the waiting job of a newer one ([#863](https://github.com/j5ik2o/event-store-adapter-java/issues/863)). `RERUN_CANCELLED`: a person re-runs a cancelled run. The instance `cancelledLeftAlone` leaves cancelled runs alone (the recovery row before #863) and breaks I8 and I8b: the latest tag stays unpublished, or a merged version above the latest tag stays untagged, with nothing left to run. The tag gate does not require the commit to be the head of `main` (a merge that does not change the version can come after it), so I8b checks every untagged version commit above the latest tag.

This repository is event-store-adapter-scala. The model is the one of j5ik2o/event-store-adapter-kotlin (`.github/quint/` at `780eb20`, which is the java model at `3439740` with `portalResolveAt` added), unchanged. Only the scala instances differ; they have the R5 shape of this repository:

- `INIT_VER = 1, INIT_PRE = false`: R5 adds the `version` file with `5.2.0` (no pre-release). The merge of R5 itself (its parent has no `version`, so the tag job releases nothing) and the lightweight tag `v5.2.0` are outside the model; the release gate tests cover them.
- `LEGACY = Set(1)`: the tag `v5.2.0` exists, but 5.2.0 is not on Maven Central. It is published by re-running all jobs of the 2026-11-11 `publish` run 42967443751, which has no release gate (`publishLegacy`).
- `REQUIRED_PUBLISHED = Set(1)`: the release gate setting `REQUIRED_PUBLISHED = '5.2.0'` in `.github/release-gate.py`. The gate refuses every newer version, in the tag job and in the `publish` workflow, until repo1 serves the twelve POMs of 5.2.0.
- `RECORD_VIEW = true`: the precheck sees deployments only through their records, as in `javaR2b` and `kotlin`.

`scala`, `scalaNoManual` and `scalaReleasesOnly` are expected to have no violation; `scalaNoRequired` drops the setting and breaks I6b (`legacyBeforeNewer`). `scalaPathsTest` runs the fixed paths of `kotlinPathsTest` with the scala constants. `scalaBeforeR5` and `scalaOldBumpLive` describe the time before R5 (the old Bump Version stopped, or still running).

Outside the model: a tag made by hand on a commit before R5 (`a68723e` of `v5.2.0` or earlier). Such a commit runs its old `publish.yml` (tags `v*`, no gate), which could publish a newer version before 5.2.0. `pushTagByHand` only tags the head after R5, and the scala instances have `OLD_PUBLISH = false`, so `MANUAL_TAGS = true` does not cover that path. CONTRIBUTING forbids such tags; tag rulesets would close it structurally.

Invariants are `safety` (I1 to I11, with I8b `untaggedReleaseRecoverable`). Instances whose names describe a missing safeguard (`stepRecordDropped`, `unknownIgnored`, `noneSkipsQuery`, `cancelledLeftAlone` and others) are expected to violate an invariant.

## Running

```sh
bash .github/quint/run-quint.sh ci          # what CI runs: typecheck, every quint test, the scala and R2b instances with 500 samples
bash .github/quint/run-quint.sh full 3000   # every instance; run it locally when the model changes and put the result in the pull request
```

The script uses `quint` from `PATH`, or the command in `QUINT` (CI uses `npx --yes @informalsystems/quint@0.32.0`).

When `central-rules.py` changes what it decides, change the model first, then the code.
