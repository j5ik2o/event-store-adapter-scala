package com.github.j5ik2o.event.store.adapter.scala.examples

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import com.github.j5ik2o.event.store.adapter.scala.internal.DynamoDBUtils
import org.scalatest.freespec.AnyFreeSpec

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
