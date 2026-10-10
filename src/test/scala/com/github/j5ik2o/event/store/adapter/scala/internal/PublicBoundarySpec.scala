package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  AsyncEventStore => JavaAsyncEventStore,
  ConfigurationException,
  ContractViolationException,
  ErrorCategory,
  EventEnvelope,
  EventStore => JavaEventStore,
  EventStoreConfig,
  EventStoreException,
  JsonPayloadSerializer,
  OptimisticLockException,
  SerializationException,
  SnapshotEnvelope,
  SnapshotReadResult => JavaSnapshotReadResult,
  StorageException,
}
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{OptionValues, TryValues}

import java.time.Instant
import java.util.{ArrayList, List => JavaList, Optional, OptionalLong}
import java.util.concurrent.{CompletableFuture, CompletionException, ExecutionException}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

final class PublicBoundarySpec extends AnyFreeSpec with OptionValues with TryValues {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val id: AggregateId = AggregateId.of("Account", "boundary")
  private val config: EventStoreConfig[String, String] = EventStoreConfig
    .builder[String, String]()
    .payloadSerializer(JsonPayloadSerializer.of(classOf[String]))
    .snapshotSerializer(JsonPayloadSerializer.of(classOf[String]))
    .build()

  private def event(seqNr: Long, value: String): EventEnvelope[String] = EventEnvelope
    .builder[String]()
    .aggregateId(id)
    .seqNr(seqNr)
    .occurredAt(Instant.parse("2026-10-10T00:00:00.123456789Z"))
    .manifest("name/v1")
    .payload(value)
    .build()

  private def snapshot(seqNr: Long, value: String): SnapshotEnvelope[String] = SnapshotEnvelope
    .builder[String]()
    .seqNr(seqNr)
    .manifest("account/v1")
    .aggregate(value)
    .build()

  "shared MemoryStorage survives synchronous and asynchronous factory instances" in {
    val storage = MemoryStorage.create()
    val sync = EventStore.ofMemory(storage, config).success.value
    val async = Await.result(EventStoreAsync.ofMemory(storage, config), 3.seconds)
    assert(sync.getLatestSnapshotById(id).success.value.isEmpty)
    sync.persistEvent(event(1L, "Alice")).success.value
    val first = Await.result(async.getLatestSnapshotById(id), 3.seconds).value
    assert(first.snapshot.isEmpty && first.headSeqNr == 1L)
    Await.result(async.persistEventAndSnapshot(event(2L, "Bob"), snapshot(2L, "Bob")), 3.seconds)
    val second = sync.getLatestSnapshotById(id).success.value.value
    assert(second.snapshot.value.aggregate() == "Bob")
    assert(second.snapshot.value.manifest() == "account/v1")
    assert(second.headSeqNr == 2L)
    val events = sync.getEventsByIdSinceSeqNr(id, 2L).success.value
    assert(events.map(_.seqNr()) == Seq(2L))
    assert(events.head.payload() == "Bob" && events.head.manifest() == "name/v1")
    assert(events.head.occurredAt() == event(2L, "Bob").occurredAt())
    assert(Await.result(async.getEventsByIdSinceSeqNr(id, 1L), 3.seconds).map(_.seqNr()) == Seq(1L, 2L))
  }

  "omitted storage is isolated for every synchronous and asynchronous creation" in {
    val first = EventStore.ofMemory(config).success.value
    val second = EventStore.ofMemory(config).success.value
    val asyncFirst = Await.result(EventStoreAsync.ofMemory(config), 3.seconds)
    val asyncSecond = Await.result(EventStoreAsync.ofMemory(config), 3.seconds)
    first.persistEvent(event(1L, "sync")).success.value
    Await.result(asyncFirst.persistEvent(event(1L, "async")), 3.seconds)
    assert(second.getLatestSnapshotById(id).success.value.isEmpty)
    assert(Await.result(asyncSecond.getLatestSnapshotById(id), 3.seconds).isEmpty)
    assert(first.getEventsByIdSinceSeqNr(id, 1L).success.value.head.payload() == "sync")
    assert(Await.result(asyncFirst.getEventsByIdSinceSeqNr(id, 1L), 3.seconds).head.payload() == "async")
  }

  "invalid Memory factory settings are classified failure values" in {
    val missingConfig = Option.empty[EventStoreConfig[String, String]].orNull
    val missingStorage = Option.empty[MemoryStorage].orNull
    assert(EventStore.ofMemory(missingConfig).failure.exception.isInstanceOf[ConfigurationException])
    assert(EventStore.ofMemory(missingStorage, config).failure.exception.isInstanceOf[ConfigurationException])
    assert(failed(EventStoreAsync.ofMemory(missingConfig)).isInstanceOf[ConfigurationException])
    assert(failed(EventStoreAsync.ofMemory(missingStorage, config)).isInstanceOf[ConfigurationException])
  }

  "real Memory serialization preserves the original cause through both public paths" in {
    val cause = new IllegalArgumentException("domain encoding failed")
    val broken = EventStoreConfig
      .builder[String, String]()
      .payloadSerializer(new com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer[String] {
        override def serialize(value: String): Array[Byte] = throw cause
        override def deserialize(bytes: Array[Byte]): String = throw cause
      })
      .snapshotSerializer(JsonPayloadSerializer.of(classOf[String]))
      .build()
    val sync = EventStore.ofMemory(broken).success.value
    val async = Await.result(EventStoreAsync.ofMemory(broken), 3.seconds)
    val syncFailure = sync.persistEvent(event(1L, "Alice")).failure.exception
    val asyncFailure = failed(async.persistEvent(event(1L, "Alice")))
    Seq(syncFailure, asyncFailure).foreach { failure =>
      assert(failure.isInstanceOf[SerializationException])
      assert(failure.getCause eq cause)
    }
    assert(sync.getLatestSnapshotById(id).success.value.isEmpty)
    assert(Await.result(async.getLatestSnapshotById(id), 3.seconds).isEmpty)
  }

  "five Java error categories and diagnostics survive all operation boundaries" in {
    val cause = new IllegalStateException("original storage or serializer cause")
    val errors: Seq[EventStoreException] = Seq(
      new OptimisticLockException(id, 2L, OptionalLong.of(2L), cause),
      new ContractViolationException("W-8", OptionalLong.of(4L), "sequence gap"),
      new SerializationException("payload failure", cause),
      new ConfigurationException("configuration failure", cause),
      new StorageException("storage failure", cause),
    )
    assert(errors.map(_.category()).toSet == ErrorCategory.values().toSet)
    errors.foreach { original =>
      val sync = new JavaEventStoreAdapter(new FailingSyncStore(original))
      val synchronous = Seq(
        sync.persistEvent(event(1L, "Alice")),
        sync.persistEventAndSnapshot(event(1L, "Alice"), snapshot(1L, "Alice")),
        sync.getLatestSnapshotById(id),
        sync.getEventsByIdSinceSeqNr(id, 1L),
      )
      synchronous.foreach(result => assert(result.failed.get eq original))
      Seq(false, true).foreach { throwsBeforeFuture =>
        val async = new JavaAsyncEventStoreAdapter(new FailingAsyncStore(original, throwsBeforeFuture))
        val asynchronous = Seq(
          async.persistEvent(event(1L, "Alice")),
          async.persistEventAndSnapshot(event(1L, "Alice"), snapshot(1L, "Alice")),
          async.getLatestSnapshotById(id),
          async.getEventsByIdSinceSeqNr(id, 1L),
        )
        asynchronous.foreach(result => assert(failed(result) eq original))
      }
      assert(
        Option(original.getCause) == (if (original.isInstanceOf[ContractViolationException]) None else Some(cause)))
    }
    val violation = errors.collectFirst { case error: ContractViolationException => error }.value
    assert(violation.rule() == "W-8" && violation.seqNr().getAsLong == 4L)
    assert(violation.getMessage.contains("W-8") && violation.getMessage.contains("4"))
  }

  "snapshot and head sequence numbers are retained independently and event results are immutable" in {
    val mutableEvents = new ArrayList[EventEnvelope[String]]()
    mutableEvents.add(event(4L, "Carol"))
    val read = JavaSnapshotReadResult.of(snapshot(3L, "Bob"), 1L)
    val sync = new JavaEventStoreAdapter(new ReadSyncStore(read, mutableEvents))
    val async = new JavaAsyncEventStoreAdapter(new ReadAsyncStore(read, mutableEvents))
    val synchronous = sync.getLatestSnapshotById(id).success.value.value
    val asynchronous = Await.result(async.getLatestSnapshotById(id), 3.seconds).value
    Seq(synchronous, asynchronous).foreach { value =>
      assert(value.snapshot.value.seqNr() == 3L && value.headSeqNr == 1L)
    }
    val syncEvents = sync.getEventsByIdSinceSeqNr(id, 4L).success.value
    val asyncEvents = Await.result(async.getEventsByIdSinceSeqNr(id, 4L), 3.seconds)
    mutableEvents.add(event(5L, "Dave"))
    assert(syncEvents.map(_.seqNr()) == Seq(4L))
    assert(asyncEvents.map(_.seqNr()) == Seq(4L))
  }

  private def failed[A](future: Future[A]): Throwable = Await.ready(future, 3.seconds).value.value.failed.get

  private final class FailingSyncStore(failure: Throwable) extends JavaEventStore[String, String] {
    override def persistEvent(event: EventEnvelope[String]): Unit = throw failure
    override def persistEventAndSnapshot(event: EventEnvelope[String], snapshot: SnapshotEnvelope[String]): Unit =
      throw failure
    override def getLatestSnapshotById(id: AggregateId): Optional[JavaSnapshotReadResult[String]] = throw failure
    override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): JavaList[EventEnvelope[String]] = throw failure
  }

  private final class FailingAsyncStore(failure: Throwable, throwsBeforeFuture: Boolean)
    extends JavaAsyncEventStore[String, String] {
    private def fail[A](): CompletableFuture[A] =
      if (throwsBeforeFuture) throw new CompletionException(failure)
      else CompletableFuture.failedFuture(new CompletionException(new ExecutionException(failure)))
    override def persistEvent(event: EventEnvelope[String]): CompletableFuture[Void] = fail()
    override def persistEventAndSnapshot(
      event: EventEnvelope[String],
      snapshot: SnapshotEnvelope[String],
    ): CompletableFuture[Void] = fail()
    override def getLatestSnapshotById(id: AggregateId): CompletableFuture[Optional[JavaSnapshotReadResult[String]]] =
      fail()
    override def getEventsByIdSinceSeqNr(
      id: AggregateId,
      start: Long): CompletableFuture[JavaList[EventEnvelope[String]]] =
      fail()
  }

  private final class ReadSyncStore(
    read: JavaSnapshotReadResult[String],
    events: JavaList[EventEnvelope[String]],
  ) extends JavaEventStore[String, String] {
    override def persistEvent(event: EventEnvelope[String]): Unit = throw new UnsupportedOperationException()
    override def persistEventAndSnapshot(event: EventEnvelope[String], snapshot: SnapshotEnvelope[String]): Unit =
      throw new UnsupportedOperationException()
    override def getLatestSnapshotById(id: AggregateId): Optional[JavaSnapshotReadResult[String]] = Optional.of(read)
    override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): JavaList[EventEnvelope[String]] = events
  }

  private final class ReadAsyncStore(
    read: JavaSnapshotReadResult[String],
    events: JavaList[EventEnvelope[String]],
  ) extends JavaAsyncEventStore[String, String] {
    override def persistEvent(event: EventEnvelope[String]): CompletableFuture[Void] =
      throw new UnsupportedOperationException()
    override def persistEventAndSnapshot(
      event: EventEnvelope[String],
      snapshot: SnapshotEnvelope[String],
    ): CompletableFuture[Void] = throw new UnsupportedOperationException()
    override def getLatestSnapshotById(id: AggregateId): CompletableFuture[Optional[JavaSnapshotReadResult[String]]] =
      CompletableFuture.completedFuture(Optional.of(read))
    override def getEventsByIdSinceSeqNr(
      id: AggregateId,
      start: Long): CompletableFuture[JavaList[EventEnvelope[String]]] =
      CompletableFuture.completedFuture(events)
  }
}
