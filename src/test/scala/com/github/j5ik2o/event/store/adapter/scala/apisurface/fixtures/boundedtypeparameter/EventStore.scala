package com.github.j5ik2o.event.store.adapter.scala.apisurface.fixtures.boundedtypeparameter

import com.github.j5ik2o.event.store.adapter.java.core.{AggregateId, EventEnvelope, SnapshotEnvelope}
import com.github.j5ik2o.event.store.adapter.scala.SnapshotReadResult
import scala.util.Try

/** Deliberately shifted shape for the negative API surface test. */
trait EventStore[P <: Comparable[P], A] {
  def persistEvent(event: EventEnvelope[P]): Try[Unit]

  def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A]): Try[Unit]

  def getLatestSnapshotById(id: AggregateId): Try[Option[SnapshotReadResult[A]]]

  def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): Try[Seq[EventEnvelope[P]]]
}
