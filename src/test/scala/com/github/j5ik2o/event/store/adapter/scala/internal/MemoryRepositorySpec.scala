package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{OptionValues, TryValues}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class MemoryRepositorySpec extends AnyFreeSpec with OptionValues with TryValues {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  "Scala domain serialization and restoration through both Memory factories" in {
    val storage = MemoryStorage.create()
    val sync = new UserAccountRepositorySync(EventStore.ofMemory(storage, UserAccount.config).success.value)
    val async =
      new UserAccountRepositoryAsync(Await.result(EventStoreAsync.ofMemory(storage, UserAccount.config), 3.seconds))
    val id = UserAccountId("domain-memory")
    assert(sync.findById(id).success.value.isEmpty)
    assert(Await.result(async.findById(id), 3.seconds).isEmpty)
    val (alice, first) = UserAccount.create(id, "Alice")
    sync.store(first).success.value
    assert(Await.result(async.findById(id), 3.seconds).value == alice)
    val (bob, second) = alice.changeName(id, 2L, "Bob")
    Await.result(async.store(second, bob), 3.seconds)
    val (carol, third) = bob.changeName(id, 3L, "Carol")
    sync.store(third).success.value
    assert(sync.findById(id).success.value.value == carol)
    assert(Await.result(async.findById(id), 3.seconds).value == carol)
    val encoded = new String(
      UserAccount.config.payloadSerializer().serialize(first.payload()),
      java.nio.charset.StandardCharsets.UTF_8)
    assert(encoded == "{\"type\":\"created\",\"name\":\"Alice\"}")
    val state =
      new String(UserAccount.config.snapshotSerializer().serialize(bob), java.nio.charset.StandardCharsets.UTF_8)
    assert(state == "{\"name\":\"Bob\"}")
  }
}
