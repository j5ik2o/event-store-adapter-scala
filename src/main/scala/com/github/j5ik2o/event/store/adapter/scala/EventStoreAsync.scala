package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event_store_adatpter_java.{ Aggregate, AggregateId, Event }

import scala.concurrent.{ ExecutionContext, Future }

trait EventStoreAsync[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]] {

  def getLatestSnapshotById(clazz: Class[A], id: AID)(implicit ec: ExecutionContext): Future[Option[(A, Long)]]

  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long)(implicit
      ec: ExecutionContext
  ): Future[Seq[E]]

  def persistEvent(event: E, version: Long)(implicit ec: ExecutionContext): Future[Unit]

  def persistEventAndSnapshot(event: E, snapshot: A)(implicit ec: ExecutionContext): Future[Unit]

}
