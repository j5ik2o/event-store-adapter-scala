package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.{Aggregate, AggregateId, Event}
import com.github.j5ik2o.event.store.adapter.scala.internal.EventStoreForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.util.Try

/**
 * Represents a companion object of [[EventStore]]. / [[EventStore]]のコンパニオンオブジェクトを表します。
 */
object EventStore {

  /**
   * Creates an instance of [[EventStore]] for DynamoDB. / DynamoDB用の[[EventStore]]のインスタンスを作成します。
   *
   * @param dynamoDbClient
   *   [[DynamoDbClient]]
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
   *   [[EventStore]]
   */
  def ofDynamoDB[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]](
    dynamoDbClient: DynamoDbClient,
    journalTableName: String,
    snapshotTableName: String,
    journalAidIndexName: String,
    snapshotAidIndexName: String,
    shardCount: Long,
  ): EventStore[AID, A, E] =
    EventStoreForDynamoDB.create(
      dynamoDbClient,
      journalTableName,
      snapshotTableName,
      journalAidIndexName,
      snapshotAidIndexName,
      shardCount,
    )
}

/**
 * Represents an event store. / イベントストアを表します。
 *
 * @tparam AID
 *   [[Aggregate]] id type / 集約IDの型
 * @tparam A
 *   [[Aggregate]] type / 集約の型
 * @tparam E
 *   [[Event]] type / イベントの型
 */
trait EventStore[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]] extends EventStoreOptions[AID, A, E] {
  override type This = EventStore[AID, A, E]

  /**
   * Gets the latest snapshot by the aggregate id. / 集約IDによる最新のスナップショットを取得します。
   *
   * @param clazz
   *   class of Aggregate A to be serialized / シリアライズ対象の集約Aのクラス
   * @param id
   *   id of [[Aggregate]] A / 集約AのID
   * @return
   *   `Try[Option[A]]`
   * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException
   *   if an error occurred during reading from the event store / イベントストアからの読み込み中にエラーが発生した場合
   * @throws com.github.j5ik2o.event.store.adapter.java.DeserializationException
   *   if an error occurred during serialization / デシリアライズ中にエラーが発生した場合
   */
  def getLatestSnapshotById(clazz: Class[A], id: AID): Try[Option[A]]

  /**
   * Gets the events by the aggregate id and since the sequence number. / IDとシーケンス番号以降のイベントを取得します。
   *
   * @param clazz
   *   class of Event E to be serialized / シリアライズ対象Eのクラス
   * @param id
   *   id of [[Aggregate]] A / 集約AのID
   * @param sequenceNumber
   *   sequence number / シーケンス番号
   * @return
   *   `Try[Seq[E]]`
   * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException
   *   if an error occurred during reading from the event store
   * @throws com.github.j5ik2o.event.store.adapter.java.DeserializationException
   *   if an error occurred during serialization / デシリアライズ中にエラーが発生した場合
   */
  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long): Try[Seq[E]]

  /**
   * Persists an event only. / イベントのみを永続化します。
   *
   * @param event
   *   [[Event]]
   * @param version
   *   バージョン
   * @return
   *   `Try[Unit]`
   * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException
   *   if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
   * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
   *   if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException
   *   if an error occurred during transaction / トランザクション中にエラーが発生した場合
   */
  def persistEvent(event: E, version: Long): Try[Unit]

  /**
   * Persists an event and a snapshot. / イベントとスナップショットを永続化します。
   *
   * @param event
   *   [[Event]]
   * @param snapshot
   *   [[Aggregate]]
   * @return
   *   `Try[Unit]`
   * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException
   *   if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
   * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException
   *   if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException
   *   if an error occurred during transaction / トランザクション中にエラーが発生した場合
   */
  def persistEventAndSnapshot(event: E, snapshot: A): Try[Unit]
}
