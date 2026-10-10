package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  ConfigurationException,
  EventEnvelope,
  EventStoreConfig,
  SnapshotEnvelope,
}
import com.github.j5ik2o.event.store.adapter.java.dynamodb.{DynamoDbEventStore, DynamoDbTableConfig}
import com.github.j5ik2o.event.store.adapter.java.memory.{MemoryEventStore, MemoryStorage}
import com.github.j5ik2o.event.store.adapter.scala.internal.{JavaEventStoreAdapter, JavaInterop}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.util.Try

/** Synchronous factories. / 同期の生成入口。 */
object EventStore {

  /** Creates an isolated process-local store. / 独立したプロセス内の保存先を作ります。 */
  def ofMemory[P, A](config: EventStoreConfig[P, A]): Try[EventStore[P, A]] =
    ofMemory(MemoryStorage.create(), config)

  /** Shares records and settings through the supplied storage. / 指定した保存先の状態と設定を共有します。 */
  def ofMemory[P, A](storage: MemoryStorage, config: EventStoreConfig[P, A]): Try[EventStore[P, A]] =
    JavaInterop.attempt {
      if (Option(storage).isEmpty || Option(config).isEmpty) {
        throw new ConfigurationException("storage and config are required")
      }
      new JavaEventStoreAdapter(MemoryEventStore.create(storage, config))
    }

  /**
   * Validates configuration in three provisioned tables; the caller owns the client. /
   * 作成済み3表の設定を照合します。クライアントは呼出元が所有します。
   */
  def ofDynamoDB[P, A](
    client: DynamoDbClient,
    tables: DynamoDbTableConfig,
    config: EventStoreConfig[P, A],
  ): Try[EventStore[P, A]] =
    JavaInterop.attempt(new JavaEventStoreAdapter(DynamoDbEventStore.create(client, tables, config)))
}

/**
 * Four envelope operations, with payload types bound at creation. / 生成時にペイロード型を決める、封筒の4操作。
 */
trait EventStore[P, A] {
  def persistEvent(event: EventEnvelope[P]): Try[Unit]

  def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A]): Try[Unit]

  /**
   * None means no head; a present result may have no snapshot. / 外側のNoneはヘッド不存在です。存在する結果もスナップショットを持たない場合があります。
   */
  def getLatestSnapshotById(id: AggregateId): Try[Option[SnapshotReadResult[A]]]

  /** Reads every event at or above start, in ascending order. / start以上の全封筒を昇順で読みます。 */
  def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): Try[Seq[EventEnvelope[P]]]
}
