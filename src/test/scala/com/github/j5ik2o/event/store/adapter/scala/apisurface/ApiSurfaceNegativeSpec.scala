package com.github.j5ik2o.event.store.adapter.scala.apisurface

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import org.scalatest.freespec.AnyFreeSpec

final class ApiSurfaceNegativeSpec extends AnyFreeSpec {
  private val base = "com.github.j5ik2o.event.store.adapter.scala"
  private val fixtures = s"$base.apisurface.fixtures"

  private def deviations(original: String, fixture: String): Seq[String] =
    ApiSurfaceChecker.load(Map(s"$base.$original" -> s"$fixtures.$fixture.$original")).check()

  "a mapping without the asynchronous store is reported" in {
    val mapping = ApiSurfaceFiles.read(ApiSurfaceFiles.mappingPath).deepCopy[ObjectNode]()
    mapping.get("stores").asInstanceOf[ArrayNode].remove(1)
    val surface = ApiSurfaceFiles.read(ApiSurfaceFiles.surfacePath)
    assert(
      new ApiSurfaceChecker(surface, mapping).check() == Seq(
        "event_store: scala.json has no store wrapped in scala.concurrent.Future"))
  }

  "a mapping without the accessors of snapshot_read is reported" in {
    val mapping = ApiSurfaceFiles.read(ApiSurfaceFiles.mappingPath).deepCopy[ObjectNode]()
    mapping.path("types").path("snapshot_read").asInstanceOf[ObjectNode].remove("accessors")
    val surface = ApiSurfaceFiles.read(ApiSurfaceFiles.surfacePath)
    assert(
      new ApiSurfaceChecker(surface, mapping).check() == Seq("snapshot_read: no accessors or checked_by in scala.json"))
  }

  "an exception class that does not exist is reported" in {
    val mapping = ApiSurfaceFiles.read(ApiSurfaceFiles.mappingPath).deepCopy[ObjectNode]()
    mapping
      .path("error_categories")
      .path("names")
      .asInstanceOf[ObjectNode]
      .put("storage", "com.github.j5ik2o.event.store.adapter.java.core.NoSuchStorageException")
    val surface = ApiSurfaceFiles.read(ApiSurfaceFiles.surfacePath)
    assert(new ApiSurfaceChecker(surface, mapping).check() == Seq(
      "error_categories.storage: class com.github.j5ik2o.event.store.adapter.java.core.NoSuchStorageException not found"))
  }

  "a shifted definition is reported, and nothing unrelated" - {
    "getEventsByIdSinceSeqNr returning Seq[P]" in
      assert(
        deviations("EventStore", "eventsreturnpayloads") == Seq(
          "get_events_by_id_since_seq_nr: EventStore.getEventsByIdSinceSeqNr returns scala.util.Try<scala.collection.immutable.Seq<P>>, " +
            "want scala.util.Try<scala.collection.immutable.Seq<EventEnvelope<P>>>",
        ))
    "getLatestSnapshotById without Option" in
      assert(
        deviations("EventStore", "snapshotwithoutoption") == Seq(
          "get_latest_snapshot_by_id: EventStore.getLatestSnapshotById returns " +
            "scala.util.Try<com.github.j5ik2o.event.store.adapter.scala.SnapshotReadResult<A>>, " +
            "want scala.util.Try<scala.Option<SnapshotReadResult<A>>>",
        ))
    "async operations returning Try" in
      assert(
        deviations("EventStoreAsync", "asyncreturnstry") == Seq(
          "persist_event: EventStoreAsync.persistEvent returns scala.util.Try<scala.runtime.BoxedUnit>, " +
            "want scala.concurrent.Future<scala.Unit>",
          "persist_event_and_snapshot: EventStoreAsync.persistEventAndSnapshot returns " +
            "scala.util.Try<scala.runtime.BoxedUnit>, want scala.concurrent.Future<scala.Unit>",
          "get_latest_snapshot_by_id: EventStoreAsync.getLatestSnapshotById returns " +
            "scala.util.Try<scala.Option<com.github.j5ik2o.event.store.adapter.scala.SnapshotReadResult<A>>>, " +
            "want scala.concurrent.Future<scala.Option<SnapshotReadResult<A>>>",
          "get_events_by_id_since_seq_nr: EventStoreAsync.getEventsByIdSinceSeqNr returns " +
            "scala.util.Try<scala.collection.immutable.Seq<com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope<P>>>, " +
            "want scala.concurrent.Future<scala.collection.immutable.Seq<EventEnvelope<P>>>",
        ))
    "SnapshotReadResult without headSeqNr" in
      assert(
        deviations("SnapshotReadResult", "withoutheadseqnr") == Seq(
          "snapshot_read.head_seq_nr: SnapshotReadResult has no accessor headSeqNr()"))
    "type parameter P bounded by Comparable[P]" in
      assert(
        deviations("EventStore", "boundedtypeparameter") == Seq(
          "event_store: type parameter P of EventStore is bounded by [java.lang.Comparable<P>]; want no bound"))
  }
}
