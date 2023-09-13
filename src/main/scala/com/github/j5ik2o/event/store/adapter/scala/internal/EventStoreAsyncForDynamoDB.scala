package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.{
  Aggregate,
  AggregateId,
  Event,
  EventSerializer,
  KeyResolver,
  SnapshotSerializer
}
import com.github.j5ik2o.event.store.adapter.scala.{ EventStore, EventStoreAsync }
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

object EventStoreAsyncForDynamoDB {
  def apply[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
      underlying: EventStore[AID, A, E]
  ): EventStoreAsyncForDynamoDB[AID, A, E] = new EventStoreAsyncForDynamoDB(underlying)

  def apply[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
      dynamoDbClient: DynamoDbClient,
      journalTableName: String,
      snapshotTableName: String,
      journalAidIndexName: String,
      snapshotAidIndexName: String,
      shardCount: Long
  ): EventStoreAsyncForDynamoDB[AID, A, E] = {
    apply(
      EventStore.ofDynamoDB[AID, A, E](
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount
      )
    )
  }
}

final class EventStoreAsyncForDynamoDB[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]] private (
    underlying: EventStore[AID, A, E]
) extends EventStoreAsync[AID, A, E] {

  override def withKeepSnapshotCount(keepSnapshotCount: Int): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withKeepSnapshotCount(keepSnapshotCount)
    EventStoreAsyncForDynamoDB(updated)
  }

  override def withDeleteTtl(deleteTtl: FiniteDuration): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withDeleteTtl(deleteTtl)
    EventStoreAsyncForDynamoDB(updated)
  }

  override def withKeyResolver(keyResolver: KeyResolver[AID]): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withKeyResolver(keyResolver)
    EventStoreAsyncForDynamoDB(updated)
  }

  override def withEventSerializer(eventSerializer: EventSerializer[AID, E]): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withEventSerializer(eventSerializer)
    EventStoreAsyncForDynamoDB(updated)
  }

  override def withSnapshotSerializer(
      snapshotSerializer: SnapshotSerializer[AID, A]
  ): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withSnapshotSerializer(snapshotSerializer)
    EventStoreAsyncForDynamoDB(updated)
  }

  override def getLatestSnapshotById(clazz: Class[A], id: AID)(implicit
      ec: ExecutionContext
  ): Future[Option[(A, Long)]] = Future {
    underlying.getLatestSnapshotById(clazz, id).get
  }

  override def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long)(implicit
      ec: ExecutionContext
  ): Future[Seq[E]] = Future {
    underlying.getEventsByIdSinceSequenceNumber(clazz, id, sequenceNumber).get
  }

  override def persistEvent(event: E, version: Long)(implicit ec: ExecutionContext): Future[Unit] = Future {
    underlying.persistEvent(event, version).get
  }

  override def persistEventAndSnapshot(event: E, snapshot: A)(implicit ec: ExecutionContext): Future[Unit] = Future {
    underlying.persistEventAndSnapshot(event, snapshot).get
  }
}
