# 新Java公開形へのScala対応

保存済み計画を引き継ぐ対応記録です。新たな契約番号や受入条件は追加しません。Java依存は`io.github.j5ik2o:event-store-adapter-java:2.0.0-SNAPSHOT`です。Scala版とsbt-dynver設定は維持します。

Javaの`core`の識別子、封筒、型付きシリアライザ、設定、保持方針、5分類例外と、`memory`・`dynamodb`の保存本体を再利用します。Scala独自型は`SnapshotReadResult[A](snapshot: Option[SnapshotEnvelope[A]], headSeqNr: Long)`だけです。

| 入口 | 同期 | 非同期 |
|---|---|---|
| `ofMemory(config)`、`ofMemory(storage, config)` | `Try[EventStore[P,A]]` | `Future[EventStoreAsync[P,A]]` |
| `ofDynamoDB(client, tables, config)` | 同上 | 同上 |
| `persistEvent(event)` | `Try[Unit]` | `Future[Unit]` |
| `persistEventAndSnapshot(event, snapshot)` | `Try[Unit]` | `Future[Unit]` |
| `getLatestSnapshotById(id)` | `Try[Option[SnapshotReadResult[A]]]` | 対応する`Future` |
| `getEventsByIdSinceSeqNr(id, start)` | `Try[Seq[EventEnvelope[P]]]` | 対応する`Future` |

同期はJava呼出しを`Try`内で評価します。非同期DynamoDB生成と操作はJavaの完了へ接続し、呼出し時の同期例外も失敗Futureへ変換します。完了例外の包みだけを外し、既分類例外を作り直しません。元原因、規則番号、番号診断を保持します。イベント列は不変のScala列へコピーします。

外側の`None`はヘッド不存在、内側の`None`は既存集約のスナップショットなしです。復元開始はスナップショット番号+1、なければ1です。ヘッドを開始番号・上界にせず、Javaの非原子的な読取結果を補正しません。

同じ`MemoryStorage`を指定した生成だけが状態を共有します。省略生成は毎回独立します。DynamoDBのクライアントと作成済み3表は呼出元が所有します。保存、ロック、保持、ページ送り、再試行はJavaの責務です。Scalaに保存本体を追加しません。

旧trait制約、version期待引数、読取ごとの`Class`、旧オプション、キー解決器・シャード、旧専用ラッパーを現行利用側とまとめて置換します。互換読取は追加せず、旧データには[手動移行案内](../../MIGRATION_GUIDE.ja.md)を提供します。

適合試験は保存済みJava支援の比較・障害注入・独立観測を再利用し、生成、4操作、時刻往復、読取中追記をScala公開入口へ接続します。実行結果はScala版別に保存し、値構築の検査と保存先の公開操作を記録から区別できます。最終mainの検査、正式レビュー、マージは調整役の後段工程です。
