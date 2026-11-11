package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.sqlite.{SqliteEventStore, SqliteStorage}
import com.github.j5ik2o.event.store.adapter.scala.internal.{
  JavaAsyncEventStoreAdapter,
  JavaEventStoreAdapter,
  JavaInterop,
}
import scala.util.Try
import scala.concurrent.Future

object SqliteEventStores {
  def of[P, A](storage: SqliteStorage, config: EventStoreConfig[P, A]): Try[EventStore[P, A]] =
    JavaInterop.attempt(new JavaEventStoreAdapter(SqliteEventStore.create(storage, config)))

  def ofAsync[P, A](storage: SqliteStorage, config: EventStoreConfig[P, A]): Future[EventStoreAsync[P, A]] =
    Future.fromTry(JavaInterop.attempt(new JavaAsyncEventStoreAdapter(SqliteEventStore.createAsync(storage, config))))

}
