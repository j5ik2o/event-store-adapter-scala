package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.scala.EventStore
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{OptionValues, TryValues}

final class UserAccountRepositorySyncSpec extends AnyFreeSpec with OptionValues with TryValues {
  "restore without a snapshot, then from the snapshot sequence on the same store" in
    DynamoDBUtils.withTables { (client, _, tables) =>
      val repository =
        new UserAccountRepositorySync(EventStore.ofDynamoDB(client, tables, UserAccount.config).success.value)
      val id = UserAccountId("sync-repository")
      assert(repository.findById(id).success.value.isEmpty)
      val (alice, first) = UserAccount.create(id, "Alice")
      repository.store(first).success.value
      assert(repository.findById(id).success.value.value == alice)
      val (bob, second) = alice.changeName(id, 2L, "Bob")
      repository.store(second, bob).success.value
      val (carol, third) = bob.changeName(id, 3L, "Carol")
      repository.store(third).success.value
      assert(repository.findById(id).success.value.value == carol)
    }
}
