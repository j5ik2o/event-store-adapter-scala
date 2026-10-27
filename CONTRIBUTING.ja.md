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
```

- `lint` は `scalafmtCheck`、`test:scalafmtCheck`、`scalafmtSbtCheck`、`scalafixAll --check` をまとめた sbt のエイリアスです。`sbt fmt` で整形を適用します（`scalafmtAll`、`scalafmtSbt`、`scalafix RemoveUnused`）。
- `python3 tools/conformance/manifest.py verify` は適合データを検査します。
- `python3 tools/user-docs/check.py` は利用者向け文書を検査します。
- `python3 -m unittest discover -s .github/tests -v` は、リリースのワークフローが使う版上げ選択の試験です。
- CI は `conformance/manifest.json`、`api-surface/surface.json`、`user-docs/template.json`、`tools/user-docs/check.py` の固定ハッシュも検査し、ワークフローを actionlint で検査します。対応する判断なしにこれらのファイルを編集しないでください。

## 設計の記録

`docs/design/next-major.md` は v5 を作る際の設計の記録です。現在の文書ではなく履歴です。現在の使い方は [README](README.ja.md) と現在のコードで確認してください。元の日本語本文は [next-major.ja.md](docs/design/next-major.ja.md) に残しています。

## 変更とリリース

- `main` に対して変更提案を出します。CI は変更提案でも動き、`CI Success` ジョブが成功する必要があります。
- 版は sbt-dynver が Git のタグから導きます。変更提案で版を手作業で書き換えないでください。
- リリースは `Bump Version` ワークフロー（`.github/workflows/bump-version.yml`）が作ります。版のタグを付けて GitHub のリリースを作成します。上げる水準は、最新のタグ以降の Conventional Commits 形式のコミットメッセージ、または起動時に指定した水準・版から選びます。
