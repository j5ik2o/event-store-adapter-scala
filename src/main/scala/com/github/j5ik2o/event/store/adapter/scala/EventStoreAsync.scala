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
import com.github.j5ik2o.event.store.adapter.scala.internal.{JavaAsyncEventStoreAdapter, JavaInterop}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.{ExecutionContext, Future}

/**
 * Asynchronous factories; use the store after the returned Future succeeds. / 非同期の生成入口。返したFutureが成功した後にストアを使います。
 */
object EventStoreAsync {

  /** Creates an isolated process-local store. / 独立したプロセス内の保存先を作ります。 */
  def ofMemory[P, A](config: EventStoreConfig[P, A]): Future[EventStoreAsync[P, A]] =
    ofMemory(MemoryStorage.create(), config)

  /**
   * Memory completes on the calling thread and shares the supplied storage. / メモリの生成は呼出スレッドで完了し、指定した保存先を共有します。
   */
  def ofMemory[P, A](storage: MemoryStorage, config: EventStoreConfig[P, A]): Future[EventStoreAsync[P, A]] =
    Future.fromTry(JavaInterop.attempt {
      if (Option(storage).isEmpty || Option(config).isEmpty) {
        throw new ConfigurationException("storage and config are required")
      }
      new JavaAsyncEventStoreAdapter(MemoryEventStore.createAsync(storage, config))
    })

  /**
   * Connects Java's asynchronous configuration I/O without blocking; the caller owns the client. /
   * Javaの非同期設定照合へ待機せず接続します。クライアントは呼出元が所有します。
   */
  def ofDynamoDB[P, A](
    client: DynamoDbAsyncClient,
    tables: DynamoDbTableConfig,
    config: EventStoreConfig[P, A],
  )(implicit ec: ExecutionContext): Future[EventStoreAsync[P, A]] =
    JavaInterop.future(DynamoDbEventStore.createAsync(client, tables, config)).map(new JavaAsyncEventStoreAdapter(_))
}

/**
 * Asynchronous envelope operations. Java failures retain their category, cause and diagnostics. /
 * 非同期の封筒操作。Javaの失敗の分類、原因、診断を保持します。
 */
trait EventStoreAsync[P, A] {
  def persistEvent(event: EventEnvelope[P])(implicit ec: ExecutionContext): Future[Unit]

  def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A])(implicit
    ec: ExecutionContext,
  ): Future[Unit]

  def getLatestSnapshotById(id: AggregateId)(implicit ec: ExecutionContext): Future[Option[SnapshotReadResult[A]]]

  def getEventsByIdSinceSeqNr(id: AggregateId, start: Long)(implicit
    ec: ExecutionContext,
  ): Future[Seq[EventEnvelope[P]]]
}
