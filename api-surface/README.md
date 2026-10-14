# API の形の照合（scala）

共通契約 v5 が公開 API に求める「形」を、ハブの [`api-surface/surface.json`](https://github.com/j5ik2o/event-store-adapter/tree/main/api-surface)（形式 0.1.0）と照合します。

| ファイル | 内容 |
|---|---|
| `surface.json` | ハブの定義をそのまま写したもの。SHA-256 は `37c91f8f94e10c73513a9c1deef990ea4deea96d5001ae700d6bf799658d0e32` |
| `scala.json` | 共通の名前と scala の公開 API の対応 |

## 照合のしかた

```sh
echo "37c91f8f94e10c73513a9c1deef990ea4deea96d5001ae700d6bf799658d0e32  api-surface/surface.json" | sha256sum -c -
sbt "+testOnly *apisurface*"
```

SHA-256 の照合は CI の `lint` ジョブでも実行します。形の照合は `src/test/scala/.../apisurface/` の試験で、コンパイル済みのクラスを Java のリフレクション（総称型の情報を含む `getGenericReturnType` など）で読み、`surface.json` と `scala.json` を突き合わせます。Scala 2.13 と 3 の両方で通ります。

## 確かめていること

- 同期の `EventStore[P, A]`（戻り値を `Try` で包む）と非同期の `EventStoreAsync[P, A]`（`Future` で包み、末尾に `implicit ExecutionContext` を取る）の 4 つの操作（`persistEvent`・`persistEventAndSnapshot`・`getLatestSnapshotById`・`getEventsByIdSinceSeqNr`）の引数と戻り値。`none` は `Unit`、`optional<T>` は `Option[T]`、`list<T>` は `Seq[T]`。
- `SnapshotReadResult[A]` の要素（`snapshot: Option[SnapshotEnvelope[A]]` と `headSeqNr: Long`）。
- ストアと `SnapshotReadResult` の型の引数が `P`・`A` で、上限の制約がないこと。
- 対応表の抜けやクラスが見つからないことは、黙って飛ばさず報告します。`Try` で包むストアと `Future` で包むストアの、どちらかの対応がないこと、`SnapshotReadResult` の要素の対応（`accessors`）がないこと、例外のクラスが見つからないことも報告します。
- 否定の試験（`ApiSurfaceNegativeSpec`）で、非同期のストアの対応や `accessors` を消した対応表、存在しない例外のクラスを書いた対応表を報告すること、形をわざとずらした 5 つの定義（試験側の別パッケージ `apisurface.fixtures`）を、それぞれ報告し、関係のないずれは報告しないこと。

## 確かめられないこと

- 封筒（`EventEnvelope`・`SnapshotEnvelope`）と例外の分類の形そのもの。scala は java のものをそのまま使うため、java の照合に任せます（`scala.json` の `checked_by: "java"`）。
- 振る舞い（適合テストデータ `conformance/` で確かめます）。
- メモリ・DynamoDB の実装。公開の型と操作だけが対象です。
- surface の型の対応は、クラスの単純名と型の引数で比べます（パッケージは比べません）。
- 型の引数に `P <: AnyRef` のような `AnyRef` の上限があること。Java のリフレクションでは、上限のない型の引数と同じく `Object` に見えるため区別できません（`AnyRef` 以外の上限は報告します）。
- `ExecutionContext` が `implicit` であること。Java のリフレクションには `implicit` の印が残らないため、末尾の引数の型だけを比べます。
