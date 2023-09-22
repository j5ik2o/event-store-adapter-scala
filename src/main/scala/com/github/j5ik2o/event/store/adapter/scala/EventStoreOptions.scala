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

  /** Specifies the number of snapshots to keep. / スナップショットを保持する数を指定します。
    *
    * @param keepSnapshotCount
    *   the number of snapshots kept / スナップショットを保持する数
    * @return
    *   [[This]]
    */
  def withKeepSnapshotCount(keepSnapshotCount: Int): This

  /** Specifies the time until it is deleted by TTL. / TTLによって削除されるまでの時間を指定します。
    *
    * @param deleteTtl
    *   Time until it is deleted by TTL / TTLによって削除されるまでの時間
    * @return
    */
  def withDeleteTtl(deleteTtl: FiniteDuration): This

  def withKeyResolver(keyResolver: KeyResolver[AID]): This

  def withEventSerializer(eventSerializer: EventSerializer[AID, E]): This

  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer[AID, A]): This
}
