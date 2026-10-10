package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class EventStoreAsyncForDynamoDBSpec extends AnyFreeSpec with OptionValues {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  "asynchronous public creation and all four operations with arbitrary Scala payloads" in
    DynamoDBUtils.withTables { (_, client, tables) =>
      val id = UserAccountId("async-example")
      val (alice, first) = UserAccount.create(id, "Alice")
      val (bob, second) = alice.changeName(id, 2L, "Bob")
      val snapshot = SnapshotEnvelope.builder[UserAccount]().seqNr(2L).aggregate(bob).build()
      val checked = for {
        store <- EventStoreAsync.ofDynamoDB(client, tables, UserAccount.config)
        missing <- store.getLatestSnapshotById(id.toJava)
        _ = assert(missing.isEmpty)
        _ <- store.persistEvent(first)
        withoutSnapshot <- store.getLatestSnapshotById(id.toJava)
        _ = assert(withoutSnapshot.value.snapshot.isEmpty && withoutSnapshot.value.headSeqNr == 1L)
        _ <- store.persistEventAndSnapshot(second, snapshot)
        read <- store.getLatestSnapshotById(id.toJava)
        events <- store.getEventsByIdSinceSeqNr(id.toJava, 1L)
      } yield {
        assert(read.value.snapshot.value.aggregate() == bob)
        assert(read.value.snapshot.value.seqNr() == 2L && read.value.headSeqNr == 2L)
        assert(events.map(_.seqNr()) == Seq(1L, 2L))
        assert(events.head.occurredAt() == first.occurredAt())
        assert(events.map(_.payload()) == Seq(first.payload(), second.payload()))
      }
      Await.result(checked, 30.seconds)
    }
}
