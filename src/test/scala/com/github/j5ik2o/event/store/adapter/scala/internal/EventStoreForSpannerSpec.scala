package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{ConfigurationException, EventStoreConfig}
import com.github.j5ik2o.event.store.adapter.java.spanner.{
  SpannerEmulator,
  SpannerRetentionPolicy,
  SpannerStorage,
  SpannerStorageConfig,
}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class EventStoreForSpannerSpec extends AnyFreeSpec {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val emulator: SpannerEmulator = SpannerEmulator.shared()

  private def newTables(): String =
    emulator.createStore(
      Option.empty[Integer].orNull,
      SpannerEmulator.ConfigRow.matching(Option.empty[Integer].orNull, "delete", Option.empty[java.lang.Long].orNull))

  private def open(prefix: String): SpannerStorage =
    SpannerStorage.open(
      emulator.client(),
      SpannerStorageConfig.builder().tablePrefix(prefix).retentionPolicy(SpannerRetentionPolicy.none()).build())

  "synchronous creation, append, reads, duplicate append and reopening on the Spanner emulator" in {
    val prefix = newTables()
    StoreChecks.verifySync(
      () => EventStore.ofSpanner(open(prefix), StoreChecks.config),
      () => EventStore.ofSpanner(open(prefix), StoreChecks.config))
  }

  "asynchronous creation, append, reads, duplicate append and reopening on the Spanner emulator" in {
    val prefix = newTables()
    StoreChecks.verifyAsync(
      () => EventStoreAsync.ofSpanner(open(prefix), StoreChecks.config),
      () => EventStoreAsync.ofSpanner(open(prefix), StoreChecks.config))
  }

  "the wrapper leaves the user-owned client usable and shares the storage between stores" in {
    val prefix = newTables()
    val storage = open(prefix)
    val first = EventStore.ofSpanner(storage, StoreChecks.config).get
    first.persistEvent(StoreChecks.event(1)).get
    val second = EventStore.ofSpanner(storage, StoreChecks.config).get
    assert(second.getLatestSnapshotById(StoreChecks.aggregateId).get.map(_.headSeqNr).contains(1L))
    val async = Await.result(EventStoreAsync.ofSpanner(storage, StoreChecks.config), 30.seconds)
    assert(Await.result(async.getEventsByIdSinceSeqNr(StoreChecks.aggregateId, 1L), 30.seconds).size == 1)
    assert(emulator.count(prefix, "journal") == 1L, "the user-owned client still answers queries")
  }

  "null arguments are a ConfigurationException, as a Failure and a failed Future" in {
    val storage = open(newTables())
    val noStorage = Option.empty[SpannerStorage].orNull
    val noConfig = Option.empty[EventStoreConfig[String, String]].orNull
    assert(EventStore.ofSpanner(noStorage, StoreChecks.config).failed.get.isInstanceOf[ConfigurationException])
    assert(EventStore.ofSpanner(storage, noConfig).failed.get.isInstanceOf[ConfigurationException])
    val creation = EventStoreAsync.ofSpanner(noStorage, StoreChecks.config)
    assert(Await.ready(creation, 30.seconds).value.get.failed.get.isInstanceOf[ConfigurationException])
  }
}
