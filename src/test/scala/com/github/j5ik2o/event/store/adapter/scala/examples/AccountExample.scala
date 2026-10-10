package com.github.j5ik2o.event.store.adapter.scala.examples

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  EventEnvelope,
  EventStoreConfig,
  JsonPayloadSerializer,
  SnapshotEnvelope,
}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

object AccountExample {
  val config: EventStoreConfig[String, String] = EventStoreConfig
    .builder[String, String]()
    .payloadSerializer(JsonPayloadSerializer.of(classOf[String]))
    .snapshotSerializer(JsonPayloadSerializer.of(classOf[String]))
    .build()

  def event(id: AggregateId, seqNr: Long, name: String): EventEnvelope[String] = EventEnvelope
    .builder[String]()
    .aggregateId(id)
    .seqNr(seqNr)
    .occurredAt(Instant.parse("2026-10-10T00:00:00.123456789Z"))
    .payload(name)
    .build()

  def snapshot(seqNr: Long, name: String): SnapshotEnvelope[String] =
    SnapshotEnvelope.builder[String]().seqNr(seqNr).aggregate(name).build()

  def restore(store: EventStore[String, String], id: AggregateId): Try[Option[String]] =
    store.getLatestSnapshotById(id).flatMap {
      case None => Success(None)
      case Some(read) =>
        val start = read.snapshot.map(_.seqNr() + 1).getOrElse(1L)
        store.getEventsByIdSinceSeqNr(id, start).map { events =>
          events.foldLeft(read.snapshot.map(_.aggregate()))((_, event) => Some(event.payload()))
        }
    }

  def restoreAsync(store: EventStoreAsync[String, String], id: AggregateId)(implicit
    ec: ExecutionContext): Future[Option[String]] =
    store.getLatestSnapshotById(id).flatMap {
      case None => Future.successful(None)
      case Some(read) =>
        val start = read.snapshot.map(_.seqNr() + 1).getOrElse(1L)
        store.getEventsByIdSinceSeqNr(id, start).map { events =>
          events.foldLeft(read.snapshot.map(_.aggregate()))((_, event) => Some(event.payload()))
        }
    }
}
