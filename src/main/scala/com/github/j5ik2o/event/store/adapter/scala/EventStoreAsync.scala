package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.{ Aggregate, AggregateId, Event }
import com.github.j5ik2o.event.store.adapter.scala.internal.EventStoreAsyncForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.{ ExecutionContext, Future }

/** Represents a companion object of [[EventStore]]. / [[EventStore]]のコンパニオンオブジェクトを表します。
  */
object EventStoreAsync {

  /** Creates an instance of [[EventStore]] for DynamoDB. / DynamoDB用の[[EventStore]]のインスタンスを作成します。
    *
    * @param dynamoDbAsyncClient
    *   [[DynamoDbAsyncClient]]
    * @param journalTableName
    *   journal table name / ジャーナルテーブル名
    * @param snapshotTableName
    *   snapshot table name / スナップショットテーブル名
    * @param journalAidIndexName
    *   journal aggregate id index name / ジャーナル集約IDインデックス名
    * @param snapshotAidIndexName
    *   snapshot aggregate id index name / スナップショット集約IDインデックス名
    * @param shardCount
    *   shard count / シャード数
    * @tparam AID
    *   aggregate id type / 集約IDの型
    * @tparam A
    *   aggregate type / 集約の型
    * @tparam E
    *   event type / イベントの型
    * @return
    *   [[EventStoreAsync]]
    */
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

/** Asynchronous version of [[EventStore]]. / [[EventStore]]の非同期版。
  *
  * @tparam AID
  *   [[AggregateId]] type / 集約IDの型
  * @tparam A
  *   [[Aggregate]] type / 集約の型
  * @tparam E
  *   [[Event]] type / イベントの型
  */
trait EventStoreAsync[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]]
    extends EventStoreOptions[AID, A, E] {
  override type This = EventStoreAsync[AID, A, E]

  /** Gets the latest snapshot by the aggregate id. / 集約IDによる最新のスナップショットを取得します。
    *
    * @param clazz
    *   class of Aggregate A to be deserialized / デシリアライズ対象の集約Aのクラス
    * @param id
    *   id of Aggregate A / 集約AのID
    * @return
    *   [[Aggregate]] wrapped by [[Future]] / [[Future]]でラップされた[[Aggregate]]
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException
    *   if an error occurred during reading from the event store
    * @throws com.github.j5ik2o.event.store.adapter.java.DeserializationException
    *   if an error occurred during deserialization / デシリアライズ中にエラーが発生した場合
    */
  def getLatestSnapshotById(clazz: Class[A], id: AID)(implicit ec: ExecutionContext): Future[Option[A]]

  /** Gets the events by the aggregate id and since the sequence number. / 集約IDとシーケンス番号以降のイベントを取得します。
    *
    * @param clazz
    *   class of Event E to be deserialized / デシリアライズ対象のイベントEのクラス
    * @param id
    *   id of Aggregate A / 集約AのID
    * @param sequenceNumber
    *   sequence number / シーケンス番号
    * @return
    *   [[Event]] Seq wrapped by [[Future]] / [[Future]]でラップされた[[Event]]のSeq
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException
    *   if an error occurred during reading from the event store
    * @throws com.github.j5ik2o.event.store.adapter.java.DeserializationException
    *   if an error occurred during deserialization / デシリアライズ中にエラーが発生した場合
    */
  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long)(implicit
      ec: ExecutionContext
  ): Future[Seq[E]]

  /** Persists an event only. / イベントのみを永続化します。
    *
    * @param event
    *   [[Event]] / イベント
    * @param version
    *   version / バージョン
    * @return
    *   [[Future]] without result / 結果を持たない[[Future]]
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException
    *   if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
    *   if an error occurred during serialization / シリアライズ中にエラーが発生した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException
    *   if an error occurred during transaction / トランザクション中にエラーが発生した場合
    */
  def persistEvent(event: E, version: Long)(implicit ec: ExecutionContext): Future[Unit]

  /** Persists an event and a snapshot. / イベントとスナップショットを永続化します。
    *
    * @param event
    *   [[Event]] / イベント
    * @param snapshot
    *   [[Aggregate]] / スナップショット
    * @return
    *   [[Future]] without result / 結果を持たない[[Future]]
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException
    *   if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
    *   if an error occurred during serialization / シリアライズ中にエラーが発生した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException
    *   if an error occurred during transaction / トランザクション中にエラーが発生した場合
    */
  def persistEventAndSnapshot(event: E, snapshot: A)(implicit ec: ExecutionContext): Future[Unit]

}
