package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.{ Aggregate, AggregateId, Event }
import com.github.j5ik2o.event.store.adapter.scala.internal.EventStoreAsyncForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.{ ExecutionContext, Future }

object EventStoreAsync {

  def ofDynamoDB[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]](
      dynamoDbAsyncClient: DynamoDbAsyncClient,
      journalTableName: String,
      snapshotTableName: String,
      journalAidIndexName: String,
      snapshotAidIndexName: String,
      shardCount: Long
  ): EventStoreAsync[AID, A, E] = {
    EventStoreAsyncForDynamoDB.create(
      dynamoDbAsyncClient,
      journalTableName,
      snapshotTableName,
      journalAidIndexName,
      snapshotAidIndexName,
      shardCount
    )
  }

}

trait EventStoreAsync[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]]
    extends EventStoreOptions[AID, A, E] {
  override type This = EventStoreAsync[AID, A, E]

  def getLatestSnapshotById(clazz: Class[A], id: AID)(implicit ec: ExecutionContext): Future[Option[A]]

  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long)(implicit
      ec: ExecutionContext
  ): Future[Seq[E]]

  def persistEvent(event: E, version: Long)(implicit ec: ExecutionContext): Future[Unit]

  def persistEventAndSnapshot(event: E, snapshot: A)(implicit ec: ExecutionContext): Future[Unit]

}
