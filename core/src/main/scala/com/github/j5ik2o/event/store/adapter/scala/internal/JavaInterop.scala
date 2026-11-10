package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{
  EventStoreExceptions,
  SnapshotReadResult => JavaSnapshotReadResult,
}
import com.github.j5ik2o.event.store.adapter.scala.SnapshotReadResult

import java.util.Optional
import java.util.concurrent.CompletionStage
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters._
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

private[scala] object JavaInterop {
  def attempt[A](call: => A): Try[A] =
    Try(call).recoverWith { case NonFatal(failure) => Failure(EventStoreExceptions.unwrap(failure)) }

  def future[A](call: => CompletionStage[A])(implicit ec: ExecutionContext): Future[A] =
    Future
      .fromTry(attempt(call))
      .flatMap(_.asScala)
      .recoverWith { case NonFatal(failure) => Future.failed(EventStoreExceptions.unwrap(failure)) }

  def snapshot[A](result: Optional[JavaSnapshotReadResult[A]]): Option[SnapshotReadResult[A]] =
    result.toScala.map(read => SnapshotReadResult(read.snapshot().toScala, read.headSeqNr()))
}
