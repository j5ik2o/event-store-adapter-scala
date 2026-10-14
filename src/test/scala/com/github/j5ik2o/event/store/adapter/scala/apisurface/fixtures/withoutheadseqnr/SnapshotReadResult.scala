package com.github.j5ik2o.event.store.adapter.scala.apisurface.fixtures.withoutheadseqnr

import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope

/** Deliberately shifted shape for the negative API surface test. */
final case class SnapshotReadResult[A](snapshot: Option[SnapshotEnvelope[A]])
