package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.{
  Aggregate,
  AggregateId,
  Event,
  EventSerializer,
  KeyResolver,
  SnapshotSerializer
}

import scala.concurrent.duration.FiniteDuration

trait EventStoreOptions[AID <: AggregateId, A <: Aggregate[A, AID], E <: Event[AID]] {
  type This <: EventStoreOptions[AID, A, E]

  def withKeepSnapshotCount(keepSnapshotCount: Int): This

  def withDeleteTtl(deleteTtl: FiniteDuration): This

  def withKeyResolver(keyResolver: KeyResolver[AID]): This

  def withEventSerializer(eventSerializer: EventSerializer[AID, E]): This

  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer[AID, A]): This
}
