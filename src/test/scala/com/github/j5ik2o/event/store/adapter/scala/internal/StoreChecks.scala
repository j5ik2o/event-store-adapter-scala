package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  EventEnvelope,
  EventStoreConfig,
  JsonPayloadSerializer,
  OptimisticLockException,
  RetentionFailureListener,
  SnapshotEnvelope,
}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

/**
 * Shared acceptance checks for the SQLite, Spanner and Bigtable factories, run through the Scala public API only. /
 * SQLite・Spanner・Bigtableの生成入口を、Scalaの公開APIだけで確かめる共通の検査。
 */
object StoreChecks {
  val aggregateId: AggregateId = AggregateId.of("Order", "1")

  def config: EventStoreConfig[String, String] = configWith(None)

  def configWith(listener: Option[RetentionFailureListener]): EventStoreConfig[String, String] = {
    val builder = EventStoreConfig
      .builder[String, String]()
      .payloadSerializer(JsonPayloadSerializer.of(classOf[String]))
      .snapshotSerializer(JsonPayloadSerializer.of(classOf[String]))
    listener.fold(builder)(builder.retentionFailureListener).build()
  }

  def event(seqNr: Long): EventEnvelope[String] =
    EventEnvelope
      .builder[String]()
      .aggregateId(aggregateId)
      .seqNr(seqNr)
      .occurredAt(Instant.EPOCH)
      .manifest("event")
      .payload("event-" + seqNr)
      .build()

  def snapshot(seqNr: Long): SnapshotEnvelope[String] =
    SnapshotEnvelope.builder[String]().seqNr(seqNr).manifest("snapshot").aggregate("snapshot-" + seqNr).build()

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val timeout: FiniteDuration = 60.seconds

  /**
   * Creation, append, snapshot and event reads, duplicate append, and reopening, through the synchronous wrapper. /
   * 生成・追記・スナップショットとイベントの読み取り・二重の追記・再オープンを、同期の包みで確かめる。
   */
  def verifySync(create: () => Try[EventStore[String, String]], reopen: () => Try[EventStore[String, String]]): Unit = {
    val store = create().get
    assert(store.getLatestSnapshotById(aggregateId).get.isEmpty, "a new aggregate has no head")
    store.persistEvent(event(1)).get
    val afterEvent = store.getLatestSnapshotById(aggregateId).get.get
    assert(afterEvent.snapshot.isEmpty && afterEvent.headSeqNr == 1L)
    store.persistEventAndSnapshot(event(2), snapshot(2)).get
    assertState(store.getLatestSnapshotById(aggregateId).get, store.getEventsByIdSinceSeqNr(aggregateId, 1L).get.toList)
    assert(store.getEventsByIdSinceSeqNr(aggregateId, 2L).get.map(_.seqNr()) == Seq(2L))
    assert(store.persistEvent(event(2)).failed.get.isInstanceOf[OptimisticLockException])
    assert(store.persistEventAndSnapshot(event(2), snapshot(2)).failed.get.isInstanceOf[OptimisticLockException])
    assert(store.getEventsByIdSinceSeqNr(aggregateId, 1L).get.map(_.seqNr()) == Seq(1L, 2L))

    val reopened = reopen().get
    assertState(
      reopened.getLatestSnapshotById(aggregateId).get,
      reopened.getEventsByIdSinceSeqNr(aggregateId, 1L).get.toList)
    reopened.persistEvent(event(3)).get
    assert(reopened.getLatestSnapshotById(aggregateId).get.get.headSeqNr == 3L)
    assert(reopened.persistEvent(event(3)).failed.get.isInstanceOf[OptimisticLockException])
  }

  /**
   * The same scenario through the asynchronous wrapper. / 同じ場面を、非同期の包みで確かめる。
   */
  def verifyAsync(
    create: () => Future[EventStoreAsync[String, String]],
    reopen: () => Future[EventStoreAsync[String, String]]): Unit = {
    def await[A](future: Future[A]): A = Await.result(future, timeout)
    def failure(future: Future[Unit]): Throwable = Await.ready(future, timeout).value.get.failed.get

    val store = await(create())
    assert(await(store.getLatestSnapshotById(aggregateId)).isEmpty, "a new aggregate has no head")
    await(store.persistEvent(event(1)))
    val afterEvent = await(store.getLatestSnapshotById(aggregateId)).get
    assert(afterEvent.snapshot.isEmpty && afterEvent.headSeqNr == 1L)
    await(store.persistEventAndSnapshot(event(2), snapshot(2)))
    assertState(
      await(store.getLatestSnapshotById(aggregateId)),
      await(store.getEventsByIdSinceSeqNr(aggregateId, 1L)).toList)
    assert(failure(store.persistEvent(event(2))).isInstanceOf[OptimisticLockException])
    assert(failure(store.persistEventAndSnapshot(event(2), snapshot(2))).isInstanceOf[OptimisticLockException])
    assert(await(store.getEventsByIdSinceSeqNr(aggregateId, 1L)).map(_.seqNr()) == Seq(1L, 2L))

    val reopened = await(reopen())
    assertState(
      await(reopened.getLatestSnapshotById(aggregateId)),
      await(reopened.getEventsByIdSinceSeqNr(aggregateId, 1L)).toList)
    await(reopened.persistEvent(event(3)))
    assert(await(reopened.getLatestSnapshotById(aggregateId)).get.headSeqNr == 3L)
    assert(failure(reopened.persistEvent(event(3))).isInstanceOf[OptimisticLockException])
  }

  private def assertState(
    head: Option[com.github.j5ik2o.event.store.adapter.scala.SnapshotReadResult[String]],
    events: List[EventEnvelope[String]]): Unit = {
    val read = head.get
    assert(read.headSeqNr == 2L)
    assert(read.snapshot.map(_.seqNr()).contains(2L))
    assert(read.snapshot.map(_.aggregate()).contains("snapshot-2"))
    assert(events.map(_.seqNr()) == List(1L, 2L))
    assert(events.map(_.payload()) == List("event-1", "event-2"))
    assert(events.forall(_.occurredAt() == Instant.EPOCH))
  }
}
