package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.{AggregateId, EventEnvelope, EventStoreConfig, PayloadSerializer}
import org.scalatest.freespec.AnyFreeSpec

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class MemoryClasspathSpec extends AnyFreeSpec {
  "core alone initializes both companions and reads and writes memory" in {
    Seq("dynamodb.DynamoDbEventStore", "spanner.SpannerStorage", "bigtable.BigtableStorage", "sqlite.SqliteStorage")
      .foreach { name =>
        intercept[ClassNotFoundException](Class.forName("com.github.j5ik2o.event.store.adapter.java." + name))
      }
    val serializer = new PayloadSerializer[Array[Byte]] {
      override def serialize(value: Array[Byte]): Array[Byte] = value
      override def deserialize(value: Array[Byte]): Array[Byte] = value
    }
    val config = EventStoreConfig
      .builder[Array[Byte], Array[Byte]]()
      .payloadSerializer(serializer)
      .snapshotSerializer(serializer)
      .build()
    val event = EventEnvelope
      .builder[Array[Byte]]()
      .aggregateId(AggregateId.of("Order", "core-only"))
      .seqNr(1L)
      .occurredAt(Instant.EPOCH)
      .payload(Array[Byte](1))
      .build()
    val store = EventStore.ofMemory(config).get
    store.persistEvent(event).get
    assert(store.getEventsByIdSinceSeqNr(event.aggregateId(), 1L).get.map(_.seqNr()) == Seq(1L))
    implicit val ec: ExecutionContext = ExecutionContext.global
    val async = Await.result(EventStoreAsync.ofMemory(config), 30.seconds)
    Await.result(async.persistEvent(event), 30.seconds)
    assert(Await.result(async.getEventsByIdSinceSeqNr(event.aggregateId(), 1L), 30.seconds).map(_.seqNr()) == Seq(1L))
  }
}
