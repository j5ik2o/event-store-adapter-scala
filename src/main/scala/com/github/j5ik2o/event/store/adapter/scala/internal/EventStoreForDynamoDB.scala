/*
 * Copyright 2023 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.scala.EventStore
import com.github.j5ik2o.event_store_adatpter_java.{ Aggregate, AggregateId, Event, EventStore => JavaEventStore }

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

final class EventStoreForDynamoDB[AID <: AggregateId, A <: Aggregate[AID], E <: Event[AID]](
    javaEventStore: JavaEventStore[AID, A, E]
) extends EventStore[AID, A, E] {

  override def getLatestSnapshotById(clazz: Class[A], id: AID): Option[(A, Long)] = {
    javaEventStore
      .getLatestSnapshotById(clazz, id).map { result =>
        (result.getAggregate, result.getVersion)
      }.toScala
  }

  override def getEventsByIdSinceSequenceNumber(clazz: Class[E], id: AID, sequenceNumber: Long): Seq[E] = {
    javaEventStore.getEventsByIdSinceSequenceNumber(clazz, id, sequenceNumber).asScala.toSeq
  }

  override def persistEvent(event: E, version: Long): Unit = {
    javaEventStore.persistEvent(event, version)
  }

  override def persistEventAndSnapshot(event: E, snapshot: A): Unit = {
    javaEventStore.persistEventAndSnapshot(event, snapshot)
  }

}
