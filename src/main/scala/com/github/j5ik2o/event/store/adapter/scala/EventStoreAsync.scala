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

/** Represents a trait to store events asynchronously. / イベントを非同期に保存するためのトレイトを表します。
  *
  * @tparam AID
  *   Aggregate ID type / 集約IDの型
  * @tparam A
  *   Aggregate type / 集約の型
  * @tparam E
  *   Event type / イベントの型
  */
trait EventStoreAsync[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]]
    extends EventStoreOptions[AID, A, E] {
  override type This = EventStoreAsync[AID, A, E]

  /** Gets the latest snapshot by id. / IDによる最新のスナップショットを取得します。
    *
    * @param clazz
    *   class of Aggregate A to be serialized / シリアライズ対象Aのクラス
    * @param id
    *   id of Aggregate A / 集約AのID
    * @return
    *   `Future[Option[A]]`
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException
    *   if an error occurred during reading from the event store
    * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
    *   serialization failed
    */
  def getLatestSnapshotById(clazz: Class[A], id: AID)(implicit ec: ExecutionContext): Future[Option[A]]

  /** Gets the events by id and since the sequence number. / IDとシーケンス番号以降のイベントを取得します。
    *
    * @param clazz
    *   class of Event E to be serialized / シリアライズ対象Eのクラス
    * @param id
    *   id of Aggregate A / 集約AのID
    * @param sequenceNumber
    *   sequence number / シーケンス番号
    * @return
    *   `Future[Seq[E]]`
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException
    *   if an error occurred during reading from the event store
    * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
    *   serialization failed
    */
  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long)(implicit
      ec: ExecutionContext
  ): Future[Seq[E]]

  /** Persists an event only. / イベントのみを永続化します。
    *
    * @param event
    *   [[Event]] / イベント
    * @param version
    *   / バージョン
    * @return
    *   `Future[Unit]`
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException
    *   if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
    *   serialization failed / シリアライズに失敗した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException
    *   if transaction failed / トランザクションに失敗した場合
    */
  def persistEvent(event: E, version: Long)(implicit ec: ExecutionContext): Future[Unit]

  /** Persists an event and a snapshot. / イベントとスナップショットを永続化します。
    *
    * @param event
    *   [[Event]] / イベント
    * @param snapshot
    *   [[Aggregate]] / スナップショット
    * @return
    *   `Future[Unit]`
    * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException
    *   if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
    *   serialization failed / シリアライズに失敗した場合
    * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException
    *   if transaction failed / トランザクションに失敗した場合
    */
  def persistEventAndSnapshot(event: E, snapshot: A)(implicit ec: ExecutionContext): Future[Unit]

}
