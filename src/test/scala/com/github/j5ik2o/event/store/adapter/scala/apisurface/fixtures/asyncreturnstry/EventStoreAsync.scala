package com.github.j5ik2o.event.store.adapter.scala.apisurface.fixtures.asyncreturnstry

import com.github.j5ik2o.event.store.adapter.java.core.{AggregateId, EventEnvelope, SnapshotEnvelope}
import com.github.j5ik2o.event.store.adapter.scala.SnapshotReadResult
import scala.concurrent.ExecutionContext
import scala.util.Try

/** Deliberately shifted shape for the negative API surface test. */
trait EventStoreAsync[P, A] {
  def persistEvent(event: EventEnvelope[P])(implicit ec: ExecutionContext): Try[Unit]

  def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A])(implicit
    ec: ExecutionContext,
  ): Try[Unit]

  def getLatestSnapshotById(id: AggregateId)(implicit ec: ExecutionContext): Try[Option[SnapshotReadResult[A]]]

  def getEventsByIdSinceSeqNr(id: AggregateId, start: Long)(implicit ec: ExecutionContext): Try[Seq[EventEnvelope[P]]]
}
