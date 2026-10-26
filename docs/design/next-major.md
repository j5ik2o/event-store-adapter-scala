<a id="新java公開形へのscala対応"></a>
# Scala support for the new Java public shape

> This document is a record of the design used when v5 was built. For current usage, check the [README](../../README.md) and the current code.

This is the support record that carries over the saved plan. It adds no new contract numbers or acceptance conditions. The Java dependency is `io.github.j5ik2o:event-store-adapter-java:2.0.0-SNAPSHOT`. The Scala version and the sbt-dynver settings are kept.

It reuses Java's `core` identifiers, envelopes, typed serializers, configuration, retention policy and five-category exceptions, and the storage implementations of `memory` and `dynamodb`. The only Scala-specific type is `SnapshotReadResult[A](snapshot: Option[SnapshotEnvelope[A]], headSeqNr: Long)`.

| Entry point | Synchronous | Asynchronous |
|---|---|---|
| `ofMemory(config)`, `ofMemory(storage, config)` | `Try[EventStore[P,A]]` | `Future[EventStoreAsync[P,A]]` |
| `ofDynamoDB(client, tables, config)` | Same as above | Same as above |
| `persistEvent(event)` | `Try[Unit]` | `Future[Unit]` |
| `persistEventAndSnapshot(event, snapshot)` | `Try[Unit]` | `Future[Unit]` |
| `getLatestSnapshotById(id)` | `Try[Option[SnapshotReadResult[A]]]` | Corresponding `Future` |
| `getEventsByIdSinceSeqNr(id, start)` | `Try[Seq[EventEnvelope[P]]]` | Corresponding `Future` |

The synchronous side evaluates Java calls inside `Try`. Asynchronous DynamoDB creation and operations are connected to Java's completion, and synchronous exceptions raised at call time are also converted into failed Futures. Only the completion-exception wrapper is removed; already-classified exceptions are not rebuilt. The original cause, rule number and numbered diagnostics are retained. The event sequence is copied into an immutable Scala sequence.

The outer `None` means the head does not exist; the inner `None` means an existing aggregate has no snapshot. Restoration starts at the snapshot number + 1, or at 1 if there is none. The head is neither the start number nor the upper bound, and Java's non-atomic read result is not corrected.

Only creations given the same `MemoryStorage` share state. Creations that omit it are independent each time. The caller owns the DynamoDB client and the three created tables. Persistence, locking, retention, paging and retries are Java's responsibility. No storage implementation is added to Scala.

The old trait constraints, version-expectation arguments, per-read `Class`, old options, key resolvers and shards, and old dedicated wrappers are replaced together with their current callers. No compatibility read is added; a [manual migration guide](../../MIGRATION_GUIDE.md) is provided for old data.

The conformance tests reuse the saved Java-supported comparison, fault injection and independent observation, and connect creation, the four operations, timestamp round trips and appends during a read to the Scala public entry points. Execution results are saved separately for each Scala version, so value-construction checks can be told apart from public operations on the storage in the records. The final check of main, the formal review and the merge are later steps for the coordinator.
