# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-scala_2.13)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

JavaのMemory・DynamoDBイベントストアを包むScalaライブラリです。同期の生成・操作は`Try`、非同期の生成・操作は`Future`を返します。ドメイン型には直列化できることだけを要求し、ライブラリのtraitを実装させません。

[English](README.md)

## 導入

Scala 2.13.18・3.6.4で構築し、`io.github.j5ik2o:event-store-adapter-java:2.0.0-SNAPSHOT`に依存します。このソースから作られたScalaのSnapshotを指定してください。版は既存のsbt-dynver設定から生成します。この変更で正式公開やScalaの手動版上げは行いません。旧成果物の公開操作は旧契約です。

```scala
val scalaAdapterVersion = "<Snapshot version built from this source>"
resolvers += "Sonatype Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
libraryDependencies += "io.github.j5ik2o" %% "event-store-adapter-scala" % scalaAdapterVersion
```

Javaの`core`にある識別子、封筒、型付き`PayloadSerializer<T>`、`EventStoreConfig`、保持方針、5分類例外を再利用します。イベントと集約状態のシリアライザは生成時に決め、読取りごとの`Class`指定は不要です。直列化するのはペイロードだけで、封筒のメタデータを混ぜません。

## 保存と復元

次は名前をイベント内容と集約状態に使う、単独でコンパイルできる補助関数です。[AccountExampleSpec](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/examples/AccountExampleSpec.scala)が両保存先・同期非同期で実行します。任意のScala case classと専用シリアライザは[UserAccountの例](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/internal/UserAccount.scala)を参照してください。

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

同じ`MemoryStorage`を渡したストアだけが保存状態、設定、ロックを共有します。`ofMemory(config)`は同期・非同期とも生成ごとに独立した保存先を作ります。Memoryの非同期処理は呼出しスレッドで完了します。

| 公開操作 | 同期結果 | 非同期結果 |
|---|---|---|
| `persistEvent(event)` | `Try[Unit]` | `Future[Unit]` |
| `persistEventAndSnapshot(event, snapshot)` | `Try[Unit]` | `Future[Unit]` |
| `getLatestSnapshotById(id)` | `Try[Option[SnapshotReadResult[A]]]` | 対応する`Future` |
| `getEventsByIdSinceSeqNr(id, start)` | `Try[Seq[EventEnvelope[P]]]` | 対応する`Future` |

番号1はスナップショットなしでもヘッドを作ります。以後はヘッドの次の番号を使います。重複・古い番号は`OptimisticLockException`、飛び番は`ContractViolationException`です。同時に書くイベントとスナップショットの番号を一致させます。期待version引数はありません。

最新読取の外側の`None`はヘッド不存在です。結果が存在し、内側の`snapshot`が`None`ならスナップショットなしの既存集約です。復元開始はなしの場合1、ある場合`snapshot.seqNr() + 1`です。`headSeqNr`を開始番号や上界にしません。Memoryは組を原子的に読みます。DynamoDBは各項目を強整合で非原子的に読むため、スナップショットがヘッドより古い組も新しい組も合法です。イベント読取りは全ページを読みます。

## DynamoDBの生成

[3表とスナップショットのグローバルセカンダリインデックス](docs/DATABASE_SCHEMA.ja.md)を先に作成します。設定済みのソフトウェア開発キットのクライアントは呼出元が所有・終了します。生成入口は設定項目を照合・初期化しますが、表は作成しません。

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

## 保持と失敗

`RetentionPolicy.none()`は現在のスナップショットだけ、`delete(n)`は最新n件の履歴を残します。DynamoDBは`ttl(n, graceSeconds)`による期限付き保持にも対応し、スナップショット表の有効期限属性を有効にします。Memoryは期限付き保持を拒みます。方針は`MemoryStorage`または`DynamoDbTableConfig`に設定します。

確定後の保持失敗は書込み成功を変えません。ログと`retentionFailureListener`で元原因を知らせます。保存、ロック、保持、再試行はJavaが所有し、Scalaは結果とFutureの包みを変換します。

失敗はJavaの例外型、`category()`、元原因、診断を保持します。5分類は`OptimisticLockException`、`ContractViolationException`、`SerializationException`、`ConfigurationException`、`StorageException`です。契約違反は`rule()`と`seqNr()`を持ちます。メッセージから分類を推測せず、失敗値を調べます。非同期Java操作を呼ぶ時点の同期例外も、失敗したScalaのFutureで返します。

## 移行と検証

[移行案内](MIGRATION_GUIDE.ja.md)に、旧版読取→アプリケーションの明示変換→新封筒での書直しを示します。保存済みデータの自動変換はありません。

```sh
sbt +compile +test +lint
python3 tools/conformance/manifest.py verify
```

固定DynamoDB Local 3.3.1の試験にはDockerが必要です。[適合試験](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/conformance/ScalaPublicConformanceSpec.scala)は受入済みの比較器・障害支援を再利用し、Scala公開生成と操作から実行します。`target/reports/<scala-version>/conformance/`に、規則別結果、manifest照合、障害適用回数、理由付き対象外を保存します。FNV-1a 64の4ケースは、この公開範囲の保存先で使わないため成功へ数えません。

MITライセンス。[LICENSE](LICENSE)を参照してください。
