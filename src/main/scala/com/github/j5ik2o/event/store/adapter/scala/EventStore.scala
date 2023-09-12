package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event_store_adatpter_java.{ Aggregate, AggregateId, Event }

import scala.util.Try

trait EventStore[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]] {

  def getLatestSnapshotById(clazz: Class[A], id: AID): Try[Option[(A, Long)]]

  def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long): Try[Seq[E]]

  def persistEvent(event: E, version: Long): Try[Unit]

  def persistEventAndSnapshot(event: E, snapshot: A): Try[Unit]
}
