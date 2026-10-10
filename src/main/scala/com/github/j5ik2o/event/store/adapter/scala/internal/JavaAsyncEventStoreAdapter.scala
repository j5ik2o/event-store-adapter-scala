package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  AsyncEventStore => JavaAsyncEventStore,
  EventEnvelope,
  SnapshotEnvelope,
}
import com.github.j5ik2o.event.store.adapter.scala.{EventStoreAsync, SnapshotReadResult}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

private[scala] final class JavaAsyncEventStoreAdapter[P, A](underlying: JavaAsyncEventStore[P, A])
  extends EventStoreAsync[P, A] {
  override def persistEvent(event: EventEnvelope[P])(implicit ec: ExecutionContext): Future[Unit] =
    JavaInterop.future(underlying.persistEvent(event)).map(_ => ())

  override def persistEventAndSnapshot(event: EventEnvelope[P], snapshot: SnapshotEnvelope[A])(implicit
    ec: ExecutionContext,
  ): Future[Unit] =
    JavaInterop.future(underlying.persistEventAndSnapshot(event, snapshot)).map(_ => ())

  override def getLatestSnapshotById(id: AggregateId)(implicit
    ec: ExecutionContext): Future[Option[SnapshotReadResult[A]]] =
    JavaInterop.future(underlying.getLatestSnapshotById(id)).map(JavaInterop.snapshot(_))

  override def getEventsByIdSinceSeqNr(id: AggregateId, start: Long)(implicit
    ec: ExecutionContext,
  ): Future[Seq[EventEnvelope[P]]] =
    JavaInterop.future(underlying.getEventsByIdSinceSeqNr(id, start)).map(_.asScala.toVector)
}
