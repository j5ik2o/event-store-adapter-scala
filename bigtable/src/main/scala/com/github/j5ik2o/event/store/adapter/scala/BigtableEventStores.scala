package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.bigtable.{BigtableEventStore, BigtableStorage}
import com.github.j5ik2o.event.store.adapter.scala.internal.{
  JavaAsyncEventStoreAdapter,
  JavaEventStoreAdapter,
  JavaInterop,
}
import scala.util.Try
import scala.concurrent.Future

object BigtableEventStores {
  def of[P, A](storage: BigtableStorage, config: EventStoreConfig[P, A]): Try[EventStore[P, A]] =
    JavaInterop.attempt(new JavaEventStoreAdapter(BigtableEventStore.create(storage, config)))

  def ofAsync[P, A](storage: BigtableStorage, config: EventStoreConfig[P, A]): Future[EventStoreAsync[P, A]] =
    Future.fromTry(JavaInterop.attempt(new JavaAsyncEventStoreAdapter(BigtableEventStore.createAsync(storage, config))))
}
