package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.scala.EventStore
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{OptionValues, TryValues}

final class EventStoreSyncForDynamoDBSpec extends AnyFreeSpec with OptionValues with TryValues {
  "synchronous public creation and all four operations with arbitrary Scala payloads" in
    DynamoDBUtils.withTables { (client, _, tables) =>
      val store = EventStore.ofDynamoDB(client, tables, UserAccount.config).success.value
      val id = UserAccountId("sync-example")
      assert(store.getLatestSnapshotById(id.toJava).success.value.isEmpty)
      val (alice, first) = UserAccount.create(id, "Alice")
      store.persistEvent(first).success.value
      val withoutSnapshot = store.getLatestSnapshotById(id.toJava).success.value.value
      assert(withoutSnapshot.snapshot.isEmpty && withoutSnapshot.headSeqNr == 1L)
      val (bob, second) = alice.changeName(id, 2L, "Bob")
      val snapshot = SnapshotEnvelope.builder[UserAccount]().seqNr(2L).aggregate(bob).build()
      store.persistEventAndSnapshot(second, snapshot).success.value
      val read = store.getLatestSnapshotById(id.toJava).success.value.value
      assert(read.snapshot.value.aggregate() == bob)
      assert(read.snapshot.value.seqNr() == 2L && read.headSeqNr == 2L)
      val events = store.getEventsByIdSinceSeqNr(id.toJava, 1L).success.value
      assert(events.map(_.seqNr()) == Seq(1L, 2L))
      assert(events.head.occurredAt() == first.occurredAt())
      assert(events.map(_.payload()) == Seq(first.payload(), second.payload()))
    }
}
