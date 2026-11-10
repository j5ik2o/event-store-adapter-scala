package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.dynamodb.{DynamoDbEventStore, DynamoDbTableConfig}
import com.github.j5ik2o.event.store.adapter.scala.internal.{
  JavaAsyncEventStoreAdapter,
  JavaEventStoreAdapter,
  JavaInterop,
}
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbClient}
import scala.util.Try
import scala.concurrent.{ExecutionContext, Future}

object DynamoDbEventStores {
  def of[P, A](
    client: DynamoDbClient,
    tables: DynamoDbTableConfig,
    config: EventStoreConfig[P, A],
  ): Try[EventStore[P, A]] =
    JavaInterop.attempt(new JavaEventStoreAdapter(DynamoDbEventStore.create(client, tables, config)))

  def ofAsync[P, A](
    client: DynamoDbAsyncClient,
    tables: DynamoDbTableConfig,
    config: EventStoreConfig[P, A],
  )(implicit ec: ExecutionContext): Future[EventStoreAsync[P, A]] =
    JavaInterop.future(DynamoDbEventStore.createAsync(client, tables, config)).map(new JavaAsyncEventStoreAdapter(_))

}
