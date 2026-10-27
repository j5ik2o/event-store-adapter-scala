# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-scala_2.13)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## Overview

Scala wrappers for the Java Memory, DynamoDB, SQLite, Spanner and Bigtable event stores. Synchronous factories and operations return `Try`; asynchronous factories and operations return `Future`. Domain payloads only need to be serializable; they implement no library trait.

[日本語](README.ja.md)

To contribute, see [CONTRIBUTING](CONTRIBUTING.md).

## Installation

The source is cross-built for Scala 2.13.18 and 3.6.4 and depends on `io.github.j5ik2o:event-store-adapter-java:5.0.2-SNAPSHOT`, a development build resolved from the Sonatype snapshot repository (the older `5.0.1` does not contain the SQLite, Spanner and Bigtable storages). The Scala library itself is published to Maven Central. Older Scala artifacts expose the previous API. This library follows [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md); its major version 5 matches the contract revision.

```scala
val scalaAdapterVersion = "5.0.1"
libraryDependencies += "io.github.j5ik2o" %% "event-store-adapter-scala" % scalaAdapterVersion
```

## Quick start

### Save and restore

This complete helper uses names as event payloads and aggregate state. It is compiled and executed by [AccountExampleSpec](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/examples/AccountExampleSpec.scala) with the Memory and DynamoDB stores and both public factory forms; the SQLite, Spanner and Bigtable set-up code below ([StorageExamples](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/examples/StorageExamples.scala)) is run by the same spec, once for the first launch and once for a restart. For arbitrary Scala case classes and dedicated serializers, see the [UserAccount example](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/internal/UserAccount.scala).

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

### Public operations

| Public operation | Synchronous result | Asynchronous result |
|---|---|---|
| `persistEvent(event)` | `Try[Unit]` | `Future[Unit]` |
| `persistEventAndSnapshot(event, snapshot)` | `Try[Unit]` | `Future[Unit]` |
| `getLatestSnapshotById(id)` | `Try[Option[SnapshotReadResult[A]]]` | Corresponding `Future` |
| `getEventsByIdSinceSeqNr(id, start)` | `Try[Seq[EventEnvelope[P]]]` | Corresponding `Future` |

Sequence 1 creates a head, even without a snapshot. Later events immediately follow the head. Duplicates and stale sequences cause `OptimisticLockException`; gaps cause `ContractViolationException`. Event and snapshot sequences must match. There is no version expectation argument.

An empty outer `Option` means no head exists. A present `SnapshotReadResult` with an empty `snapshot` means the aggregate exists without a snapshot. Replay starts at 1 without a snapshot, or `snapshot.seqNr() + 1` with one. `headSeqNr` is neither the replay start nor an upper bound. Memory reads the pair atomically; DynamoDB reads each item strongly consistently but the pair non-atomically, so a snapshot may be older or newer than the head. Event reads consume every page.

## Storages

### Memory

Only stores given the same `MemoryStorage` share records, settings and locking. `ofMemory(config)` creates an isolated storage for each call, for both synchronous and asynchronous factories. Memory's asynchronous work completes on the calling thread.

The memory storage lives in a single process. It does not persist to files, survive restarts, or share data across processes.

### DynamoDB

Provision [three tables and the snapshot global secondary index](docs/DYNAMODB_DATABASE_SCHEMA.md) before creation. The caller owns and closes configured SDK clients. Factories validate or initialize the configuration items, and never provision tables.

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

For DynamoDB, keep the three tables in one Region and write to them only from that Region. Global tables (multi-Region replicas) are not supported.

### SQLite

One local file holds the whole store (see the [SQLite schema](docs/SQLITE_DATABASE_SCHEMA.md)). Initialize a new file once, then open it on every launch. Opening never creates or initializes a file. The caller closes the `SqliteStorage`; the Scala factories never close it. Do not use a network file system.

```scala
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.sqlite.{SqliteStorage, SqliteStorageConfig}

import java.nio.file.Path
import java.time.Duration

def sqliteSettings(): SqliteStorageConfig =
  SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.delete(2)).lockWait(Duration.ofSeconds(5)).build()

// First launch only: creates the tables in a new or empty file.
def initializeSqlite(file: Path): Unit = SqliteStorage.initialize(file, sqliteSettings())

// Every launch: opens the initialized file. The caller closes it.
def openSqlite(file: Path): SqliteStorage = SqliteStorage.open(file, sqliteSettings())

// First launch
initializeSqlite(file)
val storage = openSqlite(file)
try {
  val store = EventStore.ofSqlite(storage, AccountExample.config).get
  store.persistEvent(AccountExample.event(id, 1L, "Alice")).get
} finally storage.close()

// Restart: open the same file again; do not call initialize.
val restarted = openSqlite(file)
try {
  val reopened = EventStore.ofSqlite(restarted, AccountExample.config).get
  AccountExample.restore(reopened, id).get // Some("Alice")
  reopened.persistEvent(AccountExample.event(id, 2L, "Bob")).get
  val asyncReopened = EventStoreAsync.ofSqlite(restarted, AccountExample.config) // Future
} finally restarted.close()
```

### Spanner

The operator creates the five tables and the configuration row once, outside the library (see the [Spanner schema](docs/SPANNER_DATABASE_SCHEMA.md)). The caller owns the Spanner `DatabaseClient`; opening the storage and the Scala factories never close it. The table prefix and the retention policy must match the configuration row.

```scala
import com.github.j5ik2o.event.store.adapter.java.spanner.{SpannerRetentionPolicy, SpannerStorage, SpannerStorageConfig}
import com.google.cloud.spanner.DatabaseClient

def spannerSettings(prefix: String): SpannerStorageConfig =
  SpannerStorageConfig.builder().tablePrefix(prefix).retentionPolicy(SpannerRetentionPolicy.delete(2)).build()

// Every launch: the tables and the configuration row already exist.
def openSpanner(client: DatabaseClient, prefix: String): SpannerStorage =
  SpannerStorage.open(client, spannerSettings(prefix))

// client: DatabaseClient
val storage = openSpanner(client, "accounts")
val store = EventStore.ofSpanner(storage, AccountExample.config).get
store.persistEvent(AccountExample.event(id, 1L, "Alice")).get

// Restart: open the same tables again.
val restarted = openSpanner(client, "accounts")
val reopened = EventStore.ofSpanner(restarted, AccountExample.config).get
AccountExample.restore(reopened, id).get // Some("Alice")
val asyncReopened = EventStoreAsync.ofSpanner(restarted, AccountExample.config) // Future
```

### Bigtable

The operator creates the two tables and their column families outside the library (see the [Bigtable schema](docs/BIGTABLE_DATABASE_SCHEMA.md)). Call `initialize` once on the first launch to write the configuration rows; on every later launch only open. The caller owns and closes the data and admin clients.

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

// First launch only: writes the two configuration rows into the tables the operator created.
def initializeBigtable(data: BigtableDataClient, settings: BigtableStorageConfig): Unit =
  BigtableStorage.initialize(data, settings)

// Every launch: opens the initialized tables.
def openBigtable(data: BigtableDataClient, admin: BigtableTableAdminClient, settings: BigtableStorageConfig): BigtableStorage =
  BigtableStorage.open(data, admin, settings)

// data: BigtableDataClient, admin: BigtableTableAdminClient
val settings = bigtableSettings("accounts-journal", "accounts-head")
initializeBigtable(data, settings) // first launch only
val storage = openBigtable(data, admin, settings)
val store = EventStore.ofBigtable(storage, AccountExample.config).get
store.persistEvent(AccountExample.event(id, 1L, "Alice")).get

// Restart: open again; do not call initialize.
val restarted = openBigtable(data, admin, settings)
val reopened = EventStore.ofBigtable(restarted, AccountExample.config).get
AccountExample.restore(reopened, id).get // Some("Alice")
val asyncReopened = EventStoreAsync.ofBigtable(restarted, AccountExample.config) // Future
```

The SQLite, Spanner and Bigtable asynchronous factories complete on the calling thread, like Memory. The storage and clients stay open after a store is no longer used; the caller closes them.

## Serialization

Java's `core` identifiers, envelopes, typed `PayloadSerializer<T>`, `EventStoreConfig`, `RetentionPolicy` and five exception classes are reused. Bind event and aggregate-state serializers at creation; reads take no `Class`. Serializers process payloads only, separately from envelope metadata.

## Retention

`RetentionPolicy.none()` keeps the current snapshot only; `delete(n)` keeps the newest n history snapshots. DynamoDB also supports `ttl(n, graceSeconds)` with snapshot TTL enabled, and Spanner supports `SpannerRetentionPolicy.ttl(n, graceDays)`. Memory, SQLite and Bigtable reject TTL. Configure the policy on `MemoryStorage`, `DynamoDbTableConfig`, `SqliteStorageConfig`, `SpannerStorageConfig` or `BigtableStorageConfig`.

A retention failure after commit preserves write success, logs the failure and notifies `retentionFailureListener` with the original cause.

## Errors

Failures retain the Java exception type, `category()`, original cause and diagnostics: `OptimisticLockException`, `ContractViolationException`, `SerializationException`, `ConfigurationException`, `StorageException`. Contract violations expose `rule()` and `seqNr()`. Inspect failure values, not message text, to classify errors. Synchronous exceptions raised while invoking the asynchronous Java API become failed Scala futures.

Java owns persistence, locking, cleanup and retries. The Scala layer converts only results and future wrappers.

## Migration

See [MIGRATION_GUIDE](docs/MIGRATION_GUIDE.md) for the old-version read → application conversion → new-envelope rewrite procedure. Existing stored data is not automatically converted. SQLite, Spanner and Bigtable did not exist in the 1.x releases, so there is no data to migrate; the guide describes how to start with them.

## Conformance

```sh
sbt +compile +test +lint
python3 tools/conformance/manifest.py verify
```

Docker is required for the pinned DynamoDB Local 3.3.1, Cloud Spanner emulator and Cloud Bigtable emulator tests; the SQLite tests use a local file and need no Docker. [The conformance suite](src/test/scala/com/github/j5ik2o/event/store/adapter/scala/conformance/ScalaPublicConformanceSpec.scala) reuses the accepted comparison and fault machinery through Scala public factories and operations. Reports are saved in `target/reports/<scala-version>/conformance/`; they include per-rule results, manifest checks, fault applications and reasoned exclusions. Every applicable case runs against all five storages, through both the synchronous and the asynchronous public factories. Cases are excluded only for the same reasons as in the Java implementation (a scenario for another storage, millisecond precision, the DynamoDB layout and the SQLite change feed), never because a storage is not provided.

## License

MIT License. See [LICENSE](LICENSE).
