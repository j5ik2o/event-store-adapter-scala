package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.bigtable.{BigtableEmulator, BigtableStorage, BigtableStorageConfig}
import com.github.j5ik2o.event.store.adapter.java.core.{ConfigurationException, EventStoreConfig, RetentionPolicy}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class EventStoreForBigtableSpec extends AnyFreeSpec {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val emulator: BigtableEmulator = BigtableEmulator.shared()

  private def settings(tables: BigtableEmulator.Tables): BigtableStorageConfig =
    BigtableStorageConfig
      .builder()
      .journalTableId(tables.journal)
      .headTableId(tables.head)
      .retentionPolicy(RetentionPolicy.none())
      .build()

  /** Creates the tables, then initializes once, as a first-time deployment does. */
  private def prepared(): BigtableEmulator.Tables = {
    val tables = emulator.createTables()
    BigtableStorage.initialize(emulator.data(), settings(tables))
    tables
  }

  private def open(tables: BigtableEmulator.Tables): BigtableStorage =
    BigtableStorage.open(emulator.data(), emulator.admin(), settings(tables))

  "synchronous creation, append, reads, duplicate append and reopening on the Bigtable emulator" in {
    val tables = prepared()
    StoreChecks.verifySync(
      () => EventStore.ofBigtable(open(tables), StoreChecks.config),
      () => EventStore.ofBigtable(open(tables), StoreChecks.config))
  }

  "asynchronous creation, append, reads, duplicate append and reopening on the Bigtable emulator" in {
    val tables = prepared()
    StoreChecks.verifyAsync(
      () => EventStoreAsync.ofBigtable(open(tables), StoreChecks.config),
      () => EventStoreAsync.ofBigtable(open(tables), StoreChecks.config))
  }

  "the wrapper leaves the user-owned clients usable and shares the storage between stores" in {
    val tables = prepared()
    val storage = open(tables)
    val first = EventStore.ofBigtable(storage, StoreChecks.config).get
    first.persistEvent(StoreChecks.event(1)).get
    val second = EventStore.ofBigtable(storage, StoreChecks.config).get
    assert(second.getLatestSnapshotById(StoreChecks.aggregateId).get.map(_.headSeqNr).contains(1L))
    val async = Await.result(EventStoreAsync.ofBigtable(storage, StoreChecks.config), 30.seconds)
    assert(Await.result(async.getEventsByIdSinceSeqNr(StoreChecks.aggregateId, 1L), 30.seconds).size == 1)
    assert(!emulator.rows(tables.journal).isEmpty, "the user-owned client still answers reads")
  }

  "null arguments are a ConfigurationException, as a Failure and a failed Future" in {
    val storage = open(prepared())
    val noStorage = Option.empty[BigtableStorage].orNull
    val noConfig = Option.empty[EventStoreConfig[String, String]].orNull
    assert(EventStore.ofBigtable(noStorage, StoreChecks.config).failed.get.isInstanceOf[ConfigurationException])
    assert(EventStore.ofBigtable(storage, noConfig).failed.get.isInstanceOf[ConfigurationException])
    val creation = EventStoreAsync.ofBigtable(noStorage, StoreChecks.config)
    assert(Await.ready(creation, 30.seconds).value.get.failed.get.isInstanceOf[ConfigurationException])
  }
}
