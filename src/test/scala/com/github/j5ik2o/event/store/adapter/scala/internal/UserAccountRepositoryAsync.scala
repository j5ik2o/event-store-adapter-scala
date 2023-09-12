package com.github.j5ik2o.event.store.adapter.scala.internal

import scala.concurrent.{ ExecutionContext, Future }

class UserAccountRepositoryAsync(
    eventStoreAsyncForDynamoDB: EventStoreAsyncForDynamoDB[UserAccountId, UserAccount, UserAccountEvent]
) {

  def store(userAccountEvent: UserAccountEvent, version: Long)(implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsyncForDynamoDB.persistEvent(userAccountEvent, version)

  def store(userAccountEvent: UserAccountEvent, userAccount: UserAccount)(implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsyncForDynamoDB.persistEventAndSnapshot(userAccountEvent, userAccount)

  def findById(id: UserAccountId)(implicit ec: ExecutionContext): Future[Option[UserAccount]] = {
    eventStoreAsyncForDynamoDB.getLatestSnapshotById(classOf[UserAccount], id).flatMap {
      case Some((userAccount, version)) =>
        eventStoreAsyncForDynamoDB
          .getEventsByIdSinceSequenceNumber(classOf[UserAccountEvent], id, userAccount.sequenceNumber).map { events =>
            Some(UserAccount.replay(events, userAccount, version))
          }
      case None =>
        Future.successful(None)
    }
  }

}
