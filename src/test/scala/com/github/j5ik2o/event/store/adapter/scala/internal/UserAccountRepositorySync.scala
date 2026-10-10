package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{EventEnvelope, SnapshotEnvelope}
import com.github.j5ik2o.event.store.adapter.scala.EventStore

import scala.util.{Success, Try}

final class UserAccountRepositorySync(eventStore: EventStore[UserAccountEvent, UserAccount]) {
  def store(event: EventEnvelope[UserAccountEvent]): Try[Unit] = eventStore.persistEvent(event)

  def store(event: EventEnvelope[UserAccountEvent], state: UserAccount): Try[Unit] =
    eventStore.persistEventAndSnapshot(
      event,
      SnapshotEnvelope.builder[UserAccount]().seqNr(event.seqNr()).aggregate(state).build(),
    )

  def findById(id: UserAccountId): Try[Option[UserAccount]] =
    eventStore.getLatestSnapshotById(id.toJava).flatMap {
      case Some(read) =>
        val start = read.snapshot.map(_.seqNr() + 1).getOrElse(1L)
        eventStore.getEventsByIdSinceSeqNr(id.toJava, start).map { events =>
          UserAccount.replay(events, read.snapshot.map(_.aggregate()))
        }
      case None => Success(None)
    }
}
