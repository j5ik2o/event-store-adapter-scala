package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.{
  Aggregate,
  AggregateId,
  Event,
  EventSerializer,
  EventStoreAsync => JavaEventStoreAsync,
  KeyResolver,
  SnapshotSerializer
}
import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.DurationConverters._
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters._
import scala.jdk.CollectionConverters._

private[scala] object EventStoreAsyncForDynamoDB {
  def create[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
      underlying: JavaEventStoreAsync[AID, A, E]
  ): EventStoreAsyncForDynamoDB[AID, A, E] = new EventStoreAsyncForDynamoDB(underlying)

  def create[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
      dynamoDbAsyncClient: DynamoDbAsyncClient,
      journalTableName: String,
      snapshotTableName: String,
      journalAidIndexName: String,
      snapshotAidIndexName: String,
      shardCount: Long
  ): EventStoreAsyncForDynamoDB[AID, A, E] = {
    create(
      JavaEventStoreAsync.ofDynamoDB[AID, A, E](
        dynamoDbAsyncClient,
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
    underlying: JavaEventStoreAsync[AID, A, E]
) extends EventStoreAsync[AID, A, E] {

  override def withKeepSnapshotCount(keepSnapshotCount: Int): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withKeepSnapshotCount(keepSnapshotCount)
    EventStoreAsyncForDynamoDB.create(updated)
  }

  override def withDeleteTtl(deleteTtl: FiniteDuration): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withDeleteTtl(deleteTtl.toJava)
    EventStoreAsyncForDynamoDB.create(updated)
  }

  override def withKeyResolver(keyResolver: KeyResolver[AID]): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withKeyResolver(keyResolver)
    EventStoreAsyncForDynamoDB.create(updated)
  }

  override def withEventSerializer(eventSerializer: EventSerializer[AID, E]): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withEventSerializer(eventSerializer)
    EventStoreAsyncForDynamoDB.create(updated)
  }

  override def withSnapshotSerializer(
      snapshotSerializer: SnapshotSerializer[AID, A]
  ): EventStoreAsyncForDynamoDB[AID, A, E] = {
    val updated = underlying.withSnapshotSerializer(snapshotSerializer)
    EventStoreAsyncForDynamoDB.create(updated)
  }

  override def getLatestSnapshotById(clazz: Class[A], id: AID)(implicit
      ec: ExecutionContext
  ): Future[Option[(A, Long)]] = {
    underlying.getLatestSnapshotById(clazz, id).asScala.map(_.toScala).map {
      case Some(result) => Some((result.getAggregate, result.getVersion))
      case None         => None
    }
  }

  override def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long)(implicit
      ec: ExecutionContext
  ): Future[Seq[E]] = {
    underlying.getEventsByIdSinceSequenceNumber(clazz, id, sequenceNumber).asScala.map(_.asScala.toSeq)
  }

  override def persistEvent(event: E, version: Long)(implicit ec: ExecutionContext): Future[Unit] = {
    underlying.persistEvent(event, version).asScala.map(_ => ())
  }

  override def persistEventAndSnapshot(event: E, snapshot: A)(implicit ec: ExecutionContext): Future[Unit] = {
    underlying.persistEventAndSnapshot(event, snapshot).asScala.map(_ => ())
  }
}
