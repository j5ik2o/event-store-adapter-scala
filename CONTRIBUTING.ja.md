> この文書は [CONTRIBUTING.md](CONTRIBUTING.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# 貢献ガイド

## 概要

event-store-adapter-scala に変更を加える開発者向けの案内です。ライブラリの使い方は [README](README.ja.md) を参照してください。

[English](CONTRIBUTING.md)

## 共有する仕様と設計判断

すべての言語実装で共有する仕様と設計判断は、ハブのリポジトリにあります。

- 仕様: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec>
- 設計判断の記録 (ADR): <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr>

公開される振る舞いを変える前に読んでください。このリポジトリでは再定義しません。

## ローカルの準備

以下の事実は `build.sbt`、`project/build.properties`、`.github/workflows/ci.yml` から確認したものです。

- sbt（`project/build.properties` で `sbt.version = 1.13.0`）と JDK。CI の試験は JDK 11・17・19、`lint` ジョブは JDK 25（Temurin）で動きます。
- Scala 2.13.18 と 3.6.4 でクロスビルドします。版は `sbt ++2.13.18 ...` または `sbt ++3.6.4 ...` で選び、`+` を付けると全版で実行します。
- 文書と適合データの検査に Python 3 が必要です。 `tools/user-docs/check.py` は Python 3.10 以上が必要です。
- DynamoDB Local、Cloud Spanner エミュレーター、Cloud Bigtable エミュレーターの試験に Docker が必要です。いずれも固定したイメージを使います（[README](README.ja.md#適合) を参照）。SQLite の試験は一時的なローカルファイルを使い、Docker は不要です。初回の実行を短くするため、`sbt test` の前に 2 つのエミュレーターのイメージを取得しておいてください。
- CI は `TEST_TIME_FACTOR: 3` で試験を実行します。

## 検査

CI（`.github/workflows/ci.yml`）は次を実行します。変更提案を出す前に、同じコマンドをローカルで実行してください。

```sh
sbt lint
sbt ++2.13.18 test
sbt ++3.6.4 test
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m unittest discover -s .github/tests -v
bash .github/quint/run-quint.sh ci
```

- `lint` は `scalafmtCheck`、`test:scalafmtCheck`、`scalafmtSbtCheck`、`scalafixAll --check` をまとめた sbt のエイリアスです。`sbt fmt` で整形を適用します（`scalafmtAll`、`scalafmtSbt`、`scalafix RemoveUnused`）。
- `python3 tools/conformance/manifest.py verify` は適合データを検査します。
- `python3 tools/user-docs/check.py` は利用者向け文書を検査します。
- `python3 -m unittest discover -s .github/tests -v` は、リリースの道具（関門、版上げ、bundle、Maven Central の確認）の試験です。先に依存を入れます：`python3 -m pip install -r .github/tests/requirements.txt`。
- `bash .github/quint/run-quint.sh ci` は、リリースの経路の Quint のモデルを検査します（`.github/quint/README.md`）。
- CI は `conformance/manifest.json`、`api-surface/surface.json`、`user-docs/template.json`、`tools/user-docs/check.py` の固定ハッシュも検査し、ワークフローを actionlint で検査します。対応する判断なしにこれらのファイルを編集しないでください。

## 設計の記録

`docs/design/next-major.md` は v5 を作る際の設計の記録です。現在の文書ではなく履歴です。現在の使い方は [README](README.ja.md) と現在のコードで確認してください。元の日本語本文は [next-major.ja.md](docs/design/next-major.ja.md) に残しています。

## 変更とリリース

- 変更は pull request で `main` に入れます。CI は pull request でも動き、`CI Success` ジョブが成功する必要があります。`CI Success` は、lint・test・release-workflow・release-bundle・release-model・aislop の結果をまとめて判定します。
- 版は根の `version` ファイル（1行の `X.Y.Z`）に持ち、`build.sbt` がすべてのプロジェクトの版として読みます。手で書き換えないでください。変えるのは Bump Version の版上げの pull request だけです。リリースのあとも `version` は出した版のままで、SNAPSHOT 版へは戻しません。そのため、`main` で手元の `sbt publishLocal` を流すと、出した版と同じ番号で `~/.ivy2/local` に入り、その手元では Maven Central の成果物の代わりに使われます。終わったら `~/.ivy2/local` から消してください。
- Bump Version ワークフローは手動で起動します。入力は `level`（`auto`、`patch`、`minor`、`major`）・`force_bump`・`version`・`rehearsal` です。最新のタグ以降のコミットのうち、件名が Conventional Commits の型を持つもの、または本文に `BREAKING CHANGE:`・`BREAKING-CHANGE:` があるものを数えます。1つも無ければ、`level` を指定していても版を上げません（ジョブのログに書きます）。それでも上げるときは `force_bump` か `version` を指定します。`level`（`patch`・`minor`・`major`）を明示した場合は、破壊的変更のコミットがあっても、その値をそのまま使います。`level=auto` では、型のあとの `!` か本文の破壊的変更の印があれば major、そうでなければ、型が `feat` か `revert` のコミットがあれば minor、ほかは patch です（`.github/semver-level.py`。以前の `github-tag-action` の手順と同じ規則）。`version` を明示した場合は段階を使わず、指定した版を次の版にします。その版はすべてのタグより大きくなければなりません。どの場合も、次の版は `version` の今の版より大きくなければならず、そうでなければ pull request を開く前に止まります（マージ済みで、まだタグの無い版上げの pull request があるとき）。
- Bump Version は `main` へ push しません。新しい版を `release/vX.Y.Z` の枝の `version` にコミットし、`main` への pull request を開きます。GitHub のリリースは作りません。
- `version` を変えたコミットが `main` に入ると、Bump Version のタグのジョブが、関門（`.github/release-gate.py`）でそのコミットを確かめます。コミットは、このリポジトリの `release/vX.Y.Z` からの pull request を squash でマージしたもので、`version` の版だけを変えていなければなりません。版は、前の版とすべての `vX.Y.Z` のタグより大きくなければなりません。親のコミットに `version` が無いとき（このファイルを足したコミット）は、リリースしません。
- `vX.Y.Z` のタグを push すると `publish` ワークフロー（`.github/workflows/publish.yml`）が起動します。`publish` ワークフローは、タグのコミットをもう一度関門で確かめてから、その版を Maven Central へ公開します（[リリースの出し方](#リリースの出し方)）。Snapshot（試験版）は公開しません。正式版を使ってください。
- 依存の更新は Renovate が `chore(deps):` の接頭辞付きで出します。

### リリースの出し方

1. Bump Version を流します（`rehearsal: false`）。版を決めるときは `version` に書きます。空なら、版を上げる段階で決まります。
2. pull request `version up to vX.Y.Z` を確かめます。変えているのが `version` だけで、Check unresolved comments が成功していなければなりません。リリースを出してよいときに、squash でマージします。やめるときは、閉じて枝を消します。
3. タグのジョブ（`Tag the merged version`）が、マージのコミットを関門で確かめ、`vX.Y.Z` を push します。
4. `publish` ワークフローが、ジョブ `Publish to Maven Central` を次の順に流します。
   1. タグのコミットを関門で確かめます。
   2. sbt が、すべてのプロジェクトと両方の Scala の版で、タグの版をビルドすることを確かめます（`sbt '+print version'`）。
   3. Maven Central と前の `publish` の run を確かめます（`.github/central-portal.py precheck`）。処理中の deployment があれば待ちます（最大 60 分）。版が公開済み（この版の `PUBLISHED` の deployment があるか、repo1 が12の POM をすべて返す）ならアップロードせずに先へ進み、repo1 が POM の一部だけを返すなら止まり、行方の分からない試みがあれば止まります。60 分には照会の時間も含みます。
   4. `sbt +publishSigned` で bundle を作り、タグのコミットをもう一度関門で確かめてから（bundle を作るあいだに新しいタグが push されていたら上げない）、Central Portal へ上げます（`publishingType=AUTOMATIC`）。bundle は 192 ファイルです。12 の成果物（6つのモジュールの Scala 2.13 と 3）それぞれの jar・sources・javadoc・POM と、その署名と、4つの本体の MD5・SHA-1 の checksum です。署名には checksum を付けません（Maven Central の要件にありません）。
   5. deployment の ID を artifact `central-deployment-<試みの番号>-<ID>` に残します（90 日）。
   6. deployment が `PUBLISHED` になるまで待ちます（最大 60 分）。
5. `PUBLISHED` になったら、`Create the GitHub release` が GitHub のリリースを作り、`Check Maven Central` が、repo1 が12の POM を返すまで待ちます（最大 60 分）。

予行（`rehearsal: true`）は、`version up to vX.Y.Z (rehearsal, do not merge)` という題の draft の pull request を開きます。マージせずに閉じて、枝を消してください。

署名の秘密の値（`PGP_SECRET`、`PGP_PASSPHRASE`）は bundle の手順だけに渡します。この手順は、鍵を一時の GnuPG のディレクトリに読み込み、終わったら agent を止めます。Sonatype の秘密の値は、事前の確認・アップロード・待ちの手順だけに渡します。`publish.yml` は personal access token を使わず、資格情報のファイルを書かず、依存の cache を使いません。

確かめる仕組みを保つための決まり：

- `vX.Y.Z` のタグを手で作らないでください。GitHub のリリースの画面で作るタグも同じです。タグは、そのタグが指すコミットの `publish` ワークフローを動かします。関門が入る前のコミット（`v5.2.0` のコミット `a68723e` とそれより前）には、タグを作らないでください。そのコミットの `publish.yml` は `v*` のどのタグでも動き、関門も事前の確認も無しに公開します。
- `publish.yml` のファイル名と `name` を変えず、`publish` の run と、その `central-` の artifact を消さないでください。artifact の保存期間は 90 日のままにします。事前の確認は、`publish.yml` の run を読んで前の試みの deployment を見つけます。
- `publish.yml` のジョブと手順の名前を変えないでください。事前の確認は、`.github/central-rules.py` の名前（`RELEASE_JOB`、`UPLOAD_STEP`、`PRE_UPLOAD_STEPS`）で、前の run のアップロードを見つけます。どうしても変えるときは、古い名前も集合に残します。
- `publish.yml` を変える pull request では、`.github/tests/fixtures/release-workflow.yml`（レビュー済みの写し）も同じように変えます。`.github/tests/test_release_workflow_shape.py` が `PUSH_RELEASE_TAG: 'true'` を許すのは、`publish.yml` がこの写しと行ごとに一字一句同じときだけです。違ってよいのは `uses:` の行の版だけです。
- 公開するプロジェクトや Scala の版を足す・消す pull request では、`.github/central-bundle.py` の `ARTIFACTS` も変えます。`.github/tests/test_central_bundle.py` が `build.sbt` と照らし合わせます。
- 失敗した run・取り消された run を再実行できるのは、始まってから 30 日までです。

リリースを止めるときは、`bump-version.yml` の `PUSH_RELEASE_TAG` を `'false'` にする pull request をマージします。`'false'` の間、タグのジョブは、打つはずのタグを job summary に書いて終わります。その間にマージした版上げの pull request の版は、タグが付かないまま `main` に残ります。pull request で `version` を前の版に戻し、出したい版を `version` に指定して Bump Version をやり直してください。

再実行は、`main` ではなく、その run のコミットのワークフローのファイルで動きます。リリースを止めている間は、止める前に動いたタグのジョブ（`Tag the merged version`）を再実行しないでください。その `bump-version.yml` は `PUSH_RELEASE_TAG: 'true'` のままなので、タグを push します。止める前に push したタグの `publish` の run も同じです。再実行するのは、リリースを再開してからにします。

### 5.2.0 が Maven Central に出るまで

タグ `v5.2.0` と GitHub のリリースはありますが、5.2.0 は Maven Central にありません。2026-11-11 の `publish` の run は成功しましたが、deployment は `FAILED` でした（[#920](https://github.com/j5ik2o/event-store-adapter-scala/issues/920)）。その run の `publish.yml` は bundle を上げるだけで、deployment の状態を読みません。5.2.0 は、その `publish` の run（[42967443751](https://github.com/j5ik2o/event-store-adapter-scala/actions/runs/42967443751)）の「すべてのジョブを再実行」で出します（run は成功しているので、失敗したジョブだけの再実行はできません）。再実行は、タグのコミット `a68723e` の `publish.yml`・`build.sbt`・`project/` で動き、関門を持たず、版をタグから求めます。事前の確認は、この run を数えません（`.github/central-rules.py` の `FIRST_COUNTED_RUN_ID`）。再実行が成功しても、5.2.0 が公開されたとは限りません。repo1 の12の POM で確かめてください。

- Bump Version は、repo1 が 5.2.0 の12の POM を返すまで止めたままにし、そのあとで有効にします。それまでは、版上げの pull request をマージしないでください。
- repo1 が 5.2.0 の12の POM を返すまで、関門は、タグのジョブでも `publish` ワークフローでも、それより新しい版をすべて拒否します（`.github/release-gate.py` の `REQUIRED_PUBLISHED = '5.2.0'`）。Portal で `PUBLISHED` でも、repo1 にまだ無ければ拒否します。
- それでも版上げの pull request をマージした場合、タグのジョブが関門で失敗し、タグも公開もありません。マージした版は 5.2.0 の再実行を妨げません。再実行は `v5.2.0` のワークフローで動き、マージした版は関門が止めておくからです。repo1 が 5.2.0 の12の POM を返すようになったら、失敗したタグのジョブを再実行します。
- repo1 は 404 をしばらく覚えています。5.2.0 を待つジョブを再実行する前に、12の POM を確かめてください（`.github/central-bundle.py` の成果物ごとに `https://repo1.maven.org/maven2/io/github/j5ik2o/<成果物>/5.2.0/<成果物>-5.2.0.pom`）。
- 失敗したタグのジョブを再実行できるのは、始まってから 30 日までです。過ぎたら、マージした版は飛ばします。次の版で Bump Version を流してください（たとえば、`5.2.1` がタグなしで `main` にあるなら `version=5.2.2`）。
- repo1 が 5.2.0 を返すようになったら、`REQUIRED_PUBLISHED = None` にする pull request を出します。それまでは、関門が動くたびに repo1 の 5.2.0 の12の POM を読みます。

### リリースが途中で止まったとき

たいていは、その run の失敗したジョブを再実行すれば回復します（Re-run failed jobs）。再実行のたびに、アップロードの前に Maven Central と前の試みを確かめ直すので、同じ版を二度上げることはありません。

止まり方によっては、repository variable `CENTRAL_DEPLOYMENTS`（Settings → Secrets and variables → Actions → Variables）に行を足します。1行に1つ書きます。

- `<run_id>/<試みの番号>=<deployment の ID>`：run `<run_id>` の試み `<試みの番号>` が上げた deployment。
- `<run_id>/<試みの番号>=none`：その試みは何も上げていない。または、その deployment を Portal で drop した。
- `tag/vX.Y.Z=checked`：その版の deployment が処理中でないことを、Portal で確かめた。

ID が無くて事前の確認が止まったときは、そのエラーの文が、足す行を `<run_id>/1` から `<run_id>/<run_attempt>` まで、行の無い試みすべてについてまとめて出します。試みの deployment は、Portal で `event-store-adapter-scala-<版>-run<run_id>-<試みの番号>` という名前になっています。

| 起きたこと | ワークフローの動き | 回復 |
|---|---|---|
| 5.2.0 がまだ repo1 に無い（`must wait until 5.2.0 is published on Maven Central`） | タグのジョブか `publish` ワークフローの関門が失敗する。タグもビルドもアップロードもしない | 先に 5.2.0 を出す（[5.2.0 が Maven Central に出るまで](#520-が-maven-central-に出るまで)）。repo1 が12の POM を返すようになったら、30 日のうちに失敗したジョブを再実行する |
| repo1 で 5.2.0 を読めなかった（`could not read whether 5.2.0 is on Maven Central`） | 関門が失敗する | 失敗したジョブを再実行する |
| タグが版上げの pull request の squash に付いていない、版と一緒にコードを変えた、版を下げた、タグと版が合わない、コミットの親に `version` が無い | 関門の手順が失敗する。ビルドもアップロードもしない | タグを消す。`version` を pull request で直す |
| sbt が、どれかのプロジェクトでタグと違う版をビルドする（`sbt builds [...]`） | `Check the version sbt builds` が失敗する | `version` か `build.sbt` を pull request で直し、次の版で出す |
| GitHub・Portal・repo1 への照会が失敗した（5xx、時間切れ） | 事前の確認が止まる | 失敗したジョブを再実行する |
| ほかの run の deployment が処理中 | 事前の確認が最大 60 分待ち、時間切れで失敗する | 失敗したジョブを再実行する |
| アップロードしたかもしれないのに、deployment の ID が無い試みがある（手順の一覧が切れた試み、ジョブの無い run、release ジョブの無い試みを含む） | 事前の確認が止まり、足す行を出す | 試みの job summary やログ、または Portal で名前から ID を探す。出された試みすべてに `<run_id>/<試みの番号>=<ID>` か `=none` を足して再実行する |
| アップロードが 201 と deployment の ID を返さない | アップロードの手順が失敗する。次の試みは上の行のとおり止まる | 上の行と同じ |
| bundle のファイルが足りない・多い、sbt が書いた checksum がファイルと合わない | bundle の手順が失敗する。アップロードしない | pull request で直し、次の版で出す。再実行はタグのコミットのスクリプトで動くので、直しは効かない |
| 署名の鍵が無い・違う | bundle の手順の署名で失敗する。アップロードしない | 秘密の値を直して再実行する |
| deployment が `FAILED` | 待ちの手順が Portal の errors を出して失敗する。GitHub のリリースは作らない | 失敗したジョブを再実行する（事前の確認が `FAILED` を見て、上げ直す）。中身の誤りなら次の版で直す。Maven Central の月の上限に当たったなら、上限が戻ってから再実行する |
| `FAILED` の deployment を Portal で drop した | 事前の確認が止まる（ID が見つからない） | drop したあとに `<run_id>/<試みの番号>=none` を足して再実行する |
| 60 分たっても deployment が処理中 | 待ちの手順が失敗する | 失敗したジョブを再実行する。事前の確認は同じ deployment を待ち、上げ直さない |
| 60 分たっても repo1 が版を返さない | `Check Maven Central` が失敗する | `Check Maven Central` だけを再実行する |
| GitHub のリリースを作れなかった | `Create the GitHub release` が失敗する | `Create the GitHub release` だけを再実行する |
| より新しいタグがある（版が追い越された） | 関門が止まる（`must be greater than the latest tag`）。その版は公開しない | そのまま（飛んだ版）。その版が既に `PUBLISHED` だったとき（待ちが時間切れになり、再実行の前に新しいタグができた）は、[新しいタグの前に公開された版](#新しいタグの前に公開された版)の手順で回復する |
| 待っていた公開のジョブが、`release-publish` の group にあとから着いたジョブ（ほかのタグの push、ほかの版の再実行）に置き換えられた | その run は始まる前に取り消される。その run は何も公開しない | その版が最新の `vX.Y.Z` のタグなら、取り消された run のジョブをすべて再実行する（取り消されたジョブは、アップロードに数えない）。そうでなければ、再実行しても新しいタグがあるので関門で止まる。前の試みで版が既に公開されていたとき（たとえば GitHub のリリースの作成だけが失敗していた）は、[新しいタグの前に公開された版](#新しいタグの前に公開された版)の手順で回復する。公開されていなければそのまま（飛んだ版） |
| タグのジョブ（`Tag the merged version`）が、`release-tag` の group にあとから着いたジョブに置き換えられた | その run は取り消される。その版にタグが付かない | その版が最新の `vX.Y.Z` のタグより大きければ、取り消された run を再実行する。あとにほかの pull request がマージされていても同じ（関門は `main` の先頭であることを求めない）。そうでなければそのまま（関門が拒否する） |
| `CENTRAL_DEPLOYMENTS` の行の形が違う、artifact と違う ID を書いた、Portal が知っている deployment に `none` を書いた | 事前の確認が止まる | 行を直すか消して、再実行する |
| `publish` の run を消した。タグの push で `publish` の run が起きなかった（一度に4つ以上のタグの push、障害） | 事前の確認が止まる（`publish` の run の無いタグがある：`tag vX.Y.Z has no publish.yml run`） | その版の deployment が処理中でないことを Portal で確かめ、`tag/vX.Y.Z=checked` を足して再実行する。タグは動かさない |
| `publish` の run は残っているが、試みの `central-deployment-<試みの番号>-<ID>` の artifact を消した | 事前の確認が止まる（その試みはアップロードしたかもしれないのに deployment の ID が無い）。足す行を出す。`tag/vX.Y.Z=checked` では通らない（run の無いタグにだけ効く） | その試みの job summary やログ、または Portal で名前 `event-store-adapter-scala-<版>-run<run_id>-<試みの番号>` から ID を探す。`<run_id>/<試みの番号>=<ID>` を足す。Portal にその deployment が無ければ `<run_id>/<試みの番号>=none` を足す。そのあと再実行する |
| `publish.yml` のジョブや手順の名前を変えた | 前の run で、事前の確認が止まる | 変えない。どうしても変えるときは、古い名前も `.github/central-rules.py` に残す |
| artifact の保存期間を 90 日より短くした、記録の期限が切れた | 事前の確認が止まる（`expires before 90 days` か `has expired`） | 新しい記録のために保存期間を 90 日に戻す。既にある記録の期限は早いまま変わらないので、エラーの文が出す行 `<run_id>/<試みの番号>=<ID>`（その記録の ID）を足して再実行する。事前の確認は、その deployment の状態をいつもどおり Portal で読む |
| repo1 が、その版の12の POM の一部だけを返す（`repo1 serves only N of the 12 POMs`） | 事前の確認が止まる。アップロードも GitHub のリリースもしない | 公開済みの deployment が反映の途中かもしれない。数分おいてジョブを再実行する。変わらなければ Portal で deployment を確かめる |
| 30 日を過ぎて、run を再実行できない | | その版は飛んだ版として扱い、次の版を出す。その前に、行方の分からない試みすべてに行を足す |
| 80 日より前の run | 事前の確認は数えない | |

#### 新しいタグの前に公開された版

版は Maven Central に出ているのに、その run が終わる前に新しい `vX.Y.Z` のタグが push された場合、古い版の再実行は、どれも関門で止まります。起きるのは、deployment の待ちが時間切れになり、そのあと Portal が公開したとき、または前の試みが公開したあとで後の手順（たとえば GitHub のリリースの作成）が失敗し、再実行が `release-publish` の group で置き換えられたときです。このとき、GitHub のリリースと repo1 の確認が欠けます。手で回復します。

1. repo1 が版を返すことを確かめます。`.github/central-bundle.py` の12の成果物（`ARTIFACTS`）それぞれについて、`https://repo1.maven.org/maven2/io/github/j5ik2o/<成果物>/<版>/<成果物>-<版>.pom` を見ます。
2. 前のリリースのタグ `<previous>` からリリースノートを書きます。

   ```sh
   python3 .github/create-release-note-header.py https://github.com j5ik2o/event-store-adapter-scala <previous> vX.Y.Z > release-body.md
   git log <previous>..vX.Y.Z --no-merges --format='%H%x1f%s%x1f%b%x1e' \
     | python3 .github/create-release-note-body.py https://github.com j5ik2o/event-store-adapter-scala >> release-body.md
   ```

3. リリースを作ります：`gh release create vX.Y.Z --verify-tag --title "Release vX.Y.Z" -F release-body.md`。

この場合のために関門を緩めることはしません。関門は、事前の確認より前に、秘密の値を持たずに、版がほかのどのタグより大きいことを確かめ続けます。
