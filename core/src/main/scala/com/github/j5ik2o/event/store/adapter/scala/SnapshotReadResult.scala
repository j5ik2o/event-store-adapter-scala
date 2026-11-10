package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope

/**
 * Snapshot and independently read head. Replay from snapshot.seqNr + 1, or 1 without a snapshot. /
 * スナップショットと別に読んだヘッド。復元開始はスナップショット番号+1、なければ1です。
 */
final case class SnapshotReadResult[A](snapshot: Option[SnapshotEnvelope[A]], headSeqNr: Long)
