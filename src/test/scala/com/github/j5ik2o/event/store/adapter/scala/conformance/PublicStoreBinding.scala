package com.github.j5ik2o.event.store.adapter.scala.conformance

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  AsyncEventStore => JavaAsyncEventStore,
  EventEnvelope,
  EventStore => JavaEventStore,
  EventStoreConfig,
  SnapshotEnvelope,
  SnapshotReadResult => JavaSnapshotReadResult,
}
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync, SnapshotReadResult}
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbClient}

import java.util.{List => JavaList, Optional}
import java.util.concurrent.CompletableFuture
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters._

/**
 * Test-only return conversion for the accepted Java comparison and fault machinery. /
 * 受入済みJava比較器・障害支援へScala公開結果を渡す、試験専用の接続。
 */
object PublicStoreBinding {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  def memory[P, A](
    storage: MemoryStorage,
    config: EventStoreConfig[P, A],
    asynchronous: Boolean): JavaEventStore[P, A] =
    if (asynchronous) {
      val store = Await.result(EventStoreAsync.ofMemory(storage, config), 30.seconds)
      new AwaitingFacade(store)
    } else new SyncFacade(EventStore.ofMemory(storage, config).get)

  def dynamo[P, A](
    client: DynamoDbClient,
    tables: DynamoDbTableConfig,
    config: EventStoreConfig[P, A],
  ): JavaEventStore[P, A] = new SyncFacade(EventStore.ofDynamoDB(client, tables, config).get)

  def dynamoAsync[P, A](
    client: DynamoDbAsyncClient,
    tables: DynamoDbTableConfig,
    config: EventStoreConfig[P, A],
  ): CompletableFuture[JavaAsyncEventStore[P, A]] =
    completion(EventStoreAsync.ofDynamoDB(client, tables, config).map(store => new AsyncFacade(store)))

  private def snapshot[A](result: Option[SnapshotReadResult[A]]): Optional[JavaSnapshotReadResult[A]] =
    result
      .map(read =>
        read.snapshot
          .map(JavaSnapshotReadResult.of(_, read.headSeqNr))
          .getOrElse(JavaSnapshotReadResult.withoutSnapshot[A](read.headSeqNr)))
      .toJava

  private def completion[A](future: Future[A]): CompletableFuture[A] = future.asJava.toCompletableFuture

  private final class SyncFacade[P, A](store: EventStore[P, A]) extends JavaEventStore[P, A] {
    override def persistEvent(event: EventEnvelope[P]): Unit = store.persistEvent(event).get

    override def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A]): Unit =
      store.persistEventAndSnapshot(event, snapshot).get

    override def getLatestSnapshotById(id: AggregateId): Optional[JavaSnapshotReadResult[A]] =
      snapshot(store.getLatestSnapshotById(id).get)

    override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): JavaList[EventEnvelope[P]] =
      store.getEventsByIdSinceSeqNr(id, start).get.asJava
  }

  private final class AsyncFacade[P, A](store: EventStoreAsync[P, A]) extends JavaAsyncEventStore[P, A] {
    override def persistEvent(event: EventEnvelope[P]): CompletableFuture[Void] =
      completion(store.persistEvent(event).map(_ => Option.empty[Void].orNull))

    override def persistEventAndSnapshot(
      event: EventEnvelope[P],
      snapshot: SnapshotEnvelope[A]): CompletableFuture[Void] =
      completion(store.persistEventAndSnapshot(event, snapshot).map(_ => Option.empty[Void].orNull))

    override def getLatestSnapshotById(id: AggregateId): CompletableFuture[Optional[JavaSnapshotReadResult[A]]] =
      completion(store.getLatestSnapshotById(id).map(snapshot(_)))

    override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): CompletableFuture[JavaList[EventEnvelope[P]]] =
      completion(store.getEventsByIdSinceSeqNr(id, start).map(_.asJava))
  }

  private final class AwaitingFacade[P, A](store: EventStoreAsync[P, A]) extends JavaEventStore[P, A] {
    override def persistEvent(event: EventEnvelope[P]): Unit = Await.result(store.persistEvent(event), 30.seconds)

    override def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A]): Unit =
      Await.result(store.persistEventAndSnapshot(event, snapshot), 30.seconds)

    override def getLatestSnapshotById(id: AggregateId): Optional[JavaSnapshotReadResult[A]] =
      snapshot(Await.result(store.getLatestSnapshotById(id), 30.seconds))

    override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): JavaList[EventEnvelope[P]] =
      Await.result(store.getEventsByIdSinceSeqNr(id, start), 30.seconds).asJava
  }
}
