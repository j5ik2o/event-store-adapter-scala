package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.{
  Aggregate,
  AggregateId,
  Event,
  EventSerializer,
  KeyResolver,
  SnapshotSerializer,
}
import com.github.j5ik2o.event.store.adapter.scala.EventStore
import com.github.j5ik2o.event.store.adapter.java.internal.{EventStoreForDynamoDB => JavaEventStoreForDynamoDB}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._
import scala.jdk.OptionConverters._
import scala.util.Try

private[scala] object EventStoreForDynamoDB {

  def create[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]](
    javaEventStore: JavaEventStoreForDynamoDB[AID, A, E],
  ): EventStoreForDynamoDB[AID, A, E] = new EventStoreForDynamoDB(javaEventStore)

  def create[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]](
    dynamoDbClient: DynamoDbClient,
    journalTableName: String,
    snapshotTableName: String,
    journalAidIndexName: String,
    snapshotAidIndexName: String,
    shardCount: Long,
  ): EventStoreForDynamoDB[AID, A, E] =
    create(
      JavaEventStoreForDynamoDB.create[AID, A, E](
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
      ),
    )
}

final class EventStoreForDynamoDB[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]] private (
  underlying: JavaEventStoreForDynamoDB[AID, A, E],
) extends EventStore[AID, A, E] {

  override def withKeepSnapshotCount(keepSnapshotCount: Int): EventStoreForDynamoDB[AID, A, E] = {
    val updated = underlying.withKeepSnapshotCount(keepSnapshotCount)
    EventStoreForDynamoDB.create(updated)
  }

  override def withDeleteTtl(deleteTtl: FiniteDuration): EventStoreForDynamoDB[AID, A, E] = {
    val updated = underlying.withDeleteTtl(deleteTtl.toJava)
    EventStoreForDynamoDB.create(updated)
  }

  override def withKeyResolver(keyResolver: KeyResolver[AID]): EventStoreForDynamoDB[AID, A, E] = {
    val updated = underlying.withKeyResolver(keyResolver)
    EventStoreForDynamoDB.create(updated)
  }

  override def withEventSerializer(eventSerializer: EventSerializer[AID, E]): EventStoreForDynamoDB[AID, A, E] = {
    val updated = underlying.withEventSerializer(eventSerializer)
    EventStoreForDynamoDB.create(updated)
  }

  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer[AID, A]): EventStoreForDynamoDB[AID, A, E] = {
    val updated = underlying.withSnapshotSerializer(snapshotSerializer)
    EventStoreForDynamoDB.create(updated)
  }

  override def getLatestSnapshotById(clazz: Class[A], id: AID): Try[Option[A]] = Try {
    underlying
      .getLatestSnapshotById(clazz, id)
      .toScala
  }

  override def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long): Try[Seq[E]] = Try {
    underlying.getEventsByIdSinceSequenceNumber(clazz, id, sequenceNumber).asScala.toSeq
  }

  override def persistEvent(event: E, version: Long): Try[Unit] = Try {
    underlying.persistEvent(event, version)
  }

  override def persistEventAndSnapshot(event: E, snapshot: A): Try[Unit] = Try {
    underlying.persistEventAndSnapshot(event, snapshot)
  }

}
