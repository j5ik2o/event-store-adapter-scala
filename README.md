# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-scala_2.13)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Scala wrappers for the Java Memory and DynamoDB event stores. Synchronous factories and operations return `Try`; asynchronous factories and operations return `Future`. Domain payloads only need to be serializable; they implement no library trait.

[日本語](README.ja.md)

## Dependency

The source is cross-built for Scala 2.13.18 and 3.6.4 and depends on `io.github.j5ik2o:event-store-adapter-java:2.0.0-SNAPSHOT`. Use a Scala Snapshot built from this source. Its version is produced by the existing sbt-dynver configuration; this change does not publish a release or select a new Scala version number. Older Scala artifacts expose the previous API.

```scala
val scalaAdapterVersion = "<Snapshot version built from this source>"
resolvers += "Sonatype Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
libraryDependencies += "io.github.j5ik2o" %% "event-store-adapter-scala" % scalaAdapterVersion
```

Java's `core` identifiers, envelopes, typed `PayloadSerializer<T>`, `EventStoreConfig`, `RetentionPolicy` and five exception classes are reused. Bind event and aggregate-state serializers at creation; reads take no `Class`. Serializers process payloads only, separately from envelope metadata.

## Save and restore

This complete helper uses names as event payloads and aggregate state. It is compiled and executed by [AccountExampleSpec](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/examples/AccountExampleSpec.scala) with both stores and both public factory forms. For arbitrary Scala case classes and dedicated serializers, see the [UserAccount example](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/internal/UserAccount.scala).

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

Only stores given the same `MemoryStorage` share records, settings and locking. `ofMemory(config)` creates an isolated storage for each call, for both synchronous and asynchronous factories. Memory's asynchronous work completes on the calling thread.

| Public operation | Synchronous result | Asynchronous result |
|---|---|---|
| `persistEvent(event)` | `Try[Unit]` | `Future[Unit]` |
| `persistEventAndSnapshot(event, snapshot)` | `Try[Unit]` | `Future[Unit]` |
| `getLatestSnapshotById(id)` | `Try[Option[SnapshotReadResult[A]]]` | Corresponding `Future` |
| `getEventsByIdSinceSeqNr(id, start)` | `Try[Seq[EventEnvelope[P]]]` | Corresponding `Future` |

Sequence 1 creates a head, even without a snapshot. Later events immediately follow the head. Duplicates and stale sequences cause `OptimisticLockException`; gaps cause `ContractViolationException`. Event and snapshot sequences must match. There is no version expectation argument.

An empty outer `Option` means no head exists. A present `SnapshotReadResult` with an empty `snapshot` means the aggregate exists without a snapshot. Replay starts at 1 without a snapshot, or `snapshot.seqNr() + 1` with one. `headSeqNr` is neither the replay start nor an upper bound. Memory reads the pair atomically; DynamoDB reads each item strongly consistently but the pair non-atomically, so a snapshot may be older or newer than the head. Event reads consume every page.

## DynamoDB factories

Provision [three tables and the snapshot global secondary index](docs/DATABASE_SCHEMA.md) before creation. The caller owns and closes configured SDK clients. Factories validate or initialize the configuration items, and never provision tables.

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

Use the asynchronous store only after `asyncCreation` succeeds. It is connected to Java's asynchronous configuration I/O; synchronous I/O is not scheduled inside a Scala `Future`.

## Retention and failures

`RetentionPolicy.none()` keeps the current snapshot only; `delete(n)` keeps the newest n history snapshots. DynamoDB also supports `ttl(n, graceSeconds)` with snapshot TTL enabled. Memory rejects TTL. Configure the policy on `MemoryStorage` or `DynamoDbTableConfig`.

A retention failure after commit preserves write success, logs the failure and notifies `retentionFailureListener` with the original cause. Java owns persistence, locking, cleanup and retries. The Scala layer converts only results and future wrappers.

Failures retain the Java exception type, `category()`, original cause and diagnostics: `OptimisticLockException`, `ContractViolationException`, `SerializationException`, `ConfigurationException`, `StorageException`. Contract violations expose `rule()` and `seqNr()`. Inspect failure values, not message text, to classify errors. Synchronous exceptions raised while invoking the asynchronous Java API become failed Scala futures.

## Migration and verification

See [MIGRATION_GUIDE](MIGRATION_GUIDE.md) for the old-version read → application conversion → new-envelope rewrite procedure. Existing stored data is not automatically converted.

```sh
sbt +compile +test +lint
python3 tools/conformance/manifest.py verify
```

Docker is required for the pinned DynamoDB Local 3.3.1 tests. [The conformance suite](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/conformance/ScalaPublicConformanceSpec.scala) reuses the accepted comparison and fault machinery through Scala public factories and operations. Reports are saved in `target/reports/<scala-version>/conformance/`; they include per-rule results, manifest checks, fault applications and reasoned exclusions. FNV-1a 64 cases remain outside this release's storage profiles and do not count as successes.

MIT License. See [LICENSE](LICENSE).
