package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.{ Aggregate, AggregateId, Event }
import com.github.j5ik2o.event.store.adapter.scala.internal.EventStoreForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.util.Try

object EventStore {
  def ofDynamoDB[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]](
      dynamoDbClient: DynamoDbClient,
      journalTableName: String,
      snapshotTableName: String,
      journalAidIndexName: String,
      snapshotAidIndexName: String,
      shardCount: Long
  ): EventStore[AID, A, E] = {
    EventStoreForDynamoDB.create(
      dynamoDbClient,
      journalTableName,
      snapshotTableName,
      journalAidIndexName,
      snapshotAidIndexName,
      shardCount
    )
  }
}

trait EventStore[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]] extends EventStoreOptions[AID, A, E] {
  override type This = EventStore[AID, A, E]

  def getLatestSnapshotById(clazz: Class[A], id: AID): Try[Option[A]]

  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long): Try[Seq[E]]

  def persistEvent(event: E, version: Long): Try[Unit]

  def persistEventAndSnapshot(event: E, snapshot: A): Try[Unit]
}
