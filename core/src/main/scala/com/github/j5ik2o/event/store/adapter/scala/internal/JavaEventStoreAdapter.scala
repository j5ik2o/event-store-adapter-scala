package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  EventEnvelope,
  EventStore => JavaEventStore,
  SnapshotEnvelope,
}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, SnapshotReadResult}

import scala.jdk.CollectionConverters._
import scala.util.Try

private[scala] final class JavaEventStoreAdapter[P, A](underlying: JavaEventStore[P, A]) extends EventStore[P, A] {
  override def persistEvent(event: EventEnvelope[P]): Try[Unit] =
    JavaInterop.attempt(underlying.persistEvent(event))

  override def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A]): Try[Unit] =
    JavaInterop.attempt(underlying.persistEventAndSnapshot(event, snapshot))

  override def getLatestSnapshotById(id: AggregateId): Try[Option[SnapshotReadResult[A]]] =
    JavaInterop.attempt(JavaInterop.snapshot(underlying.getLatestSnapshotById(id)))

  override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long): Try[Seq[EventEnvelope[P]]] =
    JavaInterop.attempt(underlying.getEventsByIdSinceSeqNr(id, start).asScala.toVector)
}
