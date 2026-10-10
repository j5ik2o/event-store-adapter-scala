package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{EventEnvelope, SnapshotEnvelope}
import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync

import scala.concurrent.{ExecutionContext, Future}

final class UserAccountRepositoryAsync(eventStore: EventStoreAsync[UserAccountEvent, UserAccount]) {
  def store(event: EventEnvelope[UserAccountEvent])(implicit ec: ExecutionContext): Future[Unit] =
    eventStore.persistEvent(event)

  def store(event: EventEnvelope[UserAccountEvent], state: UserAccount)(implicit ec: ExecutionContext): Future[Unit] =
    eventStore.persistEventAndSnapshot(
      event,
      SnapshotEnvelope.builder[UserAccount]().seqNr(event.seqNr()).aggregate(state).build(),
    )

  def findById(id: UserAccountId)(implicit ec: ExecutionContext): Future[Option[UserAccount]] =
    eventStore.getLatestSnapshotById(id.toJava).flatMap {
      case Some(read) =>
        val start = read.snapshot.map(_.seqNr() + 1).getOrElse(1L)
        eventStore.getEventsByIdSinceSeqNr(id.toJava, start).map { events =>
          UserAccount.replay(events, read.snapshot.map(_.aggregate()))
        }
      case None => Future.successful(None)
    }
}
