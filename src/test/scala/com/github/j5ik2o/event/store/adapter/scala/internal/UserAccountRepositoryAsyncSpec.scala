package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class UserAccountRepositoryAsyncSpec extends AnyFreeSpec with OptionValues {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  "restore asynchronously without a snapshot and after a later event on the same store" in
    DynamoDBUtils.withTables { (_, client, tables) =>
      val id = UserAccountId("async-repository")
      val (alice, first) = UserAccount.create(id, "Alice")
      val (bob, second) = alice.changeName(id, 2L, "Bob")
      val (carol, third) = bob.changeName(id, 3L, "Carol")
      val checked = for {
        store <- EventStoreAsync.ofDynamoDB(client, tables, UserAccount.config)
        repository = new UserAccountRepositoryAsync(store)
        missing <- repository.findById(id)
        _ = assert(missing.isEmpty)
        _ <- repository.store(first)
        created <- repository.findById(id)
        _ = assert(created.value == alice)
        _ <- repository.store(second, bob)
        _ <- repository.store(third)
        restored <- repository.findById(id)
      } yield assert(restored.value == carol)
      Await.result(checked, 30.seconds)
    }
}
