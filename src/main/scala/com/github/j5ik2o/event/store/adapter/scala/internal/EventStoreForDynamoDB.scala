package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.scala.EventStore
import com.github.j5ik2o.event_store_adatpter_java.{
  Aggregate,
  AggregateId,
  Event,
  EventSerializer,
  KeyResolver,
  SnapshotSerializer
}
import com.github.j5ik2o.event_store_adatpter_java.internal.{ EventStoreForDynamoDB => JavaEventStoreForDynamoDB }
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try
import scala.jdk.DurationConverters._

object EventStoreForDynamoDB {

  def apply[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
      javaEventStore: JavaEventStoreForDynamoDB[AID, A, E]
  ): EventStoreForDynamoDB[AID, A, E] = new EventStoreForDynamoDB(javaEventStore)

  def apply[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
      dynamoDbClient: DynamoDbClient,
      journalTableName: String,
      snapshotTableName: String,
      journalAidIndexName: String,
      snapshotAidIndexName: String,
      shardCount: Long
  ): EventStoreForDynamoDB[AID, A, E] = {
    apply(
      JavaEventStoreForDynamoDB.create[AID, A, E](
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

final class EventStoreForDynamoDB[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
    javaEventStore: JavaEventStoreForDynamoDB[AID, A, E]
) extends EventStore[AID, A, E] {

  def withKeepSnapshotCount(keepSnapshotCount: Int): EventStoreForDynamoDB[AID, A, E] = {
    val updated = javaEventStore.withKeepSnapshotCount(keepSnapshotCount)
    EventStoreForDynamoDB(updated)
  }

  def withDeleteTtl(deleteTtl: FiniteDuration): EventStoreForDynamoDB[AID, A, E] = {
    val updated = javaEventStore.withDeleteTtl(deleteTtl.toJava)
    EventStoreForDynamoDB(updated)
  }

  def withKeyResolver(keyResolver: KeyResolver[AID]): EventStoreForDynamoDB[AID, A, E] = {
    val updated = javaEventStore.withKeyResolver(keyResolver)
    EventStoreForDynamoDB(updated)
  }

  def withEventSerializer(eventSerializer: EventSerializer[AID, E]): EventStoreForDynamoDB[AID, A, E] = {
    val updated = javaEventStore.withEventSerializer(eventSerializer)
    EventStoreForDynamoDB(updated)
  }

  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer[AID, A]): EventStoreForDynamoDB[AID, A, E] = {
    val updated = javaEventStore.withSnapshotSerializer(snapshotSerializer)
    EventStoreForDynamoDB(updated)
  }

  override def getLatestSnapshotById(clazz: Class[A], id: AID): Try[Option[(A, Long)]] = Try {
    javaEventStore
      .getLatestSnapshotById(clazz, id).map { result =>
        (result.getAggregate, result.getVersion)
      }.toScala
  }

  override def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long): Try[Seq[E]] = Try {
    javaEventStore.getEventsByIdSinceSequenceNumber(clazz, id, sequenceNumber).asScala.toSeq
  }

  override def persistEvent(event: E, version: Long): Try[Unit] = Try {
    javaEventStore.persistEvent(event, version)
  }

  override def persistEventAndSnapshot(event: E, snapshot: A): Try[Unit] = Try {
    javaEventStore.persistEventAndSnapshot(event, snapshot)
  }

}
