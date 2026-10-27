package com.github.j5ik2o.event.store.adapter.scala.examples

import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableEmulator
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEmulator
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import com.github.j5ik2o.event.store.adapter.scala.internal.DynamoDBUtils
import org.scalatest.freespec.AnyFreeSpec

import java.nio.file.Files
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class AccountExampleSpec extends AnyFreeSpec {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  "the documented restoration example with synchronous and asynchronous Memory" in {
    val storage = MemoryStorage.create()
    check(
      EventStore.ofMemory(storage, AccountExample.config).get,
      Await.result(EventStoreAsync.ofMemory(storage, AccountExample.config), 3.seconds))
  }

  "the documented restoration example with synchronous and asynchronous DynamoDB" in
    DynamoDBUtils.withTables { (client, asyncClient, tables) =>
      check(
        EventStore.ofDynamoDB(client, tables, AccountExample.config).get,
        Await.result(EventStoreAsync.ofDynamoDB(asyncClient, tables, AccountExample.config), 30.seconds),
      )
    }

  "the documented first-run and restart flow with SQLite on a real file" in {
    val file = Files.createTempDirectory("scala-sqlite-example-").resolve("accounts.db")
    val id = AggregateId.of("Account", "sqlite-restart")
    StorageExamples.initializeSqlite(file)
    val firstRun = StorageExamples.openSqlite(file)
    try {
      val store = EventStore.ofSqlite(firstRun, AccountExample.config).get
      store.persistEvent(AccountExample.event(id, 1L, "Alice")).get
      assert(AccountExample.restore(store, id).get.contains("Alice"))
    } finally firstRun.close()
    val restarted = StorageExamples.openSqlite(file) // no initialize on restart
    try {
      val store = EventStore.ofSqlite(restarted, AccountExample.config).get
      val async = Await.result(EventStoreAsync.ofSqlite(restarted, AccountExample.config), 30.seconds)
      assert(AccountExample.restore(store, id).get.contains("Alice"))
      assert(Await.result(AccountExample.restoreAsync(async, id), 30.seconds).contains("Alice"))
      store.persistEvent(AccountExample.event(id, 2L, "Bob")).get
      assert(AccountExample.restore(store, id).get.contains("Bob"))
    } finally restarted.close()
  }

  "the documented first-run and restart flow with Spanner" in {
    val emulator = SpannerEmulator.shared()
    // The operator creates the tables and the configuration row once, outside the library.
    val prefix = emulator.createStore(
      Option.empty[Integer].orNull,
      SpannerEmulator.ConfigRow.matching(Integer.valueOf(2), "delete", Option.empty[java.lang.Long].orNull))
    val id = AggregateId.of("Account", "spanner-restart")
    val first = StorageExamples.openSpanner(emulator.client(), prefix)
    val store = EventStore.ofSpanner(first, AccountExample.config).get
    store.persistEvent(AccountExample.event(id, 1L, "Alice")).get
    assert(AccountExample.restore(store, id).get.contains("Alice"))
    val restarted = StorageExamples.openSpanner(emulator.client(), prefix)
    val reopened = EventStore.ofSpanner(restarted, AccountExample.config).get
    val async = Await.result(EventStoreAsync.ofSpanner(restarted, AccountExample.config), 30.seconds)
    assert(AccountExample.restore(reopened, id).get.contains("Alice"))
    assert(Await.result(AccountExample.restoreAsync(async, id), 30.seconds).contains("Alice"))
    reopened.persistEvent(AccountExample.event(id, 2L, "Bob")).get
    assert(AccountExample.restore(reopened, id).get.contains("Bob"))
  }

  "the documented first-run and restart flow with Bigtable" in {
    val emulator = BigtableEmulator.shared()
    val tables = emulator.createTables() // the operator creates the tables outside the library
    val settings = StorageExamples.bigtableSettings(tables.journal, tables.head)
    val id = AggregateId.of("Account", "bigtable-restart")
    StorageExamples.initializeBigtable(emulator.data(), settings)
    val first = StorageExamples.openBigtable(emulator.data(), emulator.admin(), settings)
    val store = EventStore.ofBigtable(first, AccountExample.config).get
    store.persistEvent(AccountExample.event(id, 1L, "Alice")).get
    assert(AccountExample.restore(store, id).get.contains("Alice"))
    val restarted =
      StorageExamples.openBigtable(emulator.data(), emulator.admin(), settings) // no initialize on restart
    val reopened = EventStore.ofBigtable(restarted, AccountExample.config).get
    val async = Await.result(EventStoreAsync.ofBigtable(restarted, AccountExample.config), 30.seconds)
    assert(AccountExample.restore(reopened, id).get.contains("Alice"))
    assert(Await.result(AccountExample.restoreAsync(async, id), 30.seconds).contains("Alice"))
    reopened.persistEvent(AccountExample.event(id, 2L, "Bob")).get
    assert(AccountExample.restore(reopened, id).get.contains("Bob"))
  }

  private def check(sync: EventStore[String, String], async: EventStoreAsync[String, String]): Unit = {
    val id = AggregateId.of("Account", "documented-example")
    assert(AccountExample.restore(sync, id).get.isEmpty)
    assert(Await.result(AccountExample.restoreAsync(async, id), 3.seconds).isEmpty)
    sync.persistEvent(AccountExample.event(id, 1L, "Alice")).get
    assert(AccountExample.restore(sync, id).get.contains("Alice"))
    assert(Await.result(AccountExample.restoreAsync(async, id), 3.seconds).contains("Alice"))
    Await.result(
      async.persistEventAndSnapshot(AccountExample.event(id, 2L, "Bob"), AccountExample.snapshot(2L, "Bob")),
      3.seconds)
    sync.persistEvent(AccountExample.event(id, 3L, "Carol")).get
    assert(AccountExample.restore(sync, id).get.contains("Carol"))
    assert(Await.result(AccountExample.restoreAsync(async, id), 3.seconds).contains("Carol"))
  }
}
