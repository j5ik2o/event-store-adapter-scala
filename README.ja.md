> この文書は [README.md](README.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml?query=branch%3Amain)
[![Maven Central Scala 2.13](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-scala_2.13.svg?label=Scala%202.13)](https://central.sonatype.com/artifact/io.github.j5ik2o/event-store-adapter-scala_2.13)
[![Maven Central Scala 3](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-scala_3.svg?label=Scala%203)](https://central.sonatype.com/artifact/io.github.j5ik2o/event-store-adapter-scala_3)
[![javadoc](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-scala_3.svg?label=javadoc)](https://javadoc.io/doc/io.github.j5ik2o/event-store-adapter-scala_3)
[![Scala](https://img.shields.io/badge/Scala-2.13%20%7C%203-red.svg)](https://github.com/j5ik2o/event-store-adapter-scala/blob/main/build.sbt)
[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://github.com/j5ik2o/event-store-adapter-scala/blob/main/build.sbt)
[![Contract v5](https://img.shields.io/badge/contract-v5-blueviolet.svg)](https://github.com/j5ik2o/event-store-adapter/tree/contract-v5/docs/spec)
[![Storages](https://img.shields.io/badge/storages-memory%20%7C%20DynamoDB%20%7C%20SQLite%20%7C%20Spanner%20%7C%20Bigtable-informational.svg)](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec/storage)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg)](https://github.com/j5ik2o/event-store-adapter-scala#license)

## 概要

JavaのMemory・DynamoDB・SQLite・Spanner・Bigtableイベントストアを包むScalaライブラリです。同期の生成・操作は`Try`、非同期の生成・操作は`Future`を返します。ドメイン型には直列化できることだけを要求し、ライブラリのtraitを実装させません。

[English](README.md)

開発に参加する場合は [CONTRIBUTING](CONTRIBUTING.ja.md) を参照してください。

## インストール

Scala 2.13.18・3.6.4で構築し、Maven Centralの`io.github.j5ik2o:event-store-adapter-java:5.1.0`に依存します。SQLite・Spanner・Bigtableの保存先は、次に公開するScala `5.1.0`から使えます（旧版のScala `5.0.1`にはありません）。Scalaのライブラリ自体はMaven Centralに公開しています。旧成果物の公開操作は旧契約です。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md)に従います。メジャーの番号 5 は、共通契約の版と同じです。

```scala
val scalaAdapterVersion = "5.1.0"
libraryDependencies += "io.github.j5ik2o" %% "event-store-adapter-scala" % scalaAdapterVersion
```

## はじめに

### 保存と復元

次は名前をイベント内容と集約状態に使う、単独でコンパイルできる補助関数です。[AccountExampleSpec](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/examples/AccountExampleSpec.scala)がMemoryとDynamoDBを同期・非同期で実行します。SQLite・Spanner・Bigtableの用意のコード（[StorageExamples](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/examples/StorageExamples.scala)）は同じ試験が初回の起動と再起動で実行します。任意のScala case classと専用シリアライザは[UserAccountの例](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/internal/UserAccount.scala)を参照してください。

```scala
import com.github.j5ik2o.event.store.adapter.java.core.{AggregateId, EventEnvelope, EventStoreConfig, JsonPayloadSerializer, SnapshotEnvelope}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

object AccountExample {
  val config: EventStoreConfig[String, String] = EventStoreConfig
    .builder[String, String]()
    .payloadSerializer(JsonPayloadSerializer.of(classOf[String]))
    .snapshotSerializer(JsonPayloadSerializer.of(classOf[String]))
    .build()

  def event(id: AggregateId, seqNr: Long, name: String): EventEnvelope[String] = EventEnvelope
    .builder[String]()
    .aggregateId(id)
    .seqNr(seqNr)
    .occurredAt(Instant.parse("2026-10-10T00:00:00.123456789Z"))
    .payload(name)
    .build()

  def snapshot(seqNr: Long, name: String): SnapshotEnvelope[String] =
    SnapshotEnvelope.builder[String]().seqNr(seqNr).aggregate(name).build()

  def restore(store: EventStore[String, String], id: AggregateId): Try[Option[String]] =
    store.getLatestSnapshotById(id).flatMap {
      case None => Success(None)
      case Some(read) =>
        val start = read.snapshot.map(_.seqNr() + 1).getOrElse(1L)
        store.getEventsByIdSinceSeqNr(id, start).map { events =>
          events.foldLeft(read.snapshot.map(_.aggregate()))((_, event) => Some(event.payload()))
        }
    }

  def restoreAsync(store: EventStoreAsync[String, String], id: AggregateId)(implicit ec: ExecutionContext): Future[Option[String]] =
    store.getLatestSnapshotById(id).flatMap {
      case None => Future.successful(None)
      case Some(read) =>
        val start = read.snapshot.map(_.seqNr() + 1).getOrElse(1L)
        store.getEventsByIdSinceSeqNr(id, start).map { events =>
          events.foldLeft(read.snapshot.map(_.aggregate()))((_, event) => Some(event.payload()))
        }
    }
}
```

```scala
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage

val storage = MemoryStorage.create()
val store = EventStore.ofMemory(storage, AccountExample.config).get
val id = AggregateId.of("Account", "example-1")
store.persistEvent(AccountExample.event(id, 1L, "Alice")).get
store.persistEventAndSnapshot(
  AccountExample.event(id, 2L, "Bob"), AccountExample.snapshot(2L, "Bob")
).get
val name = AccountExample.restore(store, id).get // Some("Bob")

implicit val ec: ExecutionContext = ExecutionContext.global
val updated: Future[Option[String]] = for {
  async <- EventStoreAsync.ofMemory(storage, AccountExample.config)
  _ <- async.persistEvent(AccountExample.event(id, 3L, "Carol"))
  result <- AccountExample.restoreAsync(async, id)
} yield result // Some("Carol")
```

### 公開操作

| 公開操作 | 同期結果 | 非同期結果 |
|---|---|---|
| `persistEvent(event)` | `Try[Unit]` | `Future[Unit]` |
| `persistEventAndSnapshot(event, snapshot)` | `Try[Unit]` | `Future[Unit]` |
| `getLatestSnapshotById(id)` | `Try[Option[SnapshotReadResult[A]]]` | 対応する`Future` |
| `getEventsByIdSinceSeqNr(id, start)` | `Try[Seq[EventEnvelope[P]]]` | 対応する`Future` |

番号1はスナップショットなしでもヘッドを作ります。以後はヘッドの次の番号を使います。重複・古い番号は`OptimisticLockException`、飛び番は`ContractViolationException`です。同時に書くイベントとスナップショットの番号を一致させます。期待version引数はありません。

最新読取の外側の`None`はヘッド不存在です。結果が存在し、内側の`snapshot`が`None`ならスナップショットなしの既存集約です。復元開始はなしの場合1、ある場合`snapshot.seqNr() + 1`です。`headSeqNr`を開始番号や上界にしません。Memoryは組を原子的に読みます。DynamoDBは各項目を強整合で非原子的に読むため、スナップショットがヘッドより古い組も新しい組も合法です。イベント読取りは全ページを読みます。

## 保存先

### メモリ

同じ`MemoryStorage`を渡したストアだけが保存状態、設定、ロックを共有します。`ofMemory(config)`は同期・非同期とも生成ごとに独立した保存先を作ります。Memoryの非同期処理は呼出しスレッドで完了します。

メモリの保存先は単一プロセスのメモリだけを使います。ファイルへの保存、再起動後の回復、プロセス間の共有はしません。

### DynamoDB

[3表とスナップショットのグローバルセカンダリインデックス](docs/DYNAMODB_DATABASE_SCHEMA.ja.md)を先に作成します。設定済みのソフトウェア開発キットのクライアントは呼出元が所有・終了します。生成入口は設定項目を照合・初期化しますが、表は作成しません。

```scala
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient, DynamoDbAsyncClient}

val tables = DynamoDbTableConfig.builder()
  .journalTableName("example-journal")
  .snapshotTableName("example-snapshot")
  .headTableName("example-head")
  .snapshotAidIndexName("example-history")
  .build()

// client: DynamoDbClient, asyncClient: DynamoDbAsyncClient
val syncCreation = EventStore.ofDynamoDB(client, tables, AccountExample.config)
val asyncCreation = EventStoreAsync.ofDynamoDB(asyncClient, tables, AccountExample.config)
```

`asyncCreation`の成功後にストアを使います。Javaの非同期設定照合へ接続し、同期の入出力をScalaの`Future`へ包んで実行しません。

DynamoDB では、3 つの表は同じリージョンに置き、書き込みもそのリージョンからだけ行ってください。グローバルテーブル（複数リージョンのレプリカ）は範囲外です。

### SQLite

1つのローカルファイルがストア全体を持ちます（[SQLite のスキーマ](docs/SQLITE_DATABASE_SCHEMA.ja.md)）。新しいファイルを1回だけ初期化し、起動のたびに開きます。開く操作はファイルを作成も初期化もしません。`SqliteStorage`を閉じるのは呼出元で、Scalaの生成入口は閉じません。ネットワークファイルシステムは使わないでください。

```scala
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.sqlite.{SqliteStorage, SqliteStorageConfig}

import java.nio.file.Path
import java.time.Duration

def sqliteSettings(): SqliteStorageConfig =
  SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.delete(2)).lockWait(Duration.ofSeconds(5)).build()

// 初回だけ。新しい（空の）ファイルに表を作ります。
def initializeSqlite(file: Path): Unit = SqliteStorage.initialize(file, sqliteSettings())

// 毎回。初期化済みのファイルを開きます。閉じるのは呼出元です。
def openSqlite(file: Path): SqliteStorage = SqliteStorage.open(file, sqliteSettings())

// 初回の起動
initializeSqlite(file)
val storage = openSqlite(file)
try {
  val store = EventStore.ofSqlite(storage, AccountExample.config).get
  store.persistEvent(AccountExample.event(id, 1L, "Alice")).get
} finally storage.close()

// 再起動。同じファイルを開き直します。initializeは呼びません。
val restarted = openSqlite(file)
try {
  val reopened = EventStore.ofSqlite(restarted, AccountExample.config).get
  AccountExample.restore(reopened, id).get // Some("Alice")
  reopened.persistEvent(AccountExample.event(id, 2L, "Bob")).get
  val asyncReopened = EventStoreAsync.ofSqlite(restarted, AccountExample.config) // Future
} finally restarted.close()
```

### Spanner

5つの表と設定行は、運用者がライブラリの外で1回だけ作成します（[Spanner のスキーマ](docs/SPANNER_DATABASE_SCHEMA.ja.md)）。Spannerの`DatabaseClient`は呼出元が所有し、保存先を開く操作もScalaの生成入口も閉じません。表の接頭辞と保持方針は設定行と一致させます。

```scala
import com.github.j5ik2o.event.store.adapter.java.spanner.{SpannerRetentionPolicy, SpannerStorage, SpannerStorageConfig}
import com.google.cloud.spanner.DatabaseClient

def spannerSettings(prefix: String): SpannerStorageConfig =
  SpannerStorageConfig.builder().tablePrefix(prefix).retentionPolicy(SpannerRetentionPolicy.delete(2)).build()

// 毎回。表と設定行は作成済みです。
def openSpanner(client: DatabaseClient, prefix: String): SpannerStorage =
  SpannerStorage.open(client, spannerSettings(prefix))

// client: DatabaseClient
val storage = openSpanner(client, "accounts")
val store = EventStore.ofSpanner(storage, AccountExample.config).get
store.persistEvent(AccountExample.event(id, 1L, "Alice")).get

// 再起動。同じ表を開き直します。
val restarted = openSpanner(client, "accounts")
val reopened = EventStore.ofSpanner(restarted, AccountExample.config).get
AccountExample.restore(reopened, id).get // Some("Alice")
val asyncReopened = EventStoreAsync.ofSpanner(restarted, AccountExample.config) // Future
```

### Bigtable

2つの表と列ファミリーは、運用者がライブラリの外で作成します（[Bigtable のスキーマ](docs/BIGTABLE_DATABASE_SCHEMA.ja.md)）。初回の起動で`initialize`を1回呼んで設定行を書き、以後の起動では開くだけにします。データ用と管理用のクライアントは呼出元が所有し、閉じます。

```scala
import com.github.j5ik2o.event.store.adapter.java.bigtable.{BigtableStorage, BigtableStorageConfig}
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient
import com.google.cloud.bigtable.data.v2.BigtableDataClient

def bigtableSettings(journalTableId: String, headTableId: String): BigtableStorageConfig =
  BigtableStorageConfig.builder()
    .journalTableId(journalTableId)
    .headTableId(headTableId)
    .retentionPolicy(RetentionPolicy.delete(2))
    .build()

// 初回だけ。運用者が作った表に2つの設定行を書きます。
def initializeBigtable(data: BigtableDataClient, settings: BigtableStorageConfig): Unit =
  BigtableStorage.initialize(data, settings)

// 毎回。初期化済みの表を開きます。
def openBigtable(data: BigtableDataClient, admin: BigtableTableAdminClient, settings: BigtableStorageConfig): BigtableStorage =
  BigtableStorage.open(data, admin, settings)

// data: BigtableDataClient, admin: BigtableTableAdminClient
val settings = bigtableSettings("accounts-journal", "accounts-head")
initializeBigtable(data, settings) // 初回だけ
val storage = openBigtable(data, admin, settings)
val store = EventStore.ofBigtable(storage, AccountExample.config).get
store.persistEvent(AccountExample.event(id, 1L, "Alice")).get

// 再起動。開き直します。initializeは呼びません。
val restarted = openBigtable(data, admin, settings)
val reopened = EventStore.ofBigtable(restarted, AccountExample.config).get
AccountExample.restore(reopened, id).get // Some("Alice")
val asyncReopened = EventStoreAsync.ofBigtable(restarted, AccountExample.config) // Future
```

SQLite・Spanner・Bigtableの非同期の生成入口は、Memoryと同じく呼出しスレッドで完了します。ストアを使い終えても、保存先とクライアントは開いたままです。閉じるのは呼出元です。

## 直列化

Javaの`core`にある識別子、封筒、型付き`PayloadSerializer<T>`、`EventStoreConfig`、保持方針、5分類例外を再利用します。イベントと集約状態のシリアライザは生成時に決め、読取りごとの`Class`指定は不要です。直列化するのはペイロードだけで、封筒のメタデータを混ぜません。

## 保持

`RetentionPolicy.none()`は現在のスナップショットだけ、`delete(n)`は最新n件の履歴を残します。DynamoDBは`ttl(n, graceSeconds)`による期限付き保持にも対応し、スナップショット表の有効期限属性を有効にします。Spannerは`SpannerRetentionPolicy.ttl(n, graceDays)`に対応します。Memory・SQLite・Bigtableは期限付き保持を拒みます。方針は`MemoryStorage`、`DynamoDbTableConfig`、`SqliteStorageConfig`、`SpannerStorageConfig`、`BigtableStorageConfig`に設定します。

確定後の保持失敗は書込み成功を変えません。ログと`retentionFailureListener`で元原因を知らせます。

## エラー

失敗はJavaの例外型、`category()`、元原因、診断を保持します。5分類は`OptimisticLockException`、`ContractViolationException`、`SerializationException`、`ConfigurationException`、`StorageException`です。契約違反は`rule()`と`seqNr()`を持ちます。メッセージから分類を推測せず、失敗値を調べます。非同期Java操作を呼ぶ時点の同期例外も、失敗したScalaのFutureで返します。

保存、ロック、後片付け、再試行はJavaが所有し、Scalaは結果とFutureの包みを変換します。

## 移行

[移行案内](docs/MIGRATION_GUIDE.ja.md)に、旧版読取→アプリケーションの明示変換→新封筒での書直しを示します。保存済みデータの自動変換はありません。SQLite・Spanner・Bigtableは1.x系にはなかったため、移すデータはありません。使い始める手順は移行案内にあります。

## 適合

```sh
sbt +compile +test +lint
python3 tools/conformance/manifest.py verify
```

固定DynamoDB Local 3.3.1、Cloud Spannerエミュレーター、Cloud Bigtableエミュレーターの試験にはDockerが必要です。SQLiteの試験はローカルファイルを使い、Dockerは不要です。[適合試験](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/conformance/ScalaPublicConformanceSpec.scala)は受入済みの比較器・障害支援を再利用し、Scala公開生成と操作から実行します。`target/reports/<scala-version>/conformance/`に、規則別結果、manifest照合、障害適用回数、理由付き対象外を保存します。対象のケースはすべて、5つの保存先で同期と非同期の公開生成入口から実行します。ケースを対象外にするのはJava実装と同じ理由（ほかの保存先の場面、ミリ秒精度、DynamoDBの配置、SQLiteの変更フィード）だけで、保存先が未提供であることは理由にしません。

## ライセンス

MIT と Apache-2.0 のどちらかを選べる二重ライセンス（`MIT OR Apache-2.0`）です。[LICENSE-MIT](LICENSE-MIT) と [LICENSE-APACHE](LICENSE-APACHE) を参照してください。
