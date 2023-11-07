package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync

import scala.concurrent.{ ExecutionContext, Future }

class UserAccountRepositoryAsync(
    eventStoreAsyncForDynamoDB: EventStoreAsync[UserAccountId, UserAccount, UserAccountEvent]
) {

  def store(userAccountEvent: UserAccountEvent, version: Long)(implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsyncForDynamoDB.persistEvent(userAccountEvent, version)

  def store(userAccountEvent: UserAccountEvent, userAccount: UserAccount)(implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsyncForDynamoDB.persistEventAndSnapshot(userAccountEvent, userAccount)

  def findById(id: UserAccountId)(implicit ec: ExecutionContext): Future[Option[UserAccount]] = {
    eventStoreAsyncForDynamoDB.getLatestSnapshotById(classOf[UserAccount], id).flatMap {
      case Some(userAccount) =>
        eventStoreAsyncForDynamoDB
          .getEventsByIdSinceSequenceNumber(classOf[UserAccountEvent], id, userAccount.sequenceNumber + 1).map {
            events =>
              Some(UserAccount.replay(events, userAccount))
          }
      case None =>
        Future.successful(None)
    }
  }

}
